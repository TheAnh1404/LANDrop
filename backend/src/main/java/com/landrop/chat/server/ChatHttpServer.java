package com.landrop.chat.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.auth.exception.AuthException;
import com.landrop.auth.security.JwtClaims;
import com.landrop.auth.server.AuthMiddleware;
import com.landrop.chat.dto.ChannelDto;
import com.landrop.chat.dto.ChatMessageDto;
import com.landrop.chat.dto.MessagesPageDto;
import com.landrop.chat.service.ChannelChatService;
import com.landrop.chat.service.ChatException;
import com.landrop.util.AppLogger;
import com.landrop.workspace.WorkspaceException;
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
 * Embedded HTTP server handler for Phase 17 Chat REST endpoints.
 * Mounts:
 *   GET, POST   /api/channels/:channelId/messages
 *   GET         /api/channels/:channelId
 *   PATCH       /api/messages/:messageId
 *   DELETE      /api/messages/:messageId
 */
public final class ChatHttpServer {
    private static final String TAG = "ChatHttpServer";
    private static final int MAX_BODY = 32 * 1024;

    private final AuthMiddleware auth;
    private final ChannelChatService service;
    private final ObjectMapper mapper;

    public ChatHttpServer(AuthMiddleware auth, ChannelChatService service, ObjectMapper mapper) {
        this.auth = Objects.requireNonNull(auth, "auth");
        this.service = Objects.requireNonNull(service, "service");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public void register(HttpServer server) {
        server.createContext("/api/channels", this::handleChannels);
        server.createContext("/api/channels/", this::handleChannels);
        server.createContext("/api/messages", this::handleMessages);
        server.createContext("/api/messages/", this::handleMessages);
    }

    // =========================================================================
    // /api/channels Route Dispatch
    // =========================================================================

    private void handleChannels(HttpExchange exchange) throws IOException {
        setCors(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 204, null);
            return;
        }

        try {
            JwtClaims claims = auth.authenticate(exchange);
            String path = exchange.getRequestURI().getPath();
            String base = "/api/channels";
            if (!path.startsWith(base)) {
                sendError(exchange, 404, "ROUTE_NOT_FOUND", "Route was not found.");
                return;
            }

            String tail = path.substring(base.length());
            if (tail.startsWith("/")) tail = tail.substring(1);
            String[] parts = tail.split("/", -1);

            // GET /api/channels/:channelId
            if (parts.length == 1 && !parts[0].isBlank()) {
                UUID channelId = parseUuid(parts[0]);
                if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    ChannelDto channel = service.getChannel(claims.userId(), channelId);
                    send(exchange, 200, Map.of("data", channel));
                } else {
                    methodNotAllowed(exchange, "GET");
                }
                return;
            }

            // /api/channels/:channelId/messages
            if (parts.length == 2 && "messages".equals(parts[1])) {
                UUID channelId = parseUuid(parts[0]);
                if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    getMessages(exchange, claims.userId(), channelId);
                } else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    createMessage(exchange, claims.userId(), channelId);
                } else {
                    methodNotAllowed(exchange, "GET, POST");
                }
                return;
            }

            sendError(exchange, 404, "ROUTE_NOT_FOUND", "Route was not found.");
        } catch (Exception error) {
            handleError(exchange, error);
        }
    }

    // =========================================================================
    // /api/messages Route Dispatch
    // =========================================================================

    private void handleMessages(HttpExchange exchange) throws IOException {
        setCors(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 204, null);
            return;
        }

        try {
            JwtClaims claims = auth.authenticate(exchange);
            String path = exchange.getRequestURI().getPath();
            String base = "/api/messages";
            if (!path.startsWith(base)) {
                sendError(exchange, 404, "ROUTE_NOT_FOUND", "Route was not found.");
                return;
            }

            String tail = path.substring(base.length());
            if (tail.startsWith("/")) tail = tail.substring(1);
            String[] parts = tail.split("/", -1);

            // /api/messages/:messageId
            if (parts.length == 1 && !parts[0].isBlank()) {
                UUID messageId = parseUuid(parts[0]);
                String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
                switch (method) {
                    case "PATCH" -> editMessage(exchange, claims.userId(), messageId);
                    case "DELETE" -> deleteMessage(exchange, claims.userId(), messageId);
                    default -> methodNotAllowed(exchange, "PATCH, DELETE");
                }
                return;
            }

            sendError(exchange, 404, "ROUTE_NOT_FOUND", "Route was not found.");
        } catch (Exception error) {
            handleError(exchange, error);
        }
    }

    // =========================================================================
    // Handler Implementations
    // =========================================================================

    private void getMessages(HttpExchange exchange, UUID userId, UUID channelId) throws IOException, SQLException {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        int limit = 50;
        if (query.containsKey("limit")) {
            try {
                limit = Integer.parseInt(query.get("limit"));
            } catch (NumberFormatException e) {
                throw new ChatException(400, "INVALID_QUERY", "Limit must be a valid number between 1 and 100.");
            }
        }
        String cursor = query.get("cursor");
        String before = query.get("before");
        String order = query.get("order");

        MessagesPageDto page = service.getHistory(userId, channelId, limit, cursor, before, order);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", page.items());
        response.put("nextCursor", page.nextCursor());
        response.put("data", page.items());
        response.put("page", Map.of("nextCursor", page.nextCursor() != null ? page.nextCursor() : ""));
        send(exchange, 200, response);
    }

    private void createMessage(HttpExchange exchange, UUID userId, UUID channelId) throws IOException, SQLException {
        JsonNode body = readBody(exchange, Set.of("content", "clientMessageId", "replyToId"));
        String content = parseText(body, "content", true);
        UUID clientMessageId = body.hasNonNull("clientMessageId") ? parseUuid(body.get("clientMessageId").asText()) : null;
        UUID replyToId = body.hasNonNull("replyToId") ? parseUuid(body.get("replyToId").asText()) : null;

        var saved = service.createMessage(userId, channelId, clientMessageId, content, replyToId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", saved.message());
        response.put("message", saved.message());
        send(exchange, 201, response);
    }

    private void editMessage(HttpExchange exchange, UUID userId, UUID messageId) throws IOException, SQLException {
        JsonNode body = readBody(exchange, Set.of("content"));
        String content = parseText(body, "content", true);

        ChatMessageDto updated = service.editMessage(userId, messageId, content);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", updated);
        response.put("message", updated);
        send(exchange, 200, response);
    }

    private void deleteMessage(HttpExchange exchange, UUID userId, UUID messageId) throws IOException, SQLException {
        service.deleteMessage(userId, messageId);
        send(exchange, 204, null);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void setCors(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Device-Label");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS");
    }

    private JsonNode readBody(HttpExchange exchange, Set<String> allowedFields) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw new ChatException(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json.");
        }
        byte[] bytes;
        try (InputStream in = exchange.getRequestBody()) {
            bytes = in.readNBytes(MAX_BODY + 1);
        }
        if (bytes.length > MAX_BODY) {
            throw new ChatException(413, "PAYLOAD_TOO_LARGE", "Request body exceeds maximum size.");
        }
        JsonNode node;
        try {
            node = mapper.readTree(bytes);
        } catch (Exception e) {
            throw new ChatException(400, "INVALID_REQUEST", "Malformed JSON body.");
        }
        if (node == null || !node.isObject()) {
            throw new ChatException(400, "INVALID_REQUEST", "Request body must be a JSON object.");
        }
        var fields = node.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowedFields.contains(field)) {
                throw new ChatException(400, "INVALID_REQUEST", "Unknown property: " + field);
            }
        }
        return node;
    }

    private static String parseText(JsonNode node, String field, boolean required) {
        if (!node.has(field) || node.get(field).isNull()) {
            if (required) throw new ChatException(400, "INVALID_REQUEST", field + " is required.");
            return null;
        }
        if (!node.get(field).isTextual()) {
            throw new ChatException(400, "INVALID_REQUEST", field + " must be a string.");
        }
        String val = node.get(field).asText().trim();
        if (required && val.isEmpty()) {
            throw new ChatException(400, "INVALID_REQUEST", field + " cannot be empty.");
        }
        return val;
    }

    private static UUID parseUuid(String text) {
        try {
            UUID id = UUID.fromString(text);
            if (!id.toString().equalsIgnoreCase(text)) {
                throw new IllegalArgumentException();
            }
            return id;
        } catch (Exception e) {
            throw new ChatException(400, "INVALID_REQUEST", "Invalid UUID: " + text);
        }
    }

    private static Map<String, String> parseQuery(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        Map<String, String> map = new HashMap<>();
        for (String pair : raw.split("&")) {
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
        if (error instanceof ChatException ce) {
            sendError(exchange, ce.getStatusCode(), ce.code(), ce.getMessage());
        } else if (error instanceof WorkspaceException we) {
            sendError(exchange, we.getStatusCode(), we.code(), we.getMessage());
        } else if (error instanceof AuthException ae) {
            sendError(exchange, ae.getStatusCode(), "UNAUTHORIZED", ae.getMessage());
        } else if (error instanceof SQLException se) {
            AppLogger.error(TAG, "SQL failure during request", se);
            sendError(exchange, 503, "DATABASE_UNAVAILABLE", "Database operation is temporarily unavailable.");
        } else {
            AppLogger.error(TAG, "Unhandled exception during request", error);
            sendError(exchange, 500, "INTERNAL_ERROR", "Internal server error.");
        }
    }
}
