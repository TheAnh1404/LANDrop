package com.landrop.ui.realtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landrop.ui.connection.ConnectionState;
import com.landrop.ui.models.RealtimeConnectionStatus;
import com.landrop.ui.protocol.RealtimeEnvelope;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Chat adapter over the sole ClientRealtimeService; it owns no socket or retry timer. */
public final class RealtimeGateway implements AutoCloseable {
    public interface SubscriptionListener {
        void onMessage(String payload);
        /** Invoked after the server has accepted this channel room subscription. */
        default void onReady() { }
        default void onError(Throwable error) { }
    }
    private final ClientRealtimeService client;
    private final ObjectProperty<RealtimeConnectionStatus> status = new SimpleObjectProperty<>(RealtimeConnectionStatus.IDLE);
    private final Map<String, Map<SubscriptionListener, AutoCloseable[]>> channelListeners = new ConcurrentHashMap<>();
    private final AutoCloseable welcomeSubscription;

    public RealtimeGateway(ClientRealtimeService client) {
        this.client = Objects.requireNonNull(client);
        client.stateProperty().addListener((obs, old, next) -> status.set(map(next)));
        status.set(map(client.state()));
        this.welcomeSubscription = client.subscribe("welcome", ignored -> joinChannels());
    }
    public ObjectProperty<RealtimeConnectionStatus> statusProperty() { return status; }
    public AutoCloseable subscribeChannel(String channelId, SubscriptionListener listener) {
        Objects.requireNonNull(channelId); Objects.requireNonNull(listener);
        AutoCloseable created = client.subscribe("message.created", event -> receive(channelId, listener, event));
        AutoCloseable updated = client.subscribe("message.updated", event -> receive(channelId, listener, event));
        AutoCloseable deleted = client.subscribe("message.deleted", event -> receive(channelId, listener, event));
        AutoCloseable errors = client.subscribe("error", event -> listener.onError(new IllegalStateException(event.payload().path("message").asText("Realtime request failed."))));
        channelListeners.computeIfAbsent(channelId, ignored -> new ConcurrentHashMap<>()).put(listener, new AutoCloseable[]{created, updated, deleted, errors});
        join(channelId, listener);
        return () -> unsubscribe(channelId, listener);
    }
    private void receive(String channelId, SubscriptionListener listener, RealtimeEnvelope event) {
        if (channelId.equals(event.payload().path("channelId").asText(""))) listener.onMessage(event.toJson());
    }
    private void joinChannels() { channelListeners.forEach((channelId, listeners) -> listeners.keySet().forEach(listener -> join(channelId, listener))); }
    private void join(String channelId, SubscriptionListener listener) {
        if (!client.isConnected()) return;
        ObjectNode payload = RealtimeEnvelope.newPayload(); payload.put("channelId", channelId);
        client.request("channel.join", payload).thenRun(listener::onReady)
                .exceptionally(error -> { listener.onError(error); return null; });
    }
    private void unsubscribe(String channelId, SubscriptionListener listener) {
        Map<SubscriptionListener, AutoCloseable[]> listeners = channelListeners.get(channelId);
        if (listeners == null) return;
        AutoCloseable[] closers = listeners.remove(listener);
        if (closers != null) for (AutoCloseable closer : closers) try { closer.close(); } catch (Exception ignored) { }
        if (listeners.isEmpty()) {
            channelListeners.remove(channelId, listeners);
            if (client.isConnected()) { ObjectNode payload = RealtimeEnvelope.newPayload(); payload.put("channelId", channelId); client.request("channel.leave", payload).exceptionally(error -> null); }
        }
    }
    private static RealtimeConnectionStatus map(ConnectionState value) {
        return switch (value) {
            case IDLE -> RealtimeConnectionStatus.IDLE;
            case CONNECTING -> RealtimeConnectionStatus.CONNECTING;
            case HANDSHAKING -> RealtimeConnectionStatus.HANDSHAKING;
            case CONNECTED -> RealtimeConnectionStatus.CONNECTED;
            case RECONNECTING -> RealtimeConnectionStatus.RECONNECTING;
            case ERROR -> RealtimeConnectionStatus.ERROR;
            case DISCONNECTED -> RealtimeConnectionStatus.DISCONNECTED;
        };
    }
    @Override public void close() {
        channelListeners.forEach((channel, listeners) -> listeners.keySet().forEach(listener -> unsubscribe(channel, listener)));
        try { welcomeSubscription.close(); } catch (Exception ignored) { }
    }
}
