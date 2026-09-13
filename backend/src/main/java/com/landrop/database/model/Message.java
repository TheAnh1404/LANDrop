package com.landrop.database.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a text message sent in a channel, with optional reply/thread support.
 */
public record Message(
    UUID id,
    UUID channelId,
    UUID userId,
    UUID parentMessageId,
    String content,
    Instant createdAt,
    Instant updatedAt
) {
    public Message(UUID channelId, UUID userId, UUID parentMessageId, String content) {
        this(UUID.randomUUID(), channelId, userId, parentMessageId, content, Instant.now(), Instant.now());
    }
}
