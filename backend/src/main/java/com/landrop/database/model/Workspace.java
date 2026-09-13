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
    Instant updatedAt
) {
    public Workspace(String name, String slug, String description, UUID ownerId) {
        this(UUID.randomUUID(), name, slug, description, ownerId, Instant.now(), Instant.now());
    }
}
