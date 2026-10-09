package com.landrop.server.desktop;

import com.landrop.config.AppConfig;
import com.landrop.server.config.ServerConfig;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Settings for this desktop run. Never changes the ports advertised by a running server. */
public record DesktopServerSettings(String bindHost, int httpPort, int wsPort,
                                    boolean externalClient, int clientPort, boolean minimizeToTray) {
    public DesktopServerSettings {
        bindHost = bindHost == null ? "" : bindHost.trim();
        if (bindHost.isEmpty() || bindHost.contains("/") || bindHost.contains(" ") || bindHost.contains("?")) {
            throw new IllegalArgumentException("Nhập địa chỉ bind hợp lệ, ví dụ 0.0.0.0 hoặc 127.0.0.1.");
        }
        requirePort(httpPort, "HTTP");
        requirePort(wsPort, "WebSocket");
        requirePort(clientPort, "Web client");
        if (httpPort == wsPort) throw new IllegalArgumentException("Cổng HTTP và WebSocket phải khác nhau.");
        if (externalClient && (clientPort == httpPort || clientPort == wsPort)) {
            throw new IllegalArgumentException("Cổng Next.js client phải khác cổng HTTP và WebSocket của máy chủ.");
        }
    }

    public static DesktopServerSettings load() {
        ServerConfig base = ServerConfig.load();
        int client = AppConfig.getInt("landrop.client.port", "CLIENT_PORT", 3000);
        return new DesktopServerSettings(base.getHost(), base.getHttpPort(), base.getWsPort(),
                AppConfig.getBoolean("landrop.client.external", "CLIENT_EXTERNAL", false), client, true);
    }

    public ServerConfig serverConfig() {
        ServerConfig base = ServerConfig.load();
        return new ServerConfig(bindHost, httpPort, wsPort, base.getProtocolVersion(),
                base.getHeartbeatIntervalMs(), base.getHeartbeatTimeoutMs(), base.getEnvironment(), base.getLogLevel());
    }

    public String advertisedHost(String lanIp) {
        if (bindHost.equals("127.0.0.1") || bindHost.equalsIgnoreCase("localhost")) return "127.0.0.1";
        String host = bindHost.equals("0.0.0.0") || bindHost.equals("::") ? lanIp : bindHost;
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    public String clientUrl(String lanIp, int actualHttpPort) {
        String host = advertisedHost(lanIp);
        String backend = "http://" + host + ":" + actualHttpPort;
        return externalClient ? "http://" + host + ":" + clientPort + "/connect?server="
                + URLEncoder.encode(backend, StandardCharsets.UTF_8) : backend + "/client";
    }

    private static void requirePort(int port, String label) {
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("Cổng " + label + " phải từ 1024 đến 65535.");
    }
}
