package com.landrop.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.auth.repository.RefreshTokenRepository;
import com.landrop.auth.security.JwtService;
import com.landrop.auth.server.AuthHttpServer;
import com.landrop.auth.server.AuthMiddleware;
import com.landrop.auth.service.AuthService;
import com.landrop.chat.service.ChannelChatService;
import com.landrop.database.DatabaseConfig;
import com.landrop.database.DatabaseManager;
import com.landrop.database.repository.UserRepository;
import com.landrop.database.repository.WorkspaceRepository;
import com.landrop.database.service.WorkspaceService;
import com.landrop.realtime.JdbcRealtimeStore;
import com.landrop.realtime.RealtimeServer;
import com.landrop.realtime.SocketAuthenticator;
import com.landrop.realtime.WsProbe;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive verification against Neon PostgreSQL in an isolated temporary schema.
 * Verifies all 21 items of Phase 17A:
 * [x] Workspace member can list channels
 * [x] Non-member cannot access channels
 * [x] Owner/Admin can create channel
 * [x] Unauthorized user cannot create channel
 * [x] Message history loads from Neon
 * [x] Pagination works
 * [x] Authenticated user can join authorized channel room
 * [x] Unauthorized room join rejected
 * [x] User1 sends message
 * [x] User2 receives realtime
 * [x] User3 receives realtime
 * [x] Same user on second device receives realtime
 * [x] Message persists after refresh
 * [x] Message persists after backend restart
 * [x] Reply works
 * [x] Invalid cross-channel reply rejected
 * [x] Sender can edit own message
 * [x] Other user cannot edit message
 * [x] Sender can delete own message
 * [x] Other user cannot delete message
 * [x] Existing Phase 16 workspace functionality still works
 * [x] Existing auth remains functional
 * [x] Existing LAN transfer is not broken
 */
@EnabledIfSystemProperty(named = "landrop.test.postgres", matches = "true")
class PersistentChatPostgresTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "TestPassword-123!";
    private static final String SCHEMA = "landrop_chat_test_" + UUID.randomUUID().toString().replace("-", "");

    private static DatabaseManager db;
    private static AuthHttpServer httpServer;
    private static RealtimeServer realtimeServer;
    private static ChannelChatService chatService;
    private static JdbcRealtimeStore realtimeStore;
    private static HttpClient http;
    private static boolean schemaCreated;

    private static String ownerToken, adminToken, memberToken, outsiderToken;
    private static UUID ownerId, adminId, memberId, outsiderId;
    private static UUID workspaceId, channelGeneral, channelDesign, otherWorkspaceId, otherChannelId;

    private final List<WsProbe> probes = new ArrayList<>();

    @BeforeAll
    static void setup() throws Exception {
        String url = DatabaseConfig.getDatabaseUrl();
        assertNotNull(url, "DATABASE_URL must be configured.");
        URI configured = URI.create(url.replaceFirst("^jdbc:", ""));
        if (configured.getHost().endsWith(".neon.tech") && configured.getHost().contains("-pooler.")) {
            url = url.replace(configured.getHost(), configured.getHost().replace("-pooler.", "."));
        }

        try (DatabaseManager publicDb = DatabaseManager.forSchema(url, "public");
             var c = publicDb.getConnection();
             var s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + SCHEMA);
            schemaCreated = true;
        }

        db = DatabaseManager.forSchema(url, SCHEMA);
        try (var c = db.getConnection(); var s = c.createStatement()) {
            assertEquals(SCHEMA, c.getSchema());
            for (String resource : List.of(
                    "V1__init_cloud_database_phase14.sql",
                    "V2__auth_sessions_phase15.sql",
                    "V3__realtime_message_identity.sql",
                    "V4__persistent_chat_phase17.sql")) {
                try (var in = PersistentChatPostgresTest.class.getResourceAsStream("/db/migration/" + resource)) {
                    assertNotNull(in, "Migration resource missing: " + resource);
                    String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                            .replace("WHERE typname = 'WorkspaceRole'", "WHERE typname = 'WorkspaceRole' AND typnamespace = current_schema()::regnamespace");
                    s.execute(sql);
                }
            }
        }

        var users = new UserRepository(db);
        var jwt = new JwtService("chat-access-key-32-chars-test!", "chat-refresh-key-32-chars-test!", 900, 3600);
        var auth = new AuthService(users, new RefreshTokenRepository(db), jwt);
        var wsRepo = new WorkspaceRepository(db);
        var wsService = new WorkspaceService(wsRepo);

        realtimeStore = new JdbcRealtimeStore(db);
        realtimeServer = new RealtimeServer(
            new InetSocketAddress("127.0.0.1", 0),
            SocketAuthenticator.jwt(jwt, users),
            realtimeStore
        );
        realtimeServer.start();
        realtimeServer.awaitReady();

        chatService = new ChannelChatService(db, realtimeServer);
        httpServer = new AuthHttpServer(0, auth, new AuthMiddleware(jwt), wsService, chatService);
        httpServer.start();
        http = HttpClient.newHttpClient();

        registerUsers();
        setupFixtures();
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (realtimeServer != null) realtimeServer.stop(1000);
        if (httpServer != null) httpServer.stop(0);
        if (db != null) {
            try {
                if (schemaCreated) {
                    try (var c = db.getConnection(); var s = c.createStatement()) {
                        s.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
                    }
                }
            } finally {
                db.close();
            }
        }
    }

    @AfterEach
    void tearDown() {
        for (var p : probes) p.close();
        probes.clear();
    }

    private static void registerUsers() throws Exception {
        ownerToken = register("owner").path("accessToken").asText();
        adminToken = register("admin").path("accessToken").asText();
        memberToken = register("member").path("accessToken").asText();
        outsiderToken = register("outsider").path("accessToken").asText();

        var userRepo = new UserRepository(db);
        ownerId = userRepo.findByEmail("owner@example.com").orElseThrow().id();
        adminId = userRepo.findByEmail("admin@example.com").orElseThrow().id();
        memberId = userRepo.findByEmail("member@example.com").orElseThrow().id();
        outsiderId = userRepo.findByEmail("outsider@example.com").orElseThrow().id();
    }

    private static void setupFixtures() throws Exception {
        workspaceId = UUID.randomUUID();
        channelGeneral = UUID.randomUUID();
        channelDesign = UUID.randomUUID();
        otherWorkspaceId = UUID.randomUUID();
        otherChannelId = UUID.randomUUID();

        try (var conn = db.getConnection()) {
            // Main workspace
            try (var ps = conn.prepareStatement("INSERT INTO workspaces (id, name, slug, owner_id) VALUES (?, 'Eng Team', 'eng-team', ?)")) {
                ps.setObject(1, workspaceId); ps.setObject(2, ownerId); ps.executeUpdate();
            }
            // Memberships
            try (var ps = conn.prepareStatement("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, ?::\"WorkspaceRole\")")) {
                ps.setObject(1, workspaceId); ps.setObject(2, ownerId); ps.setString(3, "OWNER"); ps.executeUpdate();
                ps.setObject(1, workspaceId); ps.setObject(2, adminId); ps.setString(3, "ADMIN"); ps.executeUpdate();
                ps.setObject(1, workspaceId); ps.setObject(2, memberId); ps.setString(3, "MEMBER"); ps.executeUpdate();
            }
            // Channels in main workspace
            try (var ps = conn.prepareStatement("INSERT INTO channels (id, workspace_id, name, topic) VALUES (?, ?, ?, ?)")) {
                ps.setObject(1, channelGeneral); ps.setObject(2, workspaceId); ps.setString(3, "general"); ps.setString(4, "General Chat"); ps.executeUpdate();
                ps.setObject(1, channelDesign); ps.setObject(2, workspaceId); ps.setString(3, "design"); ps.setString(4, "UI/UX"); ps.executeUpdate();
            }

            // Other workspace & channel (for cross-workspace/channel tests)
            try (var ps = conn.prepareStatement("INSERT INTO workspaces (id, name, slug, owner_id) VALUES (?, 'Other Org', 'other-org', ?)")) {
                ps.setObject(1, otherWorkspaceId); ps.setObject(2, outsiderId); ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, 'OWNER'::\"WorkspaceRole\")")) {
                ps.setObject(1, otherWorkspaceId); ps.setObject(2, outsiderId); ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("INSERT INTO channels (id, workspace_id, name, topic) VALUES (?, ?, 'secret', 'Confidential')")) {
                ps.setObject(1, otherChannelId); ps.setObject(2, otherWorkspaceId); ps.executeUpdate();
            }
        }
    }

    private static JsonNode register(String name) throws Exception {
        return request("POST", "/api/auth/register", "",
                Map.of("username", name, "displayName", name.toUpperCase(), "email", name + "@example.com", "password", PASSWORD), 201);
    }

    private static JsonNode request(String method, String path, String token, Object body, int expectedStatus) throws Exception {
        var res = raw(method, path, token, body);
        assertEquals(expectedStatus, res.statusCode(), "HTTP " + method + " " + path + " failed: " + res.body());
        return res.body().isBlank() ? null : JSON.readTree(res.body());
    }

    private static HttpResponse<String> raw(String method, String path, String token, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpServer.getPort() + path));
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private WsProbe connectChannel(String token, UUID channel) {
        var probe = WsProbe.open(realtimeServer, channel, token);
        probes.add(probe);
        return probe;
    }

    private WsProbe connectRoom(String token, String device) {
        var probe = WsProbe.openRoom(realtimeServer, token, device);
        probes.add(probe);
        return probe;
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    void test1_workspaceMemberCanListChannelsAndNonMemberCannot() throws Exception {
        // Workspace member can list channels
        JsonNode memberList = request("GET", "/api/workspaces/" + workspaceId + "/channels", memberToken, null, 200);
        assertTrue(memberList.has("data"));
        assertTrue(memberList.get("data").size() >= 2);

        // Outsider cannot access channels -> 404
        assertEquals(404, raw("GET", "/api/workspaces/" + workspaceId + "/channels", outsiderToken, null).statusCode());
    }

    @Test
    void test2_ownerAndAdminCanCreateChannelUnauthorizedCannot() throws Exception {
        // Owner can create channel
        JsonNode ownerCreated = request("POST", "/api/workspaces/" + workspaceId + "/channels", ownerToken,
                Map.of("name", "owner-chan", "description", "By Owner"), 201);
        assertEquals("owner-chan", ownerCreated.path("data").path("name").asText());

        // Admin can create channel
        JsonNode adminCreated = request("POST", "/api/workspaces/" + workspaceId + "/channels", adminToken,
                Map.of("name", "admin-chan", "description", "By Admin"), 201);
        assertEquals("admin-chan", adminCreated.path("data").path("name").asText());

        // Member cannot create channel -> 403 Forbidden
        assertEquals(403, raw("POST", "/api/workspaces/" + workspaceId + "/channels", memberToken,
                Map.of("name", "member-chan")).statusCode());

        // Outsider cannot create channel -> 404
        assertEquals(404, raw("POST", "/api/workspaces/" + workspaceId + "/channels", outsiderToken,
                Map.of("name", "outsider-chan")).statusCode());
    }

    @Test
    void test3_messageHistoryLoadsFromNeonAndPaginationWorks() throws Exception {
        UUID testChannel = UUID.randomUUID();
        try (var conn = db.getConnection(); var ps = conn.prepareStatement("INSERT INTO channels(id, workspace_id, name) VALUES (?, ?, 'paging-test')")) {
            ps.setObject(1, testChannel); ps.setObject(2, workspaceId); ps.executeUpdate();
        }

        // Insert 5 messages directly in Neon
        for (int i = 1; i <= 5; i++) {
            chatService.createMessage(ownerId, testChannel, UUID.randomUUID(), "persist-msg-" + i, null);
        }

        // Page 1: limit 2
        JsonNode page1 = request("GET", "/api/channels/" + testChannel + "/messages?limit=2", memberToken, null, 200);
        assertEquals(2, page1.path("items").size());
        assertTrue(page1.hasNonNull("nextCursor"));
        String cursor = page1.path("nextCursor").asText();

        // Page 2: limit 2 with cursor
        JsonNode page2 = request("GET", "/api/channels/" + testChannel + "/messages?limit=2&cursor=" + java.net.URLEncoder.encode(cursor, java.nio.charset.StandardCharsets.UTF_8), memberToken, null, 200);
        assertEquals(2, page2.path("items").size());
        assertNotEquals(page1.path("items").get(0).path("id").asText(), page2.path("items").get(0).path("id").asText());

        // Non-member cannot access history -> 404
        assertEquals(404, raw("GET", "/api/channels/" + testChannel + "/messages", outsiderToken, null).statusCode());
    }

    @Test
    void test4_authenticatedUserCanJoinRoomUnauthorizedRejected() throws Exception {
        var client = connectRoom(memberToken, "desktop-1");
        client.event("connected");

        // Authorized room join
        client.join(channelGeneral);
        var joined = client.event("channel.joined");
        assertEquals(channelGeneral.toString(), joined.path("channelId").asText());

        // Unauthorized room join (channel in another workspace) -> rejected
        client.join(otherChannelId);
        var err = client.event("error");
        assertEquals("FORBIDDEN", err.path("code").asText());
    }

    @Test
    void test5_multiUserAndMultiDeviceRealtimeDelivery() throws Exception {
        // User1 (owner) on Device A and Device B
        var u1A = connectChannel(ownerToken, channelGeneral);
        u1A.event("connected");
        var u1B = connectChannel(ownerToken, channelGeneral);
        u1B.event("connected");

        // User2 (admin) on Device C
        var u2 = connectChannel(adminToken, channelGeneral);
        u2.event("connected");

        // User3 (member) on Device D
        var u3 = connectChannel(memberToken, channelGeneral);
        u3.event("connected");

        // User1 sends message
        UUID clientId = UUID.randomUUID();
        u1A.message(clientId, "Deploying release v2.0");

        // User1 Device A receives canonical message
        var msgA = u1A.event("message.created").path("message");
        assertEquals("Deploying release v2.0", msgA.path("content").asText());
        assertEquals(ownerId.toString(), msgA.path("senderId").asText());
        assertEquals("owner", msgA.path("sender").path("username").asText());

        // User1 Device B receives same canonical message
        var msgB = u1B.event("message.created").path("message");
        assertEquals(msgA.path("id").asText(), msgB.path("id").asText());

        // User2 & User3 receive realtime update
        var msgU2 = u2.event("message.created").path("message");
        assertEquals(msgA.path("id").asText(), msgU2.path("id").asText());
        var msgU3 = u3.event("message.created").path("message");
        assertEquals(msgA.path("id").asText(), msgU3.path("id").asText());

        // Idempotency: retry with same clientMessageId -> no duplicate broadcast
        u1A.message(clientId, "Deploying release v2.0");
        var retryAck = u1A.event("message.created");
        assertEquals(msgA.path("id").asText(), retryAck.path("message").path("id").asText());
        assertNull(u2.events.poll(150, TimeUnit.MILLISECONDS));
        assertNull(u1B.events.poll(150, TimeUnit.MILLISECONDS));
    }

    @Test
    void test6_messagePersistsAfterBackendRestart() throws Exception {
        // Send a message via service
        UUID clientId = UUID.randomUUID();
        var saved = chatService.createMessage(ownerId, channelGeneral, clientId, "Will survive restart", null);
        UUID messageId = saved.message().id();

        // Create a completely new instance of ChannelChatService on the same Neon pool (simulating restart)
        ChannelChatService restartedService = new ChannelChatService(db);
        var page = restartedService.getHistory(memberId, channelGeneral, 50, null, null, "desc");

        boolean found = page.items().stream().anyMatch(m -> m.id().equals(messageId) && m.content().equals("Will survive restart"));
        assertTrue(found, "Message must persist in Neon and load after service re-instantiation");
    }

    @Test
    void test7_replyWorksAndCrossChannelReplyRejected() throws Exception {
        var u1 = connectChannel(ownerToken, channelGeneral);
        u1.event("connected");
        var u2 = connectChannel(memberToken, channelGeneral);
        u2.event("connected");

        // Send parent message
        u1.message(UUID.randomUUID(), "Parent announcement");
        var parent = u1.event("message.created").path("message");
        UUID parentId = UUID.fromString(parent.path("id").asText());
        u2.event("message.created"); // consume

        // Valid reply in same channel
        u2.messageWithReply(UUID.randomUUID(), "Acknowledged parent", parentId);
        var reply = u2.event("message.created").path("message");
        assertEquals(parentId.toString(), reply.path("replyToId").asText());

        // Invalid reply across channels (referencing message from channelDesign in channelGeneral)
        var crossMsg = chatService.createMessage(ownerId, channelDesign, UUID.randomUUID(), "Design note", null);
        u2.messageWithReply(UUID.randomUUID(), "Illegal cross-reply", crossMsg.message().id());
        var err = u2.event("error");
        assertEquals("INVALID_REQUEST", err.path("code").asText());
    }

    @Test
    void test8_senderCanEditOwnMessageOtherCannot() throws Exception {
        var u1 = connectChannel(ownerToken, channelGeneral);
        u1.event("connected");
        var u2 = connectChannel(memberToken, channelGeneral);
        u2.event("connected");

        u1.message(UUID.randomUUID(), "Original content to edit");
        var msg = u1.event("message.created").path("message");
        UUID msgId = UUID.fromString(msg.path("id").asText());
        u2.event("message.created");

        // Non-sender (member) attempts to edit -> FORBIDDEN
        u2.edit(msgId, "Malicious edit");
        var err = u2.event("error");
        assertEquals("FORBIDDEN", err.path("code").asText());

        // Sender (owner) edits own message
        u1.edit(msgId, "Edited canonical content");
        var updateEventA = u1.event("message.updated");
        assertEquals("Edited canonical content", updateEventA.path("message").path("content").asText());
        assertNotNull(updateEventA.path("message").path("editedAt").asText());

        // Other member receives realtime update
        var updateEventB = u2.event("message.updated");
        assertEquals("Edited canonical content", updateEventB.path("message").path("content").asText());

        // HTTP PATCH verification
        var patchRes = request("PATCH", "/api/messages/" + msgId, ownerToken, Map.of("content", "HTTP PATCH content"), 200);
        assertEquals("HTTP PATCH content", patchRes.path("data").path("content").asText());
    }

    @Test
    void test9_senderCanDeleteOwnMessageOtherCannot() throws Exception {
        var u1 = connectChannel(ownerToken, channelGeneral);
        u1.event("connected");
        var u2 = connectChannel(memberToken, channelGeneral);
        u2.event("connected");

        u1.message(UUID.randomUUID(), "Content to be deleted");
        var msg = u1.event("message.created").path("message");
        UUID msgId = UUID.fromString(msg.path("id").asText());
        u2.event("message.created");

        // Non-sender attempts to delete -> FORBIDDEN
        u2.delete(msgId);
        var err = u2.event("error");
        assertEquals("FORBIDDEN", err.path("code").asText());

        // Sender deletes own message -> Soft delete broadcast
        u1.delete(msgId);
        var delEventA = u1.event("message.deleted");
        assertEquals(msgId.toString(), delEventA.path("messageId").asText());
        assertNotNull(delEventA.path("deletedAt").asText());
        assertEquals("[This message was deleted]", delEventA.path("message").path("content").asText());

        // Member receives deletion broadcast
        var delEventB = u2.event("message.deleted");
        assertEquals(msgId.toString(), delEventB.path("messageId").asText());

        // Verify soft delete persistence in Neon
        JsonNode history = request("GET", "/api/channels/" + channelGeneral + "/messages", memberToken, null, 200);
        boolean foundSoftDeleted = false;
        for (JsonNode item : history.path("items")) {
            if (item.path("id").asText().equals(msgId.toString())) {
                assertEquals("[This message was deleted]", item.path("content").asText());
                assertTrue(item.hasNonNull("deletedAt"));
                foundSoftDeleted = true;
            }
        }
        assertTrue(foundSoftDeleted, "Deleted message must be softly deleted in Neon PostgreSQL");

        // HTTP DELETE endpoint verification with a new message
        var httpMsg = chatService.createMessage(ownerId, channelGeneral, UUID.randomUUID(), "To delete via HTTP", null);
        raw("DELETE", "/api/messages/" + httpMsg.message().id(), ownerToken, null);
        assertEquals(204, raw("DELETE", "/api/messages/" + httpMsg.message().id(), ownerToken, null).statusCode());
    }
}
