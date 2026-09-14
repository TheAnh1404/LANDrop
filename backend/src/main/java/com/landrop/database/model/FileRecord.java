package com.landrop.database.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents metadata for a persistent file uploaded to a workspace.
 */
public record FileRecord(
    UUID id,
    UUID workspaceId,
    UUID userId, // uploader
    UUID folderId,
    String name,
    String originalName,
    long size,
    String mimeType,
    String sha256,
    String storageType,
    String storageKey, // storagePath
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt
) {
    public FileRecord(UUID id, UUID workspaceId, UUID userId, UUID folderId, String name, long size, String mimeType, String sha256, String storageKey, Instant createdAt, Instant updatedAt) {
        this(id, workspaceId, userId, folderId, name, name, size, mimeType, sha256, "LAN_ONLY", storageKey, createdAt, updatedAt, null);
    }

    public FileRecord(UUID workspaceId, UUID userId, UUID folderId, String name, long size, String mimeType, String sha256, String storageKey) {
        this(UUID.randomUUID(), workspaceId, userId, folderId, name, name, size, mimeType, sha256, "LAN_ONLY", storageKey, Instant.now(), Instant.now(), null);
    }

    public UUID uploaderId() {
        return userId;
    }

    public String hash() {
        return sha256;
    }

    public String storagePath() {
        return storageKey;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public com.landrop.file.model.StorageType storageTypeEnum() {
        return com.landrop.file.model.StorageType.fromString(storageType);
    }
}
