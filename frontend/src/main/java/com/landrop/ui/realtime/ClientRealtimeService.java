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
    private final URI wsUri;
    private final HealthProbe health;
    private final ClientInstanceService instance;
    private final Supplier<String> tokenSupplier;
    private final Supplier<String> deviceNameSupplier;
    private final TransportFactory transports;
    private final ScheduledExecutorService scheduler;
    private final ObjectProperty<ConnectionState> state = new SimpleObjectProperty<>(ConnectionState.IDLE);
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
        this.wsUri = Objects.requireNonNull(wsUri); this.health = Objects.requireNonNull(health);
        this.instance = Objects.requireNonNull(instance); this.tokenSupplier = Objects.requireNonNull(tokenSupplier);
        this.deviceNameSupplier = Objects.requireNonNull(deviceNameSupplier); this.transports = Objects.requireNonNull(transports);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "landrop-server-control"); t.setDaemon(true); return t; });
    }
    public ReadOnlyObjectProperty<ConnectionState> stateProperty() { return state; }
    public ConnectionState state() { return state.get(); }
    public boolean isConnected() { return state() == ConnectionState.CONNECTED; }
    public CompletableFuture<Void> checkHealth() { return health.check(); }
    public void start() { explicitlyClosed.set(false); connect(false); }
    public void reconnectNow() { stopTimers(); closeTransport(); reconnectAttempt = 0; explicitlyClosed.set(false); update(ConnectionState.DISCONNECTED); connect(true); }

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
        }, REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
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
            if (healthFailure != null) { log("SERVER_HEALTH_FAILED"); update(ConnectionState.ERROR); scheduleReconnect(); return; }
            log("SERVER_HEALTH_OK"); openSocket();
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
            @Override public void onClose(int code, String reason) { onSocketLost(next, new IllegalStateException("WebSocket closed (" + code + "): " + reason)); }
            @Override public void onError(Throwable error) { onSocketLost(next, error); }
        });
        next.connect().exceptionally(error -> { onSocketLost(next, error); return null; });
    }
    private void onSocketOpen(RealtimeTransport source) {
        if (source != transport || explicitlyClosed.get()) return;
        log("WS_CONNECTED"); update(ConnectionState.HANDSHAKING);
        var hello = RealtimeEnvelope.newPayload();
        hello.put("protocolVersion", PROTOCOL_VERSION);
        hello.put("clientVersion", clientVersion());
        hello.put("clientInstanceId", instance.clientInstanceId());
        hello.put("deviceName", safeDeviceName());
        hello.put("platform", instance.platform());
        sendEnvelope(RealtimeEnvelope.create("hello", hello)).whenComplete((ignored, error) -> { if (error != null) handshakeFailed(error); });
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
            CompletableFuture<RealtimeEnvelope> request = pending.remove(event.requestId());
            IllegalStateException failure = new IllegalStateException(event.payload().path("message").asText("Server rejected request."));
            if (request != null) request.completeExceptionally(failure); dispatch(event); return;
        }
        CompletableFuture<RealtimeEnvelope> request = pending.remove(event.requestId());
        if (request != null) request.complete(event);
        boolean delivered = dispatch(event);
        if (request == null && !delivered) log("UNKNOWN_SERVER_EVENT");
    }
    private void welcome(RealtimeEnvelope event) {
        int version = event.payload().path("protocolVersion").asInt(-1);
        if (version != PROTOCOL_VERSION) { handshakeFailed(new IllegalStateException("Unsupported server protocol version: " + version)); return; }
        cancel(handshakeTimeout); reconnectAttempt = 0; update(ConnectionState.CONNECTED); log("HANDSHAKE_OK");
        long heartbeatMillis = event.payload().path("heartbeatIntervalMs").asLong(30_000L);
        if (heartbeatMillis < 1_000L) heartbeatMillis = 1_000L;
        startHeartbeat(heartbeatMillis);
        dispatch(event);
    }
    private void handshakeFailed(Throwable error) { if (state() != ConnectionState.HANDSHAKING || explicitlyClosed.get()) return; log("HANDSHAKE_FAILED"); update(ConnectionState.ERROR); closeTransport(); scheduleReconnect(); }
    private synchronized void onSocketLost(RealtimeTransport source, Throwable error) {
        if (source != transport) return;
        closeTransport(); stopTimers(); failPending(error);
        if (explicitlyClosed.get()) { update(ConnectionState.DISCONNECTED); return; }
        log("WS_DISCONNECTED"); update(ConnectionState.DISCONNECTED); scheduleReconnect();
    }
    private synchronized void scheduleReconnect() {
        if (explicitlyClosed.get() || reconnectTask != null && !reconnectTask.isDone()) return;
        long delay = BACKOFF_SECONDS[Math.min(reconnectAttempt, BACKOFF_SECONDS.length - 1)]; reconnectAttempt++;
        log("RECONNECTING"); update(ConnectionState.RECONNECTING);
        reconnectTask = scheduler.schedule(() -> { reconnectTask = null; connect(true); }, delay, TimeUnit.SECONDS);
    }
    private void startHeartbeat(long intervalMillis) {
        cancel(heartbeatTask);
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            if (isConnected()) send("heartbeat", RealtimeEnvelope.newPayload()).exceptionally(error -> null);
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
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
    private void failPending(Throwable error) { pending.values().forEach(future -> future.completeExceptionally(error)); pending.clear(); }
    private void stopTimers() { cancel(heartbeatTask); heartbeatTask = null; cancel(handshakeTimeout); handshakeTimeout = null; }
    private static void cancel(ScheduledFuture<?> task) { if (task != null) task.cancel(false); }
    private void closeTransport() { RealtimeTransport current = transport; transport = null; if (current != null) current.close(); }
    // This is a state model, not a scene node. Controllers marshal their UI work to the FX thread.
    // Keeping it synchronous also makes WELCOME atomically establish CONNECTED before dispatching subscribers.
    private void update(ConnectionState next) { state.set(next); }
    private String safeDeviceName() { String name = deviceNameSupplier.get(); return name == null || name.isBlank() ? instance.defaultDeviceName() : name.trim(); }
    private static String clientVersion() { Package p = ClientRealtimeService.class.getPackage(); return p.getImplementationVersion() == null ? FrontendConfig.CLIENT_VERSION : p.getImplementationVersion(); }
    private static void log(String event) { System.out.println("[LANDrop Client] " + event); }
    @Override public synchronized void close() { explicitlyClosed.set(true); cancel(reconnectTask); reconnectTask = null; stopTimers(); closeTransport(); failPending(new CancellationException("Realtime client closed.")); update(ConnectionState.DISCONNECTED); }
}
