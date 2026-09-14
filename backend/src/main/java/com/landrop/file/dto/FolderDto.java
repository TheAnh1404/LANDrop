package com.landrop.file.dto;

import java.util.UUID;

/**
 * Public-safe projection of Folder metadata.
 */
public record FolderDto(
    UUID id,
    UUID workspaceId,
    String name,
    UUID parentId,
    UUID createdById,
    String createdAt,
    String updatedAt,
    String deletedAt
) {
    public UUID parentFolderId() {
        return parentId;
    }
}
