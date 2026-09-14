package com.landrop.realtime;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

public final class WsProbe implements WebSocket.Listener, AutoCloseable {
    public static final ObjectMapper JSON = new ObjectMapper();
    public final BlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();
    public final CompletableFuture<Integer> closed = new CompletableFuture<>();
    private final StringBuilder text = new StringBuilder();
    public WebSocket socket;

    public static WsProbe open(RealtimeServer server, UUID channel, String token) {
        return openEndpoint(server, "/api/realtime/channels/" + channel, token, "test-device");
    }

    public static WsProbe open(RealtimeServer server, UUID channel, String token, String device) {
        return openEndpoint(server, "/api/realtime/channels/" + channel, token, device);
    }

    public static WsProbe openRoom(RealtimeServer server, String token, String device) {
        return openEndpoint(server, "/api/realtime", token, device);
    }

    public static WsProbe openEndpoint(RealtimeServer server, String path, String token, String device) {
        var probe = new WsProbe();
        var builder = HttpClient.newHttpClient().newWebSocketBuilder();
        if (device != null) builder.header("X-Device-Label", device);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        probe.socket = builder.buildAsync(URI.create("ws://127.0.0.1:" + server.getPort() + path), probe).join();
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

    public void send(Map<String, ?> command) throws Exception {
        socket.sendText(JSON.writeValueAsString(command), true).join();
    }

    public void message(UUID clientId, String content) throws Exception {
        send(Map.of("type", "message.send", "requestId", UUID.randomUUID().toString(),
                "clientMessageId", clientId.toString(), "content", content));
    }

    public void messageWithReply(UUID clientId, String content, UUID replyToId) throws Exception {
        send(Map.of("type", "message.send", "requestId", UUID.randomUUID().toString(),
                "clientMessageId", clientId.toString(), "content", content, "replyToId", replyToId.toString()));
    }

    public void join(UUID channelId) throws Exception {
        send(Map.of("type", "channel.join", "requestId", UUID.randomUUID().toString(),
                "channelId", channelId.toString()));
    }

    public void edit(UUID messageId, String content) throws Exception {
        send(Map.of("type", "message.edit", "requestId", UUID.randomUUID().toString(),
                "messageId", messageId.toString(), "content", content));
    }

    public void delete(UUID messageId) throws Exception {
        send(Map.of("type", "message.delete", "requestId", UUID.randomUUID().toString(),
                "messageId", messageId.toString()));
    }

    public JsonNode event(String type) throws Exception {
        JsonNode event = events.poll(10, TimeUnit.SECONDS);
        assertNotNull(event, "Missing " + type + " event");
        assertEquals(type, event.path("type").asText(), event.toString());
        return event;
    }

    public void history() throws Exception {
        send(Map.of("type", "history", "requestId", UUID.randomUUID().toString()));
    }

    public void requestTransfer(UUID fileId, UUID receiverUserId, String senderDeviceId, String receiverDeviceId) throws Exception {
        Map<String, Object> map = new HashMap<>();
        map.put("type", "transfer.request");
        map.put("requestId", UUID.randomUUID().toString());
        map.put("fileId", fileId.toString());
        map.put("receiverUserId", receiverUserId.toString());
        if (senderDeviceId != null) map.put("senderDeviceId", senderDeviceId);
        if (receiverDeviceId != null) map.put("receiverDeviceId", receiverDeviceId);
        send(map);
    }

    public void acceptTransfer(UUID transferId, String receiverDeviceId) throws Exception {
        Map<String, Object> map = new HashMap<>();
        map.put("type", "transfer.accept");
        map.put("requestId", UUID.randomUUID().toString());
        map.put("transferId", transferId.toString());
        if (receiverDeviceId != null) map.put("receiverDeviceId", receiverDeviceId);
        send(map);
    }

    public void rejectTransfer(UUID transferId, String reason) throws Exception {
        Map<String, Object> map = new HashMap<>();
        map.put("type", "transfer.reject");
        map.put("requestId", UUID.randomUUID().toString());
        map.put("transferId", transferId.toString());
        if (reason != null) map.put("reason", reason);
        send(map);
    }

    public void cancelTransfer(UUID transferId, String reason) throws Exception {
        Map<String, Object> map = new HashMap<>();
        map.put("type", "transfer.cancel");
        map.put("requestId", UUID.randomUUID().toString());
        map.put("transferId", transferId.toString());
        if (reason != null) map.put("reason", reason);
        send(map);
    }

    public void progressTransfer(UUID transferId, long bytesTransferred, long totalBytes, double speed) throws Exception {
        send(Map.of(
            "type", "transfer.progress",
            "requestId", UUID.randomUUID().toString(),
            "transferId", transferId.toString(),
            "bytesTransferred", bytesTransferred,
            "totalBytes", totalBytes,
            "speedBytesPerSec", speed
        ));
    }

    public void completeTransfer(UUID transferId) throws Exception {
        send(Map.of("type", "transfer.complete", "requestId", UUID.randomUUID().toString(), "transferId", transferId.toString()));
    }

    public void registerDevice(String deviceId, String deviceName, String peerId, String ip, Integer port) throws Exception {
        Map<String, Object> map = new HashMap<>();
        map.put("type", "device.register");
        map.put("requestId", UUID.randomUUID().toString());
        map.put("deviceId", deviceId);
        map.put("deviceName", deviceName);
        if (peerId != null) map.put("peerId", peerId);
        if (ip != null) map.put("ipAddress", ip);
        if (port != null) map.put("port", port);
        send(map);
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
