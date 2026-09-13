package com.landrop.database.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a hierarchical folder in a workspace.
 */
public record Folder(
    UUID id,
    UUID workspaceId,
    String name,
    UUID parentFolderId,
    Instant createdAt,
    Instant updatedAt
) {
    public Folder(UUID workspaceId, String name, UUID parentFolderId) {
        this(UUID.randomUUID(), workspaceId, name, parentFolderId, Instant.now(), Instant.now());
    }
}
