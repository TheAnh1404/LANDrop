package com.landrop.workspace;

import com.landrop.database.model.Workspace;
import com.landrop.database.model.WorkspaceMember;
import com.landrop.database.model.WorkspaceRole;
import com.landrop.database.repository.WorkspaceRepository;

import java.sql.SQLException;
import java.util.UUID;

/** Centralized Phase 16 role checks. GUEST is treated as an unsupported legacy role. */
public final class WorkspaceAccess {
    public record Membership(Workspace workspace, WorkspaceMember member) { }

    private WorkspaceAccess() { }

    public static Membership requireMember(WorkspaceRepository repository, UUID workspaceId, UUID userId)
            throws SQLException {
        var workspace = repository.findById(workspaceId)
                .orElseThrow(() -> notFound());
        var member = repository.findMember(workspaceId, userId)
                .filter(value -> value.role() == WorkspaceRole.OWNER
                        || value.role() == WorkspaceRole.ADMIN
                        || value.role() == WorkspaceRole.MEMBER)
                .orElseThrow(() -> notFound());
        return new Membership(workspace, member);
    }

    public static Membership lockMember(WorkspaceRepository repository, UUID workspaceId, UUID userId)
            throws SQLException {
        var workspace = repository.lockById(workspaceId)
                .orElseThrow(() -> notFound());
        var member = repository.findMember(workspaceId, userId)
                .filter(value -> value.role() == WorkspaceRole.OWNER
                        || value.role() == WorkspaceRole.ADMIN
                        || value.role() == WorkspaceRole.MEMBER)
                .orElseThrow(() -> notFound());
        return new Membership(workspace, member);
    }

    public static Membership requireAdmin(WorkspaceRepository repository, UUID workspaceId, UUID userId)
            throws SQLException {
        var membership = requireMember(repository, workspaceId, userId);
        if (membership.member().role() != WorkspaceRole.OWNER
                && membership.member().role() != WorkspaceRole.ADMIN) {
            throw forbidden("This action requires a workspace administrator.");
        }
        return membership;
    }

    public static Membership lockAdmin(WorkspaceRepository repository, UUID workspaceId, UUID userId)
            throws SQLException {
        var membership = lockMember(repository, workspaceId, userId);
        if (membership.member().role() != WorkspaceRole.OWNER
                && membership.member().role() != WorkspaceRole.ADMIN) {
            throw forbidden("This action requires a workspace administrator.");
        }
        return membership;
    }

    public static Membership requireOwner(WorkspaceRepository repository, UUID workspaceId, UUID userId)
            throws SQLException {
        var membership = requireMember(repository, workspaceId, userId);
        if (membership.member().role() != WorkspaceRole.OWNER) {
            throw forbidden("This action requires the workspace owner.");
        }
        return membership;
    }

    public static Membership lockOwner(WorkspaceRepository repository, UUID workspaceId, UUID userId)
            throws SQLException {
        var membership = lockMember(repository, workspaceId, userId);
        if (membership.member().role() != WorkspaceRole.OWNER) {
            throw forbidden("This action requires the workspace owner.");
        }
        return membership;
    }

    private static WorkspaceException notFound() {
        return new WorkspaceException(404, "WORKSPACE_NOT_FOUND", "Workspace was not found.");
    }

    private static WorkspaceException forbidden(String message) {
        return new WorkspaceException(403, "FORBIDDEN", message);
    }
}
