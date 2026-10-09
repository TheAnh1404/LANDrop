package com.landrop.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.landrop.auth.model.RefreshToken;
import com.landrop.auth.repository.RefreshTokenRepository;
import com.landrop.auth.security.JwtService;
import com.landrop.chat.service.ChannelChatService;
import com.landrop.database.model.User;
import com.landrop.database.repository.DeviceRepository;
import com.landrop.database.repository.UserRepository;
import com.landrop.server.clients.ClientRegistry;
import com.landrop.server.realtime.FoundationRealtimeServer;
import com.landrop.server.transfers.TransferCoordinator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/** Real sockets exercising the integrated gateway, with controlled auth/access stores. */
class Realtime01BrowserGatewayTest {
    private final User alice = new User("alice@example.test", "alice", "Alice", null);
    private final User bob = new User("bob@example.test", "bob", "Bob", null);
    private final UUID aliceSession = UUID.randomUUID();
    private final UUID bobSession = UUID.randomUUID();
    private final UUID room = UUID.randomUUID();
    private final UUID otherRoom = UUID.randomUUID();
    private final JwtService jwt = new JwtService("access-test-secret", "refresh-test-secret", 600, 3600);
    private final Map<UUID, RefreshToken> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, User> users = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> access = new ConcurrentHashMap<>();
    private final List<FoundationWsClient> clients = new ArrayList<>();
    private ClientRegistry registry;
    private FoundationRealtimeServer server;

    @BeforeEach
    void start() throws Exception {
        users.put(alice.id(), alice);
        users.put(bob.id(), bob);
        session(aliceSession, alice);
        session(bobSession, bob);
        access.put(room, ConcurrentHashMap.newKeySet());
        access.get(room).addAll(List.of(alice.id(), bob.id()));
        access.put(otherRoom, Set.of(alice.id()));
        startGateway(5000, 3000);
    }

    private void startGateway(long heartbeatTimeout, long authTimeout) throws Exception {
        registry = new ClientRegistry();
        server = new FoundationRealtimeServer(new InetSocketAddress("127.0.0.1", 0), registry,
                new TransferCoordinator(registry), 50, heartbeatTimeout, Set.of("http://localhost:3000"), authTimeout);
        server.setAuthDependencies(jwt, new UserRepository(null) {
            @Override public Optional<User> findById(UUID id) { return Optional.ofNullable(users.get(id)); }
        }, new DeviceRepository(null), new RefreshTokenRepository(null) {
            @Override public Optional<RefreshToken> findById(UUID id) { return Optional.ofNullable(sessions.get(id)); }
            @Override public boolean updateLastSeen(UUID id, Instant seenAt) { return sessions.containsKey(id); }
        });
        server.setChannelChatService(new ChannelChatService(() -> { throw new SQLException("Not a persistence test"); }, null) {
            @Override public boolean canAccess(UUID user, UUID channel) {
                return access.getOrDefault(channel, Set.of()).contains(user);
            }
        });
        server.start();
        server.awaitReady();
    }

    private void session(UUID id, User user) {
        Instant now = Instant.now();
        sessions.put(id, new RefreshToken(id, user.id(), id.toString(), null, now.plusSeconds(3600),
                now, null, false, now, now));
    }

    @AfterEach
    void stop() throws Exception {
        for (var client : clients) client.close();
        server.stop(1000);
        assertEquals(0, registry.size());
        assertEquals(0, server.subscriptionCount());
    }

    private FoundationWsClient hello() throws Exception {
        return hello(UUID.randomUUID().toString());
    }

    private FoundationWsClient hello(String instance) throws Exception {
        var client = FoundationWsClient.connect(URI.create("ws://127.0.0.1:" + server.getPort() + "/"),
                Map.of("Origin", "http://localhost:3000"));
        clients.add(client);
        client.sendJson(Map.of("type", "hello", "protocolVersion", 1, "clientInstanceId", instance));
        assertEquals("welcome", receive(client).path("type").asText());
        return client;
    }

    private FoundationWsClient bind(User user, UUID session) throws Exception {
        var client = hello();
        client.sendJson(Map.of("type", "auth", "payload", Map.of()));
        assertEquals("UNAUTHORIZED", receive(client).path("error").path("code").asText());
        client.sendJson(Map.of("type", "auth", "requestId", "auth-1", "payload", Map.of(
                "accessToken", jwt.generateAccessToken(user, session.toString()), "userId", bob.id().toString(), "role", "OWNER")));
        JsonNode ack = receive(client);
        assertEquals("auth_ack", ack.path("type").asText());
        assertEquals("auth-1", ack.path("requestId").asText());
        assertEquals(user.id().toString(), ack.path("payload").path("userId").asText());
        assertEquals(session.toString(), ack.path("payload").path("sessionId").asText());
        return client;
    }

    private JsonNode receive(FoundationWsClient client) throws Exception {
        JsonNode event = client.receive(3000);
        assertNotNull(event, "Expected a gateway event");
        return event;
    }

    private void join(FoundationWsClient client, UUID channel) throws Exception {
        client.sendJson(Map.of("type", "channel.join", "requestId", "join-1", "payload", Map.of("channelId", channel.toString())));
        assertEquals("channel.joined", receive(client).path("type").asText());
    }

    private void publish(String type) {
        server.broadcast(room, Map.of("type", type, "channelId", room, "message", Map.of("id", "canonical-id", "channelId", room)));
    }

    @Test
    void browserAuthUsesSignedIdentityAndDuplicateBindCannotChangeIt() throws Exception {
        var client = bind(alice, aliceSession);
        join(client, room);
        client.sendJson(Map.of("type", "auth", "payload", Map.of("accessToken", jwt.generateAccessToken(bob, bobSession.toString()))));
        assertEquals("UNAUTHORIZED", receive(client).path("error").path("code").asText());
        assertEquals(alice.id(), registry.findBySessionId(aliceSession.toString()).orElseThrow().userId());
        assertTrue(registry.findBySessionId(bobSession.toString()).isEmpty());
    }

    @Test
    void unauthorizedSocketAndBadCredentialsCannotJoinOrReceive() throws Exception {
        var client = hello();
        client.sendJson(Map.of("type", "auth", "payload", Map.of("accessToken", "invalid-token")));
        assertEquals("UNAUTHORIZED", receive(client).path("error").path("code").asText());
        client.sendJson(Map.of("type", "channel.join", "payload", Map.of("channelId", room)));
        assertEquals("AUTH_REQUIRED", receive(client).path("error").path("code").asText());
        publish("message.created");
        assertNull(client.receive(100));
        assertEquals(0, server.subscriptionCount());
    }

    @Test
    void expiredRefreshAndRevokedCredentialsAreRejected() throws Exception {
        List<String> badTokens = List.of(jwt.createToken(alice, "access", -1, "access-test-secret", aliceSession.toString()),
                jwt.generateRefreshToken(alice, aliceSession.toString()), jwt.generateAccessToken(alice, UUID.randomUUID().toString()));
        for (String token : badTokens) {
            var client = hello();
            client.sendJson(Map.of("type", "auth", "payload", Map.of("accessToken", token)));
            assertEquals("error", receive(client).path("type").asText());
        }
        sessions.computeIfPresent(aliceSession, (id, s) -> s.withRevoked(true));
        var client = hello();
        client.sendJson(Map.of("type", "auth", "payload", Map.of("accessToken", jwt.generateAccessToken(alice, aliceSession.toString()))));
        assertEquals("SESSION_REVOKED", receive(client).path("error").path("code").asText());
        assertTrue(registry.findByUserId(alice.id()).isEmpty());
    }

    @Test
    void roomFanoutIsScopedIdempotentAndMembershipRechecked() throws Exception {
        var a = bind(alice, aliceSession);
        var b = bind(bob, bobSession);
        var other = bind(alice, aliceSession);
        join(a, room);
        join(a, room);
        join(b, room);
        join(other, otherRoom);
        assertEquals(3, server.subscriptionCount());
        for (String type : List.of("message.created", "message.updated", "message.deleted")) {
            publish(type);
            assertEquals(type, receive(a).path("type").asText());
            assertEquals(type, receive(b).path("type").asText());
            assertNull(a.receive(75));
            assertNull(other.receive(75));
        }
        access.get(room).remove(bob.id());
        publish("message.created");
        receive(a);
        assertNull(b.receive(150));
        assertEquals(2, server.subscriptionCount());
        b.sendJson(Map.of("type", "channel.join", "payload", Map.of("channelId", otherRoom)));
        assertEquals("FORBIDDEN", receive(b).path("error").path("code").asText());
    }

    @Test
    void sessionRevokeClosesEveryTabButKeepsIndependentSession() throws Exception {
        var a = bind(alice, aliceSession);
        var tab = bind(alice, aliceSession);
        UUID independent = UUID.randomUUID();
        session(independent, alice);
        var sibling = bind(alice, independent);
        join(a, room);
        join(tab, room);
        join(sibling, room);
        sessions.computeIfPresent(aliceSession, (id, s) -> s.withRevoked(true));
        publish("message.created");
        assertEquals("SESSION_REVOKED", receive(a).path("error").path("code").asText());
        assertEquals("SESSION_REVOKED", receive(tab).path("error").path("code").asText());
        assertEquals(4002, a.awaitClose(3000));
        assertEquals(4002, tab.awaitClose(3000));
        assertEquals("message.created", receive(sibling).path("type").asText());
        assertTrue(registry.findAllBySessionId(aliceSession.toString()).isEmpty());
    }

    @Test
    void idleRevokeAndAccessExpiryAreDetectedWithoutIncomingFrames() throws Exception {
        var revoked = bind(alice, aliceSession);
        sessions.computeIfPresent(aliceSession, (id, s) -> s.withRevoked(true));
        assertEquals("SESSION_REVOKED", receive(revoked).path("error").path("code").asText());
        assertEquals(4002, revoked.awaitClose(3000));
        var expired = hello();
        expired.sendJson(Map.of("type", "auth", "payload", Map.of("accessToken",
                jwt.createToken(bob, "access", 1, "access-test-secret", bobSession.toString()))));
        assertEquals("auth_ack", receive(expired).path("type").asText());
        assertEquals("UNAUTHORIZED", receive(expired).path("error").path("code").asText());
        assertEquals(4002, expired.awaitClose(3000));
    }

    @Test
    void originAndCredentialQueryAreRejectedBeforeUpgrade() {
        URI uri = URI.create("ws://127.0.0.1:" + server.getPort());
        assertThrows(Exception.class, () -> FoundationWsClient.connect(uri, Map.of("Origin", "https://evil.example")));
        assertThrows(Exception.class, () -> FoundationWsClient.connect(URI.create(uri + "/?accessToken=not-a-token"), Map.of()));
        assertEquals(0, registry.size());
    }

    @Test
    void lanOriginIsAllowedBeforeUpgrade() throws Exception {
        URI uri = URI.create("ws://127.0.0.1:" + server.getPort());
        try (var client = FoundationWsClient.connect(uri, Map.of("Origin", "http://192.168.1.230:3000"))) {
            client.sendJson(Map.of("type", "hello", "protocolVersion", 1));
            var welcome = receive(client);
            assertEquals("welcome", welcome.path("type").asText());
        }
    }

    @Test
    void disabledUserCannotBindAndExistingSocketIsClosed() throws Exception {
        var existing = bind(alice, aliceSession);
        users.put(alice.id(), alice.withStatus(User.STATUS_DISABLED));
        assertEquals("ACCOUNT_DISABLED", receive(existing).path("error").path("code").asText());
        assertEquals(4003, existing.awaitClose(3000));
        var rejected = hello();
        rejected.sendJson(Map.of("type", "auth", "payload", Map.of("accessToken", jwt.generateAccessToken(alice, aliceSession.toString()))));
        assertEquals("ACCOUNT_DISABLED", receive(rejected).path("error").path("code").asText());
        assertEquals(4003, rejected.awaitClose(3000));
    }

    @Test
    void leaveAndGracefulShutdownRemoveAllSubscriptions() throws Exception {
        var a = bind(alice, aliceSession);
        join(a, room);
        a.sendJson(Map.of("type", "channel.leave", "requestId", "leave", "payload", Map.of("channelId", room)));
        assertEquals("channel.left", receive(a).path("type").asText());
        assertEquals(0, server.subscriptionCount());
        publish("message.created");
        assertNull(a.receive(100));
        join(a, room);
        server.stop(1000);
        assertEquals("SERVER_SHUTDOWN", receive(a).path("error").path("code").asText());
        assertEquals(1001, a.awaitClose(3000));
        assertEquals(0, registry.size());
        assertEquals(0, server.subscriptionCount());
    }

    @Test
    void pendingHelloAndPendingAuthHaveBoundedDeadline() throws Exception {
        server.stop(1000);
        startGateway(5000, 400);
        var silent = FoundationWsClient.connect(server.getPort());
        clients.add(silent);
        var noAuth = hello();
        assertEquals("AUTH_REQUIRED", receive(silent).path("error").path("code").asText());
        assertEquals(1008, silent.awaitClose(3000));
        assertEquals("AUTH_REQUIRED", receive(noAuth).path("error").path("code").asText());
        assertEquals(1008, noAuth.awaitClose(3000));
    }

    @Test
    void authenticatedInstanceReplacementCleansOldRoomAndRequiresRejoin() throws Exception {
        var original = bind(alice, aliceSession);
        join(original, room);
        String instance = registry.findBySessionId(aliceSession.toString()).orElseThrow().clientInstanceId();
        var replacement = hello(instance);
        replacement.sendJson(Map.of("type", "auth", "payload", Map.of("accessToken", jwt.generateAccessToken(alice, aliceSession.toString()))));
        assertEquals("auth_ack", receive(replacement).path("type").asText());
        assertEquals(1000, original.awaitClose(3000));
        assertEquals(1, registry.findAllBySessionId(aliceSession.toString()).size());
        publish("message.created");
        assertNull(replacement.receive(100));
        join(replacement, room);
        publish("message.created");
        assertEquals("message.created", receive(replacement).path("type").asText());
        assertEquals(1, server.subscriptionCount());
    }

    @Test
    void heartbeatTimeoutAndReconnectCleanRooms() throws Exception {
        server.stop(1000);
        startGateway(400, 3000);
        var a = bind(alice, aliceSession);
        join(a, room);
        a.sendJson(Map.of("type", "heartbeat", "requestId", "beat"));
        assertEquals("heartbeat_ack", receive(a).path("type").asText());
        assertEquals(4000, a.awaitClose(3000));
        assertEquals(0, server.subscriptionCount());
        var reconnected = bind(alice, aliceSession);
        join(reconnected, room);
        publish("message.created");
        assertEquals("message.created", receive(reconnected).path("type").asText());
        assertEquals(1, server.subscriptionCount());
    }
}
