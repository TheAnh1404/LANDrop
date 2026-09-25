package com.landrop.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.auth.model.RefreshToken;
import com.landrop.auth.repository.RefreshTokenRepository;
import com.landrop.auth.security.JwtService;
import com.landrop.auth.security.PasswordHasher;
import com.landrop.auth.server.AuthHttpHandler;
import com.landrop.auth.server.AuthHttpServer;
import com.landrop.auth.server.AuthMiddleware;
import com.landrop.auth.service.AuthService;
import com.landrop.admin.security.AdminAuthMiddleware;
import com.landrop.admin.server.AdminHttpServer;
import com.landrop.admin.service.AdminControlCenterService;
import com.landrop.database.model.Device;
import com.landrop.database.model.User;
import com.landrop.database.repository.DeviceRepository;
import com.landrop.database.repository.UserRepository;
import com.landrop.device.server.DeviceHttpHandler;
import com.landrop.device.service.DeviceService;
import com.landrop.server.clients.ClientRegistry;
import com.landrop.server.clients.ClientConnection;
import org.java_websocket.WebSocket;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/** HTTP contract regression without a live database or repository .env. */
class Foundation04HttpContractTest {

    private static final ObjectMapper JSON = AuthHttpServer.createObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final MemoryUsers users = new MemoryUsers();
    private final MemorySessions sessions = new MemorySessions();
    private final MemoryDevices devices = new MemoryDevices();
    private final ClientRegistry registry = new ClientRegistry();
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        users.create(new User("alice@example.test", "alice", "Alice", null, PasswordHasher.hash("password-A")));
        users.create(new User("bob@example.test", "bob", "Bob", null, PasswordHasher.hash("password-B")));
        users.create(new User("admin@example.test", "admin", "Admin", null,
                PasswordHasher.hash("password-admin")).withSystemRole(User.ROLE_SYSTEM_ADMIN));
        JwtService jwt = new JwtService("0123456789012345678901234567890123456789",
                "9876543210987654321098765432109876543210", 3600, 7200);
        AuthMiddleware middleware = new AuthMiddleware(jwt, users, sessions);
        AuthService auth = new AuthService(users, sessions, jwt);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        new AuthHttpHandler(auth, middleware, registry).register(server);
        new DeviceHttpHandler(new DeviceService(devices, registry, sessions), middleware).register(server);
        AdminControlCenterService adminService = new AdminControlCenterService(
                users, sessions, devices, null, registry, null, null, null);
        new AdminHttpServer(new AdminAuthMiddleware(jwt, users, sessions), adminService,
                AdminHttpServer.createObjectMapper(), "127.0.0.1", 0, 0, auth).register(server);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void loginEnrollOwnershipAndCurrentSessionLogout() throws Exception {
        HttpResponse<String> invalid = request("POST", "/api/auth/login", null,
                Map.of("email", "alice@example.test", "password", "wrong"));
        assertEquals(401, invalid.statusCode());
        assertEquals("INVALID_CREDENTIALS", json(invalid).path("code").asText());

        JsonNode alice = json(request("POST", "/api/auth/login", null,
                Map.of("email", "alice@example.test", "password", "password-A", "deviceId", "forged-device")));
        String aliceAccess = alice.path("accessToken").asText();
        String aliceRefresh = alice.path("refreshToken").asText();
        assertFalse(alice.toString().contains("passwordHash"));
        assertFalse(alice.toString().contains("tokenHash"));
        UUID aliceSession = UUID.fromString(jwtSession(aliceAccess));
        assertNull(sessions.findById(aliceSession).orElseThrow().deviceId(), "Login cannot trust a claimed device ID");

        assertEquals("alice", json(request("GET", "/api/auth/me", aliceAccess, null)).path("username").asText());
        assertEquals(401, request("GET", "/api/auth/me", null, null).statusCode());

        JsonNode enrolled = json(request("POST", "/api/devices/enroll", aliceAccess,
                Map.of("clientInstanceId", "installation-1", "deviceName", "Alice Laptop",
                        "platform", "Windows", "clientVersion", "1.0")));
        String deviceId = enrolled.path("id").asText();
        assertEquals(deviceId, sessions.findById(aliceSession).orElseThrow().deviceId());
        assertEquals(deviceId, json(request("POST", "/api/devices/enroll", aliceAccess,
                Map.of("clientInstanceId", "installation-1", "deviceName", "Renamed Laptop"))).path("id").asText());
        assertEquals("Renamed Laptop", json(request("PATCH", "/api/devices/" + deviceId, aliceAccess,
                Map.of("deviceName", "Renamed Laptop"))).path("name").asText());
        assertEquals(1, json(request("GET", "/api/devices", aliceAccess, null)).size());

        JsonNode bob = json(request("POST", "/api/auth/login", null,
                Map.of("username", "bob", "password", "password-B")));
        String bobAccess = bob.path("accessToken").asText();
        assertEquals(0, json(request("GET", "/api/devices", bobAccess, null)).size());
        assertEquals(403, request("PATCH", "/api/devices/" + deviceId, bobAccess,
                Map.of("deviceName", "Stolen" )).statusCode());
        HttpResponse<String> stolen = request("POST", "/api/devices/enroll", bobAccess,
                Map.of("clientInstanceId", "installation-1", "deviceName", "Stolen"));
        assertEquals(409, stolen.statusCode());
        assertEquals("DEVICE_ALREADY_OWNED", json(stolen).path("code").asText());

        JsonNode secondAlice = json(request("POST", "/api/auth/login", null,
                Map.of("username", "alice", "password", "password-A")));
        HttpResponse<String> logout = request("POST", "/api/auth/logout", aliceAccess,
                Map.of("refreshToken", aliceRefresh));
        assertEquals(200, logout.statusCode());
        HttpResponse<String> revoked = request("GET", "/api/auth/me", aliceAccess, null);
        assertEquals(401, revoked.statusCode());
        assertEquals("SESSION_REVOKED", json(revoked).path("code").asText());
        assertEquals(200, request("GET", "/api/auth/me", secondAlice.path("accessToken").asText(), null).statusCode(),
                "Logout must leave the other session active");
    }

    @Test
    void disabledUserCannotLoginOrUseAnExistingToken() throws Exception {
        JsonNode login = json(request("POST", "/api/auth/login", null,
                Map.of("username", "alice", "password", "password-A")));
        users.disable("alice");
        HttpResponse<String> existing = request("GET", "/api/auth/me", login.path("accessToken").asText(), null);
        assertEquals(403, existing.statusCode());
        assertEquals("ACCOUNT_DISABLED", json(existing).path("code").asText());
        HttpResponse<String> fresh = request("POST", "/api/auth/login", null,
                Map.of("username", "alice", "password", "password-A"));
        assertEquals(403, fresh.statusCode());
        assertEquals("ACCOUNT_DISABLED", json(fresh).path("code").asText());
    }

    @Test
    void adminIdentityViewAndRevocationTrackTheAuthenticatedConnection() throws Exception {
        JsonNode alice = json(request("POST", "/api/auth/login", null,
                Map.of("username", "alice", "password", "password-A")));
        String aliceAccess = alice.path("accessToken").asText();
        UUID aliceId = UUID.fromString(alice.path("user").path("id").asText());
        String sessionId = jwtSession(aliceAccess);
        String deviceId = json(request("POST", "/api/devices/enroll", aliceAccess,
                Map.of("clientInstanceId", "installation-1", "deviceName", "Alice Laptop"))).path("id").asText();
        WebSocket socket = openSocket();
        registry.register(new ClientConnection("connection-1", "installation-1", "Alice Laptop",
                "127.0.0.1:55000", Instant.now(), 1, "1.0", "Windows", "11", "amd64", socket));
        registry.bindIdentity("connection-1", aliceId, "alice", deviceId, sessionId);

        JsonNode admin = json(request("POST", "/api/admin/auth/login", null,
                Map.of("username", "admin", "password", "password-admin")));
        String adminAccess = admin.path("accessToken").asText();
        assertEquals(403, request("GET", "/api/admin/users/" + aliceId + "/identity", aliceAccess, null).statusCode());

        HttpResponse<String> detailResponse = request("GET", "/api/admin/users/" + aliceId + "/identity", adminAccess, null);
        assertEquals(200, detailResponse.statusCode());
        JsonNode detail = json(detailResponse);
        assertEquals(deviceId, detail.path("devices").get(0).path("id").asText());
        assertTrue(detail.path("devices").get(0).path("isOnline").asBoolean());
        assertEquals(sessionId, detail.path("sessions").get(0).path("id").asText());
        assertEquals("connection-1", detail.path("connections").get(0).path("connectionId").asText());
        assertFalse(detailResponse.body().contains("tokenHash"));
        assertFalse(detailResponse.body().contains("passwordHash"));

        HttpResponse<String> revoke = request("POST", "/api/admin/sessions/" + sessionId + "/revoke", adminAccess, Map.of());
        assertEquals(200, revoke.statusCode());
        assertTrue(sessions.findById(UUID.fromString(sessionId)).orElseThrow().revoked());
        assertTrue(registry.findBySessionId(sessionId).isEmpty());
        assertEquals(401, request("GET", "/api/auth/me", aliceAccess, null).statusCode());
    }

    @Test
    void adminDisableRevokesOnlyTargetUsersSessions() throws Exception {
        JsonNode alice = json(request("POST", "/api/auth/login", null,
                Map.of("username", "alice", "password", "password-A")));
        UUID aliceId = UUID.fromString(alice.path("user").path("id").asText());
        String aliceSession = jwtSession(alice.path("accessToken").asText());
        JsonNode admin = json(request("POST", "/api/admin/auth/login", null,
                Map.of("username", "admin", "password", "password-admin")));
        HttpResponse<String> disabled = request("POST", "/api/admin/users/" + aliceId + "/disable",
                admin.path("accessToken").asText(), Map.of());
        assertEquals(200, disabled.statusCode());
        assertTrue(sessions.findById(UUID.fromString(aliceSession)).orElseThrow().revoked());
        HttpResponse<String> login = request("POST", "/api/auth/login", null,
                Map.of("username", "alice", "password", "password-A"));
        assertEquals("ACCOUNT_DISABLED", json(login).path("code").asText());
        assertEquals(200, request("GET", "/api/admin/users/" + aliceId,
                admin.path("accessToken").asText(), null).statusCode());
    }

    private HttpResponse<String> request(String method, String path, String accessToken, Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path));
        if (accessToken != null) builder.header("Authorization", "Bearer " + accessToken);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body());
    }

    private String jwtSession(String accessToken) throws Exception {
        String payload = accessToken.split("\\.")[1];
        return JSON.readTree(java.util.Base64.getUrlDecoder().decode(payload)).path("sid").asText();
    }

    private WebSocket openSocket() {
        return (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(),
                new Class<?>[] {WebSocket.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isOpen" -> true;
                    case "send", "close" -> null;
                    default -> null;
                });
    }

    private static final class MemoryUsers extends UserRepository {
        private final Map<UUID, User> values = new ConcurrentHashMap<>();

        MemoryUsers() { super(null); }

        @Override public User create(User user) { values.put(user.id(), user); return user; }
        @Override public Optional<User> findById(UUID id) { return Optional.ofNullable(values.get(id)); }
        @Override public Optional<User> findByEmail(String email) { return values.values().stream().filter(u -> u.email().equals(email)).findFirst(); }
        @Override public Optional<User> findByUsername(String username) { return values.values().stream().filter(u -> u.username().equals(username)).findFirst(); }
        void disable(String username) { findByUsername(username).ifPresent(u -> values.put(u.id(), u.withStatus(User.STATUS_DISABLED))); }
        @Override public boolean updateStatus(UUID id, String status) {
            User current = values.get(id);
            if (current == null) return false;
            values.put(id, current.withStatus(status));
            return true;
        }
    }

    private static final class MemorySessions extends RefreshTokenRepository {
        private final Map<UUID, RefreshToken> values = new ConcurrentHashMap<>();

        MemorySessions() { super(null); }

        @Override public RefreshToken create(RefreshToken token) { values.put(token.id(), token); return token; }
        @Override public Optional<RefreshToken> findById(UUID id) { return Optional.ofNullable(values.get(id)); }
        @Override public Optional<RefreshToken> findByTokenHash(String hash) {
            return values.values().stream().filter(s -> s.tokenHash().equals(hash)).findFirst();
        }
        @Override public boolean revokeById(UUID id) {
            RefreshToken current = values.get(id);
            if (current == null || current.revoked()) return false;
            values.put(id, current.withRevoked(true));
            return true;
        }
        @Override public boolean bindDevice(UUID sessionId, UUID userId, String deviceId) {
            RefreshToken current = values.get(sessionId);
            if (current == null || !current.userId().equals(userId) || current.revoked()
                    || current.isExpired() || current.deviceId() != null && !current.deviceId().equals(deviceId)) return false;
            values.put(sessionId, current.withDeviceId(deviceId));
            return true;
        }
        @Override public List<RefreshToken> findActiveByUserId(UUID userId) {
            return values.values().stream().filter(s -> s.userId().equals(userId) && s.isValid()).toList();
        }
        @Override public List<RefreshToken> findAllSessions(boolean activeOnly, UUID userId, int limit, int offset) {
            return values.values().stream()
                    .filter(s -> userId == null || s.userId().equals(userId))
                    .filter(s -> !activeOnly || s.isValid())
                    .skip(offset).limit(limit).toList();
        }
        @Override public int revokeAllByUserId(UUID userId) {
            int count = 0;
            for (RefreshToken session : List.copyOf(values.values())) {
                if (session.userId().equals(userId) && revokeById(session.id())) count++;
            }
            return count;
        }
    }

    private static final class MemoryDevices extends DeviceRepository {
        private final Map<String, Device> values = new ConcurrentHashMap<>();

        MemoryDevices() { super(null); }

        @Override public Device upsert(Device device) { values.put(device.id(), device); return device; }
        @Override public Optional<Device> findById(String id) { return Optional.ofNullable(values.get(id)); }
        @Override public Optional<Device> findByClientInstanceId(String instanceId) {
            return values.values().stream().filter(d -> d.clientInstanceId().equals(instanceId)).findFirst();
        }
        @Override public List<Device> findByUserId(UUID userId) {
            return values.values().stream().filter(d -> d.userId().equals(userId)).toList();
        }
        @Override public boolean updateName(String id, String name) {
            Device current = values.get(id);
            if (current == null) return false;
            values.put(id, current.withDeviceName(name));
            return true;
        }
    }
}
