package com.landrop.realtime;

import com.fasterxml.jackson.databind.*;
import com.landrop.auth.repository.RefreshTokenRepository;
import com.landrop.auth.security.JwtService;
import com.landrop.auth.server.*;
import com.landrop.auth.service.AuthService;
import com.landrop.database.*;
import com.landrop.database.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in integration against PostgreSQL/Neon, always inside a random temporary schema. */
@EnabledIfSystemProperty(named = "landrop.test.postgres", matches = "true")
class RealtimePostgresTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String PASSWORD = "RealtimeTest-only-123!";
    static final String SCHEMA = "landrop_rt_test_" + UUID.randomUUID().toString().replace("-", "");
    static DatabaseManager db;
    static AuthHttpServer authHttp;
    static RealtimeServer realtime;
    static JdbcRealtimeStore store;
    static JsonNode a, b, c;
    static UUID userA, userB, userC, channel, workspace;
    static URI authBase;
    static HttpClient http;
    static boolean schemaCreated;

    @BeforeAll static void setup() throws Exception {
        String url = DatabaseConfig.getDatabaseUrl();
        assertNotNull(url, "Configure DATABASE_URL for the explicitly requested PostgreSQL test.");
        // Neon PgBouncer transaction pooling cannot preserve currentSchema/search_path.
        // The direct endpoint addresses the same database and keeps test isolation reliable.
        URI configured = URI.create(url.replaceFirst("^jdbc:", ""));
        if (configured.getHost().endsWith(".neon.tech") && configured.getHost().contains("-pooler."))
            url = url.replace(configured.getHost(), configured.getHost().replace("-pooler.", "."));
        db = DatabaseManager.forSchema(url, "public");
        try (var conn = db.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA " + SCHEMA);
            schemaCreated = true;
        }
        db.close();
        db = DatabaseManager.forSchema(url, SCHEMA);
        new com.landrop.database.migration.DatabaseMigrator(db).migrate();
        var users = new UserRepository(db);
        var jwt = new JwtService("integration-access-key-with-32-characters", "integration-refresh-key-with-32-characters", 900, 3600);
        var auth = new AuthService(users, new RefreshTokenRepository(db), jwt);
        authHttp = new AuthHttpServer(0, auth, new AuthMiddleware(jwt)); authHttp.start();
        authBase = URI.create(authHttp.getBaseUrl()); http = HttpClient.newHttpClient();
        a = register("u1"); b = register("u2"); c = register("u3");
        userA = UUID.fromString(a.path("user").path("id").asText());
        userB = UUID.fromString(b.path("user").path("id").asText());
        userC = UUID.fromString(c.path("user").path("id").asText());
        workspace = UUID.randomUUID(); channel = UUID.randomUUID();
        try (var conn = db.getConnection()) {
            try (var ps = conn.prepareStatement("INSERT INTO workspaces(id,name,slug,owner_id) VALUES (?, 'Integration fixture', 'integration-fixture', ?)")) {
                ps.setObject(1,workspace); ps.setObject(2,userA); ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("INSERT INTO channels(id,workspace_id,name) VALUES (?, ?, 'integration-chat')")) {
                ps.setObject(1,channel); ps.setObject(2,workspace); ps.executeUpdate();
            }
            for (UUID user : List.of(userA,userB,userC)) {
                try (var ps = conn.prepareStatement("INSERT INTO workspace_members(workspace_id,user_id) VALUES (?,?)")) {
                    ps.setObject(1,workspace); ps.setObject(2,user); ps.executeUpdate();
                }
            }
        }
        store = new JdbcRealtimeStore(db);
        realtime = new RealtimeServer(new InetSocketAddress("127.0.0.1",0), SocketAuthenticator.jwt(jwt,users),store);
        realtime.start(); realtime.awaitReady();
    }
    @AfterAll static void cleanup() throws Exception {
        if (realtime != null) realtime.stop(1000);
        if (authHttp != null) authHttp.stop(0);
        if (db != null) {
            try {
                if (schemaCreated) try (var conn = db.getConnection(); var stmt = conn.createStatement()) {
                    stmt.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
                }
            } finally { db.close(); }
        }
    }
    @BeforeEach void clearMessages() throws Exception {
        try (var conn = db.getConnection(); var stmt = conn.createStatement()) {
            assertEquals(SCHEMA, conn.getSchema());
            stmt.execute("DELETE FROM messages");
        }
    }
    static JsonNode register(String name) throws Exception {
        return response("register", Map.of("username", "test_" + name, "displayName", name,
                "email",name+"@example.com","password",PASSWORD),201);
    }
    static JsonNode loginA() throws Exception {
        return response("login",Map.of("email","u1@example.com","password",PASSWORD),200);
    }
    static HttpResponse<String> post(String operation,Object payload) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(authBase + "/api/auth/" + operation))
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload))).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    static JsonNode response(String op,Object payload,int status) throws Exception {
        var response = post(op,payload); assertEquals(status,response.statusCode());
        return JSON.readTree(response.body());
    }
    static WsProbe connect(JsonNode tokens) throws Exception {
        var probe = WsProbe.open(realtime,channel,tokens.path("accessToken").asText());
        probe.event("connected"); return probe;
    }

    @Test void realLoginMultiUserMultiDeviceAndSessionRestorePreserveChat() throws Exception {
        JsonNode laptopTokens = loginA();
        JsonNode desktopTokens = loginA();
        var sessions = new RefreshTokenRepository(db);
        assertTrue(sessions.findActiveByUserId(userA).size() >= 2);
        try (var laptop = connect(laptopTokens); var desktop = connect(desktopTokens);
             var user2 = connect(b); var user3 = connect(c)) {
            UUID id = UUID.randomUUID(); laptop.message(id,"hello PostgreSQL");
            var message = laptop.event("message.created").path("message");
            assertEquals(userA.toString(),message.path("senderId").asText());
            for (var p : List.of(desktop,user2,user3)) assertEquals(message,p.event("message.created").path("message"));
            user2.message(UUID.randomUUID(),"reply");
            for (var p : List.of(laptop,desktop,user2,user3))
                assertEquals(userB.toString(),p.event("message.created").path("message").path("senderId").asText());
            laptop.close(); laptop.closed.get(5,TimeUnit.SECONDS);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (realtime.registry().count(userA) != 1 && System.nanoTime() < end) Thread.sleep(10);
            assertEquals(1,realtime.registry().count(userA));
            desktop.message(UUID.randomUUID(),"same user remains");
            for (var p : List.of(desktop,user2,user3)) p.event("message.created");
            response("logout",Map.of("refreshToken",laptopTokens.path("refreshToken").asText()),200);
            assertEquals(401,post("refresh",Map.of("refreshToken",laptopTokens.path("refreshToken").asText())).statusCode());
            JsonNode restored = response("refresh",Map.of("refreshToken",desktopTokens.path("refreshToken").asText()),200);
            var me = http.send(HttpRequest.newBuilder(URI.create(authBase + "/api/auth/me"))
                    .header("Authorization","Bearer " + restored.path("accessToken").asText()).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200,me.statusCode()); assertEquals(userA.toString(),JSON.readTree(me.body()).path("id").asText());
            try (var freshClient = connect(restored)) {
                freshClient.history();
                assertEquals(3,freshClient.event("history").path("page").path("messages").size());
            }
        }
        // Replace the listener/store to prove history does not depend on in-memory chat state.
        realtime.stop(1000);
        var users = new UserRepository(db);
        var jwt = new JwtService("integration-access-key-with-32-characters", "integration-refresh-key-with-32-characters",900,3600);
        realtime = new RealtimeServer(new InetSocketAddress("127.0.0.1",0),SocketAuthenticator.jwt(jwt,users),new JdbcRealtimeStore(db));
        realtime.start(); realtime.awaitReady();
        try (var restarted = connect(loginA())) {
            restarted.history(); assertEquals(3,restarted.event("history").path("page").path("messages").size());
        }
    }
    @Test void concurrentRetriesPersistOnceAndKeysetHistoryDoesNotLoseEqualTimestamps() throws Exception {
        UUID clientId = UUID.randomUUID();
        List<RealtimeStore.Saved> results;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Callable<RealtimeStore.Saved>>();
            for (int i=0;i<8;i++) tasks.add(() -> store.save(userA,channel,clientId,"one message"));
            results = new ArrayList<>();
            for (var future : executor.invokeAll(tasks)) results.add(future.get());
        }
        assertEquals(1,results.stream().filter(RealtimeStore.Saved::inserted).count());
        assertEquals(1,results.stream().map(s -> s.message().id()).distinct().count());
        assertThrows(RealtimeException.class, () -> store.save(userA,channel,clientId,"changed"));
        store.save(userB,channel,clientId,"another user's own ID");
        for (int i=0;i<3;i++) store.save(userA,channel,UUID.randomUUID(),"page "+i);
        try (var conn = db.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("UPDATE messages SET created_at = '2026-09-14T00:00:00Z'");
        }
        Set<UUID> ids = new HashSet<>(); String cursor = null;
        do {
            var page = store.history(userA,channel,2,cursor);
            for (var message : page.messages()) assertTrue(ids.add(message.id()));
            cursor = page.nextCursor();
        } while (cursor != null);
        assertEquals(5,ids.size());
        assertThrows(RealtimeException.class, () -> store.history(userA,channel,10,"not-a-cursor"));
        assertThrows(RealtimeException.class, () -> store.history(UUID.randomUUID(),channel,10,null));
        try (var conn = db.getConnection(); var ps = conn.prepareStatement("UPDATE channels SET is_private=TRUE WHERE id=?")) {
            ps.setObject(1,channel); ps.executeUpdate();
        }
        try { assertFalse(store.canAccess(userA,channel)); }
        finally {
            try (var conn = db.getConnection(); var ps = conn.prepareStatement("UPDATE channels SET is_private=FALSE WHERE id=?")) {
                ps.setObject(1,channel); ps.executeUpdate();
            }
        }
    }
    @Test void concurrentRefreshAllowsOnlyOneWinnerWithoutRevokingSiblingDevice() throws Exception {
        JsonNode first = loginA(), sibling = loginA();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = List.<Callable<Integer>>of(
                    () -> post("refresh",Map.of("refreshToken",first.path("refreshToken").asText())).statusCode(),
                    () -> post("refresh",Map.of("refreshToken",first.path("refreshToken").asText())).statusCode());
            var statuses = new ArrayList<Integer>();
            for (var future : executor.invokeAll(tasks)) statuses.add(future.get());
            Collections.sort(statuses); assertEquals(List.of(200,401),statuses);
        }
        response("refresh",Map.of("refreshToken",sibling.path("refreshToken").asText()),200);
    }
}
