package com.landrop.ui.services;

import java.util.concurrent.CompletableFuture;

/**
 * Transport abstraction for real-time bi-directional messaging.
 * Decouples message state logic from socket/network implementations.
 */
public interface RealtimeTransport {

    interface Listener {
        void onOpen();
        void onMessage(String payload);
        void onClose(int statusCode, String reason);
        void onError(Throwable throwable);
    }

    void setListener(Listener listener);

    CompletableFuture<Void> connect();

    CompletableFuture<Void> send(String payload);

    void close();

    boolean isConnected();
}
