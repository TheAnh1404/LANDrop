package com.landrop.admin.dto;

import com.landrop.database.model.Workspace;

import java.time.Instant;
import java.util.UUID;

/** Administrative projection of a collaboration group (workspace). */
public record AdminWorkspaceDto(
    UUID id,
    String name,
    String slug,
    String description,
    UUID ownerId,
    String ownerUsername,
    long memberCount,
    Instant createdAt,
    Instant updatedAt
) {
    public static AdminWorkspaceDto from(Workspace workspace, String ownerUsername, long memberCount) {
        return new AdminWorkspaceDto(workspace.id(), workspace.name(), workspace.slug(), workspace.description(),
                workspace.ownerId(), ownerUsername, memberCount, workspace.createdAt(), workspace.updatedAt());
    }
}
