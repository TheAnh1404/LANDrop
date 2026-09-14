package com.landrop.ui.workspace.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.landrop.ui.auth.services.AuthStateService;
import com.landrop.ui.cloud.api.ApiException;
import com.landrop.ui.cloud.dto.Dto;
import com.landrop.ui.cloud.model.Domain;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

/**
 * Robust HTTP client implementation of WorkspaceApi supporting JWT authentication,
 * automatic token refresh on 401, flexible JSON envelopment, and comprehensive error handling.
 */
public class HttpWorkspaceApi implements WorkspaceApi {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final URI baseUri;
    private final AuthStateService authStateService;
    private final HttpClient client;
    private final Duration timeout;

    public HttpWorkspaceApi(URI apiBaseUri, AuthStateService authStateService) {
        this(apiBaseUri, authStateService, HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build(), DEFAULT_TIMEOUT);
    }

    public HttpWorkspaceApi(URI apiBaseUri, AuthStateService authStateService, HttpClient client, Duration timeout) {
        Objects.requireNonNull(apiBaseUri, "apiBaseUri cannot be null");
        this.authStateService = Objects.requireNonNull(authStateService, "authStateService cannot be null");
        this.client = Objects.requireNonNull(client, "client cannot be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout cannot be null");

        String raw = apiBaseUri.toASCIIString().replaceAll("/+$", "");
        if (!raw.endsWith("/api")) {
            raw += "/api";
        }
        this.baseUri = URI.create(raw + "/");
    }

    @Override
    public CompletableFuture<List<Domain.Workspace>> listWorkspaces() {
        return sendRequest("GET", "workspaces", null, false)
                .thenApply(this::parseWorkspaceList);
    }

    @Override
    public CompletableFuture<Domain.Workspace> getWorkspace(String workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId required");
        return sendRequest("GET", "workspaces/" + encode(workspaceId), null, false)
                .thenApply(this::parseWorkspaceItem);
    }

    @Override
    public CompletableFuture<Domain.Workspace> createWorkspace(Dto.CreateWorkspaceRequest request) {
        Objects.requireNonNull(request, "request required");
        String body = serialize(request);
        return sendRequest("POST", "workspaces", body, false)
                .thenApply(this::parseWorkspaceItem);
    }

    @Override
    public CompletableFuture<Domain.Workspace> updateWorkspace(String workspaceId, Dto.UpdateWorkspaceRequest request) {
        Objects.requireNonNull(workspaceId, "workspaceId required");
        Objects.requireNonNull(request, "request required");
        String body = serialize(request);
        return sendRequest("PATCH", "workspaces/" + encode(workspaceId), body, false)
                .thenApply(this::parseWorkspaceItem);
    }

    @Override
    public CompletableFuture<Void> deleteWorkspace(String workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId required");
        return sendRequest("DELETE", "workspaces/" + encode(workspaceId), null, false)
                .thenApply(ignored -> null);
    }

    @Override
    public CompletableFuture<List<Domain.WorkspaceMember>> listMembers(String workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId required");
        return sendRequest("GET", "workspaces/" + encode(workspaceId) + "/members", null, false)
                .thenApply(this::parseMemberList);
    }

    @Override
    public CompletableFuture<Domain.WorkspaceMember> addMember(String workspaceId, Dto.AddWorkspaceMemberRequest request) {
        Objects.requireNonNull(workspaceId, "workspaceId required");
        Objects.requireNonNull(request, "request required");
        // Phase 16A accepts an existing account by email and assigns MEMBER server-side.
        // Do not send optional UI-only role/userId fields to the strict backend contract.
        String body = serialize(java.util.Map.of("email", request.email()));
        return sendRequest("POST", "workspaces/" + encode(workspaceId) + "/members", body, false)
                .thenApply(this::parseMemberItem);
    }

    @Override
    public CompletableFuture<Domain.WorkspaceMember> updateMemberRole(String workspaceId, String userId, Dto.UpdateMemberRoleRequest request) {
        Objects.requireNonNull(workspaceId, "workspaceId required");
        Objects.requireNonNull(userId, "userId required");
        Objects.requireNonNull(request, "request required");
        String body = serialize(request);
        return sendRequest("PATCH", "workspaces/" + encode(workspaceId) + "/members/" + encode(userId), body, false)
                .thenApply(this::parseMemberItem);
    }

    @Override
    public CompletableFuture<Void> removeMember(String workspaceId, String userId) {
        Objects.requireNonNull(workspaceId, "workspaceId required");
        Objects.requireNonNull(userId, "userId required");
        return sendRequest("DELETE", "workspaces/" + encode(workspaceId) + "/members/" + encode(userId), null, false)
                .thenApply(ignored -> null);
    }

    @Override
    public CompletableFuture<Void> leaveWorkspace(String workspaceId, String userId) {
        Objects.requireNonNull(workspaceId, "workspaceId required");
        // Try dedicated leave endpoint first; if not found, fall back to removing own member row
        return sendRequest("POST", "workspaces/" + encode(workspaceId) + "/leave", null, false)
                .thenApply(ignored -> (Void) null)
                .exceptionallyCompose(ex -> {
                    Throwable cause = unwrap(ex);
                    if (cause instanceof ApiException apiEx && (apiEx.statusCode() == 404 || apiEx.statusCode() == 405)) {
                        String currentUserId = userId;
                        if ((currentUserId == null || currentUserId.isBlank()) && authStateService.user() != null) {
                            currentUserId = authStateService.user().id();
                        }
                        if (currentUserId != null && !currentUserId.isBlank()) {
                            return removeMember(workspaceId, currentUserId);
                        }
                    }
                    return CompletableFuture.failedFuture(cause);
                });
    }

    // --- Core HTTP Dispatcher with JWT & Auto-Refresh ---

    private CompletableFuture<String> sendRequest(String method, String relativePath, String body, boolean isRetry) {
        String token = authStateService.accessToken().orElse(null);
        if (token == null || token.isBlank()) {
            return CompletableFuture.failedFuture(new ApiException(401, "UNAUTHENTICATED", "Sign in is required."));
        }

        URI targetUri = URI.create(baseUri.toASCIIString() + relativePath);
        HttpRequest.Builder builder = HttpRequest.newBuilder(targetUri)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .timeout(timeout);

        if (body != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else if ("POST".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString("{}"));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                .handle((response, error) -> {
                    if (error != null) {
                        Throwable cause = unwrap(error);
                        String code = cause instanceof HttpTimeoutException ? "TIMEOUT" : "NETWORK_ERROR";
                        throw new ApiException(0, code, "Workspace service request timed out or failed: " + cause.getMessage(), cause);
                    }

                    int status = response.statusCode();

                    // Automatic refresh on 401
                    if (status == 401 && !isRetry) {
                        return authStateService.refreshAccessToken()
                                .thenCompose(newToken -> sendRequest(method, relativePath, body, true));
                    }

                    if (status >= 400) {
                        throw parseHttpError(response);
                    }

                    if (status == 204 || response.body() == null || response.body().isBlank()) {
                        return CompletableFuture.completedFuture("{\"data\":null}");
                    }

                    return CompletableFuture.completedFuture(response.body());
                })
                .thenCompose(Function.identity());
    }

    // --- JSON Parsing & Mapping ---

    private List<Domain.Workspace> parseWorkspaceList(String json) {
        try {
            JsonNode root = JSON.readTree(json);
            JsonNode data = root.has("data") ? root.get("data") : root;
            List<Domain.Workspace> list = new ArrayList<>();
            if (data.isArray()) {
                for (JsonNode item : data) {
                    list.add(mapWorkspaceNode(item));
                }
            } else if (data.isObject()) {
                list.add(mapWorkspaceNode(data));
            }
            return list;
        } catch (Exception e) {
            throw new ApiException(0, "INVALID_RESPONSE", "Failed to parse workspace list: " + e.getMessage(), e);
        }
    }

    private Domain.Workspace parseWorkspaceItem(String json) {
        try {
            JsonNode root = JSON.readTree(json);
            JsonNode data = root.has("data") ? root.get("data") : root;
            return mapWorkspaceNode(data);
        } catch (Exception e) {
            throw new ApiException(0, "INVALID_RESPONSE", "Failed to parse workspace item: " + e.getMessage(), e);
        }
    }

    private Domain.Workspace mapWorkspaceNode(JsonNode node) {
        String id = text(node, "id");
        String name = text(node, "name");
        String ownerId = text(node, "ownerId");
        if (ownerId == null) ownerId = text(node, "owner_id");
        if (ownerId == null && authStateService.user() != null) ownerId = authStateService.user().id();
        String description = text(node, "description");
        Instant createdAt = instant(node, "createdAt", "created_at");
        Instant updatedAt = instant(node, "updatedAt", "updated_at");
        return new Domain.Workspace(id, name, ownerId, description, createdAt, updatedAt);
    }

    private List<Domain.WorkspaceMember> parseMemberList(String json) {
        try {
            JsonNode root = JSON.readTree(json);
            JsonNode data = root.has("data") ? root.get("data") : root;
            List<Domain.WorkspaceMember> list = new ArrayList<>();
            if (data.isArray()) {
                for (JsonNode item : data) {
                    list.add(mapMemberNode(item));
                }
            } else if (data.isObject()) {
                list.add(mapMemberNode(data));
            }
            return list;
        } catch (Exception e) {
            throw new ApiException(0, "INVALID_RESPONSE", "Failed to parse member list: " + e.getMessage(), e);
        }
    }

    private Domain.WorkspaceMember parseMemberItem(String json) {
        try {
            JsonNode root = JSON.readTree(json);
            JsonNode data = root.has("data") ? root.get("data") : root;
            return mapMemberNode(data);
        } catch (Exception e) {
            throw new ApiException(0, "INVALID_RESPONSE", "Failed to parse member item: " + e.getMessage(), e);
        }
    }

    private Domain.WorkspaceMember mapMemberNode(JsonNode node) {
        String id = text(node, "id");
        String workspaceId = text(node, "workspaceId");
        if (workspaceId == null) workspaceId = text(node, "workspace_id");
        String userId = text(node, "userId");
        if (userId == null) userId = text(node, "user_id");

        String roleStr = text(node, "role");
        Domain.MemberRole role = Domain.MemberRole.fromString(roleStr);

        String displayName = text(node, "displayName");
        if (displayName == null) displayName = text(node, "display_name");

        String email = text(node, "email");
        String avatarUrl = text(node, "avatarUrl");
        if (avatarUrl == null) avatarUrl = text(node, "avatar_url");

        JsonNode user = node.get("user");
        if (user != null && user.isObject()) {
            if (displayName == null) displayName = text(user, "displayName");
            if (email == null) email = text(user, "email");
            if (avatarUrl == null) avatarUrl = text(user, "avatarUrl");
        }

        Instant createdAt = instant(node, "createdAt", "joined_at", "created_at");
        Instant updatedAt = instant(node, "updatedAt", "updated_at");

        return new Domain.WorkspaceMember(id != null ? id : (workspaceId + "_" + userId),
                workspaceId, userId, role, displayName, email, avatarUrl, createdAt, updatedAt);
    }

    private static String text(JsonNode node, String field) {
        if (node != null && node.has(field) && !node.get(field).isNull()) {
            return node.get(field).asText();
        }
        return null;
    }

    private static Instant instant(JsonNode node, String... fields) {
        if (node != null) {
            for (String field : fields) {
                if (node.has(field) && !node.get(field).isNull()) {
                    JsonNode val = node.get(field);
                    if (val.isNumber()) {
                        return Instant.ofEpochMilli(val.asLong());
                    }
                    try {
                        return Instant.parse(val.asText());
                    } catch (Exception ignored) {}
                }
            }
        }
        return Instant.now();
    }

    private static String serialize(Object obj) {
        try {
            return JSON.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize request: " + e.getMessage(), e);
        }
    }

    private static String encode(String segment) {
        return java.net.URLEncoder.encode(segment, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static ApiException parseHttpError(HttpResponse<String> response) {
        try {
            JsonNode root = JSON.readTree(response.body());
            JsonNode error = root.has("error") ? root.get("error") : null;
            if (error != null && error.isObject()) {
                String code = text(error, "code");
                String message = text(error, "message");
                if (code != null && message != null) {
                    return new ApiException(response.statusCode(), code, message);
                }
            }
            String message = text(root, "message");
            if (message != null && !message.isBlank()) {
                String code = text(root, "code");
                return new ApiException(response.statusCode(), code != null ? code : "HTTP_ERROR", message);
            }
        } catch (Exception ignored) {}
        return new ApiException(response.statusCode(), "HTTP_ERROR", "Request failed with HTTP status " + response.statusCode());
    }

    private static Throwable unwrap(Throwable error) {
        if (error == null) return null;
        Throwable result = error;
        while ((result instanceof CompletionException || result instanceof java.util.concurrent.ExecutionException)
                && result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }
}
