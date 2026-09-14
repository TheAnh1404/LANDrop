package com.landrop.ui.workspace.services;

import com.landrop.ui.auth.model.AuthModels;
import com.landrop.ui.auth.services.AuthStateService;
import com.landrop.ui.cloud.api.ApiException;
import com.landrop.ui.cloud.dto.Dto;
import com.landrop.ui.cloud.model.Domain;
import com.landrop.ui.workspace.api.WorkspaceApi;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Coordinates client-side workspace state, active workspace selection, member lists,
 * role-aware permission evaluations, and UI observable bindings.
 */
public class WorkspaceStateService {

    public static final String OWNER_LEAVE_RESTRICTION =
            "Workspace owners cannot leave their own workspace. To leave, delete the workspace or transfer ownership.";

    private final WorkspaceApi workspaceApi;
    private final AuthStateService authStateService;

    // Observable UI State
    private final ObservableList<Domain.Workspace> workspaces = FXCollections.observableArrayList();
    private final ObjectProperty<Domain.Workspace> activeWorkspace = new SimpleObjectProperty<>(null);
    private final ObjectProperty<Domain.WorkspaceMember> currentUserMember = new SimpleObjectProperty<>(null);
    private final ObservableList<Domain.WorkspaceMember> activeMembers = FXCollections.observableArrayList();

    private final BooleanProperty loading = new SimpleBooleanProperty(false);
    private final StringProperty errorMessage = new SimpleStringProperty("");
    private final StringProperty successMessage = new SimpleStringProperty("");

    private String preferredWorkspaceId = null;

    public WorkspaceStateService(WorkspaceApi workspaceApi, AuthStateService authStateService) {
        this.workspaceApi = Objects.requireNonNull(workspaceApi, "workspaceApi cannot be null");
        this.authStateService = Objects.requireNonNull(authStateService, "authStateService cannot be null");
    }

    // --- Observable Properties ---

    public ObservableList<Domain.Workspace> getWorkspaces() {
        return workspaces;
    }

    public ReadOnlyObjectProperty<Domain.Workspace> activeWorkspaceProperty() {
        return activeWorkspace;
    }

    public Domain.Workspace getActiveWorkspace() {
        return activeWorkspace.get();
    }

    public ReadOnlyObjectProperty<Domain.WorkspaceMember> currentUserMemberProperty() {
        return currentUserMember;
    }

    public Domain.WorkspaceMember getCurrentUserMember() {
        return currentUserMember.get();
    }

    public ObservableList<Domain.WorkspaceMember> getActiveMembers() {
        return activeMembers;
    }

    public ReadOnlyBooleanProperty loadingProperty() {
        return loading;
    }

    public boolean isLoading() {
        return loading.get();
    }

    public ReadOnlyStringProperty errorMessageProperty() {
        return errorMessage;
    }

    public String getErrorMessage() {
        return errorMessage.get();
    }

    public ReadOnlyStringProperty successMessageProperty() {
        return successMessage;
    }

    public String getSuccessMessage() {
        return successMessage.get();
    }

    public void clearMessages() {
        runSafe(() -> {
            errorMessage.set("");
            successMessage.set("");
        });
    }

    // --- Role-Aware Permission Checks ---

    public Domain.MemberRole getCurrentRole() {
        Domain.WorkspaceMember member = currentUserMember.get();
        if (member != null && member.role() != null) {
            return member.role();
        }
        Domain.Workspace ws = activeWorkspace.get();
        if (ws != null && ws.isOwner(currentUserId())) {
            return Domain.MemberRole.OWNER;
        }
        return null;
    }

    public boolean isOwner() {
        return getCurrentRole() == Domain.MemberRole.OWNER;
    }

    public boolean isAdmin() {
        Domain.MemberRole role = getCurrentRole();
        return role == Domain.MemberRole.OWNER || role == Domain.MemberRole.ADMIN;
    }

    public boolean canManageMembers() {
        return isAdmin();
    }

    public boolean canManageSettings() {
        return isAdmin();
    }

    public boolean canDeleteWorkspace() {
        return isOwner();
    }

    public boolean canLeave() {
        Domain.Workspace ws = activeWorkspace.get();
        if (ws == null) return false;
        return !ws.isOwner(currentUserId());
    }

    public String currentUserId() {
        AuthModels.AuthUser u = authStateService.user();
        return u != null ? u.id() : null;
    }

    // --- Workspace Lifecycle Actions ---

    /**
     * Loads user workspaces from API and auto-selects preferred/first workspace.
     */
    public CompletableFuture<List<Domain.Workspace>> loadWorkspaces() {
        runSafe(() -> {
            loading.set(true);
            errorMessage.set("");
        });

        return workspaceApi.listWorkspaces()
                .whenComplete((list, ex) -> {
                    runSafe(() -> {
                        loading.set(false);
                        if (ex != null) {
                            errorMessage.set(extractErrorMessage(ex));
                        } else {
                            workspaces.setAll(list != null ? list : java.util.Collections.emptyList());

                            Domain.Workspace currentActive = activeWorkspace.get();
                            Domain.Workspace toSelect = null;

                            if (preferredWorkspaceId != null) {
                                toSelect = findWorkspace(preferredWorkspaceId);
                            }
                            if (toSelect == null && currentActive != null) {
                                toSelect = findWorkspace(currentActive.id());
                            }
                            if (toSelect == null && !workspaces.isEmpty()) {
                                toSelect = workspaces.getFirst();
                            }

                            if (toSelect != null) {
                                selectWorkspace(toSelect);
                            } else {
                                activeWorkspace.set(null);
                                currentUserMember.set(null);
                                activeMembers.clear();
                            }
                        }
                    });
                });
    }

    /**
     * Selects active workspace and loads member list.
     */
    public CompletableFuture<List<Domain.WorkspaceMember>> selectWorkspace(Domain.Workspace workspace) {
        if (workspace == null) {
            runSafe(() -> {
                activeWorkspace.set(null);
                currentUserMember.set(null);
                activeMembers.clear();
            });
            return CompletableFuture.completedFuture(java.util.Collections.emptyList());
        }

        runSafe(() -> {
            activeWorkspace.set(workspace);
            preferredWorkspaceId = workspace.id();
            loading.set(true);
            errorMessage.set("");
        });

        return workspaceApi.listMembers(workspace.id())
                .whenComplete((members, ex) -> {
                    runSafe(() -> {
                        loading.set(false);
                        if (ex != null) {
                            errorMessage.set(extractErrorMessage(ex));
                        } else {
                            activeMembers.setAll(members != null ? members : java.util.Collections.emptyList());
                            resolveCurrentUserMember(workspace);
                        }
                    });
                });
    }

    public CompletableFuture<List<Domain.WorkspaceMember>> selectWorkspace(String workspaceId) {
        Domain.Workspace ws = findWorkspace(workspaceId);
        if (ws != null) {
            return selectWorkspace(ws);
        }
        // If not present in local list, fetch from API
        return workspaceApi.getWorkspace(workspaceId)
                .thenCompose(this::selectWorkspace);
    }

    /**
     * Creates a new workspace and automatically sets it as active.
     */
    public CompletableFuture<Domain.Workspace> createWorkspace(String name, String description) {
        if (name == null || name.trim().isBlank()) {
            ApiException ex = new ApiException(400, "VALIDATION_ERROR", "Workspace name cannot be empty.");
            runSafe(() -> errorMessage.set(ex.getMessage()));
            return CompletableFuture.failedFuture(ex);
        }

        runSafe(() -> {
            loading.set(true);
            errorMessage.set("");
            successMessage.set("");
        });

        Dto.CreateWorkspaceRequest req = new Dto.CreateWorkspaceRequest(name.trim(), description != null ? description.trim() : null);
        return workspaceApi.createWorkspace(req)
                .whenComplete((newWs, ex) -> {
                    runSafe(() -> {
                        loading.set(false);
                        if (ex != null) {
                            errorMessage.set(extractErrorMessage(ex));
                        } else {
                            workspaces.add(newWs);
                            successMessage.set("Workspace \"" + newWs.name() + "\" created successfully.");
                            selectWorkspace(newWs);
                        }
                    });
                });
    }

    /**
     * Updates workspace settings (name, description).
     */
    public CompletableFuture<Domain.Workspace> updateWorkspace(String workspaceId, String name, String description) {
        if (!canManageSettings()) {
            ApiException ex = new ApiException(403, "FORBIDDEN", "Only Owners and Admins can update workspace settings.");
            runSafe(() -> errorMessage.set(ex.getMessage()));
            return CompletableFuture.failedFuture(ex);
        }

        runSafe(() -> {
            loading.set(true);
            errorMessage.set("");
            successMessage.set("");
        });

        Dto.UpdateWorkspaceRequest req = new Dto.UpdateWorkspaceRequest(name.trim(), description != null ? description.trim() : null);
        return workspaceApi.updateWorkspace(workspaceId, req)
                .whenComplete((updated, ex) -> {
                    runSafe(() -> {
                        loading.set(false);
                        if (ex != null) {
                            errorMessage.set(extractErrorMessage(ex));
                        } else {
                            for (int i = 0; i < workspaces.size(); i++) {
                                if (workspaces.get(i).id().equals(workspaceId)) {
                                    workspaces.set(i, updated);
                                    break;
                                }
                            }
                            if (activeWorkspace.get() != null && activeWorkspace.get().id().equals(workspaceId)) {
                                activeWorkspace.set(updated);
                            }
                            successMessage.set("Workspace settings updated.");
                        }
                    });
                });
    }

    /**
     * Deletes a workspace (owner only).
     */
    public CompletableFuture<Void> deleteWorkspace(String workspaceId) {
        if (!canDeleteWorkspace()) {
            ApiException ex = new ApiException(403, "FORBIDDEN", "Only the workspace Owner can delete this workspace.");
            runSafe(() -> errorMessage.set(ex.getMessage()));
            return CompletableFuture.failedFuture(ex);
        }

        runSafe(() -> {
            loading.set(true);
            errorMessage.set("");
            successMessage.set("");
        });

        return workspaceApi.deleteWorkspace(workspaceId)
                .whenComplete((ignored, ex) -> {
                    runSafe(() -> {
                        loading.set(false);
                        if (ex != null) {
                            errorMessage.set(extractErrorMessage(ex));
                        } else {
                            workspaces.removeIf(w -> w.id().equals(workspaceId));
                            successMessage.set("Workspace deleted.");

                            if (activeWorkspace.get() != null && activeWorkspace.get().id().equals(workspaceId)) {
                                if (!workspaces.isEmpty()) {
                                    selectWorkspace(workspaces.getFirst());
                                } else {
                                    activeWorkspace.set(null);
                                    currentUserMember.set(null);
                                    activeMembers.clear();
                                }
                            }
                        }
                    });
                });
    }

    // --- Member Management Actions ---

    /**
     * Invites or adds a member to the active workspace.
     */
    public CompletableFuture<Domain.WorkspaceMember> addMember(String emailOrIdentifier, Domain.MemberRole role) {
        Domain.Workspace ws = activeWorkspace.get();
        if (ws == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No active workspace selected"));
        }
        if (!canManageMembers()) {
            ApiException ex = new ApiException(403, "FORBIDDEN", "You do not have permission to invite members.");
            runSafe(() -> errorMessage.set(ex.getMessage()));
            return CompletableFuture.failedFuture(ex);
        }
        if (emailOrIdentifier == null || emailOrIdentifier.trim().isBlank()) {
            ApiException ex = new ApiException(400, "VALIDATION_ERROR", "Member email or username cannot be blank.");
            runSafe(() -> errorMessage.set(ex.getMessage()));
            return CompletableFuture.failedFuture(ex);
        }

        runSafe(() -> {
            loading.set(true);
            errorMessage.set("");
            successMessage.set("");
        });

        String trimmed = emailOrIdentifier.trim();
        String roleStr = (role != null ? role : Domain.MemberRole.MEMBER).name();
        Dto.AddWorkspaceMemberRequest req = new Dto.AddWorkspaceMemberRequest(trimmed, roleStr);

        CompletableFuture<Domain.WorkspaceMember> addition = workspaceApi.addMember(ws.id(), req);
        if (role == Domain.MemberRole.ADMIN) {
            addition = addition.thenCompose(member -> workspaceApi.updateMemberRole(
                    ws.id(), member.userId(), new Dto.UpdateMemberRoleRequest(Domain.MemberRole.ADMIN.name())));
        }

        return addition
                .whenComplete((newMember, ex) -> {
                    runSafe(() -> {
                        loading.set(false);
                        if (ex != null) {
                            errorMessage.set(extractErrorMessage(ex));
                        } else {
                            // Deduplicate or replace
                            activeMembers.removeIf(m -> m.userId().equals(newMember.userId()));
                            activeMembers.add(newMember);
                            successMessage.set("Added " + newMember.nameOrFallback() + " to workspace.");
                        }
                    });
                });
    }

    /**
     * Updates the role of a member in the active workspace.
     */
    public CompletableFuture<Domain.WorkspaceMember> updateMemberRole(String userId, Domain.MemberRole newRole) {
        Domain.Workspace ws = activeWorkspace.get();
        if (ws == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No active workspace selected"));
        }
        if (!canManageMembers()) {
            ApiException ex = new ApiException(403, "FORBIDDEN", "You do not have permission to change member roles.");
            runSafe(() -> errorMessage.set(ex.getMessage()));
            return CompletableFuture.failedFuture(ex);
        }

        runSafe(() -> {
            loading.set(true);
            errorMessage.set("");
            successMessage.set("");
        });

        Dto.UpdateMemberRoleRequest req = new Dto.UpdateMemberRoleRequest(newRole.name());
        return workspaceApi.updateMemberRole(ws.id(), userId, req)
                .whenComplete((updatedMember, ex) -> {
                    runSafe(() -> {
                        loading.set(false);
                        if (ex != null) {
                            errorMessage.set(extractErrorMessage(ex));
                        } else {
                            for (int i = 0; i < activeMembers.size(); i++) {
                                if (activeMembers.get(i).userId().equals(userId)) {
                                    activeMembers.set(i, updatedMember);
                                    break;
                                }
                            }
                            if (userId.equals(currentUserId())) {
                                currentUserMember.set(updatedMember);
                            }
                            successMessage.set("Updated role for " + updatedMember.nameOrFallback() + " to " + newRole.name());
                        }
                    });
                });
    }

    /**
     * Removes a member from the active workspace.
     */
    public CompletableFuture<Void> removeMember(String userId) {
        Domain.Workspace ws = activeWorkspace.get();
        if (ws == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No active workspace selected"));
        }
        if (!canManageMembers()) {
            ApiException ex = new ApiException(403, "FORBIDDEN", "You do not have permission to remove members.");
            runSafe(() -> errorMessage.set(ex.getMessage()));
            return CompletableFuture.failedFuture(ex);
        }

        runSafe(() -> {
            loading.set(true);
            errorMessage.set("");
            successMessage.set("");
        });

        return workspaceApi.removeMember(ws.id(), userId)
                .whenComplete((ignored, ex) -> {
                    runSafe(() -> {
                        loading.set(false);
                        if (ex != null) {
                            errorMessage.set(extractErrorMessage(ex));
                        } else {
                            activeMembers.removeIf(m -> m.userId().equals(userId));
                            successMessage.set("Member removed from workspace.");
                        }
                    });
                });
    }

    /**
     * Leaves the active workspace.
     * Owners are restricted from leaving their own workspace.
     */
    public CompletableFuture<Void> leaveWorkspace() {
        Domain.Workspace ws = activeWorkspace.get();
        if (ws == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No active workspace selected"));
        }

        if (ws.isOwner(currentUserId())) {
            ApiException ex = new ApiException(400, "OWNER_RESTRICTION", OWNER_LEAVE_RESTRICTION);
            runSafe(() -> errorMessage.set(OWNER_LEAVE_RESTRICTION));
            return CompletableFuture.failedFuture(ex);
        }

        runSafe(() -> {
            loading.set(true);
            errorMessage.set("");
            successMessage.set("");
        });

        String workspaceId = ws.id();
        return workspaceApi.leaveWorkspace(workspaceId, currentUserId())
                .whenComplete((ignored, ex) -> {
                    runSafe(() -> {
                        loading.set(false);
                        if (ex != null) {
                            errorMessage.set(extractErrorMessage(ex));
                        } else {
                            workspaces.removeIf(w -> w.id().equals(workspaceId));
                            successMessage.set("Left workspace \"" + ws.name() + "\".");

                            if (!workspaces.isEmpty()) {
                                selectWorkspace(workspaces.getFirst());
                            } else {
                                activeWorkspace.set(null);
                                currentUserMember.set(null);
                                activeMembers.clear();
                            }
                        }
                    });
                });
    }

    // --- Private Helpers ---

    private void resolveCurrentUserMember(Domain.Workspace ws) {
        String uid = currentUserId();
        Domain.WorkspaceMember found = null;
        if (uid != null) {
            for (Domain.WorkspaceMember m : activeMembers) {
                if (uid.equals(m.userId())) {
                    found = m;
                    break;
                }
            }
        }

        // If user is workspace owner but not explicitly in list yet, synthesize owner member
        if (found == null && ws != null && ws.isOwner(uid)) {
            AuthModels.AuthUser u = authStateService.user();
            String displayName = u != null ? u.displayName() : "Owner";
            String email = u != null ? u.email() : null;
            String avatar = u != null ? u.avatarUrl() : null;
            found = new Domain.WorkspaceMember("member_owner", ws.id(), uid, Domain.MemberRole.OWNER,
                    displayName, email, avatar, ws.createdAt(), ws.updatedAt());
        }

        currentUserMember.set(found);
    }

    private Domain.Workspace findWorkspace(String id) {
        if (id == null) return null;
        for (Domain.Workspace w : workspaces) {
            if (w.id().equals(id)) return w;
        }
        return null;
    }

    private static String extractErrorMessage(Throwable ex) {
        if (ex == null) return "Unknown error";
        Throwable cause = ex;
        while ((cause instanceof java.util.concurrent.CompletionException ||
                cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof ApiException apiEx) {
            return apiEx.getMessage();
        }
        return cause.getMessage() != null ? cause.getMessage() : "Operation failed";
    }

    private static void runSafe(Runnable action) {
        try {
            if (Platform.isFxApplicationThread()) {
                action.run();
            } else {
                Platform.runLater(action);
            }
        } catch (IllegalStateException notFxApp) {
            action.run();
        }
    }
}
