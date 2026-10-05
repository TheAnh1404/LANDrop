package com.landrop.server.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.admin.server.AdminHttpServer;
import com.landrop.auth.server.AuthHttpHandler;
import com.landrop.device.server.DeviceHttpHandler;
import com.landrop.workspace.http.WorkspaceHttpServer;
import com.landrop.chat.server.ChatHttpServer;
import com.landrop.file.server.FileHttpServer;
import com.landrop.server.clients.ClientRegistry;
import com.landrop.server.health.HealthService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Lightweight HTTP server serving /health, status inspection, Control Center,
 * User Authentication, and Device Management endpoints (FOUNDATION-01A, FOUNDATION-03A, FOUNDATION-04A).
 * Uses Java 21 standard HttpServer with Virtual Threads.
 */
public class FoundationHttpServer {

    private final String host;
    private final int port;
    private final HealthService healthService;
    private final ClientRegistry clientRegistry;
    private final int wsPort;
    private final ObjectMapper objectMapper = com.landrop.auth.server.AuthHttpServer.createObjectMapper();
    private AdminHttpServer adminHttpServer;
    private AuthHttpHandler authHttpHandler;
    private DeviceHttpHandler deviceHttpHandler;
    private WorkspaceHttpServer workspaceHttpServer;
    private ChatHttpServer chatHttpServer;
    private FileHttpServer fileHttpServer;
    private HttpServer server;

    public FoundationHttpServer(String host, int port, int wsPort, HealthService healthService, ClientRegistry clientRegistry) {
        this(host, port, wsPort, healthService, clientRegistry, null, null, null);
    }

    public FoundationHttpServer(String host, int port, int wsPort, HealthService healthService, ClientRegistry clientRegistry, AdminHttpServer adminHttpServer) {
        this(host, port, wsPort, healthService, clientRegistry, adminHttpServer, null, null);
    }

    public FoundationHttpServer(
        String host,
        int port,
        int wsPort,
        HealthService healthService,
        ClientRegistry clientRegistry,
        AdminHttpServer adminHttpServer,
        AuthHttpHandler authHttpHandler,
        DeviceHttpHandler deviceHttpHandler
    ) {
        this(host, port, wsPort, healthService, clientRegistry, adminHttpServer,
            authHttpHandler, deviceHttpHandler, null, null, null);
    }

    /**
     * Creates the foundation HTTP server and optionally mounts the authenticated
     * workspace, chat, and file route handlers on the same port. Keeping these
     * handlers here is important: the current bootstrap owns one HttpServer, so
     * feature endpoints must be registered on it instead of on a second server.
     */
    public FoundationHttpServer(
        String host,
        int port,
        int wsPort,
        HealthService healthService,
        ClientRegistry clientRegistry,
        AdminHttpServer adminHttpServer,
        AuthHttpHandler authHttpHandler,
        DeviceHttpHandler deviceHttpHandler,
        WorkspaceHttpServer workspaceHttpServer,
        ChatHttpServer chatHttpServer,
        FileHttpServer fileHttpServer
    ) {
        this.host = host;
        this.port = port;
        this.wsPort = wsPort;
        this.healthService = healthService;
        this.clientRegistry = clientRegistry;
        this.adminHttpServer = adminHttpServer;
        this.authHttpHandler = authHttpHandler;
        this.deviceHttpHandler = deviceHttpHandler;
        this.workspaceHttpServer = workspaceHttpServer;
        this.chatHttpServer = chatHttpServer;
        this.fileHttpServer = fileHttpServer;
    }

    public void setAdminHttpServer(AdminHttpServer adminHttpServer) {
        this.adminHttpServer = adminHttpServer;
    }

    public AdminHttpServer getAdminHttpServer() {
        return adminHttpServer;
    }

    public void setAuthHttpHandler(AuthHttpHandler authHttpHandler) {
        this.authHttpHandler = authHttpHandler;
    }

    public AuthHttpHandler getAuthHttpHandler() {
        return authHttpHandler;
    }

    public void setDeviceHttpHandler(DeviceHttpHandler deviceHttpHandler) {
        this.deviceHttpHandler = deviceHttpHandler;
    }

    public DeviceHttpHandler getDeviceHttpHandler() {
        return deviceHttpHandler;
    }

    public void setWorkspaceHttpServer(WorkspaceHttpServer workspaceHttpServer) {
        this.workspaceHttpServer = workspaceHttpServer;
    }

    public void setChatHttpServer(ChatHttpServer chatHttpServer) {
        this.chatHttpServer = chatHttpServer;
    }

    public void setFileHttpServer(FileHttpServer fileHttpServer) {
        this.fileHttpServer = fileHttpServer;
    }

    public synchronized void start() throws IOException {
        if (server != null) return;

        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/health", this::handleHealth);
        server.createContext("/api/health", this::handleHealth);
        server.createContext("/status", this::handleStatus);
        server.createContext("/api/status", this::handleStatus);

        if (authHttpHandler != null) {
            authHttpHandler.register(server);
        }

        if (deviceHttpHandler != null) {
            deviceHttpHandler.register(server);
        }

        if (workspaceHttpServer != null) {
            workspaceHttpServer.register(server);
        }

        if (chatHttpServer != null) {
            chatHttpServer.register(server);
        }

        if (fileHttpServer != null) {
            fileHttpServer.register(server);
        }

        if (adminHttpServer != null) {
            adminHttpServer.register(server);
        }

        server.start();
    }

    public synchronized void stop(int delaySeconds) {
        if (server != null) {
            server.stop(delaySeconds);
            server = null;
        }
    }

    public int getPort() {
        if (server != null) {
            return server.getAddress().getPort();
        }
        return port;
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (handlePublicCors(exchange)) {
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        byte[] bytes = objectMapper.writeValueAsBytes(healthService.getHealthReport());
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (handlePublicCors(exchange)) {
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        int activeClients = clientRegistry != null ? clientRegistry.activeConnections().size() : 0;
        Map<String, Object> status = healthService.getStatusReport(activeClients, host, getPort(), wsPort);
        byte[] bytes = objectMapper.writeValueAsBytes(status);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private boolean handlePublicCors(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return true;
        }
        return false;
    }
}
