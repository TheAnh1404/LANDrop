package com.landrop.ui.connection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import javafx.application.Platform;

import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Service that automatically discovers active LANDrop servers on localhost and LAN.
 * Eliminates manual configuration of ports and server addresses.
 */
public class ServerDiscoveryService implements AutoCloseable {

    public record DiscoveredServer(
        String host,
        int httpPort,
        int wsPort,
        String serverVersion,
        int protocolVersion,
        String apiUrl,
        String wsUrl
    ) {
        public String displayAddress() {
            return "http://" + host + ":" + httpPort;
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    public static final int DEFAULT_SERVER_PORT = 3000;
    public static final int DEFAULT_WS_PORT = 3001;

    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService scanExecutor;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isScanning = new AtomicBoolean(false);

    private String preferredUrl;

    public ServerDiscoveryService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(1200))
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ServerDiscovery-Scheduler");
            t.setDaemon(true);
            return t;
        });
        this.scanExecutor = Executors.newFixedThreadPool(28, r -> {
            Thread t = new Thread(r, "ServerDiscovery-Worker");
            t.setDaemon(true);
            return t;
        });
    }

    public void setPreferredUrl(String preferredUrl) {
        this.preferredUrl = preferredUrl;
    }

    /**
     * Attempts a single immediate discovery pass.
     */
    public CompletableFuture<Optional<DiscoveredServer>> scanOnce(Consumer<String> statusReporter) {
        return CompletableFuture.supplyAsync(() -> {
            reportStatus(statusReporter, "Đang kiểm tra máy chủ cục bộ (localhost:3000)...");

            // 1. Check preferred / saved URL first
            if (preferredUrl != null && !preferredUrl.isBlank()) {
                try {
                    URI uri = URI.create(preferredUrl);
                    String host = uri.getHost() != null ? uri.getHost() : "localhost";
                    int port = uri.getPort() > 0 ? uri.getPort() : DEFAULT_SERVER_PORT;
                    DiscoveredServer s = probe(host, port);
                    if (s != null) return Optional.of(s);
                } catch (Exception ignored) {}
            }

            // 2. Check localhost & 127.0.0.1
            DiscoveredServer local1 = probe("127.0.0.1", DEFAULT_SERVER_PORT);
            if (local1 != null) return Optional.of(local1);

            DiscoveredServer local2 = probe("localhost", DEFAULT_SERVER_PORT);
            if (local2 != null) return Optional.of(local2);

            // 3. Check own LAN IPs
            List<String> ownLanIps = resolveLocalIpv4Addresses();
            for (String ownIp : ownLanIps) {
                DiscoveredServer s = probe(ownIp, DEFAULT_SERVER_PORT);
                if (s != null) return Optional.of(s);
            }

            // 4. Subnet parallel sweep
            reportStatus(statusReporter, "Đang dò tìm máy chủ LANDrop trên mạng nội bộ (LAN)...");
            List<String> subnetIps = generateSubnetCandidates(ownLanIps);
            if (!subnetIps.isEmpty()) {
                DiscoveredServer found = scanSubnetParallel(subnetIps, DEFAULT_SERVER_PORT);
                if (found != null) return Optional.of(found);
            }

            return Optional.empty();
        }, scanExecutor);
    }

    /**
     * Starts continuous auto-discovery. Scans periodically until a server is found or stopped.
     */
    public synchronized void startContinuousDiscovery(
            Consumer<DiscoveredServer> onServerFound,
            Consumer<String> statusReporter) {

        isRunning.set(true);
        triggerNextScan(onServerFound, statusReporter, 0);
    }

    private void triggerNextScan(
            Consumer<DiscoveredServer> onServerFound,
            Consumer<String> statusReporter,
            long delayMs) {

        if (!isRunning.get()) return;

        scheduler.schedule(() -> {
            if (!isRunning.get()) return;
            if (!isScanning.compareAndSet(false, true)) return;

            scanOnce(statusReporter).whenComplete((result, err) -> {
                isScanning.set(false);
                if (!isRunning.get()) return;

                if (result != null && result.isPresent()) {
                    DiscoveredServer s = result.get();
                    isRunning.set(false);
                    reportStatus(statusReporter, "Đã tìm thấy máy chủ tại " + s.displayAddress() + "! Đang kết nối...");
                    if (onServerFound != null) {
                        try {
                            Platform.runLater(() -> onServerFound.accept(s));
                        } catch (IllegalStateException ignored) {
                            onServerFound.accept(s);
                        }
                    }
                } else {
                    reportStatus(statusReporter, "Chưa tìm thấy máy chủ LANDrop. Đang chờ máy chủ bật... (tự động thử lại)");
                    // Schedule next attempt in 2.5 seconds
                    triggerNextScan(onServerFound, statusReporter, 2500);
                }
            });
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    public synchronized void stopDiscovery() {
        isRunning.set(false);
    }

    /**
     * Probes an individual host and port by checking HTTP /health.
     */
    public DiscoveredServer probe(String host, int port) {
        if (!isPortOpenQuick(host, port, 250)) {
            return null;
        }
        try {
            URI healthUri = URI.create("http://" + host + ":" + port + "/health");
            HttpRequest req = HttpRequest.newBuilder(healthUri)
                    .GET()
                    .timeout(Duration.ofMillis(1500))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                JsonNode body = JSON.readTree(resp.body());
                String status = body.path("status").asText("");
                String serverStatus = body.path("serverStatus").asText("");
                if ("ok".equalsIgnoreCase(status) || "RUNNING".equalsIgnoreCase(serverStatus)) {
                    int httpPort = body.path("httpPort").asInt(port);
                    int wsPort = body.path("wsPort").asInt(httpPort == 3000 ? DEFAULT_WS_PORT : httpPort + 1);
                    String version = body.path("serverVersion").asText("unknown");
                    int proto = body.path("protocolVersion").asInt(1);
                    String api = "http://" + host + ":" + httpPort + "/api";
                    String ws = "ws://" + host + ":" + wsPort + "/api/realtime";
                    return new DiscoveredServer(host, httpPort, wsPort, version, proto, api, ws);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private boolean isPortOpenQuick(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private DiscoveredServer scanSubnetParallel(List<String> ips, int port) {
        List<CompletableFuture<DiscoveredServer>> futures = new ArrayList<>(ips.size());
        for (String ip : ips) {
            futures.add(CompletableFuture.supplyAsync(() -> probe(ip, port), scanExecutor));
        }

        try {
            // As soon as any probe returns non-null, return it
            for (CompletableFuture<DiscoveredServer> f : futures) {
                DiscoveredServer res = f.get(600, TimeUnit.MILLISECONDS);
                if (res != null) return res;
            }
        } catch (Exception ignored) {}

        for (CompletableFuture<DiscoveredServer> f : futures) {
            try {
                DiscoveredServer s = f.getNow(null);
                if (s != null) return s;
            } catch (Exception ignored) {}
        }
        return null;
    }

    public static List<String> resolveLocalIpv4Addresses() {
        List<String> list = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp() || iface.isVirtual()) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && a.isSiteLocalAddress()) {
                        list.add(a.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    private static List<String> generateSubnetCandidates(List<String> ownIps) {
        Set<String> candidates = new LinkedHashSet<>();
        for (String ip : ownIps) {
            int lastDot = ip.lastIndexOf('.');
            if (lastDot > 0) {
                String prefix = ip.substring(0, lastDot + 1);
                // Priority: .1, .2, .100, .101
                candidates.add(prefix + "1");
                candidates.add(prefix + "2");
                candidates.add(prefix + "100");
                candidates.add(prefix + "101");
                for (int i = 1; i <= 254; i++) {
                    candidates.add(prefix + i);
                }
            }
        }
        return new ArrayList<>(candidates);
    }

    private void reportStatus(Consumer<String> statusReporter, String msg) {
        if (statusReporter != null) {
            try {
                Platform.runLater(() -> statusReporter.accept(msg));
            } catch (IllegalStateException ignored) {
                statusReporter.accept(msg);
            }
        }
    }

    @Override
    public void close() {
        stopDiscovery();
        scheduler.shutdownNow();
        scanExecutor.shutdownNow();
    }
}
