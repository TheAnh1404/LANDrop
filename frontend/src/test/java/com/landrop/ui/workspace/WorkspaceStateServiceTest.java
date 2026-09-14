package com.landrop.ui.workspace;

import com.landrop.ui.auth.api.AuthApi;
import com.landrop.ui.auth.api.InMemorySessionStore;
import com.landrop.ui.auth.dto.AuthDto;
import com.landrop.ui.auth.model.AuthModels;
import com.landrop.ui.auth.services.AuthStateService;
import com.landrop.ui.cloud.dto.Dto;
import com.landrop.ui.cloud.model.Domain;
import com.landrop.ui.workspace.api.WorkspaceApi;
import com.landrop.ui.workspace.services.WorkspaceStateService;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class WorkspaceStateServiceTest {

    private static final Instant NOW = Instant.now();

    @BeforeAll
    static void initJavaFX() {
        try {
            Platform.startup(() -> {});
            Platform.setImplicitExit(false);
        } catch (IllegalStateException ignored) {
            Platform.setImplicitExit(false);
        }
    }

    private static void awaitFx() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        assertTrue(latch.await(3, TimeUnit.SECONDS));
    }

    private static AuthStateService createAuth(String userId, String username, String displayName) {
        AuthDto.AuthUserResponse u = new AuthDto.AuthUserResponse(userId, username, displayName, username + "@example.com", null, "2026-09-14T00:00:00Z", "2026-09-14T00:00:00Z");
        AuthApi api = new AuthApi() {
            @Override public CompletableFuture<AuthDto.AuthResponse> register(AuthDto.RegisterRequest request) { return null; }
            @Override public CompletableFuture<AuthDto.AuthResponse> login(AuthDto.LoginRequest request) {
                return CompletableFuture.completedFuture(new AuthDto.AuthResponse("access", "refresh", "Bearer", 3600, u));
            }
            @Override public CompletableFuture<AuthDto.RefreshResponse> refresh(AuthDto.RefreshRequest request) { return null; }
            @Override public CompletableFuture<AuthDto.AuthUserResponse> me(String accessToken) { return CompletableFuture.completedFuture(u); }
            @Override public CompletableFuture<Void> logout(String accessToken, String refreshToken) { return CompletableFuture.completedFuture(null); }
        };
        AuthStateService auth = new AuthStateService(api, new InMemorySessionStore());
        auth.login(new AuthModels.LoginRequest("test@example.com", "pw")).join();
        return auth;
    }

    static class MockWorkspaceApi implements WorkspaceApi {
        final List<Domain.Workspace> workspaces = new CopyOnWriteArrayList<>();
        final Map<String, List<Domain.WorkspaceMember>> membersByWs = new HashMap<>();

        @Override
        public CompletableFuture<List<Domain.Workspace>> listWorkspaces() {
            return CompletableFuture.completedFuture(new ArrayList<>(workspaces));
        }

        @Override
        public CompletableFuture<Domain.Workspace> getWorkspace(String workspaceId) {
            return workspaces.stream().filter(w -> w.id().equals(workspaceId)).findFirst()
                    .map(CompletableFuture::completedFuture)
                    .orElseGet(() -> CompletableFuture.failedFuture(new NoSuchElementException()));
        }

        @Override
        public CompletableFuture<Domain.Workspace> createWorkspace(Dto.CreateWorkspaceRequest request) {
            Domain.Workspace ws = new Domain.Workspace("ws-" + UUID.randomUUID(), request.name(), "user-ada", request.description(), NOW, NOW);
            workspaces.add(ws);
            membersByWs.put(ws.id(), new CopyOnWriteArrayList<>(List.of(
                    new Domain.WorkspaceMember("m-owner", ws.id(), "user-ada", Domain.MemberRole.OWNER, "Ada", "ada@example.com", null, NOW, NOW)
            )));
            return CompletableFuture.completedFuture(ws);
        }

        @Override
        public CompletableFuture<Domain.Workspace> updateWorkspace(String workspaceId, Dto.UpdateWorkspaceRequest request) {
            for (int i = 0; i < workspaces.size(); i++) {
                if (workspaces.get(i).id().equals(workspaceId)) {
                    Domain.Workspace old = workspaces.get(i);
                    Domain.Workspace updated = new Domain.Workspace(old.id(), request.name(), old.ownerId(), request.description(), old.createdAt(), NOW);
                    workspaces.set(i, updated);
                    return CompletableFuture.completedFuture(updated);
                }
            }
            return CompletableFuture.failedFuture(new NoSuchElementException());
        }

        @Override
        public CompletableFuture<Void> deleteWorkspace(String workspaceId) {
            workspaces.removeIf(w -> w.id().equals(workspaceId));
            membersByWs.remove(workspaceId);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<List<Domain.WorkspaceMember>> listMembers(String workspaceId) {
            List<Domain.WorkspaceMember> list = membersByWs.getOrDefault(workspaceId, Collections.emptyList());
            return CompletableFuture.completedFuture(new ArrayList<>(list));
        }

        @Override
        public CompletableFuture<Domain.WorkspaceMember> addMember(String workspaceId, Dto.AddWorkspaceMemberRequest request) {
            Domain.MemberRole role = Domain.MemberRole.fromString(request.role());
            Domain.WorkspaceMember m = new Domain.WorkspaceMember("m-" + UUID.randomUUID(), workspaceId, "u-" + UUID.randomUUID(), role, request.email(), request.email(), null, NOW, NOW);
            membersByWs.computeIfAbsent(workspaceId, k -> new CopyOnWriteArrayList<>()).add(m);
            return CompletableFuture.completedFuture(m);
        }

        @Override
        public CompletableFuture<Domain.WorkspaceMember> updateMemberRole(String workspaceId, String userId, Dto.UpdateMemberRoleRequest request) {
            List<Domain.WorkspaceMember> list = membersByWs.get(workspaceId);
            if (list != null) {
                for (int i = 0; i < list.size(); i++) {
                    Domain.WorkspaceMember m = list.get(i);
                    if (m.userId().equals(userId)) {
                        Domain.WorkspaceMember updated = new Domain.WorkspaceMember(m.id(), m.workspaceId(), m.userId(), Domain.MemberRole.fromString(request.role()), m.displayName(), m.email(), m.avatarUrl(), m.createdAt(), NOW);
                        list.set(i, updated);
                        return CompletableFuture.completedFuture(updated);
                    }
                }
            }
            return CompletableFuture.failedFuture(new NoSuchElementException());
        }

        @Override
        public CompletableFuture<Void> removeMember(String workspaceId, String userId) {
            List<Domain.WorkspaceMember> list = membersByWs.get(workspaceId);
            if (list != null) {
                list.removeIf(m -> m.userId().equals(userId));
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> leaveWorkspace(String workspaceId, String userId) {
            return removeMember(workspaceId, userId);
        }
    }

    @Test
    @DisplayName("16B.3 & 16B.5: Load workspaces and auto-select active workspace")
    void testLoadWorkspacesAndAutoSelect() throws Exception {
        MockWorkspaceApi api = new MockWorkspaceApi();
        Domain.Workspace ws1 = new Domain.Workspace("ws-1", "Alpha", "user-ada", "Desc", NOW, NOW);
        Domain.Workspace ws2 = new Domain.Workspace("ws-2", "Beta", "user-ada", "Desc", NOW, NOW);
        api.workspaces.addAll(List.of(ws1, ws2));
        api.membersByWs.put("ws-1", List.of(new Domain.WorkspaceMember("m-1", "ws-1", "user-ada", Domain.MemberRole.OWNER, "Ada", null, null, NOW, NOW)));

        AuthStateService auth = createAuth("user-ada", "ada", "Ada");
        WorkspaceStateService state = new WorkspaceStateService(api, auth);

        state.loadWorkspaces().get(5, TimeUnit.SECONDS);
        awaitFx();

        assertEquals(2, state.getWorkspaces().size());
        assertNotNull(state.getActiveWorkspace());
        assertEquals("ws-1", state.getActiveWorkspace().id());
        assertTrue(state.isOwner());
        assertTrue(state.isAdmin());
        assertTrue(state.canManageMembers());
        assertTrue(state.canDeleteWorkspace());
        assertFalse(state.canLeave(), "Owner cannot leave workspace");
    }

    @Test
    @DisplayName("16B.13: Empty state when user has no workspaces")
    void testEmptyWorkspaceList() throws Exception {
        MockWorkspaceApi api = new MockWorkspaceApi(); // Empty list
        AuthStateService auth = createAuth("user-ada", "ada", "Ada");
        WorkspaceStateService state = new WorkspaceStateService(api, auth);

        state.loadWorkspaces().get(5, TimeUnit.SECONDS);
        awaitFx();

        assertTrue(state.getWorkspaces().isEmpty());
        assertNull(state.getActiveWorkspace());
        assertTrue(state.getActiveMembers().isEmpty());
    }

    @Test
    @DisplayName("16B.4: Create workspace adds to list and activates it")
    void testCreateWorkspace() throws Exception {
        MockWorkspaceApi api = new MockWorkspaceApi();
        AuthStateService auth = createAuth("user-ada", "ada", "Ada");
        WorkspaceStateService state = new WorkspaceStateService(api, auth);

        Domain.Workspace created = state.createWorkspace("New Team", "New Desc").get(5, TimeUnit.SECONDS);
        awaitFx();

        assertNotNull(created);
        assertEquals(1, state.getWorkspaces().size());
        assertEquals(created.id(), state.getActiveWorkspace().id());
        assertEquals("New Team", state.getActiveWorkspace().name());
    }

    @Test
    @DisplayName("16B.6 & 16B.9: Role-aware permissions for ADMIN and MEMBER roles")
    void testRoleAwarePermissions() throws Exception {
        MockWorkspaceApi api = new MockWorkspaceApi();
        Domain.Workspace ws = new Domain.Workspace("ws-1", "Team", "other-owner", "Desc", NOW, NOW);
        api.workspaces.add(ws);

        // 1. Ken as ADMIN
        AuthStateService authAdmin = createAuth("user-ken", "ken", "Ken");
        api.membersByWs.put("ws-1", List.of(
                new Domain.WorkspaceMember("m-owner", "ws-1", "other-owner", Domain.MemberRole.OWNER, "Owner", null, null, NOW, NOW),
                new Domain.WorkspaceMember("m-ken", "ws-1", "user-ken", Domain.MemberRole.ADMIN, "Ken", null, null, NOW, NOW)
        ));

        WorkspaceStateService stateAdmin = new WorkspaceStateService(api, authAdmin);
        stateAdmin.loadWorkspaces().get(5, TimeUnit.SECONDS);
        awaitFx();

        assertFalse(stateAdmin.isOwner());
        assertTrue(stateAdmin.isAdmin());
        assertTrue(stateAdmin.canManageMembers());
        assertTrue(stateAdmin.canManageSettings());
        assertFalse(stateAdmin.canDeleteWorkspace());
        assertTrue(stateAdmin.canLeave());

        // 2. Louise as MEMBER
        AuthStateService authMember = createAuth("user-louise", "louise", "Louise");
        api.membersByWs.put("ws-1", List.of(
                new Domain.WorkspaceMember("m-owner", "ws-1", "other-owner", Domain.MemberRole.OWNER, "Owner", null, null, NOW, NOW),
                new Domain.WorkspaceMember("m-louise", "ws-1", "user-louise", Domain.MemberRole.MEMBER, "Louise", null, null, NOW, NOW)
        ));

        WorkspaceStateService stateMember = new WorkspaceStateService(api, authMember);
        stateMember.loadWorkspaces().get(5, TimeUnit.SECONDS);
        awaitFx();

        assertFalse(stateMember.isOwner());
        assertFalse(stateMember.isAdmin());
        assertFalse(stateMember.canManageMembers());
        assertFalse(stateMember.canManageSettings());
        assertFalse(stateMember.canDeleteWorkspace());
        assertTrue(stateMember.canLeave());
    }

    @Test
    @DisplayName("16B.11: Regular member leaves workspace successfully; owner is restricted")
    void testLeaveWorkspace() throws Exception {
        MockWorkspaceApi api = new MockWorkspaceApi();
        Domain.Workspace ws = new Domain.Workspace("ws-1", "Team", "user-ada", "Desc", NOW, NOW);
        api.workspaces.add(ws);

        // Owner cannot leave
        AuthStateService authOwner = createAuth("user-ada", "ada", "Ada");
        WorkspaceStateService stateOwner = new WorkspaceStateService(api, authOwner);
        stateOwner.loadWorkspaces().get(5, TimeUnit.SECONDS);
        awaitFx();

        assertThrows(Exception.class, () -> stateOwner.leaveWorkspace().join());
        assertEquals(WorkspaceStateService.OWNER_LEAVE_RESTRICTION, stateOwner.getErrorMessage());

        // Non-owner member can leave
        AuthStateService authMember = createAuth("user-ken", "ken", "Ken");
        api.membersByWs.put("ws-1", new CopyOnWriteArrayList<>(List.of(
                new Domain.WorkspaceMember("m-owner", "ws-1", "user-ada", Domain.MemberRole.OWNER, "Ada", null, null, NOW, NOW),
                new Domain.WorkspaceMember("m-ken", "ws-1", "user-ken", Domain.MemberRole.MEMBER, "Ken", null, null, NOW, NOW)
        )));

        WorkspaceStateService stateMember = new WorkspaceStateService(api, authMember);
        stateMember.loadWorkspaces().get(5, TimeUnit.SECONDS);
        awaitFx();

        stateMember.leaveWorkspace().get(5, TimeUnit.SECONDS);
        awaitFx();

        assertTrue(stateMember.getWorkspaces().isEmpty(), "Workspace should be removed from local list");
        assertNull(stateMember.getActiveWorkspace());
    }

    @Test
    @DisplayName("16B.8 & 16B.10: Member addition, role update, and removal")
    void testMemberManagementFlow() throws Exception {
        MockWorkspaceApi api = new MockWorkspaceApi();
        Domain.Workspace ws = new Domain.Workspace("ws-1", "Team", "user-ada", "Desc", NOW, NOW);
        api.workspaces.add(ws);
        api.membersByWs.put("ws-1", new CopyOnWriteArrayList<>(List.of(
                new Domain.WorkspaceMember("m-1", "ws-1", "user-ada", Domain.MemberRole.OWNER, "Ada", "ada@example.com", null, NOW, NOW)
        )));

        AuthStateService auth = createAuth("user-ada", "ada", "Ada");
        WorkspaceStateService state = new WorkspaceStateService(api, auth);
        state.loadWorkspaces().get(5, TimeUnit.SECONDS);
        awaitFx();

        assertEquals(1, state.getActiveMembers().size());

        // Add member
        Domain.WorkspaceMember added = state.addMember("bob@example.com", Domain.MemberRole.MEMBER).get(5, TimeUnit.SECONDS);
        awaitFx();

        assertEquals(2, state.getActiveMembers().size());
        assertEquals(Domain.MemberRole.MEMBER, added.role());

        // Promote to Admin
        Domain.WorkspaceMember promoted = state.updateMemberRole(added.userId(), Domain.MemberRole.ADMIN).get(5, TimeUnit.SECONDS);
        awaitFx();

        assertEquals(Domain.MemberRole.ADMIN, promoted.role());

        // Remove member
        state.removeMember(added.userId()).get(5, TimeUnit.SECONDS);
        awaitFx();

        assertEquals(1, state.getActiveMembers().size());
        assertFalse(state.getActiveMembers().stream().anyMatch(m -> m.userId().equals(added.userId())));
    }
}
