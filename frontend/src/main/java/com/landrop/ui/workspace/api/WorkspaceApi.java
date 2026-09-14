package com.landrop.ui.workspace.api;

import com.landrop.ui.cloud.dto.Dto;
import com.landrop.ui.cloud.model.Domain;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Frontend API contract for Phase 16 Workspace and Member endpoints.
 */
public interface WorkspaceApi {

    /**
     * Retrieves all workspaces accessible to the authenticated user.
     * GET /api/workspaces
     */
    CompletableFuture<List<Domain.Workspace>> listWorkspaces();

    /**
     * Retrieves details for a specific workspace.
     * GET /api/workspaces/:workspaceId
     */
    CompletableFuture<Domain.Workspace> getWorkspace(String workspaceId);

    /**
     * Creates a new workspace. The authenticated user is automatically set as OWNER.
     * POST /api/workspaces
     */
    CompletableFuture<Domain.Workspace> createWorkspace(Dto.CreateWorkspaceRequest request);

    /**
     * Updates an existing workspace (name, description).
     * PATCH /api/workspaces/:workspaceId
     */
    CompletableFuture<Domain.Workspace> updateWorkspace(String workspaceId, Dto.UpdateWorkspaceRequest request);

    /**
     * Deletes a workspace (owner only).
     * DELETE /api/workspaces/:workspaceId
     */
    CompletableFuture<Void> deleteWorkspace(String workspaceId);

    /**
     * Retrieves the members of a workspace.
     * GET /api/workspaces/:workspaceId/members
     */
    CompletableFuture<List<Domain.WorkspaceMember>> listMembers(String workspaceId);

    /**
     * Adds or invites a member to the workspace.
     * POST /api/workspaces/:workspaceId/members
     */
    CompletableFuture<Domain.WorkspaceMember> addMember(String workspaceId, Dto.AddWorkspaceMemberRequest request);

    /**
     * Updates the role of a workspace member (promote/demote).
     * PATCH /api/workspaces/:workspaceId/members/:userId
     */
    CompletableFuture<Domain.WorkspaceMember> updateMemberRole(String workspaceId, String userId, Dto.UpdateMemberRoleRequest request);

    /**
     * Removes a member from the workspace.
     * DELETE /api/workspaces/:workspaceId/members/:userId
     */
    CompletableFuture<Void> removeMember(String workspaceId, String userId);

    /**
     * Leaves the workspace as the authenticated user.
     * Attempts POST /api/workspaces/:workspaceId/leave and falls back to DELETE /api/workspaces/:workspaceId/members/:userId.
     */
    CompletableFuture<Void> leaveWorkspace(String workspaceId, String userId);
}
