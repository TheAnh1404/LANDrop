package com.landrop.database.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a persistent text message sent in a channel, with reply, edit, and soft-delete support.
 */
public record Message(
    UUID id,
    UUID channelId,
    UUID userId,
    UUID parentMessageId,
    UUID clientMessageId,
    String content,
    Instant createdAt,
    Instant updatedAt,
    Instant editedAt,
    Instant deletedAt
) {
    public Message(UUID channelId, UUID userId, UUID parentMessageId, String content) {
        this(UUID.randomUUID(), channelId, userId, parentMessageId, null, content, Instant.now(), Instant.now(), null, null);
    }

    public Message(UUID id, UUID channelId, UUID userId, UUID parentMessageId, String content, Instant createdAt, Instant updatedAt) {
        this(id, channelId, userId, parentMessageId, null, content, createdAt, updatedAt, null, null);
    }

    /** Alias for parentMessageId matching Phase 17 replyToId terminology. */
    public UUID replyToId() {
        return parentMessageId;
    }

    /** Alias for userId matching Phase 17 senderId terminology. */
    public UUID senderId() {
        return userId;
    }

    /** Returns true if this message has been soft-deleted. */
    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** Returns true if this message has been edited. */
    public boolean isEdited() {
        return editedAt != null;
    }
}
