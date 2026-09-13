package com.landrop.ui.cloud.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Offline, read-only implementation of the Phase 14 API contract. */
public final class MockApiTransport implements ApiTransport {
    private static final String FIXTURE = "/com/landrop/ui/cloud/phase14-mock.json";
    private static final Set<String> LIST_QUERIES = Set.of("limit", "cursor");
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonNode fixture;

    public MockApiTransport() {
        try (InputStream input = MockApiTransport.class.getResourceAsStream(FIXTURE)) {
            if (input == null) {
                throw new IllegalStateException("Missing Phase 14 mock fixture: " + FIXTURE);
            }
            fixture = mapper.readTree(input);
            for (String collection : List.of("users", "workspaces", "members", "channels",
                    "messages", "folders", "files")) {
                if (fixture == null || !fixture.path(collection).isArray()) {
                    throw new IllegalStateException("Invalid mock collection: " + collection);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load Phase 14 mock fixture", e);
        }
    }

    @Override
    public CompletableFuture<String> get(String path, Map<String, String> query) {
        try {
            return CompletableFuture.completedFuture(route(path, query).toString());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private ObjectNode route(String path, Map<String, String> query) {
        if (path == null || path.isEmpty() || path.startsWith("/")
                || path.contains("?") || path.contains("#")) {
            throw notFound();
        }
        if (query == null) {
            throw invalidQuery("Query parameters must not be null");
        }
        String[] segments = path.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            try {
                // URI path segments preserve '+'; only query encoding treats it as a space.
                segments[i] = URLDecoder.decode(segments[i].replace("+", "%2B"),
                        StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw notFound();
            }
            if (segments[i].isEmpty()) {
                throw notFound();
            }
        }
        if (segments.length == 2 && segments[0].equals("users")) {
            rejectItemQueries(query);
            return item(find("users", segments[1]));
        }
        if (segments[0].equals("workspaces")) {
            if (segments.length == 1) {
                return list("workspaces", null, null, "workspaces", query);
            }
            if (segments.length == 2) {
                rejectItemQueries(query);
                return item(find("workspaces", segments[1]));
            }
            if (segments.length == 3 && Set.of("members", "channels", "files", "folders")
                    .contains(segments[2])) {
                find("workspaces", segments[1]);
                return list(segments[2], "workspaceId", segments[1],
                        "workspaces/" + segments[1] + "/" + segments[2], query);
            }
        }
        if (segments.length == 3 && segments[0].equals("channels")
                && segments[2].equals("messages")) {
            find("channels", segments[1]);
            return list("messages", "channelId", segments[1],
                    "channels/" + segments[1] + "/messages", query);
        }
        throw notFound();
    }

    private ObjectNode item(JsonNode data) {
        ObjectNode response = mapper.createObjectNode();
        response.set("data", data);
        return response;
    }

    private JsonNode find(String collection, String id) {
        for (JsonNode row : fixture.path(collection)) {
            if (row.path("id").asText().equals(id)) {
                return row;
            }
        }
        throw notFound();
    }

    private ObjectNode list(String collection, String parentField, String parentId,
                            String route, Map<String, String> query) {
        if (!LIST_QUERIES.containsAll(query.keySet())) {
            throw invalidQuery("Only limit and cursor are supported for list routes");
        }
        int limit = limit(query);
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode row : fixture.path(collection)) {
            if (parentField == null || row.path(parentField).asText().equals(parentId)) {
                rows.add(row);
            }
        }
        rows.sort(Comparator.comparing((JsonNode row) -> Instant.parse(row.path("createdAt").asText()))
                .thenComparing(row -> row.path("id").asText()));
        int start = query.containsKey("cursor") ? cursorStart(query.get("cursor"), route, rows) : 0;
        int end = Math.min(start + limit, rows.size());
        ObjectNode response = mapper.createObjectNode();
        response.set("data", mapper.valueToTree(rows.subList(start, end)));
        ObjectNode page = response.putObject("page");
        if (end < rows.size()) {
            page.put("nextCursor", cursor(route, rows.get(end - 1).path("id").asText()));
        } else {
            page.putNull("nextCursor");
        }
        return response;
    }

    private int limit(Map<String, String> query) {
        if (!query.containsKey("limit")) {
            return 50;
        }
        String value = query.get("limit");
        if (value == null || !value.matches("[1-9][0-9]*")) {
            throw invalidQuery("limit must be an integer from 1 to 100");
        }
        try {
            int limit = Integer.parseInt(value);
            if (limit > 100) {
                throw invalidQuery("limit must be an integer from 1 to 100");
            }
            return limit;
        } catch (NumberFormatException e) {
            throw invalidQuery("limit must be an integer from 1 to 100");
        }
    }

    private String cursor(String route, String after) {
        ObjectNode token = mapper.createObjectNode();
        token.put("version", 1);
        token.put("route", route);
        token.put("after", after);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(token.toString().getBytes(StandardCharsets.UTF_8));
    }

    private int cursorStart(String encoded, String route, List<JsonNode> rows) {
        if (encoded == null || encoded.length() > 4096 || !encoded.matches("[A-Za-z0-9_-]+")) {
            throw invalidQuery("Invalid cursor for this list");
        }
        try {
            JsonNode token = mapper.readTree(Base64.getUrlDecoder().decode(encoded));
            if (token == null || !token.isObject() || token.size() != 3
                    || !token.path("version").isInt() || token.path("version").intValue() != 1
                    || !token.path("route").isTextual() || !token.path("route").textValue().equals(route)
                    || !token.path("after").isTextual()) {
                throw invalidQuery("Invalid cursor for this list");
            }
            String after = token.path("after").textValue();
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).path("id").asText().equals(after)) {
                    return i + 1;
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            throw invalidQuery("Invalid cursor for this list");
        }
        throw invalidQuery("Invalid cursor for this list");
    }

    private void rejectItemQueries(Map<String, String> query) {
        if (!query.isEmpty()) {
            throw invalidQuery("Item routes do not accept query parameters");
        }
    }

    private ApiException invalidQuery(String message) {
        return new ApiException(400, "INVALID_QUERY", message);
    }

    private ApiException notFound() {
        return new ApiException(404, "NOT_FOUND", "The requested resource does not exist");
    }
}
