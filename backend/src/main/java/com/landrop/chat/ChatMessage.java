package com.landrop.chat;

import java.util.Objects;

/**
 * Immutable representation of a chat message in the LANDrop peer chat system.
 *
 * @param requestId the unique correlation ID of the packet
 * @param sender the sender identifier or peer name
 * @param content the text payload of the message
 * @param timestamp epoch millisecond timestamp when the message was created or received
 */
public record ChatMessage(long requestId, String sender, String content, long timestamp) {

    /**
     * Compact constructor validating required fields.
     */
    public ChatMessage {
        Objects.requireNonNull(sender, "Sender cannot be null");
        Objects.requireNonNull(content, "Content cannot be null");
    }

    /**
     * Convenience constructor using current system time as timestamp.
     *
     * @param requestId the unique correlation ID
     * @param sender the sender identifier or peer name
     * @param content the text content
     */
    public ChatMessage(long requestId, String sender, String content) {
        this(requestId, sender, content, System.currentTimeMillis());
    }

    public long getRequestId() {
        return requestId;
    }

    public String getSender() {
        return sender;
    }

    public String getContent() {
        return content;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "ChatMessage{" +
                "requestId=" + requestId +
                ", sender='" + sender + '\'' +
                ", content='" + content + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
