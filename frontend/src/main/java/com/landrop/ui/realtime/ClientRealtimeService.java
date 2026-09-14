package com.landrop.ui.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.landrop.ui.bootstrap.ClientInstanceService;
import com.landrop.ui.connection.ConnectionState;
import com.landrop.ui.connection.ServerHealthClient;
import com.landrop.ui.config.FrontendConfig;
import com.landrop.ui.protocol.RealtimeEnvelope;
import com.landrop.ui.services.RealtimeTransport;
import com.landrop.ui.services.WebSocketRealtimeTransport;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.property.SimpleStringProperty;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The one Client-to-Server realtime control connection. Feature code subscribes to typed
 * envelopes; it never creates a socket or owns reconnect/heartbeat timers.
 */
public final class ClientRealtimeService implements AutoCloseable {
    public interface TransportFactory { RealtimeTransport create(URI uri, String accessToken); }
    @FunctionalInterface public interface HealthProbe { CompletableFuture<Void> check(); }
    private static final int PROTOCOL_VERSION = FrontendConfig.PROTOCOL_VERSION;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);
    private static final long[] BACKOFF_SECONDS = {1, 2, 5, 10};
    private volatile URI wsUri;
    private volatile HealthProbe health;
    private final ClientInstanceService instance;
    private final Supplier<String> tokenSupplier;
    private final Supplier<String> deviceNameSupplier;
    private final TransportFactory transports;
    private final Duration requestTimeout;
    private final ScheduledExecutorService scheduler;
    private final ObjectProperty<ConnectionState> state = new SimpleObjectProperty<>(ConnectionState.IDLE);
    private final StringProperty connectionId = new SimpleStringProperty("");
    private final StringProperty serverName = new SimpleStringProperty("");
    private final StringProperty serverAddress = new SimpleStringProperty("");
    private final StringProperty serverVersion = new SimpleStringProperty("");
    private final IntegerProperty serverProtocolVersion = new SimpleIntegerProperty(-1);
    private final StringProperty clientVersion = new SimpleStringProperty(FrontendConfig.CLIENT_VERSION);
    private final StringProperty deviceName = new SimpleStringProperty("");
    private final StringProperty lastHeartbeat = new SimpleStringProperty("");
    private final StringProperty disconnectReason = new SimpleStringProperty("");
    private final IntegerProperty reconnectAttempts = new SimpleIntegerProperty();
    private final Map<String, CopyOnWriteArrayList<Consumer<RealtimeEnvelope>>> subscribers = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<RealtimeEnvelope>> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean explicitlyClosed = new AtomicBoolean();
    private volatile RealtimeTransport transport;
    private volatile ScheduledFuture<?> reconnectTask;
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile ScheduledFuture<?> handshakeTimeout;
    private volatile int reconnectAttempt;

    public ClientRealtimeService(URI wsUri, ServerHealthClient health, ClientInstanceService instance,
            Supplier<String> tokenSupplier, Supplier<String> deviceNameSupplier) {
        this(wsUri, health::check, instance, tokenSupplier, deviceNameSupplier,
                (uri, token) -> new WebSocketRealtimeTransport(uri, token));
    }
    ClientRealtimeService(URI wsUri, HealthProbe health, ClientInstanceService instance,
            Supplier<String> tokenSupplier, Supplier<String> deviceNameSupplier, TransportFactory transports) {
        this(wsUri, health, instance, tokenSupplier, deviceNameSupplier, transports, REQUEST_TIMEOUT);
    }
    ClientRealtimeService(URI wsUri, HealthProbe health, ClientInstanceService instance,
            Supplier<String> tokenSupplier, Supplier<String> deviceNameSupplier, TransportFactory transports, Duration requestTimeout) {
        this.wsUri = Objects.requireNonNull(wsUri); this.health = Objects.requireNonNull(health);
        this.instance = Objects.requireNonNull(instance); this.tokenSupplier = Objects.requireNonNull(tokenSupplier);
        this.deviceNameSupplier = Objects.requireNonNull(deviceNameSupplier); this.transports = Objects.requireNonNull(transports); this.requestTimeout = Objects.requireNonNull(requestTimeout);
        this.serverAddress.set(wsUri.toString());
        this.deviceName.set(safeDeviceName());
        this.clientVersion.set(clientVersion());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "landrop-server-control"); t.setDaemon(true); return t; });
    }
    public ReadOnlyObjectProperty<ConnectionState> stateProperty() { return state; }
    public StringProperty connectionIdProperty() { return connectionId; }
    public StringProperty serverNameProperty() { return serverName; }
    public StringProperty serverAddressProperty() { return serverAddress; }
    public StringProperty serverVersionProperty() { return serverVersion; }
    public IntegerProperty serverProtocolVersionProperty() { return serverProtocolVersion; }
    public StringProperty clientVersionProperty() { return clientVersion; }
    public StringProperty deviceNameProperty() { return deviceName; }
    public StringProperty lastHeartbeatProperty() { return lastHeartbeat; }
    public StringProperty disconnectReasonProperty() { return disconnectReason; }
    public IntegerProperty reconnectAttemptsProperty() { return reconnectAttempts; }
    public String clientInstanceId() { return instance.clientInstanceId(); }
    public ConnectionState state() { return state.get(); }
    public boolean isConnected() { return state() == ConnectionState.CONNECTED; }
    public CompletableFuture<Void> checkHealth() { return health.check(); }
    public void start() { explicitlyClosed.set(false); connect(false); }
    public synchronized void reconfigure(URI nextWsUri, HealthProbe nextHealth) {
        this.wsUri = Objects.requireNonNull(nextWsUri, "WebSocket URL is required");
        this.health = Objects.requireNonNull(nextHealth, "Health probe is required");
        serverAddress.set(nextWsUri.toString());
        log("CONFIG_CHANGED");
        stopTimers(); closeTransport(); reconnectAttempt = 0; explicitlyClosed.set(false);
        disconnectReason.set(""); update(ConnectionState.DISCONNECTED); connect(true);
    }
    public void reconnectNow() { stopTimers(); closeTransport(); reconnectAttempt = 0; explicitlyClosed.set(false); disconnectReason.set(""); update(ConnectionState.DISCONNECTED); connect(true); }

    public AutoCloseable subscribe(String eventType, Consumer<RealtimeEnvelope> listener) {
        Objects.requireNonNull(eventType); Objects.requireNonNull(listener);
        CopyOnWriteArrayList<Consumer<RealtimeEnvelope>> list = subscribers.computeIfAbsent(eventType, ignored -> new CopyOnWriteArrayList<>());
        list.add(listener);
        return () -> list.remove(listener);
    }
    public CompletableFuture<RealtimeEnvelope> request(String type, JsonNode payload) {
        RealtimeEnvelope envelope = RealtimeEnvelope.create(type, payload);
        CompletableFuture<RealtimeEnvelope> response = new CompletableFuture<>();
        if (!isConnected()) return CompletableFuture.failedFuture(new IllegalStateException("Server realtime connection is not ready."));
        pending.put(envelope.requestId(), response);
        scheduler.schedule(() -> {
            CompletableFuture<RealtimeEnvelope> timedOut = pending.remove(envelope.requestId());
            if (timedOut != null) timedOut.completeExceptionally(new TimeoutException("Timed out waiting for " + type));
        }, requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        sendEnvelope(envelope).whenComplete((ignored, error) -> {
            if (error != null) { CompletableFuture<RealtimeEnvelope> failed = pending.remove(envelope.requestId()); if (failed != null) failed.completeExceptionally(error); }
        });
        return response;
    }
    public CompletableFuture<Void> send(String type, JsonNode payload) {
        if (!isConnected()) return CompletableFuture.failedFuture(new IllegalStateException("Server realtime connection is not ready."));
        return sendEnvelope(RealtimeEnvelope.create(type, payload));
    }

    private synchronized void connect(boolean manual) {
        if (explicitlyClosed.get() || state() == ConnectionState.CONNECTING || state() == ConnectionState.HANDSHAKING || state() == ConnectionState.CONNECTED) return;
        update(manual || reconnectAttempt > 0 ? ConnectionState.RECONNECTING : ConnectionState.CONNECTING);
        log("WS_CONNECTING");
        health.check().whenComplete((ignored, healthFailure) -> {
            if (explicitlyClosed.get()) return;
            if (healthFailure != null) { log("SERVER_HEALTH_FAILED"); log("HEALTH_CHECK_FAILED"); update(ConnectionState.ERROR); scheduleReconnect(); return; }
            log("SERVER_HEALTH_OK"); log("HEALTH_CHECK_OK"); openSocket();
        });
    }
    private synchronized void openSocket() {
        if (explicitlyClosed.get()) return;
        String accessToken = tokenSupplier.get();
        if (accessToken == null || accessToken.isBlank()) { update(ConnectionState.ERROR); return; }
        closeTransport();
        RealtimeTransport next = transports.create(wsUri, accessToken);
        transport = next;
        next.setListener(new RealtimeTransport.Listener() {
            @Override public void onOpen() { onSocketOpen(next); }
            @Override public void onMessage(String payload) { onSocketMessage(next, payload); }
            @Override public void onClose(int code, String reason) { onSocketLost(next, new IllegalStateException("WebSocket closed (" + code + "): " + reason), reason); }
            @Override public void onError(Throwable error) { onSocketLost(next, error); }
        });
        next.connect().exceptionally(error -> { onSocketLost(next, error); return null; });
    }
    private void onSocketOpen(RealtimeTransport source) {
        if (source != transport || explicitlyClosed.get()) return;
        log("SERVER_CONNECTED"); log("HANDSHAKING"); update(ConnectionState.HANDSHAKING);
        var hello = RealtimeEnvelope.newPayload();
        hello.put("protocolVersion", PROTOCOL_VERSION);
        hello.put("clientVersion", clientVersion());
        hello.put("clientInstanceId", instance.clientInstanceId());
        hello.put("deviceName", safeDeviceName());
        hello.put("platform", instance.platform());
        hello.put("osVersion", instance.osVersion());
        hello.put("architecture", instance.architecture());
        clientVersion.set(clientVersion());
        deviceName.set(safeDeviceName());
        RealtimeEnvelope helloEvent = RealtimeEnvelope.create("hello", hello);
        sendHello(helloEvent).whenComplete((ignored, error) -> { if (error != null) handshakeFailed(error); });
        cancel(handshakeTimeout);
        handshakeTimeout = scheduler.schedule(() -> handshakeFailed(new TimeoutException("Server did not send welcome.")), 10, TimeUnit.SECONDS);
    }
    private void onSocketMessage(RealtimeTransport source, String raw) {
        if (source != transport || explicitlyClosed.get()) return;
        final RealtimeEnvelope event;
        try { event = RealtimeEnvelope.parse(raw); }
        catch (IllegalArgumentException invalid) { log("INVALID_SERVER_EVENT"); return; }
        if ("welcome".equals(event.type())) { welcome(event); return; }
        if ("error".equals(event.type())) {
            CompletableFuture<RealtimeEnvelope> request = removePending(event.requestId());
            String code = event.payload().path("code").asText("");
            String message = event.payload().path("message").asText("Server rejected request.");
            ServerErrorException failure = new ServerErrorException(code, message);
            if (request != null) request.completeExceptionally(failure);
            dispatch(event);
            if (isTerminalCode(code)) { markExplicitDisconnect(code, message); closeTransport(); }
            return;
        }
        boolean heartbeatAck = "heartbeat_ack".equals(event.type());
        if (heartbeatAck) lastHeartbeat.set(java.time.Instant.now().toString());
        CompletableFuture<RealtimeEnvelope> request = removePending(event.requestId());
        if (request != null) request.complete(event);
        boolean delivered = dispatch(event);
        if ("disconnect".equals(event.type())) {
            markExplicitDisconnect(event.payload().path("code").asText("ADMIN_DISCONNECT"), event.payload().path("message").asText("Disconnected by server."));
            closeTransport();
            return;
        }
        if (!heartbeatAck && request == null && !delivered) log("UNKNOWN_SERVER_EVENT");
    }
    private void welcome(RealtimeEnvelope event) {
        int version = event.payload().path("protocolVersion").asInt(-1);
        if (version != PROTOCOL_VERSION) {
            markExplicitDisconnect("PROTOCOL_VERSION_UNSUPPORTED", "Unsupported server protocol version: " + version);
            update(ConnectionState.ERROR); closeTransport(); return;
        }
        cancel(handshakeTimeout);
        connectionId.set(event.payload().path("connectionId").asText(""));
        serverName.set(event.payload().path("serverName").asText("LANDrop Server"));
        serverVersion.set(event.payload().path("serverVersion").asText(""));
        serverProtocolVersion.set(version);
        disconnectReason.set("");
        serverAddress.set(wsUri.toString());
        boolean reconnected = reconnectAttempt > 0;
        reconnectAttempt = 0; update(ConnectionState.CONNECTED); log("HANDSHAKE_OK"); if (reconnected) log("RECONNECTED");
        long heartbeatMillis = event.payload().path("heartbeatIntervalMs").asLong(30_000L);
        if (heartbeatMillis < 1_000L) heartbeatMillis = 1_000L;
        startHeartbeat(heartbeatMillis);
        dispatch(event);
    }
    private void handshakeFailed(Throwable error) { if (state() != ConnectionState.HANDSHAKING || explicitlyClosed.get()) return; log("HANDSHAKE_FAILED"); update(ConnectionState.ERROR); closeTransport(); if (!isTerminalDisconnect()) scheduleReconnect(); }
    private void onSocketLost(RealtimeTransport source, Throwable error) { onSocketLost(source, error, ""); }
    private synchronized void onSocketLost(RealtimeTransport source, Throwable error, String closeReason) {
        if (source != transport) return;
        classifyCloseReason(closeReason);
        closeTransport(); stopTimers(); failPending(error);
        if (explicitlyClosed.get()) { update(ConnectionState.DISCONNECTED); return; }
        log("SERVER_DISCONNECTED"); update(ConnectionState.DISCONNECTED); if (!isTerminalDisconnect()) scheduleReconnect();
    }
    private synchronized void scheduleReconnect() {
        if (explicitlyClosed.get() || reconnectTask != null && !reconnectTask.isDone()) return;
        long delay = BACKOFF_SECONDS[Math.min(reconnectAttempt, BACKOFF_SECONDS.length - 1)]; reconnectAttempt++;
        reconnectAttempts.set(reconnectAttempt);
        log("RECONNECTING"); update(ConnectionState.RECONNECTING);
        reconnectTask = scheduler.schedule(() -> { reconnectTask = null; connect(true); }, delay, TimeUnit.SECONDS);
    }
    private void startHeartbeat(long intervalMillis) {
        cancel(heartbeatTask);
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            if (isConnected()) {
                log("HEARTBEAT_SENT");
                send("heartbeat", RealtimeEnvelope.newPayload()).exceptionally(error -> null);
            }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }
    private CompletableFuture<Void> sendHello(RealtimeEnvelope envelope) {
        RealtimeTransport active = transport;
        if (active == null || !active.isConnected()) return CompletableFuture.failedFuture(new IllegalStateException("WebSocket is not connected."));
        return active.send(envelope.toJsonWithFlatPayload());
    }
    private CompletableFuture<Void> sendEnvelope(RealtimeEnvelope envelope) {
        RealtimeTransport active = transport;
        if (active == null || !active.isConnected()) return CompletableFuture.failedFuture(new IllegalStateException("WebSocket is not connected."));
        return active.send(envelope.toJson());
    }
    private boolean dispatch(RealtimeEnvelope event) {
        var listeners = subscribers.get(event.type());
        if (listeners == null || listeners.isEmpty()) return false;
        for (Consumer<RealtimeEnvelope> listener : listeners) {
            try { listener.accept(event); } catch (Exception ignored) { }
        }
        return true;
    }
    private CompletableFuture<RealtimeEnvelope> removePending(String requestId) {
        return requestId == null || requestId.isBlank() ? null : pending.remove(requestId);
    }
    int pendingRequestCount() { return pending.size(); }
    private void failPending(Throwable error) { pending.values().forEach(future -> future.completeExceptionally(error)); pending.clear(); }
    private void stopTimers() { cancel(heartbeatTask); heartbeatTask = null; cancel(handshakeTimeout); handshakeTimeout = null; }
    private static void cancel(ScheduledFuture<?> task) { if (task != null) task.cancel(false); }
    private void closeTransport() { RealtimeTransport current = transport; transport = null; if (current != null) current.close(); }
    private void markExplicitDisconnect(String code, String message) {
        disconnectReason.set(code + (message == null || message.isBlank() ? "" : ": " + message));
        if ("ADMIN_DISCONNECT".equals(code)) log("ADMIN_DISCONNECTED");
        if (isTerminalCode(code)) {
            explicitlyClosed.set(true);
            stopTimers();
            failPending(new ServerErrorException(code, message));
            update(ConnectionState.DISCONNECTED);
        }
    }
    private void classifyCloseReason(String reason) {
        if (reason == null || reason.isBlank()) return;
        String upper = reason.toUpperCase(java.util.Locale.ROOT);
        for (String code : TERMINAL_CODES) {
            if (upper.contains(code)) { markExplicitDisconnect(code, reason); return; }
        }
        if (upper.contains("SERVER_RESTARTING")) disconnectReason.set("SERVER_RESTARTING: " + reason);
    }
    private boolean isTerminalDisconnect() {
        String reason = disconnectReason.get();
        return explicitlyClosed.get() || (!reason.isBlank() && isTerminalCode(reason.split(":", 2)[0]));
    }
    private static boolean isTerminalCode(String code) {
        return java.util.Arrays.asList(TERMINAL_CODES).contains(code);
    }
    private static final String[] TERMINAL_CODES = {
            "ADMIN_DISCONNECT", "SERVER_SHUTDOWN", "SESSION_REVOKED",
            "ACCOUNT_DISABLED", "PROTOCOL_VERSION_UNSUPPORTED"
    };
    // This is a state model, not a scene node. Controllers marshal their UI work to the FX thread.
    // Keeping it synchronous also makes WELCOME atomically establish CONNECTED before dispatching subscribers.
    private void update(ConnectionState next) { state.set(next); }
    private String safeDeviceName() { String name = deviceNameSupplier.get(); return name == null || name.isBlank() ? instance.defaultDeviceName() : name.trim(); }
    private static String clientVersion() { Package p = ClientRealtimeService.class.getPackage(); return p.getImplementationVersion() == null ? FrontendConfig.CLIENT_VERSION : p.getImplementationVersion(); }
    private static void log(String event) { System.out.println("[LANDrop Client] " + event); }
    @Override public synchronized void close() { explicitlyClosed.set(true); cancel(reconnectTask); reconnectTask = null; stopTimers(); closeTransport(); failPending(new CancellationException("Realtime client closed.")); update(ConnectionState.DISCONNECTED); }
}
