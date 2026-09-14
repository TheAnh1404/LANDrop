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
    UUID createdById,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt
) {
    public Folder(UUID id, UUID workspaceId, String name, UUID parentFolderId, Instant createdAt, Instant updatedAt) {
        this(id, workspaceId, name, parentFolderId, null, createdAt, updatedAt, null);
    }

    public Folder(UUID workspaceId, String name, UUID parentFolderId) {
        this(UUID.randomUUID(), workspaceId, name, parentFolderId, null, Instant.now(), Instant.now(), null);
    }

    public Folder(UUID workspaceId, String name, UUID parentFolderId, UUID createdById) {
        this(UUID.randomUUID(), workspaceId, name, parentFolderId, createdById, Instant.now(), Instant.now(), null);
    }

    public UUID parentId() {
        return parentFolderId;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
