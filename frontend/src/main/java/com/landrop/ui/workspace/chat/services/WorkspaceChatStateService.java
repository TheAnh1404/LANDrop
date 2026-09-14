package com.landrop.ui.workspace.chat.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.landrop.ui.cloud.model.Domain;
import com.landrop.ui.models.RealtimeConnectionStatus;
import com.landrop.ui.services.RealtimeTransport;
import com.landrop.ui.services.WebSocketRealtimeTransport;
import com.landrop.ui.workspace.chat.api.HttpTeamChatApi;
import com.landrop.ui.workspace.chat.api.TeamChatApi;
import com.landrop.ui.workspace.services.WorkspaceStateService;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns the product-chat state graph: active workspace -> channels -> active channel -> messages.
 * It deliberately has no dependency on LAN peer/device state.
 */
public final class WorkspaceChatStateService implements AutoCloseable {
    private static final int PAGE_SIZE = 50;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final WorkspaceStateService workspaces;
    private final TeamChatApi api;
    private final URI wsBase;
    private final ObservableList<Domain.Channel> channels = FXCollections.observableArrayList();
    private final ObservableList<Domain.Message> messages = FXCollections.observableArrayList();
    private final ObjectProperty<Domain.Channel> activeChannel = new SimpleObjectProperty<>();
    private final ObjectProperty<RealtimeConnectionStatus> connectionStatus = new SimpleObjectProperty<>(RealtimeConnectionStatus.DISCONNECTED);
    private final BooleanProperty loadingChannels = new SimpleBooleanProperty();
    private final BooleanProperty loadingHistory = new SimpleBooleanProperty();
    private final BooleanProperty loadingOlder = new SimpleBooleanProperty();
    private final StringProperty errorMessage = new SimpleStringProperty("");
    private final StringProperty historyCursor = new SimpleStringProperty();
    private final AtomicLong generation = new AtomicLong();
    private volatile RealtimeTransport channelSocket;
    private volatile boolean closed;

    public WorkspaceChatStateService(WorkspaceStateService workspaces, TeamChatApi api, URI wsBase) {
        this.workspaces = Objects.requireNonNull(workspaces); this.api = Objects.requireNonNull(api); this.wsBase = wsBase;
        workspaces.activeWorkspaceProperty().addListener((o, old, next) -> loadWorkspace(next));
        if (workspaces.getActiveWorkspace() != null) loadWorkspace(workspaces.getActiveWorkspace());
    }
    public ObservableList<Domain.Channel> channels() { return channels; }
    public ObservableList<Domain.Message> messages() { return messages; }
    public ReadOnlyObjectProperty<Domain.Channel> activeChannelProperty() { return activeChannel; }
    public Domain.Channel activeChannel() { return activeChannel.get(); }
    public ReadOnlyObjectProperty<RealtimeConnectionStatus> connectionStatusProperty() { return connectionStatus; }
    public ReadOnlyBooleanProperty loadingChannelsProperty() { return loadingChannels; }
    public ReadOnlyBooleanProperty loadingHistoryProperty() { return loadingHistory; }
    public ReadOnlyBooleanProperty loadingOlderProperty() { return loadingOlder; }
    public ReadOnlyStringProperty errorMessageProperty() { return errorMessage; }
    public boolean hasOlderMessages() { return historyCursor.get() != null; }
    public boolean canManageChannels() { return workspaces.isAdmin(); }

    private void loadWorkspace(Domain.Workspace workspace) {
        long token = generation.incrementAndGet(); disconnectSocket(); run(() -> {
            channels.clear(); messages.clear(); activeChannel.set(null); historyCursor.set(null); errorMessage.set("");
            loadingChannels.set(workspace != null);
        });
        if (workspace == null) return;
        api.listChannels(workspace.id()).whenComplete((found, failure) -> run(() -> {
            if (closed || token != generation.get()) return;
            loadingChannels.set(false);
            if (failure != null) { errorMessage.set(problem(failure)); return; }
            channels.setAll(found == null ? List.of() : found);
            if (!channels.isEmpty()) selectChannel(channels.getFirst());
        }));
    }
    public void selectChannel(Domain.Channel channel) {
        if (channel == null || !channels.contains(channel)) return;
        long token = generation.incrementAndGet(); disconnectSocket(); run(() -> {
            activeChannel.set(channel); messages.clear(); historyCursor.set(null); errorMessage.set(""); loadingHistory.set(true);
        });
        api.listMessages(channel.id(), PAGE_SIZE, null).whenComplete((page, failure) -> run(() -> {
            if (closed || token != generation.get() || activeChannel.get() == null || !channel.id().equals(activeChannel.get().id())) return;
            loadingHistory.set(false);
            if (failure != null) { errorMessage.set(problem(failure)); return; }
            merge(page.messages()); historyCursor.set(page.nextCursor()); connectSocket(channel.id(), token);
        }));
    }
    public CompletableFuture<Void> loadOlder() {
        Domain.Channel channel = activeChannel.get(); String before = historyCursor.get();
        if (channel == null || before == null || loadingOlder.get()) return CompletableFuture.completedFuture(null);
        long token = generation.get(); run(() -> loadingOlder.set(true));
        return api.listMessages(channel.id(), PAGE_SIZE, before).thenAccept(page -> run(() -> {
            if (token == generation.get() && activeChannel.get() != null && channel.id().equals(activeChannel.get().id())) { merge(page.messages()); historyCursor.set(page.nextCursor()); }
        })).whenComplete((ignored, failure) -> run(() -> { loadingOlder.set(false); if (failure != null && token == generation.get()) errorMessage.set(problem(failure)); }));
    }
    public CompletableFuture<Domain.Channel> createChannel(String name, String description) {
        Domain.Workspace ws = workspaces.getActiveWorkspace();
        if (ws == null) return CompletableFuture.failedFuture(new IllegalStateException("Select a workspace first."));
        if (!canManageChannels()) return CompletableFuture.failedFuture(new IllegalStateException("Only workspace owners and admins can create channels."));
        return api.createChannel(ws.id(), name.trim(), blankToNull(description)).thenApply(created -> { run(() -> channels.add(created)); return created; });
    }
    public CompletableFuture<Domain.Message> send(String content, String replyToId) {
        Domain.Channel channel = activeChannel.get();
        if (channel == null) return CompletableFuture.failedFuture(new IllegalStateException("Select a channel first."));
        return api.sendMessage(channel.id(), content.trim(), replyToId).thenApply(message -> { run(() -> merge(List.of(message))); return message; })
                .whenComplete((m, failure) -> { if (failure != null) run(() -> errorMessage.set(problem(failure))); });
    }
    public CompletableFuture<Domain.Message> edit(Domain.Message message, String content) {
        return api.updateMessage(message.id(), content.trim()).thenApply(updated -> { run(() -> merge(List.of(updated))); return updated; });
    }
    public CompletableFuture<Domain.Message> delete(Domain.Message message) {
        return api.deleteMessage(message.id()).thenApply(deleted -> { run(() -> merge(List.of(deleted))); return deleted; });
    }
    /** Canonical ID upsert prevents REST acknowledgement + WebSocket event duplicates. */
    private void merge(Collection<Domain.Message> incoming) {
        Map<String, Domain.Message> byId = new LinkedHashMap<>(); for (Domain.Message m : messages) byId.put(m.id(), m);
        for (Domain.Message m : incoming) if (m != null && activeChannel.get() != null && activeChannel.get().id().equals(m.channelId())) byId.put(m.id(), m);
        List<Domain.Message> ordered = new ArrayList<>(byId.values()); ordered.sort(Comparator.comparing(Domain.Message::createdAt).thenComparing(Domain.Message::id)); messages.setAll(ordered);
    }
    private void connectSocket(String channelId, long token) {
        if (wsBase == null || closed) return;
        String accessToken = workspaces.authStateService().accessToken().orElse(null);
        WebSocketRealtimeTransport socket = new WebSocketRealtimeTransport(channelUri(channelId), accessToken);
        channelSocket = socket;
        socket.setListener(new RealtimeTransport.Listener() {
            @Override public void onOpen() { if (token == generation.get()) run(() -> connectionStatus.set(RealtimeConnectionStatus.CONNECTED)); }
            @Override public void onMessage(String payload) { receiveEvent(payload, channelId, token); }
            @Override public void onClose(int code, String reason) { if (!closed && token == generation.get()) { run(() -> connectionStatus.set(RealtimeConnectionStatus.RECONNECTING)); reconnect(channelId, token); } }
            @Override public void onError(Throwable error) { if (!closed && token == generation.get()) { run(() -> connectionStatus.set(RealtimeConnectionStatus.RECONNECTING)); reconnect(channelId, token); } }
        });
        run(() -> connectionStatus.set(RealtimeConnectionStatus.CONNECTING));
        socket.connect().exceptionally(failure -> { if (token == generation.get()) run(() -> { connectionStatus.set(RealtimeConnectionStatus.DISCONNECTED); errorMessage.set("Realtime connection failed; message history remains available."); }); return null; });
    }
    private void reconnect(String channelId, long token) { CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS).execute(() -> { if (!closed && token == generation.get()) connectSocket(channelId, token); }); }
    private void receiveEvent(String payload, String channelId, long token) {
        try { JsonNode root = JSON.readTree(payload); String type = root.path("type").asText();
            if (!("message.created".equals(type) || "message.updated".equals(type) || "message.deleted".equals(type))) return;
            JsonNode raw = root.has("message") ? root.get("message") : root.path("data"); Domain.Message message = HttpTeamChatApi.message(raw);
            if (token == generation.get() && channelId.equals(message.channelId())) run(() -> merge(List.of(message)));
        } catch (Exception ignored) { }
    }
    private URI channelUri(String channelId) {
        URI base = wsBase; String root = base.getScheme() + "://" + base.getAuthority();
        return URI.create(root + "/api/realtime/channels/" + channelId);
    }
    private void disconnectSocket() { RealtimeTransport socket = channelSocket; channelSocket = null; if (socket != null) socket.close(); run(() -> connectionStatus.set(RealtimeConnectionStatus.DISCONNECTED)); }
    @Override public void close() { closed = true; generation.incrementAndGet(); disconnectSocket(); run(() -> { channels.clear(); messages.clear(); activeChannel.set(null); }); }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String problem(Throwable t) { Throwable x = t; while (x.getCause() != null && (x instanceof java.util.concurrent.CompletionException || x instanceof java.util.concurrent.ExecutionException)) x = x.getCause(); return x.getMessage() == null ? "Chat request failed." : x.getMessage(); }
    private static void run(Runnable task) { try { if (Platform.isFxApplicationThread()) task.run(); else Platform.runLater(task); } catch (IllegalStateException ignored) { task.run(); } }
}
