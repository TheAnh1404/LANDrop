package com.landrop.database.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a workspace collaboration space owned by a User.
 */
public record Workspace(
    UUID id,
    String name,
    String slug,
    String description,
    UUID ownerId,
    Instant createdAt,
    Instant updatedAt,
    String joinPolicy,
    String joinPasswordHash
) {
    public Workspace(UUID id, String name, String slug, String description, UUID ownerId, Instant createdAt, Instant updatedAt) {
        this(id, name, slug, description, ownerId, createdAt, updatedAt, "INVITE_ONLY", null);
    }

    public Workspace(String name, String slug, String description, UUID ownerId) {
        this(UUID.randomUUID(), name, slug, description, ownerId, Instant.now(), Instant.now(), "INVITE_ONLY", null);
    }
}
