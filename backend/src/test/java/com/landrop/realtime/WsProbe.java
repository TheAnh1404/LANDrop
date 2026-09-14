package com.landrop.realtime;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

final class WsProbe implements WebSocket.Listener, AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper();
    final BlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();
    final CompletableFuture<Integer> closed = new CompletableFuture<>();
    private final StringBuilder text = new StringBuilder();
    WebSocket socket;
    static WsProbe open(RealtimeServer server, UUID channel, String token) {
        var probe = new WsProbe();
        var builder = HttpClient.newHttpClient().newWebSocketBuilder().header("X-Device-Label", "test-device");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        probe.socket = builder.buildAsync(URI.create("ws://127.0.0.1:" + server.getPort()
                + "/api/realtime/channels/" + channel), probe).join();
        return probe;
    }
    @Override public void onOpen(WebSocket ws) { ws.request(1); }
    @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        text.append(data);
        if (last) {
            try { events.add(JSON.readTree(text.toString())); } catch (Exception e) { throw new AssertionError(e); }
            text.setLength(0);
        }
        ws.request(1); return CompletableFuture.completedFuture(null);
    }
    @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
        closed.complete(code); return CompletableFuture.completedFuture(null);
    }
    void send(Map<String, ?> command) throws Exception { socket.sendText(JSON.writeValueAsString(command), true).join(); }
    void message(UUID clientId, String content) throws Exception {
        send(Map.of("type", "message.send", "requestId", UUID.randomUUID().toString(),
                "clientMessageId", clientId.toString(), "content", content));
    }
    JsonNode event(String type) throws Exception {
        JsonNode event = events.poll(10, TimeUnit.SECONDS);
        assertNotNull(event, "Missing " + type + " event");
        assertEquals(type, event.path("type").asText(), event.toString()); return event;
    }
    void history() throws Exception {
        send(Map.of("type", "history", "requestId", UUID.randomUUID().toString()));
    }
    @Override public void close() {
        if (socket != null) {
            try {
                if (!socket.isOutputClosed()) {
                    socket.sendClose(1000, "done").join();
                }
            } catch (Exception ignored) {}
        }
    }
}
