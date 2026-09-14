package com.landrop.file.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.auth.exception.AuthException;
import com.landrop.auth.security.JwtClaims;
import com.landrop.auth.server.AuthMiddleware;
import com.landrop.file.dto.*;
import com.landrop.file.exception.FileException;
import com.landrop.file.service.FileManagementService;
import com.landrop.file.service.TransferCoordinationService;
import com.landrop.util.AppLogger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;

/**
 * Embedded HTTP server handler for Phase 18A File Management and LAN Transfer REST APIs.
 */
public class FileHttpServer {

    private static final String TAG = "FileHttpServer";

    private final AuthMiddleware auth;
    private final FileManagementService fileService;
    private final TransferCoordinationService transferService;
    private final ObjectMapper mapper;

    public FileHttpServer(AuthMiddleware auth,
                          FileManagementService fileService,
                          TransferCoordinationService transferService,
                          ObjectMapper mapper) {
        this.auth = auth;
        this.fileService = fileService;
        this.transferService = transferService;
        this.mapper = mapper;
    }

    public void register(HttpServer server) {
        server.createContext("/api/files", this::handleFiles);
        server.createContext("/api/files/", this::handleFiles);
        server.createContext("/api/folders", this::handleFolders);
        server.createContext("/api/folders/", this::handleFolders);
        server.createContext("/api/devices", this::handleDevices);
        server.createContext("/api/devices/", this::handleDevices);
        server.createContext("/api/transfers", this::handleTransfers);
        server.createContext("/api/transfers/", this::handleTransfers);
        server.createContext("/api/users/", this::handleUsers);
    }

    // =========================================================================
    // /api/files Dispatch
    // =========================================================================

    private void handleFiles(HttpExchange exchange) throws IOException {
        setCors(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 204, null);
            return;
        }

        try {
            JwtClaims claims = auth.authenticate(exchange);
            String path = exchange.getRequestURI().getPath();
            String tail = path.replaceFirst("^/api/files/?", "");
            String[] parts = tail.isBlank() ? new String[0] : tail.split("/", -1);

            if (parts.length == 1 && !parts[0].isBlank()) {
                UUID fileId = parseUuid(parts[0]);
                String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
                switch (method) {
                    case "GET" -> {
                        FileDto file = fileService.getFile(claims.userId(), fileId);
                        send(exchange, 200, Map.of("data", file));
                    }
                    case "PATCH" -> {
                        JsonNode body = readBody(exchange, Set.of("name", "folderId"));
                        String name = body.has("name") ? body.get("name").asText(null) : null;
                        boolean updateFolder = body.has("folderId");
                        UUID folderId = updateFolder && !body.get("folderId").isNull()
                            ? parseUuid(body.get("folderId").asText()) : null;
                        FileDto updated = fileService.updateFile(claims.userId(), fileId, name, folderId, updateFolder);
                        send(exchange, 200, Map.of("data", updated));
                    }
                    case "DELETE" -> {
                        FileDto deleted = fileService.deleteFile(claims.userId(), fileId);
                        send(exchange, 200, Map.of("data", deleted));
                    }
                    default -> methodNotAllowed(exchange, "GET, PATCH, DELETE");
                }
                return;
            }

            sendError(exchange, 404, "ROUTE_NOT_FOUND", "Route was not found.");
        } catch (Exception error) {
            handleError(exchange, error);
        }
    }

    // =========================================================================
    // /api/folders Dispatch
    // =========================================================================

    private void handleFolders(HttpExchange exchange) throws IOException {
        setCors(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 204, null);
            return;
        }

        try {
            JwtClaims claims = auth.authenticate(exchange);
            String path = exchange.getRequestURI().getPath();
            String tail = path.replaceFirst("^/api/folders/?", "");
            String[] parts = tail.isBlank() ? new String[0] : tail.split("/", -1);

            if (parts.length == 1 && !parts[0].isBlank()) {
                UUID folderId = parseUuid(parts[0]);
                String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
                switch (method) {
                    case "GET" -> {
                        FolderDto folder = fileService.getFolder(claims.userId(), folderId);
                        send(exchange, 200, Map.of("data", folder));
                    }
                    case "PATCH" -> {
                        JsonNode body = readBody(exchange, Set.of("name", "parentId", "parentFolderId"));
                        String name = body.has("name") ? body.get("name").asText(null) : null;
                        boolean updateParent = body.has("parentId") || body.has("parentFolderId");
                        UUID parentId = null;
                        if (body.hasNonNull("parentId")) {
                            parentId = parseUuid(body.get("parentId").asText());
                        } else if (body.hasNonNull("parentFolderId")) {
                            parentId = parseUuid(body.get("parentFolderId").asText());
                        }
                        FolderDto updated = fileService.updateFolder(claims.userId(), folderId, name, parentId, updateParent);
                        send(exchange, 200, Map.of("data", updated));
                    }
                    case "DELETE" -> {
                        FolderDto deleted = fileService.deleteFolder(claims.userId(), folderId);
                        send(exchange, 200, Map.of("data", deleted));
                    }
                    default -> methodNotAllowed(exchange, "GET, PATCH, DELETE");
                }
                return;
            }

            sendError(exchange, 404, "ROUTE_NOT_FOUND", "Route was not found.");
        } catch (Exception error) {
            handleError(exchange, error);
        }
    }

    // =========================================================================
    // /api/devices Dispatch
    // =========================================================================

    private void handleDevices(HttpExchange exchange) throws IOException {
        setCors(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 204, null);
            return;
        }

        try {
            JwtClaims claims = auth.authenticate(exchange);
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            String path = exchange.getRequestURI().getPath();
            String tail = path.replaceFirst("^/api/devices/?", "");
            String[] parts = tail.isBlank() ? new String[0] : tail.split("/", -1);

            if (parts.length == 2 && "peer".equals(parts[0]) && "GET".equals(method)) {
                String peerId = parts[1];
                DeviceDto device = transferService.getDeviceByPeer(peerId)
                    .orElseThrow(() -> new FileException(404, "DEVICE_NOT_FOUND", "Device not found for peerId: " + peerId));
                send(exchange, 200, Map.of("data", device));
                return;
            }

            if (parts.length == 1 && !parts[0].isBlank() && !"register".equalsIgnoreCase(parts[0])) {
                String deviceId = parts[0];
                if ("GET".equals(method)) {
                    DeviceDto device = transferService.getDevice(deviceId)
                        .orElseThrow(() -> new FileException(404, "DEVICE_NOT_FOUND", "Device not found: " + deviceId));
                    send(exchange, 200, Map.of("data", device));
                    return;
                }
            }

            if ("POST".equals(method)) {
                JsonNode body = readBody(exchange, Set.of("deviceId", "deviceName", "peerId", "ipAddress", "port"));
                String deviceId = body.hasNonNull("deviceId") ? body.get("deviceId").asText() : UUID.randomUUID().toString();
                String deviceName = body.hasNonNull("deviceName") ? body.get("deviceName").asText() : "Device";
                String peerId = body.hasNonNull("peerId") ? body.get("peerId").asText() : null;
                String ipAddress = body.hasNonNull("ipAddress") ? body.get("ipAddress").asText() : null;
                Integer port = body.hasNonNull("port") ? body.get("port").asInt() : null;

                DeviceDto saved = transferService.registerDevice(claims.userId(), deviceId, deviceName, peerId, ipAddress, port);
                send(exchange, 200, Map.of("data", saved));
                return;
            }

            if ("GET".equals(method)) {
                List<DeviceDto> devices = transferService.listUserDevices(claims.userId());
                send(exchange, 200, Map.of("data", devices));
                return;
            }

            methodNotAllowed(exchange, "GET, POST");
        } catch (Exception error) {
            handleError(exchange, error);
        }
    }

    // =========================================================================
    // /api/users/:userId/devices Dispatch
    // =========================================================================

    private void handleUsers(HttpExchange exchange) throws IOException {
        setCors(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 204, null);
            return;
        }

        try {
            JwtClaims claims = auth.authenticate(exchange);
            String path = exchange.getRequestURI().getPath();
            String tail = path.replaceFirst("^/api/users/?", "");
            String[] parts = tail.split("/", -1);

            if (parts.length == 2 && "devices".equals(parts[1]) && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                UUID targetUser = parseUuid(parts[0]);
                List<DeviceDto> devices = transferService.listUserDevices(targetUser);
                send(exchange, 200, Map.of("data", devices));
                return;
            }

            sendError(exchange, 404, "ROUTE_NOT_FOUND", "Route was not found.");
        } catch (Exception error) {
            handleError(exchange, error);
        }
    }

    // =========================================================================
    // /api/transfers Dispatch
    // =========================================================================

    private void handleTransfers(HttpExchange exchange) throws IOException {
        setCors(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 204, null);
            return;
        }

        try {
            JwtClaims claims = auth.authenticate(exchange);
            String path = exchange.getRequestURI().getPath();
            String tail = path.replaceFirst("^/api/transfers/?", "");
            String[] parts = tail.isBlank() ? new String[0] : tail.split("/", -1);

            // POST /api/transfers (initiate transfer request)
            if (parts.length == 0) {
                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    JsonNode body = readBody(exchange, Set.of("fileId", "receiverUserId", "senderDeviceId", "receiverDeviceId"));
                    UUID fileId = parseUuid(body.get("fileId").asText());
                    UUID receiverUserId = parseUuid(body.get("receiverUserId").asText());
                    String senderDeviceId = body.hasNonNull("senderDeviceId") ? body.get("senderDeviceId").asText() : null;
                    String receiverDeviceId = body.hasNonNull("receiverDeviceId") ? body.get("receiverDeviceId").asText() : null;

                    TransferDto transfer = transferService.requestTransfer(claims.userId(), fileId, receiverUserId, senderDeviceId, receiverDeviceId);
                    send(exchange, 201, Map.of("data", transfer));
                } else {
                    methodNotAllowed(exchange, "POST");
                }
                return;
            }

            // GET /api/transfers/:transferId
            if (parts.length == 1) {
                UUID transferId = parseUuid(parts[0]);
                if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    TransferDto transfer = transferService.getTransfer(claims.userId(), transferId);
                    send(exchange, 200, Map.of("data", transfer));
                } else {
                    methodNotAllowed(exchange, "GET");
                }
                return;
            }

            // Action routes: /api/transfers/:transferId/:action
            if (parts.length == 2 && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                UUID transferId = parseUuid(parts[0]);
                String action = parts[1].toLowerCase(Locale.ROOT);
                switch (action) {
                    case "accept" -> {
                        JsonNode body = readBodyQuiet(exchange);
                        String receiverDeviceId = (body != null && body.hasNonNull("receiverDeviceId"))
                            ? body.get("receiverDeviceId").asText() : null;
                        TransferDto dto = transferService.acceptTransfer(claims.userId(), transferId, receiverDeviceId);
                        send(exchange, 200, Map.of("data", dto));
                    }
                    case "reject" -> {
                        JsonNode body = readBodyQuiet(exchange);
                        String reason = (body != null && body.hasNonNull("reason")) ? body.get("reason").asText() : null;
                        TransferDto dto = transferService.rejectTransfer(claims.userId(), transferId, reason);
                        send(exchange, 200, Map.of("data", dto));
                    }
                    case "cancel" -> {
                        JsonNode body = readBodyQuiet(exchange);
                        String reason = (body != null && body.hasNonNull("reason")) ? body.get("reason").asText() : null;
                        TransferDto dto = transferService.cancelTransfer(claims.userId(), transferId, reason);
                        send(exchange, 200, Map.of("data", dto));
                    }
                    case "progress" -> {
                        JsonNode body = readBody(exchange, Set.of("bytesTransferred", "totalBytes", "speed"));
                        long bytesTransferred = body.path("bytesTransferred").asLong(0);
                        long totalBytes = body.path("totalBytes").asLong(0);
                        double speed = body.path("speed").asDouble(0.0);
                        transferService.updateProgress(claims.userId(), transferId, bytesTransferred, totalBytes, speed);
                        send(exchange, 200, Map.of("data", Map.of("status", "progress_recorded")));
                    }
                    case "complete" -> {
                        TransferDto dto = transferService.completeTransfer(claims.userId(), transferId);
                        send(exchange, 200, Map.of("data", dto));
                    }
                    case "fail" -> {
                        JsonNode body = readBodyQuiet(exchange);
                        String error = (body != null && body.hasNonNull("error")) ? body.get("error").asText() : "Transfer failed.";
                        TransferDto dto = transferService.failTransfer(claims.userId(), transferId, error);
                        send(exchange, 200, Map.of("data", dto));
                    }
                    default -> sendError(exchange, 404, "ROUTE_NOT_FOUND", "Unknown transfer action.");
                }
                return;
            }

            sendError(exchange, 404, "ROUTE_NOT_FOUND", "Route was not found.");
        } catch (Exception error) {
            handleError(exchange, error);
        }
    }

    // =========================================================================
    // Workspace Sub-Routes Handling (invoked from WorkspaceHttpServer)
    // =========================================================================

    public void listWorkspaceFiles(HttpExchange exchange, UUID userId, UUID workspaceId) throws IOException, SQLException {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        UUID folderId = query.containsKey("folderId") && !query.get("folderId").isBlank()
            ? parseUuid(query.get("folderId")) : null;
        boolean rootOnly = Boolean.parseBoolean(query.getOrDefault("rootOnly", "false"))
            || "null".equalsIgnoreCase(query.get("folderId")) || "root".equalsIgnoreCase(query.get("folderId"));
        boolean includeDeleted = Boolean.parseBoolean(query.getOrDefault("includeDeleted", "false"));

        List<FileDto> files = fileService.listFiles(userId, workspaceId, folderId, rootOnly, includeDeleted);
        send(exchange, 200, Map.of("data", files));
    }

    public void createWorkspaceFile(HttpExchange exchange, UUID userId, UUID workspaceId) throws IOException, SQLException {
        JsonNode body = readBody(exchange, Set.of("name", "size", "mimeType", "hash", "folderId", "parentFolderId", "storageType"));
        String name = body.path("name").asText(null);
        long size = body.path("size").asLong(0);
        String mimeType = body.hasNonNull("mimeType") ? body.get("mimeType").asText() : null;
        String hash = body.hasNonNull("hash") ? body.get("hash").asText() : null;
        UUID folderId = body.hasNonNull("folderId") ? parseUuid(body.get("folderId").asText())
            : (body.hasNonNull("parentFolderId") ? parseUuid(body.get("parentFolderId").asText()) : null);
        String storageType = body.hasNonNull("storageType") ? body.get("storageType").asText() : "LAN_ONLY";

        FileDto created = fileService.createFileMetadata(userId, workspaceId, name, size, mimeType, hash, folderId, storageType);
        send(exchange, 201, Map.of("data", created));
    }

    public void listWorkspaceFolders(HttpExchange exchange, UUID userId, UUID workspaceId) throws IOException, SQLException {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String parentParam = query.containsKey("parentId") ? query.get("parentId") : query.get("parentFolderId");
        UUID parentId = parentParam != null && !parentParam.isBlank() && !"null".equalsIgnoreCase(parentParam) && !"root".equalsIgnoreCase(parentParam)
            ? parseUuid(parentParam) : null;
        boolean rootOnly = Boolean.parseBoolean(query.getOrDefault("rootOnly", "false"))
            || "null".equalsIgnoreCase(parentParam) || "root".equalsIgnoreCase(parentParam);
        boolean includeDeleted = Boolean.parseBoolean(query.getOrDefault("includeDeleted", "false"));

        List<FolderDto> folders = fileService.listFolders(userId, workspaceId, parentId, rootOnly, includeDeleted);
        send(exchange, 200, Map.of("data", folders));
    }

    public void createWorkspaceFolder(HttpExchange exchange, UUID userId, UUID workspaceId) throws IOException, SQLException {
        JsonNode body = readBody(exchange, Set.of("name", "parentId", "parentFolderId"));
        String name = body.path("name").asText(null);
        UUID parentId = body.hasNonNull("parentId") ? parseUuid(body.get("parentId").asText())
            : (body.hasNonNull("parentFolderId") ? parseUuid(body.get("parentFolderId").asText()) : null);

        FolderDto created = fileService.createFolder(userId, workspaceId, name, parentId);
        send(exchange, 201, Map.of("data", created));
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    private void setCors(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Device-Id, X-Peer-Id");
    }

    private UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw new FileException(400, "INVALID_REQUEST", "Invalid UUID: " + s);
        }
    }

    private JsonNode readBody(HttpExchange exchange, Set<String> allowedFields) throws IOException {
        JsonNode node = readBodyQuiet(exchange);
        if (node == null || !node.isObject()) {
            throw new FileException(400, "INVALID_REQUEST", "Request body must be a valid JSON object.");
        }
        var fields = node.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowedFields.contains(field)) {
                throw new FileException(400, "INVALID_REQUEST", "Unexpected field in request: " + field);
            }
        }
        return node;
    }

    private JsonNode readBodyQuiet(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readAllBytes();
            if (bytes.length == 0) return null;
            return mapper.readTree(bytes);
        }
    }

    private Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> map = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) return map;
        String[] pairs = rawQuery.split("&");
        for (String pair : pairs) {
            if (pair.isBlank()) continue;
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String val = parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            map.put(key, val);
        }
        return map;
    }

    private void methodNotAllowed(HttpExchange exchange, String allowed) throws IOException {
        exchange.getResponseHeaders().set("Allow", allowed);
        sendError(exchange, 405, "METHOD_NOT_ALLOWED", "Method is not allowed on this route.");
    }

    private void send(HttpExchange exchange, int status, Object body) throws IOException {
        if (status == 204 || body == null) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        byte[] payload = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private void sendError(HttpExchange exchange, int status, String code, String message) throws IOException {
        send(exchange, status, Map.of("error", Map.of("code", code, "message", message)));
    }

    private void handleError(HttpExchange exchange, Exception error) throws IOException {
        if (error instanceof FileException fe) {
            sendError(exchange, fe.getStatusCode(), fe.code(), fe.getMessage());
        } else if (error instanceof AuthException ae) {
            sendError(exchange, ae.getStatusCode(), "UNAUTHORIZED", ae.getMessage());
        } else if (error instanceof SQLException se) {
            AppLogger.error(TAG, "Database failure during request", se);
            sendError(exchange, 503, "DATABASE_UNAVAILABLE", "Database operation is temporarily unavailable.");
        } else {
            AppLogger.error(TAG, "Unhandled exception during request", error);
            sendError(exchange, 500, "INTERNAL_ERROR", "Internal server error.");
        }
    }
}
