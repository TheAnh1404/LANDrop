package com.landrop.database.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents metadata for a persistent file uploaded to a workspace.
 */
public record FileRecord(
    UUID id,
    UUID workspaceId,
    UUID userId,
    UUID folderId,
    String name,
    long size,
    String mimeType,
    String sha256,
    String storageKey,
    Instant createdAt,
    Instant updatedAt
) {
    public FileRecord(UUID workspaceId, UUID userId, UUID folderId, String name, long size, String mimeType, String sha256, String storageKey) {
        this(UUID.randomUUID(), workspaceId, userId, folderId, name, size, mimeType, sha256, storageKey, Instant.now(), Instant.now());
    }
}
