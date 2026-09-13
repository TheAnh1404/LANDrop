package com.landrop.discovery;

import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Centralized Discovery Server for the LANDrop peer network.
 * <p>
 * Manages peer registration, presence heartbeats, discovery peer lists,
 * and background stale peer evictions.
 * <p>
 * <b>Note:</b> The Discovery Server does NOT participate in or relay any chat or file data.
 */
public class DiscoveryServer implements AutoCloseable {

    public static final int DEFAULT_PORT = 8888;
    public static final long DEFAULT_CLEANUP_INTERVAL_SEC = 3L;

    private final int configuredPort;
    private final PeerRegistry registry;
    private final CountDownLatch readyLatch = new CountDownLatch(1);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private volatile ServerSocket serverSocket;
    private volatile int actualPort;
    private volatile boolean running = false;

    /**
     * Constructs a DiscoveryServer on default port 8000 and default 15s timeout.
     */
    public DiscoveryServer() {
        this(DEFAULT_PORT);
    }

    /**
     * Constructs a DiscoveryServer on the specified port with default 15s timeout.
     *
     * @param port port number (0 for dynamic system port)
     */
    public DiscoveryServer(int port) {
        this(port, PeerRegistry.DEFAULT_TIMEOUT_MS);
    }

    /**
     * Constructs a DiscoveryServer with custom port and peer timeout.
     *
     * @param port port number
     * @param timeoutMs peer heartbeat timeout limit in milliseconds
     */
    public DiscoveryServer(int port, long timeoutMs) {
        this.configuredPort = port;
        this.registry = new PeerRegistry(timeoutMs);
    }

    /**
     * Starts the discovery server synchronously. This call blocks until stopped or closed.
     */
    public void start() {
        System.out.println("=================================");
        System.out.println("LANDrop - Discovery Server");
        System.out.println("=================================");
        System.out.println();
        System.out.println("Starting discovery server...");
        System.out.println("Port: " + (configuredPort == 0 ? "(assigning dynamic port...)" : configuredPort));
        System.out.println();

        try (ServerSocket server = new ServerSocket(configuredPort)) {
            this.serverSocket = server;
            this.actualPort = server.getLocalPort();
            this.running = true;
            readyLatch.countDown();

            System.out.println("Discovery server started.");
            System.out.println("Waiting for peers...");
            System.out.println();

            startStalePeerEvictionTask();

            while (running && !server.isClosed()) {
                try {
                    Socket clientSocket = server.accept();
                    DiscoveryClientHandler handler = new DiscoveryClientHandler(clientSocket, registry);
                    Thread.ofVirtual().name("discovery-handler-" + clientSocket.getPort()).start(handler);
                } catch (SocketException e) {
                    if (!running || server.isClosed()) {
                        break;
                    }
                    System.err.println("[Discovery Error] Accept exception: " + e.getMessage());
                }
            }
        } catch (BindException e) {
            System.err.println("Unable to start discovery server.");
            System.err.println("Port " + configuredPort + " is already in use.");
        } catch (IOException e) {
            System.err.println("Discovery server error: " + e.getMessage());
        } finally {
            this.running = false;
            readyLatch.countDown();
            scheduler.shutdownNow();
        }
    }

    /**
     * Starts the discovery server asynchronously in a background thread.
     */
    public void startAsync() {
        Thread serverThread = new Thread(this::start, "discovery-server-listener");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private void startStalePeerEvictionTask() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                List<PeerInfo> timedOut = registry.cleanupStalePeers();
                for (PeerInfo peer : timedOut) {
                    System.out.println("[TIMEOUT] " + peer.getDisplayName() + " removed");
                }
            } catch (Exception e) {
                System.err.println("[Discovery Cleanup Error] " + e.getMessage());
            }
        }, DEFAULT_CLEANUP_INTERVAL_SEC, DEFAULT_CLEANUP_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    /**
     * Waits until the discovery server socket has successfully bound and is ready.
     *
     * @param timeout maximum time to wait
     * @param unit time unit
     * @return true if ready within timeout, false otherwise
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean awaitReady(long timeout, TimeUnit unit) throws InterruptedException {
        return readyLatch.await(timeout, unit);
    }

    /**
     * Stops the discovery server and shuts down resources.
     */
    public void stop() {
        this.running = false;
        readyLatch.countDown();
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        scheduler.shutdownNow();
    }

    @Override
    public void close() {
        stop();
    }

    public int getPort() {
        try {
            readyLatch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return actualPort > 0 ? actualPort : configuredPort;
    }

    public boolean isRunning() {
        return running;
    }

    public PeerRegistry getRegistry() {
        return registry;
    }

    public static void main(String[] args) {
        int port = com.landrop.config.AppConfig.getDiscoveryPort();
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0].trim());
            } catch (NumberFormatException e) {
                System.err.println("Invalid port number: " + args[0] + ". Using configured port " + port);
            }
        }

        DiscoveryServer server = new DiscoveryServer(port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "discovery-shutdown-hook"));
        server.start();
    }
}
