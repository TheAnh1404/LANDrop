package com.landrop.realtime;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.landrop.chat.service.ChannelBroadcaster;
import com.landrop.chat.service.ChatException;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.*;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.handshake.*;
import org.java_websocket.server.WebSocketServer;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Authenticated, channel-scoped and room-aware WebSocket chat for one shared backend process. */
public final class RealtimeServer extends WebSocketServer implements ChannelBroadcaster, com.landrop.file.service.TransferBroadcaster {
    private static final String PREFIX = "/api/realtime/channels/";
    private static final String BASE_PATH = "/api/realtime";
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).build();
    private final SocketAuthenticator authenticator;
    private final RealtimeStore store;
    private final ConnectionRegistry registry = new ConnectionRegistry();
    private volatile com.landrop.file.service.TransferCoordinationService transferService;
    // A short integration-phase serialization boundary guarantees persist -> broadcast order.
    private final Object dispatchLock = new Object();
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final ScheduledExecutorService expiry = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "realtime-expiry"); thread.setDaemon(true); return thread;
    });

    public RealtimeServer(InetSocketAddress address, SocketAuthenticator authenticator, RealtimeStore store) {
        super(address, 4, List.of(new Draft_6455(List.of(), 64 * 1024)));
        this.authenticator = Objects.requireNonNull(authenticator);
        this.store = Objects.requireNonNull(store);
        setReuseAddr(true);
        setConnectionLostTimeout(30);
    }

    public ConnectionRegistry registry() { return registry; }
    public void awaitReady() throws Exception { ready.get(10, TimeUnit.SECONDS); }
    public void setTransferService(com.landrop.file.service.TransferCoordinationService transferService) {
        this.transferService = transferService;
    }

    @Override
    public void broadcast(UUID channelId, Object event) {
        broadcastToChannel(channelId, event);
    }

    @Override public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(
            WebSocket socket, Draft draft, ClientHandshake request) throws InvalidDataException {
        try {
            // No query-string tokens; native Java clients send Authorization in the handshake.
            String path = request.getResourceDescriptor();
            if (request.hasFieldValue("Origin")) throw new IllegalArgumentException();

            UUID channel = null;
            if (path.startsWith(PREFIX)) {
                channel = UUID.fromString(path.substring(PREFIX.length()));
                if (!path.equals(PREFIX + channel)) throw new IllegalArgumentException();
            } else if (!path.equals(BASE_PATH) && !path.equals(BASE_PATH + "/") && !path.equals(BASE_PATH + "/ws")) {
                throw new IllegalArgumentException();
            }

            SocketPrincipal principal = authenticator.authenticate(request.getFieldValue("Authorization"));
            if (principal.expired()) throw new IllegalArgumentException();

            if (channel != null && !store.canAccess(principal.userId(), channel)) {
                throw new IllegalArgumentException();
            }

            String label = request.getFieldValue("X-Device-Label");
            if (label == null || label.isBlank()) {
                label = request.getFieldValue("X-Device-Id");
            }
            if (label != null && (label.length() > 80 || label.chars().anyMatch(Character::isISOControl))) {
                throw new IllegalArgumentException();
            }

            socket.setAttachment(new ConnectionRegistry.Connection(UUID.randomUUID(), principal, channel,
                    label, Instant.now(), socket));
            return super.onWebsocketHandshakeReceivedAsServer(socket, draft, request);
        } catch (Exception e) {
            // This library rejects the HTTP upgrade with 404, not a WS close frame.
            throw new InvalidDataException(1008, "Authentication or channel access rejected.");
        }
    }

    @Override public void onOpen(WebSocket socket, ClientHandshake request) {
        ConnectionRegistry.Connection connection = socket.getAttachment();
        if (connection == null || connection.principal().expired()) { socket.close(1008, "Sign in is required."); return; }
        synchronized (dispatchLock) {
            registry.add(connection);
            var event = new LinkedHashMap<String, Object>();
            event.put("type", "connected");
            event.put("socketId", connection.socketId());
            event.put("userId", connection.principal().userId());
            if (connection.channelId() != null) {
                event.put("channelId", connection.channelId());
            }
            event.put("accessTokenId", connection.principal().accessTokenId());
            event.put("deviceLabel", connection.deviceLabel());
            send(socket, event);
        }
    }

    @Override public void onClose(WebSocket socket, int code, String reason, boolean remote) {
        ConnectionRegistry.Connection connection = socket.getAttachment();
        if (connection != null) registry.remove(connection);
    }

    @Override public void onMessage(WebSocket socket, ByteBuffer bytes) {
        socket.close(1003, "Only JSON text messages are supported.");
    }

    @Override public void onMessage(WebSocket socket, String text) {
        ConnectionRegistry.Connection connection = socket.getAttachment();
        if (connection == null || connection.principal().expired()) { socket.close(4001, "Refresh access and reconnect."); return; }
        String requestId = null;
        try {
            if (text.length() > 32_768) throw invalid();
            JsonNode command = JSON.readTree(text);
            if (command == null || !command.isObject()) throw invalid();
            requestId = uuid(command, "requestId").toString();
            String rawType = string(command, "type");
            String type = rawType.replace(':', '.');

            Set<String> allowed = switch (type) {
                case "message.send" -> Set.of("type", "requestId", "clientMessageId", "content", "channelId", "replyToId");
                case "message.edit", "message.update" -> Set.of("type", "requestId", "messageId", "content");
                case "message.delete" -> Set.of("type", "requestId", "messageId");
                case "channel.join" -> Set.of("type", "requestId", "channelId", "workspaceId");
                case "channel.leave" -> Set.of("type", "requestId", "channelId");
                case "history" -> Set.of("type", "requestId", "channelId", "limit", "before");
                case "transfer.request" -> Set.of("type", "requestId", "fileId", "receiverUserId", "senderDeviceId", "receiverDeviceId");
                case "transfer.accept" -> Set.of("type", "requestId", "transferId", "receiverDeviceId");
                case "transfer.reject" -> Set.of("type", "requestId", "transferId", "reason");
                case "transfer.cancel" -> Set.of("type", "requestId", "transferId", "reason");
                case "transfer.progress" -> Set.of("type", "requestId", "transferId", "bytesTransferred", "totalBytes", "speedBytesPerSec");
                case "transfer.complete" -> Set.of("type", "requestId", "transferId");
                case "transfer.fail" -> Set.of("type", "requestId", "transferId", "error");
                case "device.register" -> Set.of("type", "requestId", "deviceId", "deviceName", "peerId", "ipAddress", "port");
                default -> throw invalid();
            };

            var names = command.fieldNames();
            while (names.hasNext()) if (!allowed.contains(names.next())) throw invalid();

            synchronized (dispatchLock) {
                if (connection.principal().expired()) { socket.close(4001, "Refresh access and reconnect."); return; }

                switch (type) {
                    case "channel.join" -> {
                        UUID channelId = uuid(command, "channelId");
                        UUID workspaceId = command.hasNonNull("workspaceId") ? uuid(command, "workspaceId") : null;
                        store.verifyChannelRoomAccess(connection.principal().userId(), channelId, workspaceId);
                        connection.joinRoom(channelId);
                        var response = new LinkedHashMap<String, Object>();
                        response.put("type", "channel.joined");
                        response.put("requestId", requestId);
                        response.put("channelId", channelId);
                        if (workspaceId != null) response.put("workspaceId", workspaceId);
                        send(socket, response);
                    }
                    case "channel.leave" -> {
                        UUID channelId = uuid(command, "channelId");
                        connection.leaveRoom(channelId);
                        send(socket, Map.of("type", "channel.left", "requestId", requestId, "channelId", channelId));
                    }
                    case "message.send" -> {
                        UUID channelId = resolveChannel(command, connection);
                        String content = string(command, "content");
                        if (content.isBlank() || content.length() > 4000 || content.indexOf('\0') >= 0) throw invalid();
                        UUID clientId = uuid(command, "clientMessageId");
                        UUID replyToId = command.hasNonNull("replyToId") ? uuid(command, "replyToId") : null;

                        var saved = store.save(connection.principal().userId(), channelId, clientId, content, replyToId);
                        var event = Map.of("type", "message.created", "requestId", requestId, "channelId", channelId, "message", saved.message());
                        if (saved.inserted()) broadcastToChannel(channelId, event);
                        else send(socket, event); // Lost acknowledgement retry
                    }
                    case "message.edit", "message.update" -> {
                        UUID messageId = uuid(command, "messageId");
                        String content = string(command, "content");
                        if (content.isBlank() || content.length() > 4000 || content.indexOf('\0') >= 0) throw invalid();

                        var updated = store.edit(connection.principal().userId(), messageId, content);
                        var event = Map.of("type", "message.updated", "requestId", requestId, "channelId", updated.channelId(),
                                "messageId", messageId, "message", updated);
                        broadcastToChannel(updated.channelId(), event);
                    }
                    case "message.delete" -> {
                        UUID messageId = uuid(command, "messageId");
                        var deleted = store.delete(connection.principal().userId(), messageId);
                        var event = new LinkedHashMap<String, Object>();
                        event.put("type", "message.deleted");
                        event.put("requestId", requestId);
                        event.put("channelId", deleted.channelId());
                        event.put("messageId", messageId);
                        if (deleted.deletedAt() != null) event.put("deletedAt", deleted.deletedAt());
                        event.put("message", deleted);
                        broadcastToChannel(deleted.channelId(), event);
                    }
                    case "history" -> {
                        UUID channelId = resolveChannel(command, connection);
                        int limit = 50;
                        if (command.has("limit")) {
                            if (!command.get("limit").isIntegralNumber() || !command.get("limit").canConvertToInt()) throw invalid();
                            limit = command.get("limit").intValue();
                        }
                        if (limit < 1 || limit > 100) throw invalid();
                        String before = command.hasNonNull("before") ? string(command, "before") : null;
                        var page = store.history(connection.principal().userId(), channelId, limit, before);
                        send(socket, Map.of("type", "history", "requestId", requestId, "page", page));
                    }
                    case "transfer.request" -> {
                        if (transferService == null) throw invalid();
                        UUID fileId = uuid(command, "fileId");
                        UUID receiverUserId = uuid(command, "receiverUserId");
                        String senderDeviceId = command.hasNonNull("senderDeviceId") ? string(command, "senderDeviceId") : connection.deviceLabel();
                        String receiverDeviceId = command.hasNonNull("receiverDeviceId") ? string(command, "receiverDeviceId") : null;
                        var result = transferService.requestTransfer(connection.principal().userId(), fileId, receiverUserId, senderDeviceId, receiverDeviceId);
                        send(socket, Map.of("type", "transfer.requested", "requestId", requestId, "data", result));
                    }
                    case "transfer.accept" -> {
                        if (transferService == null) throw invalid();
                        UUID transferId = uuid(command, "transferId");
                        String receiverDeviceId = command.hasNonNull("receiverDeviceId") ? string(command, "receiverDeviceId") : connection.deviceLabel();
                        var result = transferService.acceptTransfer(connection.principal().userId(), transferId, receiverDeviceId);
                        send(socket, Map.of("type", "transfer.accepted", "requestId", requestId, "data", result));
                    }
                    case "transfer.reject" -> {
                        if (transferService == null) throw invalid();
                        UUID transferId = uuid(command, "transferId");
                        String reason = command.hasNonNull("reason") ? string(command, "reason") : null;
                        var result = transferService.rejectTransfer(connection.principal().userId(), transferId, reason);
                        send(socket, Map.of("type", "transfer.rejected", "requestId", requestId, "data", result));
                    }
                    case "transfer.cancel" -> {
                        if (transferService == null) throw invalid();
                        UUID transferId = uuid(command, "transferId");
                        String reason = command.hasNonNull("reason") ? string(command, "reason") : null;
                        var result = transferService.cancelTransfer(connection.principal().userId(), transferId, reason);
                        send(socket, Map.of("type", "transfer.cancelled", "requestId", requestId, "data", result));
                    }
                    case "transfer.progress" -> {
                        if (transferService == null) throw invalid();
                        UUID transferId = uuid(command, "transferId");
                        long transferred = command.path("bytesTransferred").asLong(0);
                        long total = command.path("totalBytes").asLong(0);
                        double speed = command.path("speedBytesPerSec").asDouble(0.0);
                        transferService.updateProgress(connection.principal().userId(), transferId, transferred, total, speed);
                        send(socket, Map.of("type", "transfer.progress.ack", "requestId", requestId));
                    }
                    case "transfer.complete" -> {
                        if (transferService == null) throw invalid();
                        UUID transferId = uuid(command, "transferId");
                        var result = transferService.completeTransfer(connection.principal().userId(), transferId);
                        send(socket, Map.of("type", "transfer.completed", "requestId", requestId, "data", result));
                    }
                    case "transfer.fail" -> {
                        if (transferService == null) throw invalid();
                        UUID transferId = uuid(command, "transferId");
                        String error = command.hasNonNull("error") ? string(command, "error") : "Transfer failed";
                        var result = transferService.failTransfer(connection.principal().userId(), transferId, error);
                        send(socket, Map.of("type", "transfer.failed", "requestId", requestId, "data", result));
                    }
                    case "device.register" -> {
                        if (transferService == null) throw invalid();
                        String deviceId = command.hasNonNull("deviceId") ? string(command, "deviceId") : connection.deviceLabel();
                        String deviceName = command.hasNonNull("deviceName") ? string(command, "deviceName") : "Device";
                        String peerId = command.hasNonNull("peerId") ? string(command, "peerId") : null;
                        String ipAddress = command.hasNonNull("ipAddress") ? string(command, "ipAddress") : null;
                        Integer port = command.hasNonNull("port") ? command.get("port").asInt() : null;
                        var result = transferService.registerDevice(connection.principal().userId(), deviceId, deviceName, peerId, ipAddress, port);
                        send(socket, Map.of("type", "device.registered", "requestId", requestId, "data", result));
                    }
                }
            }
        } catch (RealtimeException e) {
            error(socket, requestId, e.code(), e.getMessage());
        } catch (ChatException e) {
            error(socket, requestId, e.code(), e.getMessage());
        } catch (com.landrop.file.exception.FileException e) {
            error(socket, requestId, e.code(), e.getMessage());
        } catch (java.sql.SQLException e) {
            error(socket, requestId, "PERSISTENCE_UNAVAILABLE", "Chat or file storage is unavailable.");
        } catch (Exception e) {
            error(socket, requestId, "INVALID_REQUEST", "Invalid request.");
        }
    }

    private UUID resolveChannel(JsonNode command, ConnectionRegistry.Connection connection) {
        if (command.hasNonNull("channelId")) {
            return uuid(command, "channelId");
        }
        if (connection.channelId() != null) {
            return connection.channelId();
        }
        if (connection.rooms().size() == 1) {
            return connection.rooms().iterator().next();
        }
        throw new RealtimeException("INVALID_REQUEST", "Channel ID must be specified.");
    }

    public void broadcastToChannel(UUID channel, Object event) {
        synchronized (dispatchLock) {
            Map<UUID, Boolean> access = new HashMap<>();
            for (var connection : registry.snapshot()) {
                if (!connection.isInRoom(channel)) continue;
                if (connection.principal().expired()) { connection.socket().close(4001, "Refresh access and reconnect."); continue; }
                boolean permitted = access.computeIfAbsent(connection.principal().userId(), user -> {
                    try { return store.canAccess(user, channel); } catch (Exception e) { return false; }
                });
                if (permitted) send(connection.socket(), event);
                else connection.socket().close(1008, "Channel access is unavailable.");
            }
        }
    }

    @Override
    public void broadcast(UUID userId, String deviceId, String event, Object payload) {
        synchronized (dispatchLock) {
            List<ConnectionRegistry.Connection> targets = registry.getConnections(userId);
            if (targets.isEmpty()) return;

            Map<String, Object> message = new LinkedHashMap<>();
            message.put("type", event);
            message.put("event", event);
            if (payload instanceof Map<?, ?> map) {
                map.forEach((k, v) -> message.put(String.valueOf(k), v));
            } else if (payload != null) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> props = JSON.convertValue(payload, Map.class);
                    props.forEach((k, v) -> message.put(String.valueOf(k), v));
                } catch (Exception ignored) {}
                message.put("data", payload);
                message.put("transfer", payload);
            }

            boolean sent = false;
            if (deviceId != null && !deviceId.isBlank()) {
                for (var conn : targets) {
                    if (deviceId.equals(conn.deviceLabel())) {
                        send(conn.socket(), message);
                        sent = true;
                    }
                }
            }
            if (!sent) {
                for (var conn : targets) {
                    send(conn.socket(), message);
                }
            }
        }
    }

    private void error(WebSocket socket, String request, String code, String message) {
        var event = new LinkedHashMap<String, Object>();
        event.put("type", "error"); event.put("requestId", request); event.put("code", code); event.put("message", message);
        send(socket, event);
    }

    private void send(WebSocket socket, Object value) {
        try { if (socket.isOpen()) socket.send(JSON.writeValueAsString(value)); }
        catch (Exception e) { socket.close(1011, "Reconnect to recover chat history."); }
    }

    private static String string(JsonNode node, String key) {
        if (!node.path(key).isTextual()) throw invalid();
        return node.get(key).textValue();
    }

    private static UUID uuid(JsonNode node, String key) {
        try {
            String value = string(node, key);
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value)) throw invalid();
            return id;
        } catch (IllegalArgumentException e) { throw invalid(); }
    }

    private static RealtimeException invalid() {
        return new RealtimeException("INVALID_REQUEST", "Invalid chat request.");
    }

    @Override public void onError(WebSocket socket, Exception e) {
        if (socket == null) ready.completeExceptionally(new IllegalStateException("Realtime listener failed."));
        else socket.close(1011, "Reconnect to recover chat history.");
    }

    @Override public void onStart() {
        expiry.scheduleAtFixedRate(() -> {
            for (var c : registry.snapshot()) if (c.principal().expired())
                c.socket().close(4001, "Refresh access and reconnect.");
        }, 1, 1, TimeUnit.SECONDS);
        ready.complete(null);
    }

    @Override public void stop(int timeout) throws InterruptedException {
        expiry.shutdownNow();
        super.stop(timeout);
    }
}
