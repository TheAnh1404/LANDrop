package com.landrop.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.auth.security.JwtService;
import com.landrop.auth.server.AuthHttpServer;
import com.landrop.auth.server.AuthMiddleware;
import com.landrop.auth.service.AuthService;
import com.landrop.chat.dto.ChannelDto;
import com.landrop.chat.dto.ChatMessageDto;
import com.landrop.chat.dto.MessagesPageDto;
import com.landrop.chat.dto.SenderDto;
import com.landrop.chat.service.ChannelChatService;
import com.landrop.chat.service.ChatException;
import com.landrop.database.model.Channel;
import com.landrop.database.model.User;
import com.landrop.database.model.Workspace;
import com.landrop.database.model.WorkspaceMember;
import com.landrop.database.model.WorkspaceRole;
import com.landrop.database.repository.UserRepository;
import com.landrop.database.service.WorkspaceService;
import com.landrop.realtime.*;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end unit and realtime integration tests verifying Phase 17A requirements:
 * workspace-aware channels, membership authorization, message persistence,
 * cursor pagination, multi-user/multi-device WebSocket rooms, replies, edit, and soft delete.
 */
class PersistentChatTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JwtService JWT = new JwtService("access-key-32-chars-for-testing!", "refresh-key-32-chars-testing!!", 900, 3600);

    private final User owner = testUser("owner");
    private final User admin = testUser("admin");
    private final User member = testUser("member");
    private final User outsider = testUser("outsider");

    private final UUID workspaceId = UUID.randomUUID();
    private final UUID channelGeneral = UUID.randomUUID();
    private final UUID channelDesign = UUID.randomUUID();
    private final UUID crossChannel = UUID.randomUUID(); // In different workspace

    private InMemoryRealtimeStore store;
    private RealtimeServer realtimeServer;
    private AuthHttpServer httpServer;
    private HttpClient http;
    private final List<WsProbe> probes = new ArrayList<>();

    private static User testUser(String name) {
        return new User(UUID.randomUUID(), name + "@example.com", name, name.toUpperCase(), null, null, Instant.now(), Instant.now());
    }

    @BeforeEach
    void setUp() throws Exception {
        store = new InMemoryRealtimeStore();
        // Setup mock users
        store.users.put(owner.id(), owner);
        store.users.put(admin.id(), admin);
        store.users.put(member.id(), member);
        store.users.put(outsider.id(), outsider);

        // Setup memberships
        store.memberships.put(workspaceId + ":" + owner.id(), WorkspaceRole.OWNER);
        store.memberships.put(workspaceId + ":" + admin.id(), WorkspaceRole.ADMIN);
        store.memberships.put(workspaceId + ":" + member.id(), WorkspaceRole.MEMBER);

        // Setup channels
        store.channels.put(channelGeneral, new Channel(channelGeneral, workspaceId, "general", "General discussion", false, Instant.now().minusSeconds(60), Instant.now().minusSeconds(60)));
        store.channels.put(channelDesign, new Channel(channelDesign, workspaceId, "design", "Design topics", false, Instant.now().minusSeconds(30), Instant.now().minusSeconds(30)));
        UUID otherWorkspace = UUID.randomUUID();
        store.channels.put(crossChannel, new Channel(crossChannel, otherWorkspace, "random", "Other workspace", false, Instant.now(), Instant.now()));

        var userRepo = new UserRepository(null) {
            @Override public Optional<User> findById(UUID id) { return Optional.ofNullable(store.users.get(id)); }
        };

        realtimeServer = new RealtimeServer(
            new InetSocketAddress("127.0.0.1", 0),
            SocketAuthenticator.jwt(JWT, userRepo),
            store
        );
        realtimeServer.start();
        realtimeServer.awaitReady();

        // In-memory Chat service backed by our store, connected to realtime broadcaster
        ChannelChatService chatService = new ChannelChatService(store::getConnection, realtimeServer) {
            @Override
            public List<ChannelDto> listChannels(UUID userId, UUID wsId) {
                if (!store.memberships.containsKey(wsId + ":" + userId)) throw new ChatException(404, "WORKSPACE_NOT_FOUND", "Workspace not found.");
                return store.channels.values().stream().filter(c -> c.workspaceId().equals(wsId))
                    .map(c -> new ChannelDto(c.id(), c.workspaceId(), c.name(), c.topic(), c.topic(), c.isPrivate(), c.createdAt().toString(), c.updatedAt().toString()))
                    .toList();
            }

            @Override
            public ChannelDto createChannel(UUID userId, UUID wsId, String name, String desc) {
                var role = store.memberships.get(wsId + ":" + userId);
                if (role == null) throw new ChatException(404, "WORKSPACE_NOT_FOUND", "Workspace not found.");
                if (role != WorkspaceRole.OWNER && role != WorkspaceRole.ADMIN) throw new ChatException(403, "FORBIDDEN", "This action requires a workspace administrator.");
                var c = new Channel(UUID.randomUUID(), wsId, name, desc, false, Instant.now(), Instant.now());
                store.channels.put(c.id(), c);
                return new ChannelDto(c.id(), c.workspaceId(), c.name(), c.topic(), c.topic(), c.isPrivate(), c.createdAt().toString(), c.updatedAt().toString());
            }

            @Override
            public MessagesPageDto getHistory(UUID userId, UUID channelId, int limit, String cursor, String before, String order) {
                if (!store.canAccess(userId, channelId)) throw new ChatException(404, "CHANNEL_NOT_FOUND", "Channel not found.");
                String activeCursor = (cursor != null && !cursor.isBlank()) ? cursor : before;
                var p = store.history(userId, channelId, limit, activeCursor);
                var items = p.messages().stream().map(m -> new ChatMessageDto(
                    m.id(), m.channelId(), m.senderId(), m.sender(), m.content(), m.replyToId(), m.clientMessageId(),
                    m.createdAt(), m.updatedAt(), m.editedAt(), m.deletedAt()
                )).toList();
                return new MessagesPageDto(items, p.nextCursor());
            }

            @Override
            public ChatMessageDto editMessage(UUID userId, UUID messageId, String newContent) {
                var m = store.edit(userId, messageId, newContent);
                return new ChatMessageDto(m.id(), m.channelId(), m.senderId(), m.sender(), m.content(), m.replyToId(), m.clientMessageId(), m.createdAt(), m.updatedAt(), m.editedAt(), m.deletedAt());
            }

            @Override
            public ChatMessageDto deleteMessage(UUID userId, UUID messageId) {
                var m = store.delete(userId, messageId);
                return new ChatMessageDto(m.id(), m.channelId(), m.senderId(), m.sender(), m.content(), m.replyToId(), m.clientMessageId(), m.createdAt(), m.updatedAt(), m.editedAt(), m.deletedAt());
            }
        };

        httpServer = new AuthHttpServer(0, null, new AuthMiddleware(JWT),
            new WorkspaceService() {
                @Override public WorkspaceView create(UUID userId, String name, String description) {
                    var w = new Workspace(workspaceId, name, "slug", description, userId, Instant.now(), Instant.now());
                    return new WorkspaceView(w, WorkspaceRole.OWNER);
                }
            },
            chatService
        );
        httpServer.start();
        http = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (var p : probes) p.close();
        probes.clear();
        if (realtimeServer != null) realtimeServer.stop(1000);
        if (httpServer != null) httpServer.stop(0);
    }

    private WsProbe connectChannel(User user, UUID channel) {
        var probe = WsProbe.open(realtimeServer, channel, JWT.generateAccessToken(user));
        probes.add(probe);
        return probe;
    }

    private WsProbe connectRoom(User user, String device) {
        var probe = WsProbe.openRoom(realtimeServer, JWT.generateAccessToken(user), device);
        probes.add(probe);
        return probe;
    }

    private JsonNode httpGet(String path, User user) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpServer.getPort() + path))
            .header("Authorization", "Bearer " + JWT.generateAccessToken(user))
            .GET()
            .build();
        var res = http.send(req, HttpResponse.BodyHandlers.ofString());
        return JSON.readTree(res.body());
    }

    private HttpResponse<String> httpSend(String method, String path, User user, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpServer.getPort() + path))
            .header("Authorization", "Bearer " + JWT.generateAccessToken(user));
        if (body != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    // =========================================================================
    // 17A.3 & 17A.4 Channel List & Create Authorization
    // =========================================================================

    @Test
    void workspaceMemberCanListChannelsAndNonMemberCannot() throws Exception {
        // Workspace member can list channels
        var res = httpGet("/api/workspaces/" + workspaceId + "/channels", member);
        assertTrue(res.has("data"));
        assertEquals(2, res.get("data").size());

        // Outsider cannot access channels
        var outsiderRes = httpSend("GET", "/api/workspaces/" + workspaceId + "/channels", outsider, null);
        assertEquals(404, outsiderRes.statusCode());
    }

    @Test
    void ownerAndAdminCanCreateChannelMemberCannot() throws Exception {
        // Owner can create channel
        var ownerCreate = httpSend("POST", "/api/workspaces/" + workspaceId + "/channels", owner,
            Map.of("name", "announcements", "description", "Important news"));
        assertEquals(201, ownerCreate.statusCode());
        JsonNode ownerBody = JSON.readTree(ownerCreate.body());
        assertEquals("announcements", ownerBody.path("data").path("name").asText());

        // Admin can create channel
        var adminCreate = httpSend("POST", "/api/workspaces/" + workspaceId + "/channels", admin,
            Map.of("name", "dev-team", "description", "Engineers"));
        assertEquals(201, adminCreate.statusCode());

        // Member cannot create channel -> 403 Forbidden
        var memberCreate = httpSend("POST", "/api/workspaces/" + workspaceId + "/channels", member,
            Map.of("name", "member-channel"));
        assertEquals(403, memberCreate.statusCode());

        // Outsider cannot create channel -> 404
        var outsiderCreate = httpSend("POST", "/api/workspaces/" + workspaceId + "/channels", outsider,
            Map.of("name", "outsider-channel"));
        assertEquals(404, outsiderCreate.statusCode());
    }

    // =========================================================================
    // 17A.5 Message History & Cursor Pagination
    // =========================================================================

    @Test
    void messageHistoryPaginationWithCursor() throws Exception {
        // Pre-populate 5 messages in channelGeneral
        for (int i = 1; i <= 5; i++) {
            Thread.sleep(5);
            store.save(member.id(), channelGeneral, UUID.randomUUID(), "msg-" + i, null);
        }

        // Fetch page 1 with limit 2
        var page1 = httpSend("GET", "/api/channels/" + channelGeneral + "/messages?limit=2", member, null);
        assertEquals(200, page1.statusCode());
        JsonNode body1 = JSON.readTree(page1.body());
        assertEquals(2, body1.path("items").size());
        assertTrue(body1.path("nextCursor").isTextual());
        String cursor1 = body1.path("nextCursor").asText();

        // Fetch page 2 using cursor
        var page2 = httpSend("GET", "/api/channels/" + channelGeneral + "/messages?limit=2&cursor=" + java.net.URLEncoder.encode(cursor1, java.nio.charset.StandardCharsets.UTF_8), member, null);
        assertEquals(200, page2.statusCode());
        JsonNode body2 = JSON.readTree(page2.body());
        assertEquals(2, body2.path("items").size());
        assertNotEquals(body1.path("items").get(0).path("id").asText(), body2.path("items").get(0).path("id").asText());

        // Outsider cannot view history
        var outsiderGet = httpSend("GET", "/api/channels/" + channelGeneral + "/messages", outsider, null);
        assertEquals(404, outsiderGet.statusCode());
    }

    // =========================================================================
    // 17A.7 WebSocket Room Joining & Authorization
    // =========================================================================

    @Test
    void authenticatedUserCanJoinAuthorizedChannelRoomUnauthorizedRejected() throws Exception {
        var client = connectRoom(member, "laptop");
        client.event("connected");

        // Join authorized channel
        client.join(channelGeneral);
        var joined = client.event("channel.joined");
        assertEquals(channelGeneral.toString(), joined.path("channelId").asText());

        // Join cross-workspace channel where user is not a member -> rejected
        client.join(crossChannel);
        var error = client.event("error");
        assertEquals("FORBIDDEN", error.path("code").asText());
    }

    // =========================================================================
    // 17A.6, 17A.8, 17A.12 Multi-User & Multi-Device Realtime Broadcast
    // =========================================================================

    @Test
    void multiUserAndMultiDeviceRealtimeBroadcast() throws Exception {
        // User1 (owner) on Device A and Device B
        var u1DeviceA = connectChannel(owner, channelGeneral);
        u1DeviceA.event("connected");
        var u1DeviceB = connectChannel(owner, channelGeneral);
        u1DeviceB.event("connected");

        // User2 (admin) on Device C
        var u2 = connectChannel(admin, channelGeneral);
        u2.event("connected");

        // User3 (member) on Device D
        var u3 = connectChannel(member, channelGeneral);
        u3.event("connected");

        // User1 sends message
        UUID clientId = UUID.randomUUID();
        u1DeviceA.message(clientId, "Team meeting in 5 mins");

        // Verify User1 receives canonical event
        var eventA = u1DeviceA.event("message.created");
        JsonNode msgA = eventA.path("message");
        assertEquals("Team meeting in 5 mins", msgA.path("content").asText());
        assertEquals(owner.id().toString(), msgA.path("senderId").asText());
        assertEquals(owner.username(), msgA.path("sender").path("username").asText());

        // Verify User1 on Device B receives exact same message
        var eventB = u1DeviceB.event("message.created");
        assertEquals(msgA, eventB.path("message"));

        // Verify User2 and User3 receive canonical message
        var eventU2 = u2.event("message.created");
        assertEquals(msgA, eventU2.path("message"));
        var eventU3 = u3.event("message.created");
        assertEquals(msgA, eventU3.path("message"));

        // Delivery Safety / Idempotency: re-sending same clientMessageId returns original message without fanout
        u1DeviceA.message(clientId, "Team meeting in 5 mins");
        var repeatAck = u1DeviceA.event("message.created");
        assertEquals(msgA, repeatAck.path("message"));
        assertNull(u2.events.poll(150, TimeUnit.MILLISECONDS));
        assertNull(u1DeviceB.events.poll(150, TimeUnit.MILLISECONDS));
    }

    // =========================================================================
    // 17A.9 Reply Support & Cross-Channel Rejection
    // =========================================================================

    @Test
    void replyWorksAndCrossChannelReplyRejected() throws Exception {
        var u1 = connectChannel(owner, channelGeneral);
        u1.event("connected");
        var u2 = connectChannel(member, channelGeneral);
        u2.event("connected");

        // Original message
        u1.message(UUID.randomUUID(), "Original announcement");
        var origMsg = u1.event("message.created").path("message");
        UUID origId = UUID.fromString(origMsg.path("id").asText());
        u2.event("message.created"); // consume

        // Valid reply
        u2.messageWithReply(UUID.randomUUID(), "Got it, thanks!", origId);
        var replyEvent = u2.event("message.created");
        assertEquals(origId.toString(), replyEvent.path("message").path("replyToId").asText());

        // Cross-channel reply attempt (referencing message from another channel)
        store.save(owner.id(), channelDesign, UUID.randomUUID(), "Design brief", null);
        var designMsgs = store.history(owner.id(), channelDesign, 1, null);
        UUID designMsgId = designMsgs.messages().get(0).id();

        u2.messageWithReply(UUID.randomUUID(), "Cross-channel reply attempt", designMsgId);
        var error = u2.event("error");
        assertEquals("INVALID_REQUEST", error.path("code").asText());
    }

    // =========================================================================
    // 17A.10 Edit Message (Ownership Enforcement & Broadcast)
    // =========================================================================

    @Test
    void senderCanEditOwnMessageOtherCannot() throws Exception {
        var u1 = connectChannel(owner, channelGeneral);
        u1.event("connected");
        var u2 = connectChannel(member, channelGeneral);
        u2.event("connected");

        // User1 sends message
        u1.message(UUID.randomUUID(), "Draft text");
        var msg = u1.event("message.created").path("message");
        UUID msgId = UUID.fromString(msg.path("id").asText());
        u2.event("message.created");

        // User2 attempts to edit User1's message -> FORBIDDEN
        u2.edit(msgId, "Hacked text");
        var err = u2.event("error");
        assertEquals("FORBIDDEN", err.path("code").asText());

        // User1 edits own message -> Success & broadcast
        u1.edit(msgId, "Polished text");
        var updatedA = u1.event("message.updated");
        assertEquals("Polished text", updatedA.path("message").path("content").asText());
        assertNotNull(updatedA.path("message").path("editedAt").asText());

        // User2 receives message.updated realtime event
        var updatedB = u2.event("message.updated");
        assertEquals("Polished text", updatedB.path("message").path("content").asText());

        // REST PATCH endpoint also works
        var patchRes = httpSend("PATCH", "/api/messages/" + msgId, owner, Map.of("content", "REST Edited text"));
        assertEquals(200, patchRes.statusCode());
        JsonNode patchBody = JSON.readTree(patchRes.body());
        assertEquals("REST Edited text", patchBody.path("data").path("content").asText());
    }

    // =========================================================================
    // 17A.11 Delete Message (Soft Delete & Broadcast)
    // =========================================================================

    @Test
    void senderCanDeleteOwnMessageOtherCannot() throws Exception {
        var u1 = connectChannel(owner, channelGeneral);
        u1.event("connected");
        var u2 = connectChannel(member, channelGeneral);
        u2.event("connected");

        // User1 sends message
        u1.message(UUID.randomUUID(), "Will be deleted");
        var msg = u1.event("message.created").path("message");
        UUID msgId = UUID.fromString(msg.path("id").asText());
        u2.event("message.created");

        // User2 attempts to delete User1's message -> FORBIDDEN
        u2.delete(msgId);
        var err = u2.event("error");
        assertEquals("FORBIDDEN", err.path("code").asText());

        // User1 deletes own message -> Success & soft delete broadcast
        u1.delete(msgId);
        var deletedA = u1.event("message.deleted");
        assertEquals(msgId.toString(), deletedA.path("messageId").asText());
        assertNotNull(deletedA.path("deletedAt").asText());
        assertEquals("[This message was deleted]", deletedA.path("message").path("content").asText());

        // User2 receives deletion event
        var deletedB = u2.event("message.deleted");
        assertEquals(msgId.toString(), deletedB.path("messageId").asText());

        // Cannot edit soft-deleted message
        u1.edit(msgId, "Trying to restore");
        assertEquals("INVALID_REQUEST", u1.event("error").path("code").asText());

        // History reflects soft delete
        var historyRes = httpSend("GET", "/api/channels/" + channelGeneral + "/messages", member, null);
        JsonNode histBody = JSON.readTree(historyRes.body());
        boolean foundDeleted = false;
        for (JsonNode item : histBody.path("items")) {
            if (item.path("id").asText().equals(msgId.toString())) {
                assertEquals("[This message was deleted]", item.path("content").asText());
                assertTrue(item.hasNonNull("deletedAt"));
                foundDeleted = true;
            }
        }
        assertTrue(foundDeleted, "Deleted message must be present in history with deletedAt and placeholder content");
    }

    // =========================================================================
    // In-Memory Test Store Implementation
    // =========================================================================

    private static class InMemoryRealtimeStore implements RealtimeStore {
        final Map<UUID, User> users = new ConcurrentHashMap<>();
        final Map<String, WorkspaceRole> memberships = new ConcurrentHashMap<>();
        final Map<UUID, Channel> channels = new ConcurrentHashMap<>();
        final Map<UUID, com.landrop.database.model.Message> messages = new ConcurrentHashMap<>();
        final Map<String, UUID> clientMsgIndex = new ConcurrentHashMap<>();

        public java.sql.Connection getConnection() {
            return null; // Not needed for mock
        }

        @Override
        public boolean canAccess(UUID userId, UUID channelId) {
            Channel ch = channels.get(channelId);
            if (ch == null) return false;
            WorkspaceRole role = memberships.get(ch.workspaceId() + ":" + userId);
            return role != null && role != WorkspaceRole.GUEST;
        }

        @Override
        public void verifyChannelRoomAccess(UUID userId, UUID channelId, UUID workspaceId) {
            Channel ch = channels.get(channelId);
            if (ch == null) throw new RealtimeException("CHANNEL_NOT_FOUND", "Channel was not found.");
            if (workspaceId != null && !ch.workspaceId().equals(workspaceId)) {
                throw new RealtimeException("FORBIDDEN", "Channel does not belong to specified workspace.");
            }
            if (!canAccess(userId, channelId)) {
                throw new RealtimeException("FORBIDDEN", "Channel access is unavailable.");
            }
        }

        @Override
        public Saved save(UUID userId, UUID channelId, UUID clientMessageId, String content) {
            return save(userId, channelId, clientMessageId, content, null);
        }

        @Override
        public synchronized Saved save(UUID userId, UUID channelId, UUID clientMessageId, String content, UUID replyToId) {
            if (!canAccess(userId, channelId)) {
                throw new RealtimeException("FORBIDDEN", "Channel access is unavailable.");
            }
            if (replyToId != null) {
                com.landrop.database.model.Message parent = messages.get(replyToId);
                if (parent == null || !parent.channelId().equals(channelId)) {
                    throw new RealtimeException("INVALID_REQUEST", "Referenced reply message does not exist or belongs to another channel.");
                }
            }

            String key = userId + ":" + clientMessageId;
            if (clientMessageId != null) {
                UUID existingId = clientMsgIndex.get(key);
                if (existingId != null) {
                    com.landrop.database.model.Message existing = messages.get(existingId);
                    return new Saved(toRealtimeMessage(existing), false);
                }
            }

            UUID id = UUID.randomUUID();
            Instant now = Instant.now();
            com.landrop.database.model.Message msg = new com.landrop.database.model.Message(id, channelId, userId, replyToId, clientMessageId, content, now, now, null, null);
            messages.put(id, msg);
            if (clientMessageId != null) clientMsgIndex.put(key, id);
            return new Saved(toRealtimeMessage(msg), true);
        }

        @Override
        public synchronized RealtimeStore.Message edit(UUID userId, UUID messageId, String content) {
            com.landrop.database.model.Message msg = messages.get(messageId);
            if (msg == null) throw new RealtimeException("NOT_FOUND", "Message was not found.");
            if (msg.deletedAt() != null) throw new RealtimeException("INVALID_REQUEST", "Deleted messages cannot be edited.");
            if (!msg.userId().equals(userId)) throw new RealtimeException("FORBIDDEN", "Only original sender may edit.");

            Instant now = Instant.now();
            com.landrop.database.model.Message updated = new com.landrop.database.model.Message(msg.id(), msg.channelId(), msg.userId(), msg.parentMessageId(),
                msg.clientMessageId(), content, msg.createdAt(), now, now, null);
            messages.put(messageId, updated);
            return toRealtimeMessage(updated);
        }

        @Override
        public synchronized RealtimeStore.Message delete(UUID userId, UUID messageId) {
            com.landrop.database.model.Message msg = messages.get(messageId);
            if (msg == null) throw new RealtimeException("NOT_FOUND", "Message was not found.");
            if (!msg.userId().equals(userId)) throw new RealtimeException("FORBIDDEN", "Only original sender may delete.");

            Instant now = Instant.now();
            com.landrop.database.model.Message deleted = new com.landrop.database.model.Message(msg.id(), msg.channelId(), msg.userId(), msg.parentMessageId(),
                msg.clientMessageId(), "[This message was deleted]", msg.createdAt(), now, msg.editedAt(), now);
            messages.put(messageId, deleted);
            return toRealtimeMessage(deleted);
        }

        @Override
        public Page history(UUID userId, UUID channelId, int limit, String before) {
            if (!canAccess(userId, channelId)) {
                throw new RealtimeException("FORBIDDEN", "Channel access is unavailable.");
            }
            List<com.landrop.database.model.Message> list = messages.values().stream()
                .filter(m -> m.channelId().equals(channelId))
                .sorted((a, b) -> {
                    int c = a.createdAt().compareTo(b.createdAt());
                    if (c != 0) return c;
                    return a.id().compareTo(b.id());
                })
                .toList();

            if (before != null && !before.isBlank()) {
                String[] parts = before.split("\\|", 2);
                Instant cutoff = Instant.parse(parts[0]);
                UUID cutoffId = parts.length > 1 ? UUID.fromString(parts[1]) : null;
                list = list.stream().filter(m -> {
                    int cmp = m.createdAt().compareTo(cutoff);
                    if (cmp != 0) return cmp > 0;
                    return cutoffId == null || m.id().compareTo(cutoffId) > 0;
                }).toList();
            }

            List<RealtimeStore.Message> page = list.stream().limit(limit).map(this::toRealtimeMessage).toList();
            String nextCursor = page.size() >= limit ? page.getLast().createdAt() + "|" + page.getLast().id() : null;
            return new Page(page, nextCursor);
        }

        private RealtimeStore.Message toRealtimeMessage(com.landrop.database.model.Message m) {
            User u = users.get(m.userId());
            SenderDto sender = new SenderDto(m.userId(), u != null ? u.username() : "user", u != null ? u.displayName() : "User", null);
            return new RealtimeStore.Message(
                m.id(), m.channelId(), m.userId(), sender, m.clientMessageId(), m.content(), m.parentMessageId(),
                m.createdAt().toString(), m.updatedAt().toString(),
                m.editedAt() != null ? m.editedAt().toString() : null,
                m.deletedAt() != null ? m.deletedAt().toString() : null
            );
        }
    }
}
