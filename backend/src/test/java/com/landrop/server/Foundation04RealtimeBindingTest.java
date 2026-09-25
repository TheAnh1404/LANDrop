package com.landrop.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.landrop.auth.model.RefreshToken;
import com.landrop.auth.repository.RefreshTokenRepository;
import com.landrop.auth.security.JwtService;
import com.landrop.database.model.Device;
import com.landrop.database.model.User;
import com.landrop.database.repository.DeviceRepository;
import com.landrop.database.repository.UserRepository;
import com.landrop.server.clients.ClientRegistry;
import com.landrop.server.protocol.ProtocolConstants;
import com.landrop.server.realtime.FoundationRealtimeServer;
import com.landrop.server.transfers.TransferCoordinator;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class Foundation04RealtimeBindingTest {

    @Test
    void helloBindsOnlyTheVerifiedSessionAndDeviceAndRevocationDisconnects() throws Exception {
        User user = new User("alice@example.test", "alice", "Alice", null);
        Device device = new Device("device-1", user.id(), "installation-1", "Alice Laptop", "Windows", "1.0");
        UUID sessionId = UUID.randomUUID();
        Instant now = Instant.now();
        RefreshToken session = new RefreshToken(sessionId, user.id(), "stored-hash", device.id(),
                now.plusSeconds(3600), now, null, false, now, now);
        SessionStore sessions = new SessionStore(session);
        DeviceStore devices = new DeviceStore(device);
        ClientRegistry registry = new ClientRegistry();
        JwtService jwt = new JwtService("test-access-secret", "test-refresh-secret", 600, 3600);
        FoundationRealtimeServer server = new FoundationRealtimeServer(
                new InetSocketAddress("127.0.0.1", 0), registry, new TransferCoordinator(registry), 15_000, 45_000);
        server.setAuthDependencies(jwt, new UserStore(user), devices, sessions);
        server.start();
        try {
            server.awaitReady();
            String token = jwt.generateAccessToken(user, sessionId.toString());
            try (FoundationWsClient client = FoundationWsClient.connect(server.getPort(), token)) {
                client.sendJson(hello("installation-1", device.id(), UUID.randomUUID().toString()));
                JsonNode welcome = client.receive(3_000);
                assertNotNull(welcome);
                assertEquals(ProtocolConstants.EVENT_WELCOME, welcome.path("type").asText());
                assertTrue(welcome.path("payload").path("authenticated").asBoolean());
                assertEquals(user.id().toString(), welcome.path("payload").path("userId").asText());
                assertEquals(device.id(), welcome.path("payload").path("deviceId").asText());
                assertEquals(sessionId.toString(), welcome.path("payload").path("sessionId").asText());
                String connectionId = welcome.path("connectionId").asText();
                assertEquals(connectionId, registry.findBySessionId(sessionId.toString()).orElseThrow().connectionId());
                assertTrue(devices.lastSeenWritten.await(3, TimeUnit.SECONDS));
                assertTrue(sessions.lastSeenWritten);

                sessions.session = session.withRevoked(true);
                client.sendJson(Map.of("type", ProtocolConstants.EVENT_HEARTBEAT, "requestId", "beat-1"));
                JsonNode error = client.receive(3_000);
                assertNotNull(error);
                assertEquals("SESSION_REVOKED", error.path("error").path("code").asText());
                client.awaitClose(3_000);
                assertTrue(registry.findBySessionId(sessionId.toString()).isEmpty());
            }
        } finally {
            server.stop(1_000);
        }
    }

    @Test
    void helloRejectsMismatchedInstallationBeforeAuthenticatedWelcome() throws Exception {
        User user = new User("alice@example.test", "alice", "Alice", null);
        Device device = new Device("device-1", user.id(), "installation-1", "Alice Laptop", "Windows", "1.0");
        UUID sessionId = UUID.randomUUID();
        Instant now = Instant.now();
        RefreshToken session = new RefreshToken(sessionId, user.id(), "stored-hash", device.id(),
                now.plusSeconds(3600), now, null, false, now, now);
        ClientRegistry registry = new ClientRegistry();
        JwtService jwt = new JwtService("test-access-secret", "test-refresh-secret", 600, 3600);
        FoundationRealtimeServer server = new FoundationRealtimeServer(
                new InetSocketAddress("127.0.0.1", 0), registry, new TransferCoordinator(registry), 15_000, 45_000);
        server.setAuthDependencies(jwt, new UserStore(user), new DeviceStore(device), new SessionStore(session));
        server.start();
        try {
            server.awaitReady();
            try (FoundationWsClient client = FoundationWsClient.connect(server.getPort(),
                    jwt.generateAccessToken(user, sessionId.toString()))) {
                client.sendJson(hello("another-installation", device.id(), user.id().toString()));
                JsonNode error = client.receive(3_000);
                assertNotNull(error);
                assertEquals(ProtocolConstants.EVENT_ERROR, error.path("type").asText());
                assertEquals("DEVICE_CONFLICT", error.path("error").path("code").asText());
                client.awaitClose(3_000);
                assertTrue(registry.findBySessionId(sessionId.toString()).isEmpty());
            }
        } finally {
            server.stop(1_000);
        }
    }

    private Map<String, Object> hello(String installation, String deviceId, String untrustedUserId) {
        return Map.of("type", ProtocolConstants.EVENT_HELLO,
                "protocolVersion", ProtocolConstants.PROTOCOL_VERSION,
                "clientInstanceId", installation, "deviceId", deviceId,
                "deviceName", "Alice Laptop", "userId", untrustedUserId);
    }

    private static final class UserStore extends UserRepository {
        private final User user;

        UserStore(User user) { super(null); this.user = user; }

        @Override
        public Optional<User> findById(UUID id) { return user.id().equals(id) ? Optional.of(user) : Optional.empty(); }
    }

    private static final class DeviceStore extends DeviceRepository {
        private final Device device;
        final CountDownLatch lastSeenWritten = new CountDownLatch(1);

        DeviceStore(Device device) { super(null); this.device = device; }

        @Override
        public Optional<Device> findById(String id) { return device.id().equals(id) ? Optional.of(device) : Optional.empty(); }

        @Override
        public boolean updateLastSeen(String id, Instant seenAt) {
            if (device.id().equals(id)) lastSeenWritten.countDown();
            return device.id().equals(id);
        }
    }

    private static final class SessionStore extends RefreshTokenRepository {
        volatile RefreshToken session;
        volatile boolean lastSeenWritten;

        SessionStore(RefreshToken session) { super(null); this.session = session; }

        @Override
        public Optional<RefreshToken> findById(UUID id) {
            return session.id().equals(id) ? Optional.of(session) : Optional.empty();
        }

        @Override
        public boolean updateLastSeen(UUID id, Instant seenAt) {
            if (session.id().equals(id)) lastSeenWritten = true;
            return session.id().equals(id);
        }
    }
}
