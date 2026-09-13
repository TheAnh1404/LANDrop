package com.landrop.database.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a user's membership and assigned role within a workspace.
 */
public record WorkspaceMember(
    UUID id,
    UUID workspaceId,
    UUID userId,
    WorkspaceRole role,
    Instant joinedAt,
    Instant updatedAt
) {
    public WorkspaceMember(UUID workspaceId, UUID userId, WorkspaceRole role) {
        this(UUID.randomUUID(), workspaceId, userId, role, Instant.now(), Instant.now());
    }
}
