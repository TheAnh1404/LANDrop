package com.landrop.ui.services;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Standard Java 21 LTS HTTP/WebSocket transport implementation.
 * Connects asynchronously with timeout, authorization header, and query param support.
 */
public class WebSocketRealtimeTransport implements RealtimeTransport, WebSocket.Listener {

    private final URI baseWsUri;
    private final String bearerToken;
    private final HttpClient httpClient;
    private final StringBuilder messageBuffer = new StringBuilder();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile WebSocket webSocket;
    private volatile Listener listener;

    public WebSocketRealtimeTransport(URI baseWsUri, String bearerToken) {
        this.baseWsUri = baseWsUri;
        this.bearerToken = bearerToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public CompletableFuture<Void> connect() {
        URI targetUri = baseWsUri;

        WebSocket.Builder builder = httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10));

        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }

        return builder.buildAsync(targetUri, this).thenAccept(ws -> {
            if (closed.get()) { ws.abort(); return; }
        });
    }

    @Override
    public CompletableFuture<Void> send(String payload) {
        WebSocket ws = this.webSocket;
        if (ws == null || !connected.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("WebSocket is not connected"));
        }
        return ws.sendText(payload, true).thenAccept(ignored -> {});
    }

    @Override
    public void close() {
        closed.set(true);
        connected.set(false);
        WebSocket ws = this.webSocket;
        this.webSocket = null;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "Client close");
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    // WebSocket.Listener callbacks

    @Override
    public void onOpen(WebSocket webSocket) {
        if (closed.get()) { webSocket.abort(); return; }
        this.webSocket = webSocket;
        this.connected.set(true);
        webSocket.request(1);
        if (listener != null) {
            listener.onOpen();
        }
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        messageBuffer.append(data);
        if (last) {
            String message = messageBuffer.toString();
            messageBuffer.setLength(0);
            if (listener != null) {
                try {
                    listener.onMessage(message);
                } catch (Exception e) {
                    System.err.println("[WebSocket] Error dispatching message to listener: " + e.getMessage());
                }
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        this.connected.set(false);
        if (listener != null) {
            listener.onClose(statusCode, reason);
        }
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        this.connected.set(false);
        if (listener != null) {
            listener.onError(error);
        }
    }
}
