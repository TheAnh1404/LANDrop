package com.landrop.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.auth.dto.AuthResponse;
import com.landrop.auth.repository.RefreshTokenRepository;
import com.landrop.auth.security.JwtService;
import com.landrop.auth.server.AuthHttpHandler;
import com.landrop.auth.server.AuthMiddleware;
import com.landrop.auth.service.AuthService;
import com.landrop.chat.server.ChatHttpServer;
import com.landrop.chat.service.ChannelChatService;
import com.landrop.database.DatabaseConfig;
import com.landrop.database.DatabaseManager;
import com.landrop.database.model.User;
import com.landrop.database.repository.DeviceRepository;
import com.landrop.database.repository.UserRepository;
import com.landrop.server.clients.ClientRegistry;
import com.landrop.server.realtime.FoundationRealtimeServer;
import com.landrop.server.transfers.TransferCoordinator;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Existing REST handlers + foundation browser gateway + PostgreSQL, in a disposable schema. */
@EnabledIfSystemProperty(named = "landrop.test.postgres", matches = "true")
class Realtime01PostgresTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SCHEMA = "landrop_rt01_" + UUID.randomUUID().toString().replace("-", "");
    private static DatabaseManager adminDb;
    private static DatabaseManager db;
    private static boolean schemaCreated;
    private final JwtService jwt = new JwtService("rt01-access-test", "rt01-refresh-test", 900, 3600);
    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicBoolean failCommit = new AtomicBoolean();
    private final List<FoundationWsClient> clients = new ArrayList<>();
    private UserRepository users;
    private RefreshTokenRepository sessions;
    private AuthService authService;
    private ChannelChatService chat;
    private ClientRegistry registry;
    private FoundationRealtimeServer gateway;
    private HttpServer rest;
    private User alice, bob, outsider;
    private AuthResponse aliceAuth, bobAuth, outsiderAuth;
    private UUID workspace, channel, otherChannel, foreignChannel;

    @BeforeAll
    static void createIsolatedSchema() throws Exception {
        String url = DatabaseConfig.getDatabaseUrl();
        assertNotNull(url, "Configure the explicitly authorized test DATABASE_URL");
        URI endpoint = URI.create(url.replaceFirst("^jdbc:", ""));
        if (endpoint.getHost().endsWith(".neon.tech") && endpoint.getHost().contains("-pooler.")) {
            url = url.replace(endpoint.getHost(), endpoint.getHost().replace("-pooler.", "."));
        }
        // Transaction pooling can reset search_path; use a session-stable direct endpoint.
        adminDb = DatabaseManager.forSchema(url, "public");
        assertTrue(SCHEMA.matches("landrop_rt01_[a-f0-9]{32}"));
        try (var c = adminDb.getConnection(); var s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + SCHEMA);
            schemaCreated = true;
        }
        db = DatabaseManager.forSchema(url, SCHEMA);
        try (var c = db.getConnection(); var s = c.createStatement()) {
            assertEquals(SCHEMA, c.getSchema());
            for (String migration : List.of("V1__init_cloud_database_phase14.sql", "V2__auth_sessions_phase15.sql",
                    "V3__realtime_message_identity.sql", "V4__persistent_chat_phase17.sql", "V5__file_management_phase18.sql",
                    "V6__server_control_center_phase03.sql", "V7__authentication_device_enrollment_phase04.sql")) {
                try (var input = Realtime01PostgresTest.class.getResourceAsStream("/db/migration/" + migration)) {
                    assertNotNull(input);
                    String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace(
                            "WHERE typname = 'WorkspaceRole'", "WHERE typname = 'WorkspaceRole' AND typnamespace = current_schema()::regnamespace");
                    s.execute(sql);
                }
            }
        }
    }

    @AfterAll
    static void dropOnlyOurSchema() throws Exception {
        try {
            if (db != null) db.close();
            if (schemaCreated && adminDb != null) {
                assertTrue(SCHEMA.matches("landrop_rt01_[a-f0-9]{32}"));
                try (var c = adminDb.getConnection(); var s = c.createStatement()) {
                    s.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
                    try (var p = c.prepareStatement("SELECT 1 FROM pg_namespace WHERE nspname = ?")) {
                        p.setString(1, SCHEMA);
                        try (var rows = p.executeQuery()) { assertFalse(rows.next(), "Test schema must be removed"); }
                    }
                }
            }
        } finally {
            if (adminDb != null) adminDb.close();
        }
    }

    @BeforeEach
    void fixturesAndTransports() throws Exception {
        users = new UserRepository(db);
        sessions = new RefreshTokenRepository(db);
        authService = new AuthService(users, sessions, jwt);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        alice = users.create(new User("a" + suffix + "@example.test", "a" + suffix, "Alice", null));
        bob = users.create(new User("b" + suffix + "@example.test", "b" + suffix, "Bob", null));
        outsider = users.create(new User("c" + suffix + "@example.test", "c" + suffix, "Outsider", null));
        aliceAuth = authService.issueAuthTokens(alice);
        bobAuth = authService.issueAuthTokens(bob);
        outsiderAuth = authService.issueAuthTokens(outsider);
        workspace = UUID.randomUUID();
        UUID foreignWorkspace = UUID.randomUUID();
        channel = UUID.randomUUID(); otherChannel = UUID.randomUUID(); foreignChannel = UUID.randomUUID();
        sql("INSERT INTO workspaces (id,name,slug,owner_id) VALUES (?,'Team',?,?)", workspace, "team" + suffix, alice.id());
        sql("INSERT INTO workspaces (id,name,slug,owner_id) VALUES (?,'Foreign',?,?)", foreignWorkspace, "foreign" + suffix, bob.id());
        sql("INSERT INTO workspace_members (workspace_id,user_id,role) VALUES (?,?,'OWNER')", workspace, alice.id());
        sql("INSERT INTO workspace_members (workspace_id,user_id,role) VALUES (?,?,'MEMBER')", workspace, bob.id());
        sql("INSERT INTO workspace_members (workspace_id,user_id,role) VALUES (?,?,'OWNER')", foreignWorkspace, bob.id());
        sql("INSERT INTO channels (id,workspace_id,name) VALUES (?,?,'general')", channel, workspace);
        sql("INSERT INTO channels (id,workspace_id,name) VALUES (?,?,'other')", otherChannel, workspace);
        sql("INSERT INTO channels (id,workspace_id,name) VALUES (?,?,'foreign')", foreignChannel, foreignWorkspace);
        startTransports();
    }

    private void startTransports() throws Exception {
        registry = new ClientRegistry();
        gateway = new FoundationRealtimeServer(new InetSocketAddress("127.0.0.1", 0), registry,
                new TransferCoordinator(registry), 5000, 120000, Set.of("http://localhost:3000"), 15000);
        gateway.setAuthDependencies(jwt, users, new DeviceRepository(db), sessions);
        chat = new ChannelChatService(this::transactionConnection, gateway);
        gateway.setChannelChatService(chat);
        gateway.start(); gateway.awaitReady();
        var middleware = new AuthMiddleware(jwt, users, sessions);
        rest = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        new AuthHttpHandler(authService, middleware, registry).register(rest);
        new ChatHttpServer(middleware, chat, JSON).register(rest);
        rest.start();
    }

    private Connection transactionConnection() throws SQLException {
        Connection delegate = db.getConnection();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("commit") && failCommit.get()) throw new SQLException("Injected commit failure");
                    try { return method.invoke(delegate, args); }
                    catch (InvocationTargetException e) { throw e.getCause(); }
                });
    }

    @AfterEach
    void stopTransports() throws Exception {
        for (var client : clients) client.close();
        clients.clear();
        if (rest != null) rest.stop(0);
        if (gateway != null) gateway.stop(1000);
    }

    private static void sql(String sql, Object... parameters) throws SQLException {
        try (var c = db.getConnection(); var p = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) p.setObject(i + 1, parameters[i]);
            p.executeUpdate();
        }
    }

    private HttpRequest request(String method, String path, String token, Object body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + rest.getAddress().getPort() + path));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.header("Content-Type", "application/json");
        return b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
    }

    private JsonNode api(String method, String path, String token, Object body, int status) throws Exception {
        var result = http.send(request(method, path, token, body), HttpResponse.BodyHandlers.ofString());
        assertEquals(status, result.statusCode(), method + " " + path);
        return result.body().isBlank() ? null : JSON.readTree(result.body());
    }

    private FoundationWsClient connect(AuthResponse auth, UUID room) throws Exception {
        return connect(auth.accessToken(), room);
    }

    private FoundationWsClient connect(String accessToken, UUID room) throws Exception {
        var client = FoundationWsClient.connect(URI.create("ws://127.0.0.1:" + gateway.getPort() + "/"), Map.of("Origin", "http://localhost:3000"));
        clients.add(client);
        client.sendJson(Map.of("type", "hello", "protocolVersion", 1, "clientInstanceId", UUID.randomUUID().toString()));
        assertEquals("welcome", event(client).path("type").asText());
        client.sendJson(Map.of("type", "auth", "payload", Map.of("accessToken", accessToken)));
        assertEquals("auth_ack", event(client).path("type").asText());
        if (room != null) {
            client.sendJson(Map.of("type", "channel.join", "payload", Map.of("channelId", room)));
            assertEquals("channel.joined", event(client).path("type").asText());
        }
        return client;
    }

    private JsonNode event(FoundationWsClient client) throws Exception {
        JsonNode event = client.receive(5000);
        assertNotNull(event, "Expected realtime event");
        return event;
    }

    private String messagesPath() { return "/api/channels/" + channel + "/messages"; }

    private int count(UUID clientMessageId) throws SQLException {
        try (var c = db.getConnection(); var p = c.prepareStatement("SELECT count(*) FROM messages WHERE client_message_id = ?")) {
            p.setObject(1, clientMessageId);
            try (var rows = p.executeQuery()) { rows.next(); return rows.getInt(1); }
        }
    }

    @Test
    void realRestLoginAndRefreshAuthenticateBrowserWithoutEnrolledDevice() throws Exception {
        String name = "browser_" + UUID.randomUUID().toString().substring(0, 12);
        String password = "Realtime-Test-Password-123!";
        var registered = api("POST", "/api/auth/register", null, Map.of("username", name,
                "email", name + "@example.test", "password", password, "displayName", "Browser User"), 201);
        UUID userId = UUID.fromString(registered.path("user").path("id").asText());
        sql("INSERT INTO workspace_members (workspace_id,user_id,role) VALUES (?,?,'MEMBER')", workspace, userId);
        api("POST", "/api/auth/login", null, Map.of("emailOrUsername", name, "password", "wrong-password"), 401);
        var loggedIn = api("POST", "/api/auth/login", null, Map.of("emailOrUsername", name, "password", password), 200);
        String token = loggedIn.path("accessToken").asText();
        var browser = connect(token, channel);
        assertEquals(userId, registry.findByUserId(userId).getFirst().userId());
        assertNull(registry.findByUserId(userId).getFirst().deviceId());
        assertEquals(userId.toString(), api("GET", "/api/auth/me", token, null, 200).path("id").asText());
        var refreshed = api("POST", "/api/auth/refresh", null, Map.of("refreshToken", loggedIn.path("refreshToken").asText()), 200);
        assertEquals("SESSION_REVOKED", event(browser).path("error").path("code").asText());
        assertEquals(4002, browser.awaitClose(5000));
        api("GET", messagesPath(), token, null, 401);
        var rebound = connect(refreshed.path("accessToken").asText(), channel);
        api("POST", messagesPath(), refreshed.path("accessToken").asText(),
                Map.of("content", "after refresh", "clientMessageId", UUID.randomUUID()), 201);
        assertEquals("message.created", event(rebound).path("type").asText());
    }

    @Test
    void existingAdminRevokeAndDisableHooksTerminateActualPersistedSessions() throws Exception {
        var a = connect(aliceAuth, channel);
        var tab = connect(aliceAuth, channel);
        var independentAuth = authService.issueAuthTokens(alice);
        var independent = connect(independentAuth, channel);
        var control = new com.landrop.admin.service.AdminControlCenterService(users, sessions, registry, null, null, null);
        UUID sessionId = UUID.fromString(jwt.verifyToken(aliceAuth.accessToken(), false).sessionId());
        assertTrue(control.revokeSession(sessionId, "test-admin"));
        assertTrue(sessions.findById(sessionId).orElseThrow().revoked());
        for (var client : List.of(a, tab)) {
            assertEquals("SESSION_REVOKED", event(client).path("error").path("code").asText());
            assertEquals(4002, client.awaitClose(5000));
        }
        assertTrue(independent.isOpen());
        User admin = users.create(new User("admin" + UUID.randomUUID() + "@example.test", "admin" + UUID.randomUUID(), "Test Admin", null)
                .withSystemRole(User.ROLE_SYSTEM_ADMIN));
        assertTrue(control.disableUser(alice.id(), admin.id(), admin.username()));
        assertTrue(users.findById(alice.id()).orElseThrow().isDisabled());
        assertEquals("ACCOUNT_DISABLED", event(independent).path("error").path("code").asText());
        assertEquals(4003, independent.awaitClose(5000));
        assertTrue(sessions.findById(UUID.fromString(jwt.verifyToken(independentAuth.accessToken(), false).sessionId()))
                .orElseThrow().revoked());
        api("GET", messagesPath(), independentAuth.accessToken(), null, 401);
    }

    @Test
    void concurrentRestCreatePersistsOnceAndDeliversCanonicalOnlyToItsRoom() throws Exception {
        var sender = connect(aliceAuth, channel);
        var receiver = connect(bobAuth, channel);
        var anotherChannel = connect(aliceAuth, otherChannel);
        var anotherWorkspace = connect(bobAuth, foreignChannel);
        var denied = connect(outsiderAuth, null);
        denied.sendJson(Map.of("type", "channel.join", "payload", Map.of("channelId", channel)));
        assertEquals("FORBIDDEN", event(denied).path("error").path("code").asText());
        UUID clientMessageId = UUID.randomUUID();
        Map<String, Object> body = Map.of("content", "canonical content", "clientMessageId", clientMessageId);
        var first = http.sendAsync(request("POST", messagesPath(), aliceAuth.accessToken(), body), HttpResponse.BodyHandlers.ofString());
        var second = http.sendAsync(request("POST", messagesPath(), aliceAuth.accessToken(), body), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, first.get().statusCode()); assertEquals(201, second.get().statusCode());
        JsonNode canonical = JSON.readTree(first.get().body()).path("data");
        assertEquals(canonical, JSON.readTree(second.get().body()).path("data"));
        assertEquals(alice.id().toString(), canonical.path("senderId").asText());
        assertEquals(canonical, event(sender).path("payload").path("message"));
        assertEquals(canonical, event(receiver).path("payload").path("message"));
        assertEquals(1, count(clientMessageId));
        for (var client : List.of(sender, receiver, anotherChannel, anotherWorkspace, denied)) assertNull(client.receive(150));
        api("POST", messagesPath(), aliceAuth.accessToken(), Map.of("content", "different", "clientMessageId", clientMessageId), 409);
        api("GET", messagesPath(), outsiderAuth.accessToken(), null, 404);
        api("POST", messagesPath(), null, body, 401);
        assertEquals(canonical.path("id"), api("GET", messagesPath(), aliceAuth.accessToken(), null, 200).path("items").get(0).path("id"));
    }

    @Test
    void editDeleteAndReplyConflictUseExistingRestDtosAndCorrectEvents() throws Exception {
        var parent1 = chat.createMessage(alice.id(), channel, UUID.randomUUID(), "parent one", null).message();
        var parent2 = chat.createMessage(alice.id(), channel, UUID.randomUUID(), "parent two", null).message();
        var sender = connect(aliceAuth, channel);
        var receiver = connect(bobAuth, channel);
        UUID retryId = UUID.randomUUID();
        var created = api("POST", messagesPath(), aliceAuth.accessToken(), Map.of("content", "reply", "clientMessageId", retryId, "replyToId", parent1.id()), 201).path("data");
        assertEquals("message.created", event(sender).path("type").asText()); event(receiver);
        api("POST", messagesPath(), aliceAuth.accessToken(), Map.of("content", "reply", "clientMessageId", retryId, "replyToId", parent2.id()), 409);
        assertNull(receiver.receive(100));
        String messagePath = "/api/messages/" + created.path("id").asText();
        api("PATCH", messagePath, bobAuth.accessToken(), Map.of("content", "forbidden"), 403);
        var updated = api("PATCH", messagePath, aliceAuth.accessToken(), Map.of("content", "edited"), 200).path("data");
        for (var client : List.of(sender, receiver)) {
            var e = event(client); assertEquals("message.updated", e.path("type").asText());
            assertEquals(updated, e.path("payload").path("message"));
        }
        api("DELETE", messagePath, aliceAuth.accessToken(), null, 204);
        for (var client : List.of(sender, receiver)) {
            var e = event(client); assertEquals("message.deleted", e.path("type").asText());
            assertFalse(e.path("payload").path("message").path("deletedAt").isNull());
            assertEquals(created.path("id"), e.path("payload").path("messageId"));
        }
    }

    @Test
    void failedCommitDoesNotPersistOrBroadcastAndFanoutFailureDoesNotUndoSuccess() throws Exception {
        var receiver = connect(bobAuth, channel);
        UUID failedId = UUID.randomUUID();
        failCommit.set(true);
        api("POST", messagesPath(), aliceAuth.accessToken(), Map.of("content", "failed", "clientMessageId", failedId), 503);
        failCommit.set(false);
        assertEquals(0, count(failedId));
        assertNull(receiver.receive(150));
        UUID savedId = UUID.randomUUID();
        chat.setBroadcaster((room, e) -> { throw new IllegalStateException("Injected fanout failure"); });
        api("POST", messagesPath(), aliceAuth.accessToken(), Map.of("content", "saved", "clientMessageId", savedId), 201);
        assertEquals(1, count(savedId));
        assertNull(receiver.receive(150));
        assertEquals(1, api("GET", messagesPath(), bobAuth.accessToken(), null, 200).path("items").size());
    }

    @Test
    void membershipRemovalAndRealLogoutRevokeAllSharedTabs() throws Exception {
        var sender = connect(aliceAuth, channel);
        var tab = connect(aliceAuth, channel);
        var receiver = connect(bobAuth, channel);
        var independentAuth = authService.issueAuthTokens(alice);
        var independent = connect(independentAuth, channel);
        sql("DELETE FROM workspace_members WHERE workspace_id = ? AND user_id = ?", workspace, bob.id());
        api("POST", messagesPath(), aliceAuth.accessToken(), Map.of("content", "after removal", "clientMessageId", UUID.randomUUID()), 201);
        event(sender); event(tab); event(independent);
        assertNull(receiver.receive(200));
        api("GET", messagesPath(), bobAuth.accessToken(), null, 404);
        api("POST", "/api/auth/logout", aliceAuth.accessToken(), Map.of("refreshToken", aliceAuth.refreshToken()), 200);
        for (var client : List.of(sender, tab)) {
            assertEquals("SESSION_REVOKED", event(client).path("error").path("code").asText());
            assertEquals(4002, client.awaitClose(5000));
        }
        api("POST", messagesPath(), aliceAuth.accessToken(), Map.of("content", "revoked"), 401);
        api("POST", messagesPath(), independentAuth.accessToken(), Map.of("content", "independent", "clientMessageId", UUID.randomUUID()), 201);
        assertEquals("message.created", event(independent).path("type").asText());
    }

    @Test
    void backendRestartReauthRejoinAndRestPaginationRecoverCommittedHistory() throws Exception {
        var sender = connect(aliceAuth, channel);
        UUID firstId = UUID.randomUUID();
        var saved = api("POST", messagesPath(), aliceAuth.accessToken(), Map.of("content", "survives", "clientMessageId", firstId), 201).path("data");
        event(sender);
        stopTransports();
        startTransports();
        var rejoined = connect(aliceAuth, channel);
        assertEquals(saved.path("id"), api("GET", messagesPath(), aliceAuth.accessToken(), null, 200).path("items").get(0).path("id"));
        api("POST", messagesPath(), bobAuth.accessToken(), Map.of("content", "after restart", "clientMessageId", UUID.randomUUID()), 201);
        assertEquals("message.created", event(rejoined).path("type").asText());
        var page = api("GET", messagesPath() + "?limit=1&order=desc", aliceAuth.accessToken(), null, 200);
        assertEquals(1, page.path("items").size());
        String cursor = java.net.URLEncoder.encode(page.path("nextCursor").asText(), StandardCharsets.UTF_8);
        var older = api("GET", messagesPath() + "?limit=1&order=desc&cursor=" + cursor, aliceAuth.accessToken(), null, 200);
        assertEquals(saved.path("id"), older.path("items").get(0).path("id"));
        assertEquals(1, count(firstId));
    }
}
