package com.landrop.device.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.auth.dto.ErrorResponse;
import com.landrop.auth.exception.AuthException;
import com.landrop.auth.exception.ForbiddenException;
import com.landrop.auth.exception.NotFoundException;
import com.landrop.auth.exception.ValidationException;
import com.landrop.auth.model.AuthErrorCode;
import com.landrop.auth.security.JwtClaims;
import com.landrop.auth.server.AuthMiddleware;
import com.landrop.device.dto.DeviceDto;
import com.landrop.device.dto.DeviceEnrollRequest;
import com.landrop.device.dto.DeviceRenameRequest;
import com.landrop.device.service.DeviceService;
import com.landrop.util.AppLogger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Standard HTTP handler exposing REST endpoints for Device Enrollment, Listing, and Management (FOUNDATION-04A.6 - 04A.7, 04A.13 - 04A.14).
 */
public class DeviceHttpHandler {

    private static final String TAG = "DeviceHttpHandler";
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final DeviceService deviceService;
    private final AuthMiddleware authMiddleware;
    private final ObjectMapper objectMapper;

    public DeviceHttpHandler(DeviceService deviceService, AuthMiddleware authMiddleware) {
        this(deviceService, authMiddleware, com.landrop.auth.server.AuthHttpServer.createObjectMapper());
    }

    public DeviceHttpHandler(DeviceService deviceService, AuthMiddleware authMiddleware, ObjectMapper objectMapper) {
        this.deviceService = Objects.requireNonNull(deviceService, "deviceService cannot be null");
        this.authMiddleware = Objects.requireNonNull(authMiddleware, "authMiddleware cannot be null");
        this.objectMapper = objectMapper != null ? objectMapper : com.landrop.auth.server.AuthHttpServer.createObjectMapper();
    }

    public void register(HttpServer server) {
        server.createContext("/api/devices/enroll", this::handleEnroll);
        server.createContext("/api/devices", this::handleDevices);
        server.createContext("/api/devices/", this::handleDevices);
    }

    public void handleEnroll(HttpExchange exchange) throws IOException {
        if (handleCorsPreflight(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange, "POST");
            return;
        }

        try {
            JwtClaims claims = authMiddleware.authenticate(exchange);
            DeviceEnrollRequest req = parseRequestBody(exchange, DeviceEnrollRequest.class);
            DeviceDto enrolled = deviceService.enrollDevice(claims.userId(), req);
            sendJsonResponse(exchange, 201, enrolled);
        } catch (Exception e) {
            handleError(exchange, e);
        }
    }

    public void handleDevices(HttpExchange exchange) throws IOException {
        if (handleCorsPreflight(exchange)) return;

        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod().toUpperCase();

        try {
            JwtClaims claims = authMiddleware.authenticate(exchange);

            if ("/api/devices".equals(path) || "/api/devices/".equals(path)) {
                if ("GET".equals(method)) {
                    List<DeviceDto> devices = deviceService.listUserDevices(claims.userId());
                    sendJsonResponse(exchange, 200, devices);
                    return;
                }
                sendMethodNotAllowed(exchange, "GET");
                return;
            }

            // Sub-routes: /api/devices/{deviceId}
            String prefix = "/api/devices/";
            if (path.startsWith(prefix)) {
                String remainder = path.substring(prefix.length());
                String[] parts = remainder.split("/");
                String deviceId = parts[0];

                if ("PATCH".equals(method)) {
                    DeviceRenameRequest renameReq = parseRequestBody(exchange, DeviceRenameRequest.class);
                    DeviceDto updated = deviceService.renameDevice(claims.userId(), deviceId, renameReq);
                    sendJsonResponse(exchange, 200, updated);
                    return;
                } else if ("GET".equals(method)) {
                    Optional<DeviceDto> deviceOpt = deviceService.getDevice(deviceId);
                    if (deviceOpt.isEmpty()) {
                        throw new NotFoundException("Device not found: " + deviceId, AuthErrorCode.DEVICE_NOT_FOUND);
                    }
                    DeviceDto d = deviceOpt.get();
                    if (!d.userId().equals(claims.userId()) && !claims.isSystemAdmin()) {
                        throw new ForbiddenException("You do not have access to this device", AuthErrorCode.FORBIDDEN);
                    }
                    sendJsonResponse(exchange, 200, d);
                    return;
                }
                sendMethodNotAllowed(exchange, "GET, PATCH");
                return;
            }

            sendJsonResponse(exchange, 404, new ErrorResponse(AuthErrorCode.DEVICE_NOT_FOUND, "Not Found", "Endpoint not found: " + path, 404));
        } catch (Exception e) {
            handleError(exchange, e);
        }
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
