package com.landrop.ui.workspace.chat.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.landrop.ui.auth.services.AuthStateService;
import com.landrop.ui.cloud.api.ApiException;
import com.landrop.ui.cloud.api.ApiClient;
import com.landrop.ui.cloud.model.Domain;

import java.net.URI;
import java.net.URLEncoder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Small, strict-enough Phase 17 client. Identity is never included in write payloads. */
public final class HttpTeamChatApi implements TeamChatApi {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ApiClient client;

    public HttpTeamChatApi(URI apiBaseUri, AuthStateService auth) {
        this.client = new ApiClient(apiBaseUri, auth);
    }

    @Override public CompletableFuture<List<Domain.Channel>> listChannels(String workspaceId) {
        return request("GET", "workspaces/" + segment(workspaceId) + "/channels", null)
                .thenApply(json -> { List<Domain.Channel> result = new ArrayList<>(); for (JsonNode n : data(json)) result.add(channel(n)); return result; });
    }
    @Override public CompletableFuture<Domain.Channel> createChannel(String workspaceId, String name, String description) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>(); payload.put("name", name); payload.put("description", description);
        return request("POST", "workspaces/" + segment(workspaceId) + "/channels", body(payload))
                .thenApply(json -> channel(data(json)));
    }
    @Override public CompletableFuture<MessagePage> listMessages(String channelId, int limit, String before) {
        String path = "channels/" + segment(channelId) + "/messages?limit=" + limit + (before == null ? "" : "&before=" + segment(before));
        return request("GET", path, null).thenApply(json -> {
            JsonNode root = node(json); JsonNode page = root.path("page"); List<Domain.Message> result = new ArrayList<>();
            for (JsonNode n : root.path("data")) result.add(message(n));
            String next = text(page, "nextCursor"); if (next == null) next = text(page, "before");
            return new MessagePage(List.copyOf(result), next);
        });
    }
    @Override public CompletableFuture<Domain.Message> sendMessage(String channelId, String content, String replyToId) {
        Map<String, Object> payload = replyToId == null ? Map.of("content", content) : Map.of("content", content, "replyToId", replyToId);
        return request("POST", "channels/" + segment(channelId) + "/messages", body(payload)).thenApply(json -> message(data(json)));
    }
    @Override public CompletableFuture<Domain.Message> updateMessage(String messageId, String content) {
        return request("PATCH", "messages/" + segment(messageId), body(Map.of("content", content))).thenApply(json -> message(data(json)));
    }
    @Override public CompletableFuture<Domain.Message> deleteMessage(String messageId) {
        return request("DELETE", "messages/" + segment(messageId), null).thenApply(json -> message(data(json)));
    }

    private CompletableFuture<String> request(String method, String path, String body) {
        return client.request(method, path, body);
    }
    private static ApiException error(int status, String body) {
        try { JsonNode e = node(body).path("error"); return new ApiException(status, text(e, "code") == null ? "HTTP_ERROR" : text(e, "code"), text(e, "message") == null ? "Chat request failed." : text(e, "message")); }
        catch (Exception ignored) { return new ApiException(status, "HTTP_ERROR", "Chat request failed with HTTP " + status); }
    }
    private static JsonNode node(String json) { try { return JSON.readTree(json); } catch (Exception e) { throw new ApiException(0, "INVALID_RESPONSE", "Chat API returned invalid JSON", e); } }
    private static JsonNode data(String json) { JsonNode root = node(json); return root.has("data") ? root.get("data") : root; }
    private static String body(Object value) { try { return JSON.writeValueAsString(value); } catch (Exception e) { throw new IllegalArgumentException("Cannot encode chat request", e); } }
    private static String segment(String value) { return URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8); }
    private static String text(JsonNode node, String key) { return node != null && node.hasNonNull(key) ? node.get(key).asText() : null; }
    private static Instant instant(JsonNode n, String... keys) { for (String k : keys) { String value = text(n, k); if (value != null) try { return Instant.parse(value); } catch (Exception ignored) {} } return Instant.now(); }
    private static Domain.Channel channel(JsonNode n) { return new Domain.Channel(text(n,"id"), text(n,"workspaceId"), text(n,"name"), text(n,"description"), instant(n,"createdAt"), instant(n,"updatedAt","createdAt")); }
    /** Supports sender object and the senderId used by the pre-17 realtime store. */
    public static Domain.Message message(JsonNode n) {
        JsonNode sender = n.path("sender"); String senderId = text(n,"senderId"); if (senderId == null && sender.isObject()) senderId = text(sender,"id");
        String content = text(n,"content"); String deletedAt = text(n,"deletedAt");
        return new Domain.Message(text(n,"id"), text(n,"channelId"), senderId, content == null ? "" : content,
                Domain.MessageType.TEXT, text(n,"replyToId"), null, instant(n,"createdAt"), instant(n,"updatedAt","createdAt"),
                parseInstant(text(n,"editedAt")), parseInstant(deletedAt));
    }
    private static Instant parseInstant(String value) { if (value == null) return null; try { return Instant.parse(value); } catch (Exception ignored) { return null; } }
}
