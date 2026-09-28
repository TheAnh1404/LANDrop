package com.landrop.chat;

/**
 * Event listener interface for observing chat service events.
 * Decouples chat business logic and networking from UI frameworks (e.g. CLI, JavaFX).
 */
public interface ChatListener {

    /**
     * Invoked when a new chat message is received from the remote peer.
     *
     * @param message the received ChatMessage
     */
    default void onMessageReceived(ChatMessage message) {}

    /**
     * Invoked when a chat message has been successfully sent to the remote peer.
     *
     * @param message the sent ChatMessage
     */
    default void onMessageSent(ChatMessage message) {}

    /**
     * Invoked when a clipboard text snippet is received from the remote peer.
     *
     * @param message the received ClipboardMessage
     */
    default void onClipboardReceived(com.landrop.protocol.ClipboardMessage message) {}

    /**
     * Invoked when a chat or network error occurs.
     *
     * @param error the exception encountered
     */
    default void onChatError(Exception error) {}

    /**
     * Invoked when the chat session is disconnected.
     */
    default void onChatDisconnected() {}
}
