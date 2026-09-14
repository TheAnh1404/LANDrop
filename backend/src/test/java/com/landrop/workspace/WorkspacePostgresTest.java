package com.landrop.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.auth.repository.RefreshTokenRepository;
import com.landrop.auth.security.JwtService;
import com.landrop.auth.server.AuthHttpServer;
import com.landrop.auth.server.AuthMiddleware;
import com.landrop.auth.service.AuthService;
import com.landrop.database.DatabaseConfig;
import com.landrop.database.DatabaseManager;
import com.landrop.database.repository.UserRepository;
import com.landrop.database.repository.WorkspaceRepository;
import com.landrop.database.service.WorkspaceService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in Phase 16 API verification against an isolated PostgreSQL schema. */
@EnabledIfSystemProperty(named = "landrop.test.postgres", matches = "true")
class WorkspacePostgresTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "WorkspaceTest-only-123!";
    private static final String SCHEMA = "landrop_ws_test_" + UUID.randomUUID().toString().replace("-", "");
    private static DatabaseManager db;
    private static AuthHttpServer server;
    private static URI base;
    private static HttpClient http;
    private static boolean schemaCreated;
    private static String ownerToken, adminToken, memberToken, outsiderToken;

    @BeforeAll static void setup() throws Exception {
        String url = DatabaseConfig.getDatabaseUrl(); assertNotNull(url);
        URI configured = URI.create(url.replaceFirst("^jdbc:", ""));
        if (configured.getHost().endsWith(".neon.tech") && configured.getHost().contains("-pooler."))
            url = url.replace(configured.getHost(), configured.getHost().replace("-pooler.", "."));
        try (DatabaseManager publicDb = DatabaseManager.forSchema(url, "public"); var c = publicDb.getConnection(); var s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + SCHEMA); schemaCreated = true;
        }
        db = DatabaseManager.forSchema(url, SCHEMA);
        try (var c = db.getConnection(); var s = c.createStatement()) {
            assertEquals(SCHEMA, c.getSchema());
            for (String resource : List.of("V1__init_cloud_database_phase14.sql", "V2__auth_sessions_phase15.sql", "V3__realtime_message_identity.sql")) {
                try (var in = WorkspacePostgresTest.class.getResourceAsStream("/db/migration/" + resource)) {
                    assertNotNull(in); String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                            .replace("WHERE typname = 'WorkspaceRole'", "WHERE typname = 'WorkspaceRole' AND typnamespace = current_schema()::regnamespace");
                    s.execute(sql);
                }
            }
        }
        var users = new UserRepository(db);
        var jwt = new JwtService("workspace-access-key-with-32-characters", "workspace-refresh-key-with-32-characters", 900, 3600);
        var auth = new AuthService(users, new RefreshTokenRepository(db), jwt);
        server = new AuthHttpServer(0, auth, new AuthMiddleware(jwt), new WorkspaceService(new WorkspaceRepository(db)));
        server.start(); base = URI.create(server.getBaseUrl()); http = HttpClient.newHttpClient();
        registerUsers();
    }

    @AfterAll static void cleanup() throws Exception {
        if (server != null) server.stop(0);
        if (db != null) { if (schemaCreated) try (var c = db.getConnection(); var s = c.createStatement()) { s.execute("DROP SCHEMA " + SCHEMA + " CASCADE"); } db.close(); }
    }

    private static void registerUsers() throws Exception {
        ownerToken = register("owner").path("accessToken").asText();
        adminToken = register("admin").path("accessToken").asText();
        memberToken = register("member").path("accessToken").asText();
        outsiderToken = register("outsider").path("accessToken").asText();
    }

    @Test void ownerCreatesWorkspaceAndSeesOnlyOwnMembership() throws Exception {
        JsonNode created = request("POST", "/api/workspaces", ownerToken, Map.of("name", "Product", "description", "Core"), 201);
        String id = created.path("data").path("id").asText();
        assertEquals("OWNER", created.path("data").path("role").asText());
        JsonNode list = request("GET", "/api/workspaces", ownerToken, null, 200);
        assertEquals(1, list.path("data").size());
        assertEquals(404, raw("GET", "/api/workspaces/" + id, outsiderToken, null).statusCode());
    }

    @Test void memberLifecycleAndRoleRulesAreEnforced() throws Exception {
        String id = request("POST", "/api/workspaces", ownerToken, Map.of("name", "Team"), 201).path("data").path("id").asText();
        request("POST", "/api/workspaces/" + id + "/members", ownerToken, Map.of("email", "admin@example.com"), 201);
        request("POST", "/api/workspaces/" + id + "/members", ownerToken, Map.of("email", "member@example.com"), 201);
        request("PATCH", "/api/workspaces/" + id + "/members/" + userId("admin"), ownerToken, Map.of("role", "ADMIN"), 200);
        assertEquals(403, raw("DELETE", "/api/workspaces/" + id + "/members/" + userId("admin"), memberToken, null).statusCode());
        request("POST", "/api/workspaces/" + id + "/leave", memberToken, null, 204);
        assertEquals(404, raw("GET", "/api/workspaces/" + id + "/members", memberToken, null).statusCode());
        request("POST", "/api/workspaces/" + id + "/members", adminToken, Map.of("email", "member@example.com"), 201);
        request("DELETE", "/api/workspaces/" + id + "/members/" + userId("member"), adminToken, null, 204);
        assertEquals(409, raw("POST", "/api/workspaces/" + id + "/leave", ownerToken, null).statusCode());
    }

    @Test void outsiderCannotReadAndOwnerCanUpdateDelete() throws Exception {
        String id = request("POST", "/api/workspaces", ownerToken, Map.of("name", "To delete"), 201).path("data").path("id").asText();
        assertEquals(404, raw("GET", "/api/workspaces/" + id, outsiderToken, null).statusCode());
        request("PATCH", "/api/workspaces/" + id, ownerToken, Map.of("name", "Renamed"), 200);
        request("DELETE", "/api/workspaces/" + id, ownerToken, null, 204);
        assertEquals(404, raw("GET", "/api/workspaces/" + id, ownerToken, null).statusCode());
    }

    private static JsonNode register(String name) throws Exception { return request("POST", "/api/auth/register", "", Map.of("username", name, "displayName", name, "email", name + "@example.com", "password", PASSWORD), 201); }
    private static JsonNode login(String name) throws Exception { return request("POST", "/api/auth/login", "", Map.of("email", name + "@example.com", "password", PASSWORD), 200); }
    private static String userId(String name) throws Exception { return new com.landrop.database.repository.UserRepository(db).findByEmail(name + "@example.com").orElseThrow().id().toString(); }
    private static JsonNode request(String method, String path, String token, Object body, int status) throws Exception { var response = raw(method, path, token, body); assertEquals(status, response.statusCode(), response.body()); return response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body()); }
    private static HttpResponse<String> raw(String method, String path, String token, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(base.resolve(path)); if (token != null && !token.isBlank()) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
        return http.send(switch (method) { case "POST" -> builder.POST(publisher).build(); case "PATCH" -> builder.method("PATCH", publisher).build(); case "DELETE" -> builder.DELETE().build(); default -> builder.GET().build(); }, HttpResponse.BodyHandlers.ofString());
    }
}
