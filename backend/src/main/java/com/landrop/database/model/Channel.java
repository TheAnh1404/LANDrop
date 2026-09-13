package com.landrop.database.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a chat channel within a workspace.
 */
public record Channel(
    UUID id,
    UUID workspaceId,
    String name,
    String topic,
    boolean isPrivate,
    Instant createdAt,
    Instant updatedAt
) {
    public Channel(UUID workspaceId, String name, String topic, boolean isPrivate) {
        this(UUID.randomUUID(), workspaceId, name, topic, isPrivate, Instant.now(), Instant.now());
    }
}
