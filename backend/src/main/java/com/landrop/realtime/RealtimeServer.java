package com.landrop.realtime;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
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

/** Authenticated, channel-scoped WebSocket chat for one shared backend process. */
public final class RealtimeServer extends WebSocketServer {
    private static final String PREFIX = "/api/realtime/channels/";
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).build();
    private final SocketAuthenticator authenticator;
    private final RealtimeStore store;
    private final ConnectionRegistry registry = new ConnectionRegistry();
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

    @Override public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(
            WebSocket socket, Draft draft, ClientHandshake request) throws InvalidDataException {
        try {
            // No query-string tokens; native Java clients send Authorization in the handshake.
            String path = request.getResourceDescriptor();
            if (!path.startsWith(PREFIX) || request.hasFieldValue("Origin")) throw new IllegalArgumentException();
            UUID channel = UUID.fromString(path.substring(PREFIX.length()));
            if (!path.equals(PREFIX + channel)) throw new IllegalArgumentException();
            SocketPrincipal principal = authenticator.authenticate(request.getFieldValue("Authorization"));
            if (principal.expired() || !store.canAccess(principal.userId(), channel)) throw new IllegalArgumentException();
            String label = request.getFieldValue("X-Device-Label");
            if (label.length() > 80 || label.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
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
            send(socket, Map.of("type", "connected", "socketId", connection.socketId(),
                    "userId", connection.principal().userId(), "channelId", connection.channelId(),
                    "accessTokenId", connection.principal().accessTokenId(), "deviceLabel", connection.deviceLabel()));
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
            String type = string(command, "type");
            Set<String> allowed = switch (type) {
                case "message.send" -> Set.of("type", "requestId", "clientMessageId", "content");
                case "history" -> Set.of("type", "requestId", "limit", "before");
                default -> throw invalid();
            };
            var names = command.fieldNames();
            while (names.hasNext()) if (!allowed.contains(names.next())) throw invalid();
            synchronized (dispatchLock) {
                if (connection.principal().expired()) { socket.close(4001, "Refresh access and reconnect."); return; }
                if (type.equals("message.send")) {
                    String content = string(command, "content");
                    if (content.isBlank() || content.length() > 4000 || content.indexOf('\0') >= 0) throw invalid();
                    UUID clientId = uuid(command, "clientMessageId");
                    // No senderId/peer/user is read from JSON, even if a client tries to supply one.
                    var saved = store.save(connection.principal().userId(), connection.channelId(), clientId, content);
                    var event = Map.of("type", "message.created", "requestId", requestId, "message", saved.message());
                    if (saved.inserted()) broadcastCanonical(connection.channelId(), event);
                    else send(socket, event); // Lost acknowledgement retry: same canonical ID, no repeat fanout.
                } else {
                    int limit = 50;
                    if (command.has("limit")) {
                        if (!command.get("limit").isIntegralNumber() || !command.get("limit").canConvertToInt()) throw invalid();
                        limit = command.get("limit").intValue();
                    }
                    if (limit < 1 || limit > 100) throw invalid();
                    String before = command.hasNonNull("before") ? string(command, "before") : null;
                    var page = store.history(connection.principal().userId(), connection.channelId(), limit, before);
                    send(socket, Map.of("type", "history", "requestId", requestId, "page", page));
                }
            }
        } catch (RealtimeException e) {
            error(socket, requestId, e.code(), e.getMessage());
        } catch (java.sql.SQLException e) {
            error(socket, requestId, "PERSISTENCE_UNAVAILABLE", "Chat storage is unavailable. Retry with the same clientMessageId.");
        } catch (Exception e) {
            error(socket, requestId, "INVALID_REQUEST", "Invalid chat request.");
        }
    }
    private void broadcastCanonical(UUID channel, Object event) {
        Map<UUID, Boolean> access = new HashMap<>();
        for (var connection : registry.snapshot()) {
            if (!connection.channelId().equals(channel)) continue;
            if (connection.principal().expired()) { connection.socket().close(4001, "Refresh access and reconnect."); continue; }
            boolean permitted = access.computeIfAbsent(connection.principal().userId(), user -> {
                try { return store.canAccess(user, channel); } catch (Exception e) { return false; }
            });
            if (permitted) send(connection.socket(), event);
            else connection.socket().close(1008, "Channel access is unavailable.");
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
