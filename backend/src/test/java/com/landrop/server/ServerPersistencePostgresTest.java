package com.landrop.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.landrop.auth.dto.RegisterRequest;
import com.landrop.auth.exception.UnauthorizedException;
import com.landrop.auth.model.RefreshToken;
import com.landrop.auth.repository.RefreshTokenRepository;
import com.landrop.auth.security.JwtService;
import com.landrop.auth.security.PasswordHasher;
import com.landrop.auth.server.AuthHttpServer;
import com.landrop.auth.service.AuthService;
import com.landrop.database.DatabaseManager;
import com.landrop.database.migration.DatabaseMigrator;
import com.landrop.database.repository.UserRepository;
import com.landrop.server.bootstrap.ServerBootstrap;
import com.landrop.server.config.ServerConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Runs only on an explicitly selected test database, using one disposable schema per test. */
@EnabledIfSystemProperty(named = "landrop.test.postgres", matches = "true")
class ServerPersistencePostgresTest {
    private static final String PASSWORD = "Persistence-only-123!";
    private static final String ACCESS_SECRET = "persistence-access-secret-at-least-32-characters";
    private static final String REFRESH_SECRET = "persistence-refresh-secret-at-least-32-characters";
    private final String schema = "landrop_persistence_" + UUID.randomUUID().toString().replace("-", "");
    private final Map<String, String> originalProperties = new HashMap<>();
    private final com.fasterxml.jackson.databind.ObjectMapper json = AuthHttpServer.createObjectMapper();
    private String databaseUrl;
    private ServerBootstrap bootstrap;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach void setup() throws Exception {
        databaseUrl = System.getenv("TEST_DATABASE_URL");
        Assumptions.assumeTrue(databaseUrl != null && !databaseUrl.isBlank(), "Set TEST_DATABASE_URL to a dedicated test database");
        property("landrop.database.url", databaseUrl);
        property("landrop.database.schema", schema);
        property("landrop.database.enabled", "true");
        property("landrop.jwt.access.secret", ACCESS_SECRET);
        property("landrop.jwt.refresh.secret", REFRESH_SECRET);
        property("landrop.admin.password", PASSWORD);
        DatabaseManager.closeIfInitialized();
        try (var pool = DatabaseManager.forSchema(databaseUrl, "public"); var c = pool.getConnection(); var s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        }
    }

    @AfterEach void cleanup() throws Exception {
        if (bootstrap != null) bootstrap.stop();
        if (databaseUrl != null) {
            DatabaseManager.closeIfInitialized();
            try (var pool = DatabaseManager.forSchema(databaseUrl, "public"); var c = pool.getConnection(); var s = c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
        originalProperties.forEach((key, value) -> {
            if (value == null) System.clearProperty(key); else System.setProperty(key, value);
        });
    }

    private void property(String name, String value) {
        if (!originalProperties.containsKey(name)) originalProperties.put(name, System.getProperty(name));
        System.setProperty(name, value);
    }

    private DatabaseManager migrate() throws Exception {
        var db = DatabaseManager.getInstance();
        new DatabaseMigrator(db).migrate();
        return db;
    }

    private AuthService auth(DatabaseManager db, RefreshTokenRepository sessions) {
        return new AuthService(new UserRepository(db), sessions, new JwtService(ACCESS_SECRET, REFRESH_SECRET, 900, 3600));
    }

    private RegisterRequest registration(String username) {
        return new RegisterRequest(username + "@example.test", username, PASSWORD, username);
    }

    @Test void completeClientDataAndCredentialsSurviveRestartOfTheSameServer() throws Exception {
        bootstrap = new ServerBootstrap(new ServerConfig("127.0.0.1", 0, 0, 1, 30000, 90000, "test", "INFO"));
        bootstrap.start(); // Empty schema must be migrated before any API accepts a request.
        assertEquals(bootstrap.getRealtimeServer().getPort(), request("GET", "/status", null, null, 200).path("wsPort").asInt());
        var owner = request("POST", "/api/auth/register", null,
            Map.of("username", "owner", "email", "owner@example.test", "password", PASSWORD), 201);
        var member = request("POST", "/api/auth/register", null,
            Map.of("username", "member", "email", "member@example.test", "password", PASSWORD), 201);
        String token = owner.path("accessToken").asText();
        String memberToken = member.path("accessToken").asText();
        String workspace = request("POST", "/api/workspaces", token, Map.of("name", "Persistence team"), 201).path("data").path("id").asText();
        request("POST", "/api/workspaces/" + workspace + "/members", token, Map.of("email", "member@example.test"), 201);
        String channel = request("POST", "/api/workspaces/" + workspace + "/channels", token, Map.of("name", "general"), 201).path("data").path("id").asText();
        String message = request("POST", "/api/channels/" + channel + "/messages", token,
            Map.of("content", "Persist this message", "clientMessageId", UUID.randomUUID().toString()), 201).path("data").path("id").asText();
        String device = request("POST", "/api/devices/enroll", token,
            Map.of("clientInstanceId", "persistence-installation", "deviceName", "Test desktop", "platform", "web"), 201).path("id").asText();
        String folder = request("POST", "/api/workspaces/" + workspace + "/folders", token, Map.of("name", "Documents"), 201).path("data").path("id").asText();
        String file = request("POST", "/api/workspaces/" + workspace + "/files", token,
            Map.of("name", "notes.txt", "size", 32, "mimeType", "text/plain", "folderId", folder), 201).path("data").path("id").asText();
        String transfer = request("POST", "/api/transfers", token,
            Map.of("fileId", file, "receiverUserId", member.path("user").path("id").asText()), 201).path("data").path("id").asText();
        request("POST", "/api/transfers/" + transfer + "/accept", memberToken, Map.of(), 200);
        request("POST", "/api/transfers/" + transfer + "/progress", token,
            Map.of("bytesTransferred", 32, "totalBytes", 32, "speed", 32), 200);
        request("POST", "/api/transfers/" + transfer + "/complete", token, Map.of(), 200);

        var db = DatabaseManager.getInstance();
        assertEquals(1, count(db, "devices"));
        assertEquals(2, count(db, "refresh_tokens"));
        assertEquals(2, count(db, "workspace_members"));
        for (String table : List.of("workspaces", "channels", "messages", "folders", "files", "transfers")) assertEquals(1, count(db, table), table);
        var admin = bootstrap.getUserRepository().findByUsername("admin").orElseThrow();
        bootstrap.getUserRepository().updatePasswordHash(admin.id(), PasswordHasher.hash("Changed-admin-456!"));

        bootstrap.stop();
        bootstrap.start();
        assertTrue(PasswordHasher.verify("Changed-admin-456!", bootstrap.getUserRepository().findById(admin.id()).orElseThrow().passwordHash()));
        request("GET", "/api/auth/me", token, null, 200);
        request("GET", "/api/workspaces/" + workspace, token, null, 200);
        assertEquals("Persist this message", request("GET", "/api/channels/" + channel + "/messages", token, null, 200).path("data").get(0).path("content").asText());
        request("GET", "/api/folders/" + folder, token, null, 200);
        request("GET", "/api/files/" + file, token, null, 200);
        assertEquals("COMPLETED", request("GET", "/api/transfers/" + transfer, token, null, 200).path("data").path("status").asText());
        request("GET", "/api/devices", token, null, 200);
        var refreshed = request("POST", "/api/auth/refresh", null, Map.of("refreshToken", owner.path("refreshToken").asText()), 200);
        token = refreshed.path("accessToken").asText();
        request("PATCH", "/api/messages/" + message, token, Map.of("content", "Edited after restart"), 200);
        request("DELETE", "/api/messages/" + message, token, null, 204);
        request("PATCH", "/api/files/" + file, token, Map.of("name", "renamed.txt"), 200);
        request("DELETE", "/api/files/" + file, token, null, 200);
        request("DELETE", "/api/folders/" + folder, token, null, 200);
        var login = request("POST", "/api/auth/login", null, Map.of("identifier", "owner", "password", PASSWORD), 200);
        request("POST", "/api/auth/logout", null, Map.of("refreshToken", login.path("refreshToken").asText()), 200);
        request("GET", "/api/auth/me", login.path("accessToken").asText(), null, 401);
        assertEquals(1, count(DatabaseManager.getInstance(), "devices"));
        bootstrap.stop();
        bootstrap.start();
        request("GET", "/api/auth/me", login.path("accessToken").asText(), null, 401);
        assertTrue(request("GET", "/api/channels/" + channel + "/messages", token, null, 200).path("data").get(0).path("deletedAt").isTextual());
        try (var c = DatabaseManager.getInstance().getConnection(); var s = c.createStatement()) {
            for (String table : List.of("files", "folders")) {
                try (var r = s.executeQuery("SELECT count(*) FROM " + table + " WHERE deleted_at IS NOT NULL")) { r.next(); assertEquals(1, r.getLong(1)); }
            }
        }
    }

    @Test void failedSessionInsertRollsBackRegistrationAndAllowsRetry() throws Exception {
        var db = migrate();
        var failing = new RefreshTokenRepository(db) {
            @Override public RefreshToken create(Connection conn, RefreshToken token) throws SQLException {
                throw new SQLException("Injected session insert failure", "08006");
            }
        };
        assertThrows(SQLException.class, () -> auth(db, failing).register(registration("retry_user")));
        assertTrue(new UserRepository(db).findByUsername("retry_user").isEmpty());
        assertEquals(0, count(db, "refresh_tokens"));
        assertNotNull(auth(db, new RefreshTokenRepository(db)).register(registration("retry_user")));
        assertEquals(1, count(db, "users"));
        assertEquals(1, count(db, "refresh_tokens"));
    }

    @Test void failedRefreshInsertKeepsOldSessionUsable() throws Exception {
        var db = migrate();
        var sessions = new RefreshTokenRepository(db);
        var good = auth(db, sessions);
        var registered = good.register(registration("refresh_user"));
        var failing = new RefreshTokenRepository(db) {
            @Override public RefreshToken create(Connection conn, RefreshToken token) throws SQLException {
                throw new SQLException("Injected replacement session failure");
            }
        };
        assertThrows(SQLException.class, () -> auth(db, failing).refresh(registered.refreshToken()));
        assertFalse(sessions.findByTokenHash(JwtService.hashToken(registered.refreshToken())).orElseThrow().revoked());
        assertEquals(1, sessions.countActiveSessions());
        assertNotNull(good.refresh(registered.refreshToken()));
        assertEquals(1, sessions.countActiveSessions());
    }

    @Test void concurrentRefreshConsumesOriginalSessionOnlyOnce() throws Exception {
        var db = migrate();
        var service = auth(db, new RefreshTokenRepository(db));
        String token = service.register(registration("concurrent_user")).refreshToken();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Boolean> refresh = () -> {
                start.await();
                try { service.refresh(token); return true; } catch (UnauthorizedException expected) { return false; }
            };
            var first = executor.submit(refresh);
            var second = executor.submit(refresh);
            start.countDown();
            assertNotEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, new RefreshTokenRepository(db).countActiveSessions());
        assertEquals(2, count(db, "refresh_tokens"));
    }

    @Test void migrationsAreRecordedOnceAndChecksumMismatchAborts() throws Exception {
        var db = migrate();
        long first = count(db, "landrop_schema_history");
        new DatabaseMigrator(db).migrate();
        assertEquals(first, count(db, "landrop_schema_history"));
        try (var c = db.getConnection(); var s = c.createStatement()) { s.executeUpdate("UPDATE landrop_schema_history SET checksum = repeat('0', 64) WHERE version = 1"); }
        assertThrows(SQLException.class, () -> new DatabaseMigrator(db).migrate());
        assertEquals(0, count(db, "users"));
    }

    @Test void failedDeviceSessionBindingRollsBackDeviceEnrollment() throws Exception {
        var db = migrate();
        var sessions = new RefreshTokenRepository(db);
        var registered = auth(db, sessions).register(registration("device_user"));
        var session = sessions.findByTokenHash(JwtService.hashToken(registered.refreshToken())).orElseThrow();
        var failing = new RefreshTokenRepository(db) {
            @Override public boolean bindDevice(Connection conn, UUID id, UUID user, String device) {
                return false;
            }
        };
        var service = new com.landrop.device.service.DeviceService(new com.landrop.database.repository.DeviceRepository(db), null, failing);
        var request = new com.landrop.device.dto.DeviceEnrollRequest("installation", "Desktop", null, "web", "1.0");
        assertThrows(com.landrop.auth.exception.ConflictException.class,
            () -> service.enrollDevice(session.userId(), session.id(), request));
        assertEquals(0, count(db, "devices"));
        assertNull(sessions.findById(session.id()).orElseThrow().deviceId());
    }

    private long count(DatabaseManager db, String table) throws SQLException {
        try (var c = db.getConnection(); var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM " + table)) { r.next(); return r.getLong(1); }
    }

    private JsonNode request(String method, String path, String token, Object body, int expected) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + bootstrap.getHttpServer().getPort() + path));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        var parsed = response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
        assertEquals(expected, response.statusCode(), method + " " + path + ": " + parsed.path("message").asText());
        return parsed;
    }
}
