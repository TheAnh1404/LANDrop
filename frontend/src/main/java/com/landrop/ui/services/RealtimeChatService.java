package com.landrop.ui.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.ui.auth.model.AuthModels;
import com.landrop.ui.auth.services.AuthStateService;
import com.landrop.ui.models.RealtimeConnectionStatus;
import com.landrop.ui.models.UIMessage;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service managing real-time chat operations, WebSocket transport, message deduplication,
 * optimistic reconciliation, and connection lifecycle feedback.
 */
public class RealtimeChatService implements RealtimeTransport.Listener, AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RealtimeTransport transport;
    private final AuthStateService authStateService;
    private final URI wsUri;

    private final ObjectProperty<RealtimeConnectionStatus> connectionStatus =
            new SimpleObjectProperty<>(RealtimeConnectionStatus.DISCONNECTED);

    private final ObservableList<UIMessage> messages = FXCollections.observableArrayList();

    // Deduplication and reconciliation stores
    private final Set<String> seenMessageIds = ConcurrentHashMap.newKeySet();
    private final Map<String, UIMessage> optimisticByTempId = new ConcurrentHashMap<>();

    private final AtomicBoolean explicitlyClosed = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "realtime-reconnect");
        t.setDaemon(true);
        return t;
    });

    private ScheduledFuture<?> reconnectTask;

    /**
     * Constructs a RealtimeChatService with a standard WebSocket transport.
     */
    public RealtimeChatService(URI wsUri, AuthStateService authStateService) {
        this.wsUri = Objects.requireNonNull(wsUri, "wsUri cannot be null");
        this.authStateService = Objects.requireNonNull(authStateService, "authStateService cannot be null");
        String token = authStateService.accessToken().orElse(null);
        this.transport = new WebSocketRealtimeTransport(wsUri, token);
        this.transport.setListener(this);
    }

    /**
     * Constructs a RealtimeChatService with an injectable RealtimeTransport (used for testing or custom brokers).
     */
    public RealtimeChatService(RealtimeTransport transport, AuthStateService authStateService) {
        this.wsUri = null;
        this.transport = Objects.requireNonNull(transport, "transport cannot be null");
        this.authStateService = Objects.requireNonNull(authStateService, "authStateService cannot be null");
        this.transport.setListener(this);
    }

    public ObjectProperty<RealtimeConnectionStatus> connectionStatusProperty() {
        return connectionStatus;
    }

    public RealtimeConnectionStatus getConnectionStatus() {
        return connectionStatus.get();
    }

    public ObservableList<UIMessage> getMessages() {
        return messages;
    }

    public CompletableFuture<Void> connect() {
        explicitlyClosed.set(false);
        cancelScheduledReconnect();
        runSafe(() -> connectionStatus.set(RealtimeConnectionStatus.CONNECTING));

        return transport.connect().whenComplete((ignored, ex) -> {
            if (ex != null) {
                runSafe(() -> connectionStatus.set(RealtimeConnectionStatus.DISCONNECTED));
                scheduleReconnect();
            } else {
                runSafe(() -> connectionStatus.set(RealtimeConnectionStatus.CONNECTED));
            }
        });
    }

    /**
     * Sends a chat message with optimistic local update and server reconciliation.
     */
    public CompletableFuture<UIMessage> sendMessage(String text) {
        if (text == null || text.trim().isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Message content cannot be blank"));
        }

        AuthModels.AuthUser currentUser = authStateService != null ? authStateService.user() : null;
        String currentUserId = currentUser != null ? currentUser.id() : "local-user";
        String displayName = currentUser != null ? currentUser.displayName() : "You";
        String username = currentUser != null ? currentUser.username() : null;
        String avatarUrl = currentUser != null ? currentUser.avatarUrl() : null;

        String tempId = "opt-" + UUID.randomUUID();
        UIMessage optimistic = UIMessage.optimistic(tempId, currentUserId, displayName, username, avatarUrl, text.trim());

        // 1. Optimistic local update
        optimisticByTempId.put(tempId, optimistic);
        runSafe(() -> messages.add(optimistic));

        // 2. Wire payload construction
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "chat.message");
        payload.put("action", "send_message");
        payload.put("tempId", tempId);
        payload.put("channelId", "default");
        payload.put("content", text.trim());
        payload.put("senderId", currentUserId);
        payload.put("senderDisplayName", displayName);
        payload.put("senderUsername", username);
        payload.put("timestamp", System.currentTimeMillis());

        String jsonPayload;
        try {
            jsonPayload = MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(e);
        }

        // 3. Dispatch to transport
        if (!transport.isConnected()) {
            // Even if offline/reconnecting, optimistic message is recorded locally
            return CompletableFuture.completedFuture(optimistic);
        }

        return transport.send(jsonPayload)
                .thenApply(v -> optimistic)
                .exceptionally(ex -> {
                    System.err.println("[Realtime] Failed to send message frame: " + ex.getMessage());
                    return optimistic;
                });
    }

    /**
     * Ingests and processes a canonical or broadcast message payload.
     * Guarantees message deduplication by canonical {@code message.id} and reconciles optimistic messages.
     */
    public void processIncomingMessage(UIMessage canonicalMsg, String tempId) {
        if (canonicalMsg == null) return;

        runSafe(() -> {
            // Deduplication Check 1: Canonical ID already processed?
            if (canonicalMsg.id() != null && seenMessageIds.contains(canonicalMsg.id())) {
                return;
            }
            if (canonicalMsg.id() != null) {
                seenMessageIds.add(canonicalMsg.id());
            }

            // Reconciliation Check 2: Matches local optimistic message?
            UIMessage matchingOptimistic = null;
            if (tempId != null && !tempId.isBlank()) {
                matchingOptimistic = optimisticByTempId.remove(tempId);
            }

            // Fuzzy correlation if server did not echo tempId but sent matching content for self
            if (matchingOptimistic == null && canonicalMsg.isMine(currentUserId())) {
                for (Map.Entry<String, UIMessage> entry : optimisticByTempId.entrySet()) {
                    UIMessage opt = entry.getValue();
                    if (opt.content().equals(canonicalMsg.content()) &&
                            Math.abs(opt.timestamp() - canonicalMsg.timestamp()) < 15000) {
                        matchingOptimistic = opt;
                        optimisticByTempId.remove(entry.getKey());
                        break;
                    }
                }
            }

            if (matchingOptimistic != null) {
                // Reconcile: in-place replacement
                int index = messages.indexOf(matchingOptimistic);
                if (index >= 0) {
                    messages.set(index, canonicalMsg);
                } else {
                    messages.add(canonicalMsg);
                }
            } else {
                // New incoming message from another user or from same user on another device
                messages.add(canonicalMsg);
            }
        });
    }

    private String currentUserId() {
        return (authStateService != null && authStateService.user() != null)
                ? authStateService.user().id()
                : null;
    }

    // Transport Listener implementation

    @Override
    public void onOpen() {
        cancelScheduledReconnect();
        runSafe(() -> connectionStatus.set(RealtimeConnectionStatus.CONNECTED));
    }

    @Override
    public void onMessage(String payload) {
        if (payload == null || payload.isBlank()) return;

        try {
            JsonNode root = MAPPER.readTree(payload);

            // Check if envelope has nested "data" or "payload"
            JsonNode dataNode = root.has("data") ? root.get("data") : (root.has("payload") ? root.get("payload") : root);

            String id = text(dataNode, "id");
            if (id == null) id = text(root, "id");
            if (id == null) id = UUID.randomUUID().toString();

            String tempId = text(dataNode, "tempId");
            if (tempId == null) tempId = text(root, "tempId");

            String senderId = text(dataNode, "senderId");
            if (senderId == null) senderId = text(dataNode, "userId");
            if (senderId == null) senderId = text(root, "senderId");

            String senderDisplayName = text(dataNode, "senderDisplayName");
            if (senderDisplayName == null) senderDisplayName = text(dataNode, "displayName");
            if (senderDisplayName == null) senderDisplayName = text(root, "senderDisplayName");

            String senderUsername = text(dataNode, "senderUsername");
            if (senderUsername == null) senderUsername = text(dataNode, "username");

            String senderAvatarUrl = text(dataNode, "senderAvatarUrl");
            if (senderAvatarUrl == null) senderAvatarUrl = text(dataNode, "avatarUrl");

            String content = text(dataNode, "content");
            if (content == null) content = text(dataNode, "text");
            if (content == null) content = text(root, "content");
            if (content == null) return; // ignore non-chat packet

            long timestamp = 0;
            if (dataNode.has("timestamp") && dataNode.get("timestamp").isNumber()) {
                timestamp = dataNode.get("timestamp").asLong();
            } else if (dataNode.has("createdAt")) {
                try {
                    timestamp = Instant.parse(dataNode.get("createdAt").asText()).toEpochMilli();
                } catch (Exception ignored) {
                }
            }
            if (timestamp <= 0) {
                timestamp = System.currentTimeMillis();
            }

            boolean isMine = senderId != null && senderId.equals(currentUserId());
            if (senderDisplayName == null && isMine && authStateService.user() != null) {
                senderDisplayName = authStateService.user().displayName();
            }

            UIMessage canonical = new UIMessage(
                    id,
                    senderId != null ? senderId : (isMine ? currentUserId() : "remote-user"),
                    senderDisplayName != null ? senderDisplayName : (senderUsername != null ? senderUsername : "User"),
                    senderUsername,
                    senderAvatarUrl,
                    content,
                    timestamp,
                    isMine
            );

            processIncomingMessage(canonical, tempId);

        } catch (Exception e) {
            System.err.println("[Realtime] Failed to parse incoming payload: " + e.getMessage());
        }
    }

    private static String text(JsonNode node, String field) {
        if (node != null && node.has(field) && !node.get(field).isNull()) {
            return node.get(field).asText();
        }
        return null;
    }

    @Override
    public void onClose(int statusCode, String reason) {
        if (explicitlyClosed.get()) {
            runSafe(() -> connectionStatus.set(RealtimeConnectionStatus.DISCONNECTED));
            return;
        }
        runSafe(() -> connectionStatus.set(RealtimeConnectionStatus.RECONNECTING));
        scheduleReconnect();
    }

    @Override
    public void onError(Throwable throwable) {
        if (explicitlyClosed.get()) return;
        runSafe(() -> connectionStatus.set(RealtimeConnectionStatus.RECONNECTING));
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (explicitlyClosed.get()) return;
        cancelScheduledReconnect();
        reconnectTask = scheduler.schedule(() -> {
            if (!explicitlyClosed.get() && !transport.isConnected()) {
                connect();
            }
        }, 3, TimeUnit.SECONDS);
    }

    private void cancelScheduledReconnect() {
        if (reconnectTask != null && !reconnectTask.isDone()) {
            reconnectTask.cancel(false);
            reconnectTask = null;
        }
    }

    @Override
    public void close() {
        explicitlyClosed.set(true);
        cancelScheduledReconnect();
        transport.close();
        scheduler.shutdown();
        runSafe(() -> connectionStatus.set(RealtimeConnectionStatus.DISCONNECTED));
    }

    private static void runSafe(Runnable action) {
        try {
            if (Platform.isFxApplicationThread()) {
                action.run();
            } else {
                Platform.runLater(action);
            }
        } catch (IllegalStateException notFxApp) {
            // In headless JUnit tests where JavaFX toolkit may not be running
            action.run();
        }
    }
}
