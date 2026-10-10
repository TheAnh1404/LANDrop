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
    private volatile int wsPort;
    private final ObjectMapper objectMapper = com.landrop.auth.server.AuthHttpServer.createObjectMapper();
    private AdminHttpServer adminHttpServer;
    private AuthHttpHandler authHttpHandler;
    private DeviceHttpHandler deviceHttpHandler;
    private WorkspaceHttpServer workspaceHttpServer;
    private ChatHttpServer chatHttpServer;
    private FileHttpServer fileHttpServer;
    private HttpServer server;
    private byte[] cachedPortalHtml;
    private byte[] cachedClientHtml;
    private java.util.concurrent.ExecutorService executor;

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
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);

        server.createContext("/", this::handleRoot);
        server.createContext("/connect", this::handleClientUi);
        server.createContext("/client", this::handleClientUi);
        server.createContext("/auth/login", this::handleClientUi);
        server.createContext("/auth/register", this::handleClientUi);
        server.createContext("/api", this::handleApiRoot);
        server.createContext("/api/", this::handleApiRoot);
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
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public int getPort() {
        if (server != null) {
            return server.getAddress().getPort();
        }
        return port;
    }

    public void setRealtimePort(int actualPort) {
        this.wsPort = actualPort;
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

    private void handleRoot(HttpExchange exchange) throws IOException {
        if (handlePublicCors(exchange)) {
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getRawQuery();
        boolean adminTarget = query != null && java.util.Arrays.stream(query.split("&")).anyMatch("target=admin"::equals);
        if ("/admin".equals(path) || (path != null && path.startsWith("/admin/")) || adminTarget) {
            byte[] bytes = objectMapper.writeValueAsBytes(Map.of("error", "ADMIN_WEB_REMOVED", "message", "Use LANDrop Server Desktop for administration"));
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(410, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
            return;
        }
        boolean isClientPath = path == null || path.isEmpty() || "/".equals(path) || "/index.html".equals(path)
                || "/connect".equals(path) || "/client".equals(path)
                || (path != null && (path.startsWith("/auth/") || path.startsWith("/w/") || "/workspaces".equals(path)));

        if (isClientPath) {
            // If JSON requested explicitly by an API client, return API status catalog
            String accept = exchange.getRequestHeaders().getFirst("Accept");
            if (accept != null && accept.contains("application/json") && !accept.contains("text/html")) {
                handleApiRoot(exchange);
                return;
            }

            byte[] bytes = getClientHtmlBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
            return;
        }

        if ("/favicon.ico".equals(path)) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }

        // For any browser navigation request expecting HTML, serve the Client SPA
        String accept = exchange.getRequestHeaders().getFirst("Accept");
        if (accept != null && accept.contains("text/html")) {
            byte[] bytes = getClientHtmlBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
            return;
        }

        // Return structured 404 for unmatched non-HTML routes
        Map<String, Object> notFound = Map.of(
            "error", "NOT_FOUND",
            "message", "Endpoint not found: " + path,
            "status", 404
        );
        byte[] bytes = objectMapper.writeValueAsBytes(notFound);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(404, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void handleApiRoot(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!"/api".equals(path) && !"/api/".equals(path)) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type");
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            boolean unavailable = authHttpHandler == null && (path.startsWith("/api/auth/")
                || path.startsWith("/api/devices") || path.startsWith("/api/workspaces")
                || path.startsWith("/api/channels") || path.startsWith("/api/messages")
                || path.startsWith("/api/files") || path.startsWith("/api/folders") || path.startsWith("/api/transfers"));
            byte[] bytes = objectMapper.writeValueAsBytes(Map.of(
                "error", unavailable ? "PERSISTENCE_UNAVAILABLE" : "ROUTE_NOT_FOUND",
                "message", unavailable ? "Database persistence is disabled. Configure DATABASE_URL and restart the server." : "Route was not found.",
                "status", unavailable ? 503 : 404));
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(unavailable ? 503 : 404, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
            return;
        }
        if (handlePublicCors(exchange)) {
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        Map<String, Object> apiInfo = new java.util.LinkedHashMap<>();
        apiInfo.put("service", "LANDrop Server API");
        apiInfo.put("status", "UP");
        apiInfo.put("version", 1);
        apiInfo.put("endpoints", Map.of(
            "health", "/health",
            "status", "/status",
            "adminApi", "/api/admin",
            "auth", "/api/auth",
            "workspaces", "/api/workspaces",
            "devices", "/api/devices"
        ));

        byte[] bytes = objectMapper.writeValueAsBytes(apiInfo);
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

    private synchronized byte[] getPortalHtmlBytes() {
        if (cachedPortalHtml != null) {
            return cachedPortalHtml;
        }

        try (java.io.InputStream is = getClass().getResourceAsStream("/portal/index.html")) {
            if (is != null) {
                cachedPortalHtml = is.readAllBytes();
                return cachedPortalHtml;
            }
        } catch (IOException ignored) {}

        String fallback = """
            <!DOCTYPE html><html lang="vi"><head><meta charset="UTF-8"><title>LANDrop Portal</title></head>
            <body style="font-family:sans-serif; background:#0f172a; color:#f8fafc; padding:2rem; text-align:center;">
            <h1>LANDrop LAN Portal</h1>
            <p>Không gian truyền tệp và cộng tác mạng nội bộ LANDrop.</p>
            <div style="margin:2rem 0;">
              <p>LANDrop Server Desktop</p>
            </div>
            </body></html>
            """;
        cachedPortalHtml = fallback.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return cachedPortalHtml;
    }

    private void handleClientUi(HttpExchange exchange) throws IOException {
        if (handlePublicCors(exchange)) {
            return;
        }

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        byte[] bytes = getClientHtmlBytes();
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private synchronized byte[] getClientHtmlBytes() {
        if (cachedClientHtml != null) {
            return cachedClientHtml;
        }

        try (java.io.InputStream is = getClass().getResourceAsStream("/client/index.html")) {
            if (is != null) {
                cachedClientHtml = is.readAllBytes();
                return cachedClientHtml;
            }
        } catch (IOException ignored) {}

        return getPortalHtmlBytes();
    }
}
