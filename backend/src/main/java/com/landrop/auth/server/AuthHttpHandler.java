package com.landrop.auth.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.auth.dto.*;
import com.landrop.auth.exception.AuthException;
import com.landrop.auth.exception.ValidationException;
import com.landrop.auth.model.AuthErrorCode;
import com.landrop.auth.security.JwtClaims;
import com.landrop.auth.service.AuthService;
import com.landrop.server.clients.ClientRegistry;
import com.landrop.util.AppLogger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Standard HTTP handler exposing REST endpoints for User Authentication (FOUNDATION-04A.2 - 04A.3, 04A.12).
 */
public class AuthHttpHandler {

    private static final String TAG = "AuthHttpHandler";
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final AuthService authService;
    private final AuthMiddleware authMiddleware;
    private final ClientRegistry clientRegistry;
    private final ObjectMapper objectMapper;

    public AuthHttpHandler(AuthService authService, AuthMiddleware authMiddleware) {
        this(authService, authMiddleware, null, AuthHttpServer.createObjectMapper());
    }

    public AuthHttpHandler(AuthService authService, AuthMiddleware authMiddleware, ClientRegistry clientRegistry) {
        this(authService, authMiddleware, clientRegistry, AuthHttpServer.createObjectMapper());
    }

    public AuthHttpHandler(AuthService authService, AuthMiddleware authMiddleware, ClientRegistry clientRegistry, ObjectMapper objectMapper) {
        this.authService = Objects.requireNonNull(authService, "authService cannot be null");
        this.authMiddleware = Objects.requireNonNull(authMiddleware, "authMiddleware cannot be null");
        this.clientRegistry = clientRegistry;
        this.objectMapper = objectMapper != null ? objectMapper : AuthHttpServer.createObjectMapper();
    }

    public void register(HttpServer server) {
        server.createContext("/api/auth/register", this::handleRegister);
        server.createContext("/api/auth/login", this::handleLogin);
        server.createContext("/api/auth/refresh", this::handleRefresh);
        server.createContext("/api/auth/logout", this::handleLogout);
        server.createContext("/api/auth/me", this::handleMe);
        server.createContext("/api/auth/health", this::handleHealth);
    }

    public void handleRegister(HttpExchange exchange) throws IOException {
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

    public void handleLogin(HttpExchange exchange) throws IOException {
        if (handleCorsPreflight(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange, "POST");
            return;
        }

        try {
            LoginRequest req = parseRequestBody(exchange, LoginRequest.class);
            AuthResponse response = authService.loginClient(req);
            sendJsonResponse(exchange, 200, response);
        } catch (Exception e) {
            handleError(exchange, e);
        }
    }

    public void handleRefresh(HttpExchange exchange) throws IOException {
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

    public void handleLogout(HttpExchange exchange) throws IOException {
        if (handleCorsPreflight(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange, "POST");
            return;
        }

        try {
            String refreshToken = null;
            byte[] bodyBytes = readRequestBody(exchange);
            if (bodyBytes.length > 0) {
                try {
                    LogoutRequest req = objectMapper.readValue(bodyBytes, LogoutRequest.class);
                    refreshToken = req.refreshToken();
                } catch (Exception ignored) {}
            }

            Optional<UUID> revokedSession;
            if (refreshToken != null && !refreshToken.isBlank()) {
                revokedSession = authService.logoutSession(refreshToken);
            } else {
                JwtClaims claims = authMiddleware.authenticate(exchange);
                revokedSession = authService.logoutCurrentSession(claims);
            }
            if (clientRegistry != null) {
                revokedSession.ifPresent(sessionId -> clientRegistry.disconnectSession(sessionId.toString(),
                        AuthErrorCode.SESSION_REVOKED, "Current session signed out"));
            }

            sendJsonResponse(exchange, 200, Map.of(
                "success", true,
                "message", "Logged out successfully"
            ));
        } catch (Exception e) {
            handleError(exchange, e);
        }
    }

    public void handleMe(HttpExchange exchange) throws IOException {
        if (handleCorsPreflight(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange, "GET");
            return;
        }

        try {
            JwtClaims claims = authMiddleware.authenticate(exchange);
            UserDto user = authService.getCurrentClientUser(claims.userId());
            sendJsonResponse(exchange, 200, user);
        } catch (Exception e) {
            handleError(exchange, e);
        }
    }

    public void handleHealth(HttpExchange exchange) throws IOException {
        if (handleCorsPreflight(exchange)) return;
        sendJsonResponse(exchange, 200, Map.of(
            "status", "UP",
            "service", "LANDrop-Authentication",
            "timestamp", java.time.Instant.now().toString()
        ));
    }

    // =========================================================================
    // Utilities & Error Handling
    // =========================================================================

    public boolean handleCorsPreflight(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, PATCH, OPTIONS");
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

    private <T> T parseRequestBody(HttpExchange exchange, Class<T> clazz) throws IOException {
        byte[] bytes = readRequestBody(exchange);
        if (bytes.length == 0) {
            throw new ValidationException("Request body cannot be empty", AuthErrorCode.VALIDATION_ERROR);
        }
        return objectMapper.readValue(bytes, clazz);
    }

    public void sendJsonResponse(HttpExchange exchange, int statusCode, Object body) throws IOException {
        byte[] responseBytes = objectMapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }

    public void sendMethodNotAllowed(HttpExchange exchange, String allowedMethod) throws IOException {
        ErrorResponse err = new ErrorResponse(null, "Method Not Allowed", "Allowed method: " + allowedMethod, 405);
        sendJsonResponse(exchange, 405, err);
    }

    public void handleError(HttpExchange exchange, Exception e) throws IOException {
        int status;
        String errorName;
        String errorCode = null;
        String message = e.getMessage();

        if (e instanceof AuthException ae) {
            status = ae.getStatusCode();
            errorCode = ae.getErrorCode();
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
            errorCode = "INTERNAL_ERROR";
            message = "Internal database operation failed";
            AppLogger.error(TAG, "Database error", e);
        } else {
            status = 500;
            errorName = "Internal Server Error";
            errorCode = "INTERNAL_ERROR";
            message = "An unexpected error occurred";
            AppLogger.error(TAG, "Unexpected error", e);
        }

        ErrorResponse err = new ErrorResponse(errorCode, errorName, message != null ? message : errorName, status);
        sendJsonResponse(exchange, status, err);
    }
}
