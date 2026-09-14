package com.landrop.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.*;

/**
 * Lightweight test WebSocket client for validating FOUNDATION-01A server specifications.
 */
public class FoundationWsClient implements WebSocket.Listener, AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
    private final CompletableFuture<Integer> closedCode = new CompletableFuture<>();
    private final StringBuilder buffer = new StringBuilder();
    private WebSocket webSocket;

    public static FoundationWsClient connect(int port) throws Exception {
        FoundationWsClient client = new FoundationWsClient();
        HttpClient http = HttpClient.newHttpClient();
        client.webSocket = http.newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + port), client)
                .get(5, TimeUnit.SECONDS);
        return client;
    }

    @Override
    public void onOpen(WebSocket ws) {
        ws.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        buffer.append(data);
        if (last) {
            try {
                messages.add(JSON.readTree(buffer.toString()));
            } catch (Exception e) {
                // Ignore parsing errors in raw listener
            }
            buffer.setLength(0);
        }
        ws.request(1);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
        closedCode.complete(statusCode);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
        closedCode.completeExceptionally(error);
    }

    public void sendJson(Object payload) throws Exception {
        String text = JSON.writeValueAsString(payload);
        webSocket.sendText(text, true).get(5, TimeUnit.SECONDS);
    }

    public void sendRaw(String rawText) throws Exception {
        webSocket.sendText(rawText, true).get(5, TimeUnit.SECONDS);
    }

    public JsonNode receive(long timeoutMs) throws InterruptedException {
        return messages.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public int awaitClose(long timeoutMs) throws Exception {
        return closedCode.get(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public boolean isOpen() {
        return !webSocket.isInputClosed() && !webSocket.isOutputClosed();
    }

    @Override
    public void close() {
        if (webSocket != null && isOpen()) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Test finish").get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                webSocket.abort();
            }
        }
    }
}
