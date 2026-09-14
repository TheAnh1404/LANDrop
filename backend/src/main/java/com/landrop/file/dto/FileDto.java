package com.landrop.file.dto;

import java.util.UUID;

/**
 * Public-safe projection of File metadata.
 * Sensitive server filesystem paths, credentials, and network secrets are omitted.
 */
public record FileDto(
    UUID id,
    UUID workspaceId,
    UUID uploaderId,
    UUID folderId,
    String name,
    String originalName,
    long size,
    String mimeType,
    String hash,
    String storageType,
    String createdAt,
    String updatedAt,
    String deletedAt
) {
    public UUID parentFolderId() {
        return folderId;
    }

    public UUID userId() {
        return uploaderId;
    }
}
