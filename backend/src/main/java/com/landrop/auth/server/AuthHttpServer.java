package com.landrop.auth.server;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.landrop.auth.config.AuthConfig;
import com.landrop.auth.dto.*;
import com.landrop.auth.exception.AuthException;
import com.landrop.auth.exception.ValidationException;
import com.landrop.auth.repository.RefreshTokenRepository;
import com.landrop.auth.security.JwtClaims;
import com.landrop.auth.security.JwtService;
import com.landrop.auth.service.AuthService;
import com.landrop.database.DatabaseManager;
import com.landrop.database.repository.UserRepository;
import com.landrop.database.service.WorkspaceService;
import com.landrop.workspace.http.WorkspaceHttpServer;
import com.landrop.util.AppLogger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP server for Phase 15A Authentication REST API.
 * Uses Java 21 standard HttpServer with Virtual Threads.
 */
public class AuthHttpServer {

    private static final String TAG = "AuthHttpServer";

    private final int port;
    private final AuthService authService;
    private final AuthMiddleware authMiddleware;
    private final WorkspaceHttpServer workspaceHttpServer;
    private final com.landrop.chat.server.ChatHttpServer chatHttpServer;
    private final com.landrop.chat.service.ChannelChatService channelChatService;
    private final com.landrop.file.service.FileManagementService fileManagementService;
    private final com.landrop.file.service.TransferCoordinationService transferCoordinationService;
    private final com.landrop.file.server.FileHttpServer fileHttpServer;
    private final ObjectMapper objectMapper;
    private HttpServer httpServer;

    public AuthHttpServer(AuthService authService, AuthMiddleware authMiddleware) {
        this(AuthConfig.getAuthPort(), authService, authMiddleware, new WorkspaceService(), new com.landrop.chat.service.ChannelChatService());
    }

    public AuthHttpServer(int port, AuthService authService, AuthMiddleware authMiddleware) {
        this(port, authService, authMiddleware, new WorkspaceService(), new com.landrop.chat.service.ChannelChatService());
    }

    public AuthHttpServer(int port, AuthService authService, AuthMiddleware authMiddleware,
                          WorkspaceService workspaceService) {
        this(port, authService, authMiddleware, workspaceService, new com.landrop.chat.service.ChannelChatService());
    }

    public AuthHttpServer(int port, AuthService authService, AuthMiddleware authMiddleware,
                          WorkspaceService workspaceService, com.landrop.chat.service.ChannelChatService channelChatService) {
        this(port, authService, authMiddleware, workspaceService, channelChatService,
             new com.landrop.file.service.FileManagementService(), new com.landrop.file.service.TransferCoordinationService());
    }

    public AuthHttpServer(int port, AuthService authService, AuthMiddleware authMiddleware,
                          WorkspaceService workspaceService, com.landrop.chat.service.ChannelChatService channelChatService,
                          com.landrop.file.service.FileManagementService fileManagementService,
                          com.landrop.file.service.TransferCoordinationService transferCoordinationService) {
        this.port = port;
        this.authService = authService;
        this.authMiddleware = authMiddleware;
        this.objectMapper = createObjectMapper();
        this.channelChatService = channelChatService != null ? channelChatService : new com.landrop.chat.service.ChannelChatService();
        this.fileManagementService = fileManagementService != null ? fileManagementService : new com.landrop.file.service.FileManagementService();
        this.transferCoordinationService = transferCoordinationService != null ? transferCoordinationService : new com.landrop.file.service.TransferCoordinationService();
        this.fileHttpServer = new com.landrop.file.server.FileHttpServer(authMiddleware, this.fileManagementService, this.transferCoordinationService, objectMapper);
        this.workspaceHttpServer = new WorkspaceHttpServer(authMiddleware, workspaceService, this.channelChatService, this.fileHttpServer, objectMapper);
        this.chatHttpServer = new com.landrop.chat.server.ChatHttpServer(authMiddleware, this.channelChatService, objectMapper);
    }

    public static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.getFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        SimpleModule timeModule = new SimpleModule();
        timeModule.addSerializer(Instant.class, new JsonSerializer<>() {
            @Override
            public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.writeString(value != null ? value.toString() : null);
            }
        });
        timeModule.addDeserializer(Instant.class, new JsonDeserializer<>() {
            @Override
            public Instant deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                String text = p.getText();
                return (text != null && !text.isBlank()) ? Instant.parse(text) : null;
            }
        });
        mapper.registerModule(timeModule);
        return mapper;
    }

    public synchronized void start() throws IOException {
        if (httpServer != null) {
            return;
        }

        httpServer = HttpServer.create(new InetSocketAddress(AuthConfig.getBindHost(), port), 0);
        httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        // Configure REST endpoints
        httpServer.createContext("/api/auth/register", this::handleRegister);
        httpServer.createContext("/api/auth/login", this::handleLogin);
        httpServer.createContext("/api/auth/refresh", this::handleRefresh);
        httpServer.createContext("/api/auth/logout", this::handleLogout);
        httpServer.createContext("/api/auth/me", this::handleMe);
        httpServer.createContext("/api/auth/health", this::handleHealth);
        httpServer.createContext("/health", this::handleHealth);
        workspaceHttpServer.register(httpServer);
        chatHttpServer.register(httpServer);
        fileHttpServer.register(httpServer);

        httpServer.start();
        AppLogger.info(TAG, "Auth HTTP Server started on port " + getPort() + " (Virtual Threads enabled)");
    }

    public com.landrop.chat.service.ChannelChatService getChannelChatService() {
        return channelChatService;
    }

    public com.landrop.file.service.FileManagementService getFileManagementService() {
        return fileManagementService;
    }

    public com.landrop.file.service.TransferCoordinationService getTransferCoordinationService() {
        return transferCoordinationService;
    }

    public com.landrop.file.server.FileHttpServer getFileHttpServer() {
        return fileHttpServer;
    }

    public synchronized void stop(int delaySeconds) {
        if (httpServer != null) {
            httpServer.stop(delaySeconds);
            httpServer = null;
            AppLogger.info(TAG, "Auth HTTP Server stopped");
        }
    }

    public int getPort() {
        if (httpServer != null) {
            return httpServer.getAddress().getPort();
        }
        return port;
    }

    public String getBaseUrl() {
        return "http://localhost:" + getPort();
    }

    // ==========================================
    // ENDPOINT HANDLERS
    // ==========================================

    private void handleRegister(HttpExchange exchange) throws IOException {
        if (handleCorsPreflight(exchange)) return;

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange, "POST");
            return;
        }

        try {
            RegisterRequest req = parseRequestBody(exchange, RegisterRequest.class);
            AuthResponse response = authService.register(req);
            sendJsonResponse(exchange, 201, response);
        } catch (Exception e) {
            handleError(exchange, e);
        }
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        if (handleCorsPreflight(exchange)) return;

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange, "POST");
            return;
        }

        try {
            LoginRequest req = parseRequestBody(exchange, LoginRequest.class);
            AuthResponse response = authService.login(req);
            sendJsonResponse(exchange, 200, response);
        } catch (Exception e) {
            handleError(exchange, e);
        }
    }

    private void handleRefresh(HttpExchange exchange) throws IOException {
        if (handleCorsPreflight(exchange)) return;

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange, "POST");
            return;
        }

        try {
            RefreshRequest req = parseRequestBody(exchange, RefreshRequest.class);
            AuthResponse response = authService.refresh(req);
            sendJsonResponse(exchange, 200, response);
        } catch (Exception e) {
            handleError(exchange, e);
        }
    }

    private void handleLogout(HttpExchange exchange) throws IOException {
        if (handleCorsPreflight(exchange)) return;

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange, "POST");
            return;
        }

        try {
            String refreshToken = null;
            byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
            if (bodyBytes.length > 0) {
                try {
                    LogoutRequest req = objectMapper.readValue(bodyBytes, LogoutRequest.class);
                    refreshToken = req.refreshToken();
                } catch (Exception ignored) {
                }
            }

            if (refreshToken != null && !refreshToken.isBlank()) {
                authService.logout(refreshToken);
            }

            sendJsonResponse(exchange, 200, Map.of(
                "success", true,
                "message", "Logged out successfully"
            ));
        } catch (Exception e) {
            handleError(exchange, e);
        }
    }

    private void handleMe(HttpExchange exchange) throws IOException {
        if (handleCorsPreflight(exchange)) return;

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange, "GET");
            return;
        }

        try {
            JwtClaims claims = authMiddleware.authenticate(exchange);
            UserDto user = authService.getCurrentUser(claims.userId());
            sendJsonResponse(exchange, 200, user);
        } catch (Exception e) {
            handleError(exchange, e);
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (handleCorsPreflight(exchange)) return;

        String path = exchange.getRequestURI().getPath();
        if ("/health".equals(path)) {
            long uptimeSeconds = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
            sendJsonResponse(exchange, 200, Map.of(
                "status", "ok",
                "serverVersion", com.landrop.server.protocol.ProtocolConstants.SERVER_VERSION,
                "protocolVersion", com.landrop.server.protocol.ProtocolConstants.PROTOCOL_VERSION,
                "uptime", uptimeSeconds
            ));
            return;
        }

        sendJsonResponse(exchange, 200, Map.of(
            "status", "UP",
            "phase", "15A",
            "service", "LANDrop-Authentication",
            "timestamp", Instant.now().toString()
        ));
    }

    // ==========================================
    // UTILITY METHODS
    // ==========================================

    private boolean handleCorsPreflight(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return true;
        }
        return false;
    }

    private <T> T parseRequestBody(HttpExchange exchange, Class<T> clazz) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            byte[] bytes = is.readAllBytes();
            if (bytes.length == 0) {
                throw new ValidationException("Request body cannot be empty");
            }
            return objectMapper.readValue(bytes, clazz);
        }
    }

    private void sendJsonResponse(HttpExchange exchange, int statusCode, Object body) throws IOException {
        byte[] responseBytes = objectMapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }

    private void sendMethodNotAllowed(HttpExchange exchange, String allowedMethod) throws IOException {
        ErrorResponse err = new ErrorResponse("Method Not Allowed", "Allowed method: " + allowedMethod, 405);
        sendJsonResponse(exchange, 405, err);
    }

    private void handleError(HttpExchange exchange, Exception e) throws IOException {
        int status;
        String errorName;
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
            AppLogger.error(TAG, "Database error processing HTTP request", e);
        } else {
            status = 500;
            errorName = "Internal Server Error";
            message = "An unexpected error occurred";
            AppLogger.error(TAG, "Unexpected error processing HTTP request", e);
        }

        ErrorResponse err = new ErrorResponse(errorName, message != null ? message : errorName, status);
        sendJsonResponse(exchange, status, err);
    }

    public static void main(String[] args) {
        try {
            DatabaseManager dbManager = DatabaseManager.getInstance();
            if (!dbManager.testConnection()) {
                System.err.println("Database connection failed. Please verify your DATABASE_URL in .env");
                System.exit(1);
            }

            UserRepository userRepository = new UserRepository(dbManager);
            RefreshTokenRepository refreshTokenRepository = new RefreshTokenRepository(dbManager);
            JwtService jwtService = new JwtService(
                AuthConfig.getJwtAccessSecret(),
                AuthConfig.getJwtRefreshSecret(),
                AuthConfig.getAccessTokenExpirySeconds(),
                AuthConfig.getRefreshTokenExpirySeconds()
            );
            AuthService authService = new AuthService(userRepository, refreshTokenRepository, jwtService);
            AuthMiddleware authMiddleware = new AuthMiddleware(jwtService);

            int port = AuthConfig.getAuthPort();
            AuthHttpServer server = new AuthHttpServer(port, authService, authMiddleware);
            var realtime = new com.landrop.realtime.RealtimeServer(
                new InetSocketAddress(AuthConfig.getBindHost(), AuthConfig.getRealtimePort()),
                com.landrop.realtime.SocketAuthenticator.jwt(jwtService, userRepository),
                new com.landrop.realtime.JdbcRealtimeStore(dbManager));
            server.getChannelChatService().setBroadcaster(realtime);
            server.getTransferCoordinationService().setBroadcaster(realtime);
            realtime.setTransferService(server.getTransferCoordinationService());
            realtime.start();
            try {
                realtime.awaitReady();
                server.start();
            } catch (Exception startupFailure) {
                realtime.stop(1000);
                dbManager.close();
                throw startupFailure;
            }

            System.out.println("==================================================");
            System.out.println("  LANDrop Authentication Server (Phase 15A)");
            System.out.println("  Listening on http://localhost:" + server.getPort());
            System.out.println("  Bind: " + AuthConfig.getBindHost() + "; realtime port: " + realtime.getPort());
            System.out.println("==================================================");

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Stopping Auth Server...");
                server.stop(0);
                try { realtime.stop(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                try {
                    dbManager.close();
                } catch (Exception ignored) {}
            }));
            new java.util.concurrent.CountDownLatch(1).await();
        } catch (Exception e) {
            AppLogger.error(TAG, "Failed to start Auth HTTP Server", e);
            System.err.println("Fatal: " + e.getMessage());
            System.exit(1);
        }
    }
}
