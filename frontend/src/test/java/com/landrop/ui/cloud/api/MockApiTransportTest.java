package com.landrop.ui.cloud.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class MockApiTransportTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final MockApiTransport transport = new MockApiTransport();

    @Test
    void itemRoutesReturnContractEnvelopesAndDecodePathSegments() throws Exception {
        JsonNode user = read("users/user-%61very", Map.of());
        assertEquals(1, user.size());
        assertEquals("user-avery", user.path("data").path("id").asText());
        assertTrue(user.path("data").path("avatarUrl").isNull());
        JsonNode workspace = read("workspaces/workspace-demo", Map.of());
        assertEquals("user-avery", workspace.path("data").path("ownerId").asText());
    }

    @Test
    void allListRoutesReturnContractEnvelopes() throws Exception {
        Map<String, Integer> routes = Map.of(
                "workspaces", 1,
                "workspaces/workspace-demo/members", 3,
                "workspaces/workspace-demo/channels", 3,
                "channels/channel-general/messages", 3,
                "workspaces/workspace-demo/folders", 2,
                "workspaces/workspace-demo/files", 3);
        for (Map.Entry<String, Integer> route : routes.entrySet()) {
            JsonNode response = read(route.getKey(), Map.of());
            assertEquals(2, response.size(), route.getKey());
            assertEquals(route.getValue(), response.path("data").size(), route.getKey());
            assertTrue(response.path("page").path("nextCursor").isNull(), route.getKey());
        }
    }

    @Test
    void paginationVisitsEachMessageOnceInChronologicalOrder() throws Exception {
        String path = "channels/channel-general/messages";
        List<String> ids = new ArrayList<>();
        Map<String, String> query = new HashMap<>(Map.of("limit", "1"));
        for (int page = 0; page < 3; page++) {
            JsonNode response = read(path, query);
            assertEquals(1, response.path("data").size());
            ids.add(response.path("data").get(0).path("id").asText());
            JsonNode next = response.path("page").path("nextCursor");
            if (page == 2) {
                assertTrue(next.isNull());
            } else {
                assertTrue(next.isTextual());
                query.put("cursor", next.asText());
            }
        }
        assertEquals(List.of("message-welcome", "message-reply", "message-file"), ids);
    }

    @Test
    void cursorsCannotBeReusedOnAnotherListOrParent() throws Exception {
        String channelCursor = read("channels/channel-general/messages", Map.of("limit", "1"))
                .path("page").path("nextCursor").asText();
        assertApiFailure("channels/channel-design/messages", Map.of("cursor", channelCursor),
                400, "INVALID_QUERY");
        String memberCursor = read("workspaces/workspace-demo/members", Map.of("limit", "1"))
                .path("page").path("nextCursor").asText();
        assertApiFailure("workspaces/workspace-demo/channels", Map.of("cursor", memberCursor),
                400, "INVALID_QUERY");
    }

    @Test
    void invalidQueriesFailWithAnActionableApiError() {
        for (String limit : List.of("0", "101", "-1", "1.5", "abc", "", "9999999999999999")) {
            assertApiFailure("workspaces", Map.of("limit", limit), 400, "INVALID_QUERY");
        }
        for (String cursor : List.of("", "not-a-cursor", "%%%%")) {
            assertApiFailure("workspaces", Map.of("cursor", cursor), 400, "INVALID_QUERY");
        }
        assertApiFailure("workspaces", Map.of("folderId", "folder-shared"), 400, "INVALID_QUERY");
        assertApiFailure("users/user-avery", Map.of("limit", "1"), 400, "INVALID_QUERY");
        Map<String, String> nullCursor = new HashMap<>();
        nullCursor.put("cursor", null);
        assertApiFailure("workspaces", nullCursor, 400, "INVALID_QUERY");
    }

    @Test
    void missingParentsAreDistinctFromExistingEmptyCollections() throws Exception {
        JsonNode empty = read("channels/channel-empty/messages", Map.of());
        assertTrue(empty.path("data").isEmpty());
        assertTrue(empty.path("page").path("nextCursor").isNull());
        for (String path : List.of("users/missing", "workspaces/missing", "workspaces/missing/members",
                "workspaces/missing/channels", "workspaces/missing/files", "workspaces/missing/folders",
                "channels/missing/messages", "workspaces/workspace-demo/unknown", "/workspaces")) {
            assertApiFailure(path, Map.of(), 404, "NOT_FOUND");
        }
    }

    @Test
    void fixtureReferencesAndMetadataRemainCoherent() throws Exception {
        JsonNode members = read("workspaces/workspace-demo/members", Map.of()).path("data");
        Set<String> users = new HashSet<>();
        Set<String> roles = new HashSet<>();
        for (JsonNode member : members) {
            String userId = member.path("userId").asText();
            assertEquals(userId, read("users/" + userId, Map.of()).path("data").path("id").asText());
            users.add(userId);
            roles.add(member.path("role").asText());
        }
        assertEquals(Set.of("OWNER", "ADMIN", "MEMBER"), roles);
        JsonNode folders = read("workspaces/workspace-demo/folders", Map.of()).path("data");
        Set<String> folderIds = new HashSet<>();
        folders.forEach(folder -> folderIds.add(folder.path("id").asText()));
        for (JsonNode folder : folders) {
            assertTrue(users.contains(folder.path("createdById").asText()));
            assertTrue(folder.path("parentId").isNull()
                    || folderIds.contains(folder.path("parentId").asText()));
        }
        Set<String> fileIds = new HashSet<>();
        for (JsonNode file : read("workspaces/workspace-demo/files", Map.of()).path("data")) {
            fileIds.add(file.path("id").asText());
            assertTrue(users.contains(file.path("uploadedById").asText()));
            assertTrue(file.path("folderId").isNull() || folderIds.contains(file.path("folderId").asText()));
            assertTrue(file.path("sizeBytes").isTextual());
            assertTrue(Long.parseLong(file.path("sizeBytes").asText()) >= 0);
        }
        for (JsonNode channel : read("workspaces/workspace-demo/channels", Map.of()).path("data")) {
            JsonNode messages = read("channels/" + channel.path("id").asText() + "/messages", Map.of())
                    .path("data");
            Set<String> messageIds = new HashSet<>();
            messages.forEach(message -> messageIds.add(message.path("id").asText()));
            for (JsonNode message : messages) {
                assertTrue(users.contains(message.path("senderId").asText()));
                assertTrue(message.path("replyToId").isNull()
                        || messageIds.contains(message.path("replyToId").asText()));
                if (message.path("type").asText().equals("FILE")) {
                    assertTrue(fileIds.contains(message.path("fileId").asText()));
                } else {
                    assertTrue(message.path("fileId").isNull());
                }
            }
        }
    }

    private JsonNode read(String path, Map<String, String> query) throws Exception {
        return mapper.readTree(transport.get(path, query).join());
    }

    private void assertApiFailure(String path, Map<String, String> query, int status, String code) {
        CompletionException failure = assertThrows(CompletionException.class,
                () -> transport.get(path, query).join());
        ApiException api = assertInstanceOf(ApiException.class, failure.getCause());
        assertEquals(status, api.statusCode());
        assertEquals(code, api.code());
    }
}
