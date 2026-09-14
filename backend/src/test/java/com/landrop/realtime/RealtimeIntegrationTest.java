package com.landrop.realtime;

import com.landrop.auth.security.JwtService;
import com.landrop.database.model.User;
import com.landrop.database.repository.UserRepository;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RealtimeIntegrationTest {
    private final User a = user("a"), b = user("b"), c = user("c"), outsider = user("outsider");
    private final UUID channel = UUID.randomUUID(), other = UUID.randomUUID();
    private final JwtService jwt = new JwtService("test-access-secret-only-32-characters", "test-refresh-secret-only-32-characters", 900, 3600);
    private final List<WsProbe> probes = new ArrayList<>();
    private MemoryStore store;
    private RealtimeServer server;
    private static User user(String name) { return new User(UUID.randomUUID(), name + "@example.com", name, name, null, null, Instant.now(), Instant.now()); }
    @BeforeEach void start() throws Exception {
        store = new MemoryStore();
        var users = new UserRepository(null) {
            @Override public Optional<User> findById(UUID id) {
                return List.of(a,b,c,outsider).stream().filter(u -> u.id().equals(id)).findFirst();
            }
        };
        server = new RealtimeServer(new InetSocketAddress("127.0.0.1", 0), SocketAuthenticator.jwt(jwt, users), store);
        server.start(); server.awaitReady();
    }
    @AfterEach void stop() throws Exception {
        for (var p : probes) p.close();
        if (server != null) server.stop(1000);
    }
    private WsProbe connect(User user, UUID target) throws Exception {
        var probe = WsProbe.open(server, target, jwt.generateAccessToken(user)); probes.add(probe);
        assertEquals(user.id().toString(), probe.event("connected").path("userId").asText()); return probe;
    }
    @Test void threeUsersAndTwoDevicesReceiveCanonicalMessagesWithoutDuplicateEcho() throws Exception {
        var a1 = connect(a, channel); var a2 = connect(a, channel);
        var b1 = connect(b, channel); var c1 = connect(c, channel); var isolated = connect(b, other);
        UUID clientId = UUID.randomUUID();
        a1.message(clientId, "hello");
        var canonical = a1.event("message.created").path("message");
        assertEquals(a.id().toString(), canonical.path("senderId").asText());
        for (var p : List.of(a2,b1,c1)) assertEquals(canonical, p.event("message.created").path("message"));
        assertEquals(1, store.saved.size());
        a1.message(clientId, "hello");
        assertEquals(canonical, a1.event("message.created").path("message"));
        assertNull(a2.events.poll(150, TimeUnit.MILLISECONDS));
        assertNull(isolated.events.poll(150, TimeUnit.MILLISECONDS));
        b1.message(UUID.randomUUID(), "reply");
        for (var p : List.of(a1,a2,b1,c1))
            assertEquals(b.id().toString(), p.event("message.created").path("message").path("senderId").asText());
        assertEquals(2, server.registry().count(a.id()));
        a1.close(); a1.closed.get(3, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (server.registry().count(a.id()) != 1 && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(1, server.registry().count(a.id()));
        a2.message(UUID.randomUUID(), "still online");
        for (var p : List.of(a2,b1,c1)) p.event("message.created");
    }
    @Test void rejectsMissingInvalidRefreshExpiredAndUnknownUserCredentials() {
        for (String token : Arrays.asList(null, "invalid", jwt.generateRefreshToken(a),
                new JwtService("test-access-secret-only-32-characters", "r", -1, 100).generateAccessToken(a),
                jwt.generateAccessToken(user("unknown")))) {
            assertThrows(CompletionException.class, () -> WsProbe.open(server, channel, token));
        }
        assertThrows(CompletionException.class, () -> WsProbe.open(server, channel, jwt.generateAccessToken(outsider)));
        assertTrue(server.registry().snapshot().isEmpty());
    }
    @Test void rejectsSpoofedSenderAndMalformedCommandsWithoutPersistence() throws Exception {
        var p = connect(a, channel);
        p.send(Map.of("type", "message.send", "requestId", UUID.randomUUID().toString(),
                "clientMessageId", UUID.randomUUID().toString(), "content", "spoof", "senderId", b.id().toString()));
        assertEquals("INVALID_REQUEST", p.event("error").path("code").asText());
        p.message(UUID.randomUUID(), " ");
        p.event("error");
        p.socket.sendText("{", true).join(); p.event("error");
        assertTrue(store.saved.isEmpty());
    }
    @Test void failedPersistenceNeverBroadcastsAndRetryCanRecover() throws Exception {
        var p = connect(a, channel); var recipient = connect(b, channel);
        store.unavailable = true;
        UUID id = UUID.randomUUID(); p.message(id, "recover");
        assertEquals("PERSISTENCE_UNAVAILABLE", p.event("error").path("code").asText());
        assertNull(recipient.events.poll(100, TimeUnit.MILLISECONDS)); assertTrue(store.saved.isEmpty());
        store.unavailable = false; p.message(id, "recover");
        assertEquals(p.event("message.created").path("message"), recipient.event("message.created").path("message"));
    }
    @Test void reconnectReadsHistoryAndChannelRevocationBlocksSend() throws Exception {
        var p = connect(a, channel); p.message(UUID.randomUUID(), "persisted");
        var message = p.event("message.created").path("message");
        p.close();
        var restored = connect(a, channel); restored.history();
        assertEquals(message, restored.event("history").path("page").path("messages").get(0));
        store.revoked.add(a.id()); restored.message(UUID.randomUUID(), "denied");
        assertEquals("FORBIDDEN", restored.event("error").path("code").asText());
    }
    @Test void idleSocketClosesWhenItsAccessTokenExpires() throws Exception {
        String token = new JwtService("test-access-secret-only-32-characters", "r", 2, 100).generateAccessToken(a);
        var p = WsProbe.open(server, channel, token); probes.add(p); p.event("connected");
        assertEquals(4001, p.closed.get(5, TimeUnit.SECONDS));
    }
    private final class MemoryStore implements RealtimeStore {
        final Map<String,Message> saved = new LinkedHashMap<>();
        final Set<UUID> revoked = ConcurrentHashMap.newKeySet();
        volatile boolean unavailable;
        public boolean canAccess(UUID user, UUID ch) { return !user.equals(outsider.id()) && !revoked.contains(user) && (ch.equals(channel) || ch.equals(other)); }
        public synchronized Saved save(UUID user, UUID ch, UUID id, String content) throws SQLException {
            if (!canAccess(user,ch)) throw new RealtimeException("FORBIDDEN","Channel access is unavailable.");
            if (unavailable) throw new SQLException("internal connection details");
            String key = user + ":" + id; var previous = saved.get(key);
            if (previous != null) return new Saved(previous, false);
            String now = Instant.now().toString();
            var message = new Message(UUID.randomUUID(), ch, user, id, content, now, now);
            saved.put(key,message); return new Saved(message,true);
        }
        public synchronized Page history(UUID user, UUID ch, int limit, String before) {
            if (!canAccess(user,ch)) throw new RealtimeException("FORBIDDEN","Channel access is unavailable.");
            return new Page(saved.values().stream().filter(m -> m.channelId().equals(ch)).toList(),null);
        }
    }
}
