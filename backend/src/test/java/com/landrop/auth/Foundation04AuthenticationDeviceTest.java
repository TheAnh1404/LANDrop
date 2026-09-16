package com.landrop.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.admin.service.AdminControlCenterService;
import com.landrop.auth.config.AuthConfig;
import com.landrop.auth.model.AuthErrorCode;
import com.landrop.auth.security.JwtService;
import com.landrop.database.DatabaseConfig;
import com.landrop.database.DatabaseManager;
import com.landrop.database.migration.DatabaseMigrator;
import com.landrop.database.model.User;
import com.landrop.server.FoundationWsClient;
import com.landrop.server.bootstrap.ServerBootstrap;
import com.landrop.server.config.ServerConfig;
import com.landrop.server.protocol.ProtocolConstants;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end integration test suite validating FOUNDATION-04A:
 * Server Authentication, Device Enrollment, Identity Hierarchy,
 * Authenticated Realtime Gateways, and Realtime Revocation Enforcement.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Foundation04AuthenticationDeviceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static ServerBootstrap bootstrap;
    private static HttpClient http;
    private static String baseUrl;
    private static int wsPort;

    private static String userAToken;
    private static String userARefreshToken;
    private static String userAEmail;
    private static String userAPassword;
    private static UUID userAId;
    private static String userAUsername;

    private static String userBToken;
    private static UUID userBId;
    private static String userBUsername;

    private static String enrolledDeviceId;
    private static final String SHARED_CLIENT_INSTANCE_ID = "test-instance-" + UUID.randomUUID();

    @BeforeAll
    static void setUpAll() throws Exception {
        String dbUrl = DatabaseConfig.getDatabaseUrl();
        assumeTrue(dbUrl != null && !dbUrl.isBlank(), "DATABASE_URL is not configured; skipping integration tests.");

        DatabaseManager db = DatabaseManager.getInstance();
        assumeTrue(db.testConnection(), "Neon database connection failed; skipping integration tests.");

        // Run migrations to ensure V7 is in place
        DatabaseMigrator migrator = new DatabaseMigrator(db);
        migrator.migrate();

        ServerConfig config = new ServerConfig(
            "127.0.0.1",
            0,
            0,
            ProtocolConstants.PROTOCOL_VERSION,
            ProtocolConstants.DEFAULT_HEARTBEAT_INTERVAL_MS,
            ProtocolConstants.DEFAULT_HEARTBEAT_TIMEOUT_MS,
            "test",
            "INFO"
        );

        bootstrap = new ServerBootstrap(config);
        bootstrap.start();

        int httpPort = bootstrap.getHttpServer().getPort();
        wsPort = bootstrap.getRealtimeServer().getPort();
        baseUrl = "http://127.0.0.1:" + httpPort;
        http = HttpClient.newHttpClient();
    }

    @AfterAll
    static void tearDownAll() {
        if (bootstrap != null) {
            bootstrap.stop();
        }
    }

    @Test
    @Order(1)
    @DisplayName("1. User registration and valid login returns tokens and UserDto")
    void testRegisterAndLoginSuccess() throws Exception {
        userAUsername = "userA_" + System.currentTimeMillis();
        String email = userAUsername + "@example.com";
        String password = "SecurePassword123!";
        userAEmail = email;
        userAPassword = password;

        // Register User A
        var regBody = JSON.writeValueAsString(Map.of(
            "email", email,
            "username", userAUsername,
            "displayName", "Alice Tester",
            "password", password
        ));
        var regReq = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/auth/register"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(regBody))
            .build();
        var regResp = http.send(regReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, regResp.statusCode(), "Registration should succeed: " + regResp.body());

        // Login User A
        var loginBody = JSON.writeValueAsString(Map.of(
            "identifier", email,
            "password", password
        ));
        var loginReq = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/auth/login"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(loginBody))
            .build();
        var loginResp = http.send(loginReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, loginResp.statusCode(), "Login should succeed: " + loginResp.body());

        JsonNode json = JSON.readTree(loginResp.body());
        assertTrue(json.hasNonNull("accessToken"), "Must contain accessToken");
        assertTrue(json.hasNonNull("refreshToken"), "Must contain refreshToken");
        assertTrue(json.hasNonNull("user"), "Must contain user object");

        userAToken = json.path("accessToken").asText();
        userARefreshToken = json.path("refreshToken").asText();
        userAId = UUID.fromString(json.path("user").path("id").asText());

        // Security check: passwords/hashes must never leak
        assertFalse(loginResp.body().contains("passwordHash"), "passwordHash must not leak");
        assertFalse(loginResp.body().contains(password), "raw password must not leak");
    }

    @Test
    @Order(2)
    @DisplayName("2. Login with invalid credentials returns HTTP 401 INVALID_CREDENTIALS")
    void testLoginInvalidCredentials() throws Exception {
        var loginBody = JSON.writeValueAsString(Map.of(
            "identifier", userAUsername,
            "password", "WrongPassword123!"
        ));
        var req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/auth/login"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(loginBody))
            .build();
        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, resp.statusCode());

        JsonNode json = JSON.readTree(resp.body());
        assertEquals(AuthErrorCode.INVALID_CREDENTIALS, json.path("code").asText());
    }

    @Test
    @Order(3)
    @DisplayName("3. Login with disabled account returns HTTP 403 ACCOUNT_DISABLED")
    void testLoginDisabledAccount() throws Exception {
        String username = "disabledUser_" + System.currentTimeMillis();
        String email = username + "@example.com";
        String password = "Password123!";

        // Register user
        var regBody = JSON.writeValueAsString(Map.of(
            "email", email,
            "username", username,
            "password", password
        ));
        var regReq = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/auth/register"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(regBody))
            .build();
        var regResp = http.send(regReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, regResp.statusCode());
        UUID disabledUserId = UUID.fromString(JSON.readTree(regResp.body()).path("user").path("id").asText());

        // Disable account in DB
        bootstrap.getUserRepository().updateStatus(disabledUserId, User.STATUS_DISABLED);

        // Attempt login
        var loginBody = JSON.writeValueAsString(Map.of(
            "identifier", username,
            "password", password
        ));
        var loginReq = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/auth/login"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(loginBody))
            .build();
        var loginResp = http.send(loginReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, loginResp.statusCode());

        JsonNode json = JSON.readTree(loginResp.body());
        assertEquals(AuthErrorCode.ACCOUNT_DISABLED, json.path("code").asText());
    }

    @Test
    @Order(4)
    @DisplayName("4. GET /api/auth/me returns current user profile with Bearer token")
    void testGetCurrentUserWithBearerToken() throws Exception {
        var req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/auth/me"))
            .header("Authorization", "Bearer " + userAToken)
            .GET()
            .build();
        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());

        JsonNode user = JSON.readTree(resp.body());
        assertEquals(userAId.toString(), user.path("id").asText());
        assertEquals(userAUsername, user.path("username").asText());

        // Unauthorized when missing token
        var unauthReq = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/auth/me"))
            .GET()
            .build();
        var unauthResp = http.send(unauthReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, unauthResp.statusCode());
    }

    @Test
    @Order(5)
    @DisplayName("5. Refresh token rotation and logout revocation")
    void testRefreshTokenLifecycle() throws Exception {
        // Refresh token rotation
        var refreshBody = JSON.writeValueAsString(Map.of("refreshToken", userARefreshToken));
        var req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/auth/refresh"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(refreshBody))
            .build();
        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());

        JsonNode json = JSON.readTree(resp.body());
        String rotatedAccess = json.path("accessToken").asText();
        String rotatedRefresh = json.path("refreshToken").asText();
        assertNotEquals(userARefreshToken, rotatedRefresh, "Refresh token should be rotated");

        // Old refresh token must be revoked (reuse prevention)
        var oldRefreshReq = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/auth/refresh"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(refreshBody))
            .build();
        var oldRefreshResp = http.send(oldRefreshReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, oldRefreshResp.statusCode());

        // Logout with rotated token
        var logoutBody = JSON.writeValueAsString(Map.of("refreshToken", rotatedRefresh));
        var logoutReq = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/auth/logout"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(logoutBody))
            .build();
        var logoutResp = http.send(logoutReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, logoutResp.statusCode());

        // Refresh after logout must fail
        var afterLogoutReq = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/auth/refresh"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(logoutBody))
            .build();
        var afterLogoutResp = http.send(afterLogoutReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, afterLogoutResp.statusCode());

        // The remainder of this ordered suite exercises a fresh, valid session.
        var loginAgain = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/auth/login"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of(
                "identifier", userAEmail, "password", userAPassword
            )))).build();
        var loginAgainResp = http.send(loginAgain, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, loginAgainResp.statusCode());
        JsonNode fresh = JSON.readTree(loginAgainResp.body());
        userAToken = fresh.path("accessToken").asText();
        userARefreshToken = fresh.path("refreshToken").asText();
    }

    @Test
    @Order(6)
    @DisplayName("6. Device enrollment registers new device for user")
    void testDeviceEnrollmentSuccess() throws Exception {
        var enrollBody = JSON.writeValueAsString(Map.of(
            "clientInstanceId", SHARED_CLIENT_INSTANCE_ID,
            "deviceName", "Alice MacBook Pro",
            "platform", "macos",
            "clientVersion", "1.0.0"
        ));
        var req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/devices/enroll"))
            .header("Authorization", "Bearer " + userAToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(enrollBody))
            .build();
        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, resp.statusCode(), "Enrollment failed: " + resp.body());

        JsonNode dev = JSON.readTree(resp.body());
        assertTrue(dev.hasNonNull("id"), "Must contain device ID");
        assertEquals("Alice MacBook Pro", dev.path("deviceName").asText());
        assertEquals(SHARED_CLIENT_INSTANCE_ID, dev.path("clientInstanceId").asText());
        assertEquals("macos", dev.path("platform").asText());

        enrolledDeviceId = dev.path("id").asText();
    }

    @Test
    @Order(7)
    @DisplayName("7. Device enrollment is idempotent for the same user")
    void testDeviceEnrollmentIdempotency() throws Exception {
        var reEnrollBody = JSON.writeValueAsString(Map.of(
            "clientInstanceId", SHARED_CLIENT_INSTANCE_ID,
            "deviceName", "Alice MacBook Pro (Updated)",
            "platform", "macos",
            "clientVersion", "1.0.1"
        ));
        var req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/devices/enroll"))
            .header("Authorization", "Bearer " + userAToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(reEnrollBody))
            .build();
        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertTrue(resp.statusCode() == 200 || resp.statusCode() == 201);

        JsonNode dev = JSON.readTree(resp.body());
        assertEquals(enrolledDeviceId, dev.path("id").asText(), "Device ID must remain consistent");
        assertEquals("Alice MacBook Pro (Updated)", dev.path("deviceName").asText());
    }

    @Test
    @Order(8)
    @DisplayName("8. Device enrollment conflict with another user returns HTTP 409 DEVICE_ALREADY_OWNED")
    void testDeviceEnrollmentConflict() throws Exception {
        // Register User B
        userBUsername = "userB_" + System.currentTimeMillis();
        var regBody = JSON.writeValueAsString(Map.of(
            "email", userBUsername + "@example.com",
            "username", userBUsername,
            "password", "PasswordB123!"
        ));
        var regReq = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/auth/register"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(regBody))
            .build();
        var regResp = http.send(regReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, regResp.statusCode());

        userBToken = JSON.readTree(regResp.body()).path("accessToken").asText();
        userBId = UUID.fromString(JSON.readTree(regResp.body()).path("user").path("id").asText());

        // User B attempts to claim the same SHARED_CLIENT_INSTANCE_ID owned by User A
        var conflictBody = JSON.writeValueAsString(Map.of(
            "clientInstanceId", SHARED_CLIENT_INSTANCE_ID,
            "deviceName", "Bob Hijack Attempt",
            "platform", "windows",
            "clientVersion", "1.0.0"
        ));
        var conflictReq = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/devices/enroll"))
            .header("Authorization", "Bearer " + userBToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(conflictBody))
            .build();
        var resp = http.send(conflictReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(409, resp.statusCode(), "Must return 409 Conflict");

        JsonNode json = JSON.readTree(resp.body());
        assertEquals(AuthErrorCode.DEVICE_ALREADY_OWNED, json.path("code").asText());
    }

    @Test
    @Order(9)
    @DisplayName("9. GET /api/devices returns user-owned devices and online status")
    void testListDevices() throws Exception {
        var req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/devices"))
            .header("Authorization", "Bearer " + userAToken)
            .GET()
            .build();
        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());

        JsonNode list = JSON.readTree(resp.body());
        assertTrue(list.isArray());
        assertTrue(list.size() >= 1);

        JsonNode dev = list.get(0);
        assertEquals(enrolledDeviceId, dev.path("id").asText());
        assertFalse(dev.path("isOnline").asBoolean(), "Device should not be online before WS connection");
    }

    @Test
    @Order(10)
    @DisplayName("10. PATCH /api/devices/:id renames device and enforces ownership")
    void testRenameDevice() throws Exception {
        // Owner renames device
        var renameBody = JSON.writeValueAsString(Map.of("deviceName", "Alice Workstation"));
        var req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/devices/" + enrolledDeviceId))
            .header("Authorization", "Bearer " + userAToken)
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(renameBody))
            .build();
        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());

        JsonNode updated = JSON.readTree(resp.body());
        assertEquals("Alice Workstation", updated.path("deviceName").asText());

        // Non-owner (User B) attempts to rename User A's device
        var unauthReq = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/devices/" + enrolledDeviceId))
            .header("Authorization", "Bearer " + userBToken)
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(renameBody))
            .build();
        var unauthResp = http.send(unauthReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, unauthResp.statusCode(), "Non-owner should be forbidden");
    }

    @Test
    @Order(11)
    @DisplayName("11. WebSocket bearer handshake binds enrolled user, device, and session")
    void testWebSocketAuthHandshakeAndBinding() throws Exception {
        try (FoundationWsClient client = FoundationWsClient.connect(wsPort, userAToken)) {
            client.sendJson(Map.of(
                "type", "hello",
                "protocolVersion", ProtocolConstants.PROTOCOL_VERSION,
                "clientInstanceId", SHARED_CLIENT_INSTANCE_ID,
                "deviceId", enrolledDeviceId,
                "deviceName", "Alice Workstation",
                "clientVersion", "1.0.0",
                "platform", "macos"
            ));

            JsonNode welcome = client.receive(3000);
            assertNotNull(welcome, "Expected welcome envelope");
            assertEquals("welcome", welcome.path("type").asText());
            String connId = welcome.path("connectionId").asText();
            JsonNode payload = welcome.path("payload");
            assertTrue(payload.path("authenticated").asBoolean());
            assertEquals(userAId.toString(), payload.path("userId").asText());
            assertEquals(userAUsername, payload.path("username").asText());
            assertEquals(enrolledDeviceId, payload.path("deviceId").asText());
            assertFalse(payload.path("sessionId").asText().isBlank());

            // 3. Verify registry reflects authenticated state and device is marked online
            var connOpt = bootstrap.getClientRegistry().get(connId);
            assertTrue(connOpt.isPresent());
            var conn = connOpt.get();
            assertTrue(conn.isAuthenticated());
            assertEquals(userAId, conn.userId());
            assertEquals(enrolledDeviceId, conn.deviceId());
            assertTrue(bootstrap.getClientRegistry().isDeviceOnline(enrolledDeviceId));
            assertTrue(bootstrap.getClientRegistry().isUserOnline(userAId));

            // 4. Send heartbeat
            client.sendJson(Map.of("type", "heartbeat", "requestId", "hb-1"));
            JsonNode hbAck = client.receive(3000);
            assertNotNull(hbAck);
            assertEquals("heartbeat_ack", hbAck.path("type").asText());
        }
    }

    @Test
    @Order(12)
    @DisplayName("12. Session revocation disconnects active WebSocket connection (Code 4002)")
    void testSessionRevocationDisconnectsRealtimeClient() throws Exception {
        try (FoundationWsClient client = FoundationWsClient.connect(wsPort, userAToken)) {
            client.sendJson(Map.of(
                "type", "hello",
                "protocolVersion", ProtocolConstants.PROTOCOL_VERSION,
                "clientInstanceId", SHARED_CLIENT_INSTANCE_ID,
                "deviceId", enrolledDeviceId,
                "deviceName", "Revocation Test Device"
            ));
            JsonNode welcome = client.receive(3000);
            assertNotNull(welcome);
            String testSessionId = welcome.path("payload").path("sessionId").asText();
            assertFalse(testSessionId.isBlank());

            // Revoke session via ClientRegistry
            boolean disconnected = bootstrap.getClientRegistry().disconnectSession(testSessionId, "SESSION_REVOKED", "Admin revoked session");
            assertTrue(disconnected, "Session disconnection must find and close connection");

            // Client must receive close code 4002
            int closeCode = client.awaitClose(3000);
            assertEquals(ProtocolConstants.CLOSE_SESSION_REVOKED, closeCode, "Should close with 4002 SESSION_REVOKED");
        }
    }

    @Test
    @Order(13)
    @DisplayName("13. Account disable disconnects active WebSocket connection (Code 4003)")
    void testAccountDisabledDisconnectsRealtimeClient() throws Exception {
        // Register a temporary user for disable testing
        String username = "realtimeDisable_" + System.currentTimeMillis();
        var regBody = JSON.writeValueAsString(Map.of(
            "email", username + "@example.com",
            "username", username,
            "password", "Password123!"
        ));
        var regResp = http.send(HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/auth/register"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(regBody))
            .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, regResp.statusCode());

        String tempToken = JSON.readTree(regResp.body()).path("accessToken").asText();
        UUID tempUserId = UUID.fromString(JSON.readTree(regResp.body()).path("user").path("id").asText());

        String tempInstanceId = "disable-user-instance-" + System.currentTimeMillis();
        var enrollResp = http.send(HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/devices/enroll"))
            .header("Authorization", "Bearer " + tempToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of(
                "clientInstanceId", tempInstanceId, "deviceName", "Disable User Test Device",
                "platform", "test", "clientVersion", "1.0.0"
            )))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, enrollResp.statusCode());
        String tempDeviceId = JSON.readTree(enrollResp.body()).path("id").asText();

        try (FoundationWsClient client = FoundationWsClient.connect(wsPort, tempToken)) {
            client.sendJson(Map.of(
                "type", "hello",
                "protocolVersion", ProtocolConstants.PROTOCOL_VERSION,
                "clientInstanceId", tempInstanceId,
                "deviceId", tempDeviceId,
                "deviceName", "Disable User Test Device"
            ));
            JsonNode welcome = client.receive(3000);
            assertNotNull(welcome);
            assertTrue(welcome.path("payload").path("authenticated").asBoolean());

            // Disable user via AdminControlCenterService
            AdminControlCenterService adminService = bootstrap.getAdminControlCenterService();
            adminService.disableUser(tempUserId, null, "TEST_ADMIN");

            // Client must receive close code 4003
            int closeCode = client.awaitClose(3000);
            assertEquals(ProtocolConstants.CLOSE_ACCOUNT_DISABLED, closeCode, "Should close with 4003 ACCOUNT_DISABLED");
        }
    }
}
