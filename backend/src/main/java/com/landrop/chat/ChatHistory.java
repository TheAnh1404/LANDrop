package com.landrop.chat;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe, in-memory repository for chat messages during runtime.
 * Persists messages in memory only for the duration of the application session.
 */
public class ChatHistory {

    private final List<ChatMessage> messages = new CopyOnWriteArrayList<>();

    /**
     * Appends a message to the history.
     *
     * @param message the chat message to record
     */
    public void add(ChatMessage message) {
        Objects.requireNonNull(message, "Message cannot be null");
        messages.add(message);
    }

    /**
     * Returns an unmodifiable view of all recorded messages in chronological order.
     *
     * @return unmodifiable list of chat messages
     */
    public List<ChatMessage> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    /**
     * Returns the number of messages in history.
     *
     * @return message count
     */
    public int size() {
        return messages.size();
    }

    /**
     * Clears all messages from memory.
     */
    public void clear() {
        messages.clear();
    }
}
