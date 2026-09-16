package com.landrop.admin.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.admin.dto.AdminDashboardDto;
import com.landrop.admin.dto.AdminUserDto;
import com.landrop.admin.security.AdminAuthMiddleware;
import com.landrop.admin.service.AdminControlCenterService;
import com.landrop.auth.dto.ErrorResponse;
import com.landrop.auth.dto.LoginRequest;
import com.landrop.auth.dto.AuthResponse;
import com.landrop.auth.exception.AuthException;
import com.landrop.auth.exception.ValidationException;
import com.landrop.auth.security.JwtClaims;
import com.landrop.auth.service.AuthService;
import com.landrop.util.AppLogger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/**
 * Embedded HTTP server handler for LANDrop Server Control Center (FOUNDATION-03A).
 * Exposes /admin Web Console and /api/admin/* + /api/server/* REST endpoints.
 */
public class AdminHttpServer {

    private static final String TAG = "AdminHttpServer";
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final AdminAuthMiddleware authMiddleware;
    private final AuthService authService;
    private final AdminControlCenterService service;
    private final ObjectMapper objectMapper;
    private final String bindHost;
    private final int httpPort;
    private final int wsPort;
    private volatile int actualHttpPort = -1;
    private volatile int actualWsPort = -1;

    private byte[] cachedConsoleHtml;

    public void setActualPorts(int httpPort, int wsPort) {
        this.actualHttpPort = httpPort;
        this.actualWsPort = wsPort;
    }

    public int getEffectiveHttpPort() {
        return (actualHttpPort > 0) ? actualHttpPort : httpPort;
    }

    public int getEffectiveWsPort() {
        return (actualWsPort > 0) ? actualWsPort : wsPort;
    }

    public AdminHttpServer(
        AdminAuthMiddleware authMiddleware,
        AdminControlCenterService service,
        ObjectMapper objectMapper,
        String bindHost,
        int httpPort,
        int wsPort
    ) {
        this(authMiddleware, service, objectMapper, bindHost, httpPort, wsPort, null);
    }

    public AdminHttpServer(
        AdminAuthMiddleware authMiddleware,
        AdminControlCenterService service,
        ObjectMapper objectMapper,
        String bindHost,
        int httpPort,
        int wsPort,
        AuthService authService
    ) {
        this.authMiddleware = Objects.requireNonNull(authMiddleware, "authMiddleware");
        this.service = Objects.requireNonNull(service, "service");
        this.authService = authService;
        this.objectMapper = configureObjectMapper(objectMapper != null ? objectMapper : new ObjectMapper());
        this.bindHost = bindHost != null ? bindHost : "0.0.0.0";
        this.httpPort = httpPort;
        this.wsPort = wsPort;
    }

    public static ObjectMapper createObjectMapper() {
        return configureObjectMapper(new ObjectMapper());
    }

    public static ObjectMapper configureObjectMapper(ObjectMapper mapper) {
        com.fasterxml.jackson.databind.module.SimpleModule timeModule = new com.fasterxml.jackson.databind.module.SimpleModule();
        timeModule.addSerializer(Instant.class, new com.fasterxml.jackson.databind.JsonSerializer<>() {
            @Override
            public void serialize(Instant value, com.fasterxml.jackson.core.JsonGenerator gen, com.fasterxml.jackson.databind.SerializerProvider serializers) throws IOException {
                gen.writeString(value != null ? value.toString() : null);
            }
        });
        timeModule.addDeserializer(Instant.class, new com.fasterxml.jackson.databind.JsonDeserializer<>() {
            @Override
            public Instant deserialize(com.fasterxml.jackson.core.JsonParser p, com.fasterxml.jackson.databind.DeserializationContext ctxt) throws IOException {
                String text = p.getText();
                return (text != null && !text.isBlank()) ? Instant.parse(text) : null;
            }
        });
        mapper.registerModule(timeModule);
        return mapper;
    }

    public void register(HttpServer server) {
        // Web Console UI
        server.createContext("/admin", this::handleAdminUi);
        server.createContext("/admin/", this::handleAdminUi);

        // Control Center REST Endpoints
        server.createContext("/api/admin/auth/login", this::handleAdminLogin);
        server.createContext("/api/admin/dashboard", this::handleDashboard);
        server.createContext("/api/admin/users", this::handleUsers);
        server.createContext("/api/admin/users/", this::handleUsers);
        server.createContext("/api/admin/sessions", this::handleSessions);
        server.createContext("/api/admin/sessions/", this::handleSessions);
        server.createContext("/api/admin/clients", this::handleClients);
        server.createContext("/api/admin/clients/", this::handleClients);
        server.createContext("/api/admin/logs", this::handleLogs);

        // Server Information, Interfaces, and Configuration (accessible via both /api/admin/server/* and /api/server/*)
        server.createContext("/api/admin/server", this::handleServerRoutes);
        server.createContext("/api/admin/server/", this::handleServerRoutes);
        server.createContext("/api/server", this::handleServerRoutes);
        server.createContext("/api/server/", this::handleServerRoutes);

        AppLogger.info(TAG, "Admin Control Center endpoints registered at /admin and /api/admin/*");
    }

    /** Login endpoint reserved for the Server Control Center. */
    private void handleAdminLogin(HttpExchange exchange) throws IOException {
        if (handleCorsPreflight(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange, "POST");
            return;
        }
        if (authService == null) {
            sendJsonResponse(exchange, 503, new ErrorResponse("SERVICE_UNAVAILABLE", "Admin authentication is unavailable", 503));
            return;
        }
        try {
            LoginRequest request = objectMapper.readValue(readRequestBody(exchange), LoginRequest.class);
            AuthResponse response = authService.loginAdmin(request);
            sendJsonResponse(exchange, 200, response);
        } catch (Exception e) {
            handleError(exchange, e);
        }
    }

    // =========================================================================
    // 1. Web Console UI Handler
    // =========================================================================

    private void handleAdminUi(HttpExchange exchange) throws IOException {
        if (handleCorsPreflight(exchange)) return;

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange, "GET");
            return;
        }

        byte[] htmlBytes = getConsoleHtmlBytes();
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, htmlBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(htmlBytes);
        }
    }

    private synchronized byte[] getConsoleHtmlBytes() {
        if (cachedConsoleHtml != null) {
            return cachedConsoleHtml;
        }

        try (InputStream is = getClass().getResourceAsStream("/admin/index.html")) {
            if (is != null) {
                cachedConsoleHtml = is.readAllBytes();
                return cachedConsoleHtml;
            }
        } catch (IOException ignored) {}

        // Fallback minimal HTML
        String fallback = """
            <!DOCTYPE html><html><head><title>LANDrop Server Control Center</title></head>
            <body style="font-family:sans-serif; background:#0f172a; color:#f8fafc; padding:2rem;">
            <h1>LANDrop Server Control Center</h1><p>Control center active.</p></body></html>
            """;
        cachedConsoleHtml = fallback.getBytes(StandardCharsets.UTF_8);
        return cachedConsoleHtml;
    }

    // =========================================================================
    // 2. Admin Dashboard API
    // =========================================================================

    private void handleDashboard(HttpExchange exchange) throws IOException {
        if (handleCorsPreflight(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange, "GET");
            return;
        }

        try {
            authMiddleware.requireSystemAdmin(exchange);
            AdminDashboardDto dashboard = service.getDashboard();
            sendJsonResponse(exchange, 200, dashboard);
        } catch (Exception e) {
            handleError(exchange, e);
        }
    }

    // =========================================================================
    // 3. User Management APIs
    // =========================================================================

    private void handleUsers(HttpExchange exchange) throws IOException {
        if (handleCorsPreflight(exchange)) return;

        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod().toUpperCase();

        try {
            JwtClaims adminClaims = authMiddleware.requireSystemAdmin(exchange);

            if ("/api/admin/users".equals(path) || "/api/admin/users/".equals(path)) {
                if ("GET".equals(method)) {
                    Map<String, String> q = parseQueryParams(exchange.getRequestURI().getQuery());
                    int offset = parseInt(q.get("offset"), 0);
                    int limit = parseInt(q.get("limit"), 50);
                    String search = q.get("search");
                    String status = q.get("status");

                    var users = service.listUsers(offset, limit, search, status);
                    sendJsonResponse(exchange, 200, users);
                    return;
                }
                sendMethodNotAllowed(exchange, "GET");
                return;
            }

            // Sub-routes: /api/admin/users/{id}, /api/admin/users/{id}/disable, /api/admin/users/{id}/enable, /api/admin/users/{id}/revoke-sessions
            String prefix = "/api/admin/users/";
            if (path.startsWith(prefix)) {
                String remainder = path.substring(prefix.length());
                String[] parts = remainder.split("/");

                UUID targetUserId;
                try {
                    targetUserId = UUID.fromString(parts[0]);
                } catch (IllegalArgumentException e) {
                    throw new ValidationException("Invalid user UUID format");
                }

                if (parts.length == 1) {
                    if ("GET".equals(method)) {
                        Optional<AdminUserDto> userOpt = service.getUser(targetUserId);
                        if (userOpt.isEmpty()) {
                            sendJsonResponse(exchange, 404, new ErrorResponse("Not Found", "User not found", 404));
                            return;
                        }
                        sendJsonResponse(exchange, 200, userOpt.get());
                        return;
                    }
                    sendMethodNotAllowed(exchange, "GET");
                    return;
                }

                String action = parts[1].toLowerCase();
                if ("disable".equals(action)) {
                    if (!"POST".equals(method)) { sendMethodNotAllowed(exchange, "POST"); return; }
                    boolean ok = service.disableUser(targetUserId, adminClaims.userId(), adminClaims.username());
                    sendJsonResponse(exchange, 200, Map.of("success", ok, "userId", targetUserId, "status", "DISABLED"));
                    return;
                } else if ("enable".equals(action)) {
                    if (!"POST".equals(method)) { sendMethodNotAllowed(exchange, "POST"); return; }
                    boolean ok = service.enableUser(targetUserId, adminClaims.username());
                    sendJsonResponse(exchange, 200, Map.of("success", ok, "userId", targetUserId, "status", "ACTIVE"));
                    return;
                } else if ("revoke-sessions".equals(action)) {
                    if (!"POST".equals(method)) { sendMethodNotAllowed(exchange, "POST"); return; }
                    int count = service.revokeUserSessions(targetUserId, adminClaims.username());
                    sendJsonResponse(exchange, 200, Map.of("success", true, "userId", targetUserId, "revokedSessions", count));
                    return;
                }
            }

            sendJsonResponse(exchange, 404, new ErrorResponse("Not Found", "Endpoint not found", 404));
        } catch (Exception e) {
            handleError(exchange, e);
        }
    }

    // =========================================================================
    // 4. Session Management APIs
    // =========================================================================

    private void handleSessions(HttpExchange exchange) throws IOException {
        if (handleCorsPreflight(exchange)) return;

        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod().toUpperCase();

        try {
            JwtClaims adminClaims = authMiddleware.requireSystemAdmin(exchange);

            if ("/api/admin/sessions".equals(path) || "/api/admin/sessions/".equals(path)) {
                if ("GET".equals(method)) {
                    Map<String, String> q = parseQueryParams(exchange.getRequestURI().getQuery());
                    boolean activeOnly = !"false".equalsIgnoreCase(q.get("activeOnly"));
                    int offset = parseInt(q.get("offset"), 0);
                    int limit = parseInt(q.get("limit"), 50);
                    UUID userId = null;
                    if (q.containsKey("userId") && !q.get("userId").isBlank()) {
                        try { userId = UUID.fromString(q.get("userId")); } catch (IllegalArgumentException ignored) {}
                    }

                    var sessions = service.listSessions(activeOnly, userId, limit, offset);
                    sendJsonResponse(exchange, 200, sessions);
                    return;
                }
                sendMethodNotAllowed(exchange, "GET");
                return;
            }

            // Sub-route: /api/admin/sessions/{id}/revoke
            String prefix = "/api/admin/sessions/";
            if (path.startsWith(prefix)) {
                String remainder = path.substring(prefix.length());
                String[] parts = remainder.split("/");

                UUID sessionId;
                try {
                    sessionId = UUID.fromString(parts[0]);
                } catch (IllegalArgumentException e) {
                    throw new ValidationException("Invalid session UUID format");
                }

                if (parts.length >= 2 && "revoke".equalsIgnoreCase(parts[1])) {
                    if (!"POST".equals(method)) { sendMethodNotAllowed(exchange, "POST"); return; }
                    boolean revoked = service.revokeSession(sessionId, adminClaims.username());
                    sendJsonResponse(exchange, 200, Map.of("success", revoked, "sessionId", sessionId, "revoked", true));
                    return;
                }
            }

            sendJsonResponse(exchange, 404, new ErrorResponse("Not Found", "Endpoint not found", 404));
        } catch (Exception e) {
            handleError(exchange, e);
        }
    }

    // =========================================================================
    // 5. Client Management APIs
    // =========================================================================

    private void handleClients(HttpExchange exchange) throws IOException {
        if (handleCorsPreflight(exchange)) return;

        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod().toUpperCase();

        try {
            JwtClaims adminClaims = authMiddleware.requireSystemAdmin(exchange);

            if ("/api/admin/clients".equals(path) || "/api/admin/clients/".equals(path)) {
                if ("GET".equals(method)) {
                    var clients = service.listClients();
                    sendJsonResponse(exchange, 200, clients);
                    return;
                }
                sendMethodNotAllowed(exchange, "GET");
                return;
            }

            // Sub-route: /api/admin/clients/{connectionId}/disconnect
            String prefix = "/api/admin/clients/";
            if (path.startsWith(prefix)) {
                String remainder = path.substring(prefix.length());
                String[] parts = remainder.split("/");

                String connectionId = parts[0];
                if (parts.length >= 2 && "disconnect".equalsIgnoreCase(parts[1])) {
                    if (!"POST".equals(method)) { sendMethodNotAllowed(exchange, "POST"); return; }

                    String reason = "Disconnected by Administrator";
                    byte[] bodyBytes = readRequestBody(exchange);
                    if (bodyBytes.length > 0) {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> map = objectMapper.readValue(bodyBytes, Map.class);
                            if (map.containsKey("reason") && map.get("reason") != null) {
                                reason = map.get("reason").toString();
                            }
                        } catch (Exception ignored) {}
                    }

                    boolean disconnected = service.disconnectClient(connectionId, reason, adminClaims.username());
                    sendJsonResponse(exchange, 200, Map.of("success", disconnected, "connectionId", connectionId));
                    return;
                }
            }

            sendJsonResponse(exchange, 404, new ErrorResponse("Not Found", "Endpoint not found", 404));
        } catch (Exception e) {
            handleError(exchange, e);
        }
    }

    // =========================================================================
    // 6. Operational Logging APIs
    // =========================================================================

    private void handleLogs(HttpExchange exchange) throws IOException {
        if (handleCorsPreflight(exchange)) return;

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange, "GET");
            return;
        }

        try {
            authMiddleware.requireSystemAdmin(exchange);
            Map<String, String> q = parseQueryParams(exchange.getRequestURI().getQuery());
            int offset = parseInt(q.get("offset"), 0);
            int limit = parseInt(q.get("limit"), 50);
            String level = q.get("level");
            String type = q.get("type");
            String search = q.get("search");

            var logs = service.getLogs(limit, offset, level, type, search);
            sendJsonResponse(exchange, 200, logs);
        } catch (Exception e) {
            handleError(exchange, e);
        }
    }

    // =========================================================================
    // 7. Server Routes (info, interfaces, config)
    // =========================================================================

    private void handleServerRoutes(HttpExchange exchange) throws IOException {
        if (handleCorsPreflight(exchange)) return;

        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod().toUpperCase();

        try {
            JwtClaims adminClaims = authMiddleware.requireSystemAdmin(exchange);

            if (path.endsWith("/info")) {
                if (!"GET".equals(method)) { sendMethodNotAllowed(exchange, "GET"); return; }
                var info = service.getServerInfo(bindHost, getEffectiveHttpPort(), getEffectiveWsPort());
                sendJsonResponse(exchange, 200, info);
                return;
            }

            if (path.endsWith("/interfaces")) {
                if (!"GET".equals(method)) { sendMethodNotAllowed(exchange, "GET"); return; }
                var interfaces = service.getNetworkInterfaces(bindHost);
                sendJsonResponse(exchange, 200, interfaces);
                return;
            }

            if (path.endsWith("/config")) {
                if ("GET".equals(method)) {
                    var config = service.getConfig();
                    sendJsonResponse(exchange, 200, config);
                    return;
                } else if ("POST".equals(method) || "PUT".equals(method)) {
                    byte[] bodyBytes = readRequestBody(exchange);
                    if (bodyBytes.length == 0) {
                        throw new ValidationException("Request body cannot be empty");
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> updates = objectMapper.readValue(bodyBytes, Map.class);
                    var updated = service.updateConfig(updates, adminClaims.username());
                    sendJsonResponse(exchange, 200, service.getConfig());
                    return;
                }
                sendMethodNotAllowed(exchange, "GET, POST, PUT");
                return;
            }

            sendJsonResponse(exchange, 404, new ErrorResponse("Not Found", "Endpoint not found: " + path, 404));
        } catch (Exception e) {
            handleError(exchange, e);
        }
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private boolean handleCorsPreflight(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return true;
        }
        return false;
    }

    private byte[] readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return is.readNBytes(MAX_BODY_BYTES);
        }
    }

    private void sendJsonResponse(HttpExchange exchange, int statusCode, Object body) throws IOException {
        byte[] responseBytes = objectMapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }

    private void sendMethodNotAllowed(HttpExchange exchange, String allowed) throws IOException {
        ErrorResponse err = new ErrorResponse("Method Not Allowed", "Allowed: " + allowed, 405);
        sendJsonResponse(exchange, 405, err);
    }

    private void handleError(HttpExchange exchange, Exception e) throws IOException {
        int status = 500;
        String errorName = "Internal Server Error";
        String message = e.getMessage();

        if (e instanceof AuthException ae) {
            status = ae.getStatusCode();
            errorName = switch (status) {
                case 400 -> "Bad Request";
                case 401 -> "Unauthorized";
                case 403 -> "Forbidden";
                case 404 -> "Not Found";
                case 409 -> "Conflict";
                default -> "Authentication Error";
            };
        } else if (e instanceof SQLException) {
            status = 500;
            errorName = "Database Error";
            message = "Internal database operation failed";
            AppLogger.error(TAG, "Database error", e);
        } else {
            AppLogger.error(TAG, "Unexpected error", e);
        }

        try {
            ErrorResponse err = new ErrorResponse(errorName, message != null ? message : errorName, status);
            sendJsonResponse(exchange, status, err);
        } catch (Exception writeEx) {
            String fallback = "{\"error\":\"" + errorName + "\",\"message\":\"" + (message != null ? message.replace("\"", "\\\"") : "") + "\",\"status\":" + status + "}";
            byte[] b = fallback.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, b.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(b);
            }
        }
    }

    private Map<String, String> parseQueryParams(String query) {
        if (query == null || query.isBlank()) return Map.of();
        Map<String, String> map = new HashMap<>();
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String val = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                map.put(key, val);
            }
        }
        return map;
    }

    private int parseInt(String val, int defaultVal) {
        if (val == null || val.isBlank()) return defaultVal;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
}
