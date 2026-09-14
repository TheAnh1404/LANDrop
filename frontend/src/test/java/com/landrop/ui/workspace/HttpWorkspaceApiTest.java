package com.landrop.ui.workspace;

import com.landrop.ui.auth.api.AuthApi;
import com.landrop.ui.auth.api.InMemorySessionStore;
import com.landrop.ui.auth.dto.AuthDto;
import com.landrop.ui.auth.model.AuthModels;
import com.landrop.ui.auth.services.AuthStateService;
import com.landrop.ui.cloud.api.ApiException;
import com.landrop.ui.cloud.dto.Dto;
import com.landrop.ui.cloud.model.Domain;
import com.landrop.ui.workspace.api.HttpWorkspaceApi;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class HttpWorkspaceApiTest {

    private HttpServer server;
    private ExecutorService executor;
    private final List<String> capturedRequests = new ArrayList<>();
    private URI baseUri;
    private AuthStateService authState;
    private final AtomicInteger refreshCounter = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);

        server.createContext("/api/workspaces", exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");

            synchronized (capturedRequests) {
                capturedRequests.add(method + " " + path + " " + authHeader);
            }

            int statusCode = 200;
            String responseBody = "";

            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                statusCode = 401;
                responseBody = "{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"Missing token\"}}";
            } else if (path.equals("/api/workspaces") && "GET".equals(method)) {
                responseBody = "{\"data\":[{\"id\":\"ws-1\",\"name\":\"Engineering\",\"ownerId\":\"u-1\",\"description\":\"Dev\",\"createdAt\":\"2026-09-14T00:00:00Z\",\"updatedAt\":\"2026-09-14T00:00:00Z\"}]}";
            } else if (path.equals("/api/workspaces") && "POST".equals(method)) {
                responseBody = "{\"data\":{\"id\":\"ws-new\",\"name\":\"Design\",\"ownerId\":\"u-1\",\"description\":\"UI/UX\",\"createdAt\":\"2026-09-14T00:00:00Z\",\"updatedAt\":\"2026-09-14T00:00:00Z\"}}";
            } else if (path.equals("/api/workspaces/ws-1") && "GET".equals(method)) {
                responseBody = "{\"data\":{\"id\":\"ws-1\",\"name\":\"Engineering\",\"ownerId\":\"u-1\",\"description\":\"Dev\",\"createdAt\":\"2026-09-14T00:00:00Z\",\"updatedAt\":\"2026-09-14T00:00:00Z\"}}";
            } else if (path.equals("/api/workspaces/ws-1") && "PATCH".equals(method)) {
                responseBody = "{\"data\":{\"id\":\"ws-1\",\"name\":\"Engineering Renamed\",\"ownerId\":\"u-1\",\"description\":\"Updated Dev\",\"createdAt\":\"2026-09-14T00:00:00Z\",\"updatedAt\":\"2026-09-14T00:01:00Z\"}}";
            } else if (path.equals("/api/workspaces/ws-1") && "DELETE".equals(method)) {
                statusCode = 204;
                responseBody = "";
            } else if (path.equals("/api/workspaces/ws-1/members") && "GET".equals(method)) {
                responseBody = "{\"data\":[{\"id\":\"m-1\",\"workspaceId\":\"ws-1\",\"userId\":\"u-1\",\"role\":\"OWNER\",\"displayName\":\"Ada\",\"email\":\"ada@example.com\",\"avatarUrl\":null,\"createdAt\":\"2026-09-14T00:00:00Z\",\"updatedAt\":\"2026-09-14T00:00:00Z\"}]}";
            } else if (path.equals("/api/workspaces/ws-1/members") && "POST".equals(method)) {
                responseBody = "{\"data\":{\"id\":\"m-2\",\"workspaceId\":\"ws-1\",\"userId\":\"u-2\",\"role\":\"MEMBER\",\"displayName\":\"Ken\",\"email\":\"ken@example.com\",\"avatarUrl\":null,\"createdAt\":\"2026-09-14T00:00:00Z\",\"updatedAt\":\"2026-09-14T00:00:00Z\"}}";
            } else if (path.equals("/api/workspaces/ws-1/members/u-2") && "PATCH".equals(method)) {
                responseBody = "{\"data\":{\"id\":\"m-2\",\"workspaceId\":\"ws-1\",\"userId\":\"u-2\",\"role\":\"ADMIN\",\"displayName\":\"Ken\",\"email\":\"ken@example.com\",\"avatarUrl\":null,\"createdAt\":\"2026-09-14T00:00:00Z\",\"updatedAt\":\"2026-09-14T00:02:00Z\"}}";
            } else if (path.equals("/api/workspaces/ws-1/members/u-2") && "DELETE".equals(method)) {
                statusCode = 204;
                responseBody = "";
            } else if (path.equals("/api/workspaces/ws-1/leave") && "POST".equals(method)) {
                statusCode = 200;
                responseBody = "{\"data\":{\"message\":\"Left workspace successfully\"}}";
            } else {
                statusCode = 404;
                responseBody = "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"Route not found\"}}";
            }

            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/");

        // Setup AuthStateService with an active user and mock API
        AuthDto.AuthUserResponse mockUser = new AuthDto.AuthUserResponse("u-1", "ada", "Ada", "ada@example.com", null, "2026-09-14T00:00:00Z", "2026-09-14T00:00:00Z");
        AuthApi authApi = new AuthApi() {
            @Override public CompletableFuture<AuthDto.AuthResponse> register(AuthDto.RegisterRequest request) { return null; }
            @Override public CompletableFuture<AuthDto.AuthResponse> login(AuthDto.LoginRequest request) {
                return CompletableFuture.completedFuture(new AuthDto.AuthResponse("access-token-1", "refresh-token-1", "Bearer", 3600, mockUser));
            }
            @Override public CompletableFuture<AuthDto.RefreshResponse> refresh(AuthDto.RefreshRequest request) {
                refreshCounter.incrementAndGet();
                return CompletableFuture.completedFuture(new AuthDto.RefreshResponse("access-token-refreshed", "refresh-token-2", "Bearer", 3600));
            }
            @Override public CompletableFuture<AuthDto.AuthUserResponse> me(String accessToken) { return CompletableFuture.completedFuture(mockUser); }
            @Override public CompletableFuture<Void> logout(String accessToken, String refreshToken) { return CompletableFuture.completedFuture(null); }
        };

        authState = new AuthStateService(authApi, new InMemorySessionStore());
        authState.login(new AuthModels.LoginRequest("ada@example.com", "pass")).join();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdown();
    }

    @Test
    @DisplayName("16B.2: listWorkspaces sends GET /api/workspaces with Bearer token")
    void testListWorkspaces() {
        HttpWorkspaceApi api = new HttpWorkspaceApi(baseUri, authState);
        List<Domain.Workspace> workspaces = api.listWorkspaces().join();

        assertEquals(1, workspaces.size());
        assertEquals("ws-1", workspaces.getFirst().id());
        assertEquals("Engineering", workspaces.getFirst().name());
        assertEquals("u-1", workspaces.getFirst().ownerId());

        synchronized (capturedRequests) {
            assertEquals(1, capturedRequests.size());
            assertTrue(capturedRequests.getFirst().startsWith("GET /api/workspaces Bearer access-token-1"));
        }
    }

    @Test
    @DisplayName("16B.2: getWorkspace sends GET /api/workspaces/:workspaceId")
    void testGetWorkspace() {
        HttpWorkspaceApi api = new HttpWorkspaceApi(baseUri, authState);
        Domain.Workspace ws = api.getWorkspace("ws-1").join();

        assertEquals("ws-1", ws.id());
        assertEquals("Engineering", ws.name());
    }

    @Test
    @DisplayName("16B.2: createWorkspace sends POST /api/workspaces without exposing ownerId")
    void testCreateWorkspace() {
        HttpWorkspaceApi api = new HttpWorkspaceApi(baseUri, authState);
        Domain.Workspace created = api.createWorkspace(new Dto.CreateWorkspaceRequest("Design", "UI/UX")).join();

        assertEquals("ws-new", created.id());
        assertEquals("Design", created.name());
        assertEquals("u-1", created.ownerId());
    }

    @Test
    @DisplayName("16B.2: updateWorkspace sends PATCH /api/workspaces/:workspaceId")
    void testUpdateWorkspace() {
        HttpWorkspaceApi api = new HttpWorkspaceApi(baseUri, authState);
        Domain.Workspace updated = api.updateWorkspace("ws-1", new Dto.UpdateWorkspaceRequest("Engineering Renamed", "Updated Dev")).join();

        assertEquals("ws-1", updated.id());
        assertEquals("Engineering Renamed", updated.name());
        assertEquals("Updated Dev", updated.description());
    }

    @Test
    @DisplayName("16B.2: deleteWorkspace sends DELETE /api/workspaces/:workspaceId")
    void testDeleteWorkspace() {
        HttpWorkspaceApi api = new HttpWorkspaceApi(baseUri, authState);
        assertDoesNotThrow(() -> api.deleteWorkspace("ws-1").join());
    }

    @Test
    @DisplayName("16B.2: listMembers sends GET /api/workspaces/:workspaceId/members")
    void testListMembers() {
        HttpWorkspaceApi api = new HttpWorkspaceApi(baseUri, authState);
        List<Domain.WorkspaceMember> members = api.listMembers("ws-1").join();

        assertEquals(1, members.size());
        Domain.WorkspaceMember m = members.getFirst();
        assertEquals("m-1", m.id());
        assertEquals("u-1", m.userId());
        assertEquals(Domain.MemberRole.OWNER, m.role());
        assertEquals("Ada", m.nameOrFallback());
    }

    @Test
    @DisplayName("16B.2: addMember sends POST /api/workspaces/:workspaceId/members")
    void testAddMember() {
        HttpWorkspaceApi api = new HttpWorkspaceApi(baseUri, authState);
        Domain.WorkspaceMember newMember = api.addMember("ws-1", new Dto.AddWorkspaceMemberRequest("ken@example.com", "MEMBER")).join();

        assertEquals("m-2", newMember.id());
        assertEquals("u-2", newMember.userId());
        assertEquals(Domain.MemberRole.MEMBER, newMember.role());
        assertEquals("Ken", newMember.nameOrFallback());
    }

    @Test
    @DisplayName("16B.2: updateMemberRole sends PATCH /api/workspaces/:workspaceId/members/:userId")
    void testUpdateMemberRole() {
        HttpWorkspaceApi api = new HttpWorkspaceApi(baseUri, authState);
        Domain.WorkspaceMember promoted = api.updateMemberRole("ws-1", "u-2", new Dto.UpdateMemberRoleRequest("ADMIN")).join();

        assertEquals("m-2", promoted.id());
        assertEquals(Domain.MemberRole.ADMIN, promoted.role());
    }

    @Test
    @DisplayName("16B.2: removeMember sends DELETE /api/workspaces/:workspaceId/members/:userId")
    void testRemoveMember() {
        HttpWorkspaceApi api = new HttpWorkspaceApi(baseUri, authState);
        assertDoesNotThrow(() -> api.removeMember("ws-1", "u-2").join());
    }

    @Test
    @DisplayName("16B.2: leaveWorkspace sends POST /api/workspaces/:workspaceId/leave")
    void testLeaveWorkspace() {
        HttpWorkspaceApi api = new HttpWorkspaceApi(baseUri, authState);
        assertDoesNotThrow(() -> api.leaveWorkspace("ws-1", "u-2").join());
    }

    @Test
    @DisplayName("16B.2: 404 / 400 error responses map to ApiException with error message")
    void testErrorHandling() {
        HttpWorkspaceApi api = new HttpWorkspaceApi(baseUri, authState);
        CompletionException ex = assertThrows(CompletionException.class, () -> api.getWorkspace("non-existent").join());
        assertInstanceOf(ApiException.class, ex.getCause());
        ApiException apiEx = (ApiException) ex.getCause();
        assertEquals(404, apiEx.statusCode());
        assertEquals("Route not found", apiEx.getMessage());
    }
}
