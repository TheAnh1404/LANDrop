package com.landrop.ui.models;

import com.landrop.chat.ChatMessage;
import com.landrop.ui.cloud.model.Domain;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;

/**
 * Presentation wrapper for chat messages, providing formatted timestamp,
 * sender User identity (id, displayName, username, avatar), canonical ID,
 * and authenticated user ownership checking.
 */
public record UIMessage(
        String id,
        String senderId,
        String senderDisplayName,
        String senderUsername,
        String senderAvatarUrl,
        String content,
        long timestamp,
        boolean isLocal
) {
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    public UIMessage {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(content, "content cannot be null");
    }

    /**
     * Backward-compatible constructor for existing P2P/Core tests.
     */
    public UIMessage(long requestId, String sender, String content, long timestamp, boolean isLocal) {
        this(
                String.valueOf(requestId),
                isLocal ? "local-user" : "remote-user",
                sender,
                sender,
                null,
                content,
                timestamp,
                isLocal
        );
    }

    /**
     * Determines whether this message belongs to the currently authenticated user.
     * Evaluates: {@code message.senderId === auth.user.id}
     * Never assumes "peerA" or physical device position.
     *
     * @param currentUserId the authenticated user ID (from Phase 15 auth state)
     * @return true if the message was authored by the current user
     */
    public boolean isMine(String currentUserId) {
        if (currentUserId != null && !currentUserId.isBlank() && senderId != null && !senderId.isBlank()) {
            return currentUserId.equals(senderId);
        }
        return isLocal;
    }

    /**
     * Resolves the user-facing display label for the sender.
     * Prefers displayName, then username, then fallback "User".
     */
    public String senderLabel() {
        if (senderDisplayName != null && !senderDisplayName.isBlank()) {
            return senderDisplayName;
        }
        if (senderUsername != null && !senderUsername.isBlank()) {
            return senderUsername;
        }
        return "User";
    }

    /**
     * Generates a 1-2 character avatar initials badge for the sender.
     */
    public String senderInitials() {
        String name = senderLabel();
        if (name == null || name.isBlank()) return "?";
        String trimmed = name.trim();
        String[] parts = trimmed.split("\\s+");
        if (parts.length >= 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
            return (parts[0].substring(0, 1) + parts[1].substring(0, 1)).toUpperCase();
        }
        return trimmed.substring(0, Math.min(2, trimmed.length())).toUpperCase();
    }

    public String formattedTime() {
        return TIME_FORMATTER.format(Instant.ofEpochMilli(timestamp));
    }

    // Backward-compatible accessors
    public String sender() {
        return senderLabel();
    }

    public long requestId() {
        try {
            return Long.parseLong(id);
        } catch (Exception ignored) {
            return id != null ? Math.abs((long) id.hashCode()) : 0L;
        }
    }

    public static UIMessage from(ChatMessage msg, boolean isLocal) {
        return new UIMessage(msg.requestId(), msg.sender(), msg.content(), msg.timestamp(), isLocal);
    }

    public static UIMessage from(Domain.Message msg, String currentUserId, String displayName, String username, String avatarUrl) {
        boolean isMine = currentUserId != null && currentUserId.equals(msg.senderId());
        return new UIMessage(
                msg.id(),
                msg.senderId(),
                displayName != null ? displayName : (isMine ? "You" : "User"),
                username,
                avatarUrl,
                msg.content(),
                msg.createdAt() != null ? msg.createdAt().toEpochMilli() : System.currentTimeMillis(),
                isMine
        );
    }

    public static UIMessage optimistic(String tempId, String senderId, String displayName, String username, String avatarUrl, String content) {
        return new UIMessage(
                tempId != null ? tempId : "opt-" + UUID.randomUUID(),
                senderId != null ? senderId : "local-user",
                displayName,
                username,
                avatarUrl,
                content,
                System.currentTimeMillis(),
                true
        );
    }

    public static UIMessage canonical(String id, String senderId, String displayName, String username, String avatarUrl,
                                      String content, long timestamp, String currentUserId) {
        boolean isMine = currentUserId != null && currentUserId.equals(senderId);
        return new UIMessage(
                id != null ? id : UUID.randomUUID().toString(),
                senderId != null ? senderId : "",
                displayName,
                username,
                avatarUrl,
                content,
                timestamp > 0 ? timestamp : System.currentTimeMillis(),
                isMine
        );
    }
}
