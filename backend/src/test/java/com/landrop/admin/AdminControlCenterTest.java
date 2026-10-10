package com.landrop.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.admin.dto.*;
import com.landrop.admin.logging.OperationalLogEntry;
import com.landrop.admin.logging.OperationalLogService;
import com.landrop.admin.security.AdminAuthMiddleware;
import com.landrop.admin.security.AdminBootstrapService;
import com.landrop.admin.server.AdminHttpServer;
import com.landrop.admin.service.AdminControlCenterService;
import com.landrop.auth.exception.ForbiddenException;
import com.landrop.auth.exception.UnauthorizedException;
import com.landrop.auth.exception.ValidationException;
import com.landrop.auth.model.RefreshToken;
import com.landrop.auth.repository.RefreshTokenRepository;
import com.landrop.auth.security.JwtClaims;
import com.landrop.auth.security.JwtService;
import com.landrop.auth.security.PasswordHasher;
import com.landrop.database.model.User;
import com.landrop.database.repository.UserRepository;
import com.landrop.server.FoundationWsClient;
import com.landrop.server.bootstrap.ServerBootstrap;
import com.landrop.server.clients.ClientRegistry;
import com.landrop.server.config.ServerConfig;
import com.landrop.server.config.ServerConfigManager;
import com.landrop.server.network.NetworkInterfaceInspector;
import com.landrop.server.protocol.ProtocolConstants;
import com.landrop.server.transfers.TransferCoordinator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit and integration tests for LANDrop Server Control Center (FOUNDATION-03A).
 * Validates System Admin, Server Info, Network Interfaces, Server Config,
 * Dashboard, User Management, Sessions, Clients, Logging, and Admin Web Console.
 */
public class AdminControlCenterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String JWT_SECRET = "0123456789012345678901234567890123456789";

    private JwtService jwtService;
    private HttpClient httpClient;
    private ServerBootstrap bootstrap;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(JWT_SECRET, JWT_SECRET, 3600L, 86400L);
        httpClient = HttpClient.newHttpClient();
        cleanupConfigFile();
    }

    @AfterEach
    void tearDown() {
        if (bootstrap != null && bootstrap.isRunning()) {
            bootstrap.stop();
        }
        cleanupConfigFile();
    }

    private void cleanupConfigFile() {
        try {
            Files.deleteIfExists(Path.of("data/server-config.json"));
        } catch (Exception ignored) {}
    }

    // =========================================================================
    // 1. System Admin Model & Claims Tests (03A.1 & 03A.2)
    // =========================================================================

    @Test
    @DisplayName("03A.1: User model and JWT claims correctly represent SYSTEM_ADMIN role")
    void testUserAndJwtAdminRole() {
        UUID id = UUID.randomUUID();
        User adminUser = new User(
            id,
            "admin@landrop.local",
            "admin",
            "System Admin",
            null,
            PasswordHasher.hash("secure123"),
            User.STATUS_ACTIVE,
            User.ROLE_SYSTEM_ADMIN,
            Instant.now(),
            Instant.now()
        );

        assertTrue(adminUser.isSystemAdmin());
        assertTrue(adminUser.isActive());
        assertFalse(adminUser.isDisabled());

        User regularUser = new User("user@landrop.local", "user", "User", null);
        assertFalse(regularUser.isSystemAdmin());
        assertEquals("USER", regularUser.systemRole());
        assertEquals("ACTIVE", regularUser.status());

        // Token generation and claim extraction
        String adminToken = jwtService.generateAccessToken(adminUser);
        JwtClaims adminClaims = jwtService.verifyToken(adminToken, false);
        assertTrue(adminClaims.isSystemAdmin());
        assertEquals(User.ROLE_SYSTEM_ADMIN, adminClaims.systemRole());

        String userToken = jwtService.generateAccessToken(regularUser);
        JwtClaims userClaims = jwtService.verifyToken(userToken, false);
        assertFalse(userClaims.isSystemAdmin());
        assertEquals("USER", userClaims.systemRole());
    }

    @Test
    @DisplayName("03A.1b: Fixed local administrator credential is restored on bootstrap")
    void testFixedAdminCredential() throws Exception {
        ServerConfig config = new ServerConfig("127.0.0.1", 0, 0, 1, 1000L, 3000L, "test", "INFO");
        bootstrap = new ServerBootstrap(config);
        bootstrap.start();
        org.junit.jupiter.api.Assumptions.assumeTrue(bootstrap.getUserRepository() != null, "PostgreSQL repository required for admin credential test");

        UserRepository users = bootstrap.getUserRepository();
        AdminBootstrapService fixedAdmin = new AdminBootstrapService(users);
        User admin = fixedAdmin.ensureSystemAdminExists();
        User persisted = users.findByUsername(AdminBootstrapService.FIXED_ADMIN_USERNAME).orElseThrow();
        assertTrue(PasswordHasher.verify(AdminBootstrapService.FIXED_ADMIN_PASSWORD, persisted.passwordHash()));
        assertTrue(persisted.isSystemAdmin());
        assertTrue(persisted.isActive());

        users.updatePasswordHash(persisted.id(), PasswordHasher.hash("a-different-password"));
        fixedAdmin.ensureSystemAdminExists();
        User restored = users.findById(persisted.id()).orElseThrow();
        assertTrue(PasswordHasher.verify(AdminBootstrapService.FIXED_ADMIN_PASSWORD, restored.passwordHash()));
        assertThrows(UnsupportedOperationException.class,
                () -> com.landrop.admin.security.AdminPasswordResetMain.main(new String[0]));
    }

    @Test
    @DisplayName("03A.2: AdminAuthMiddleware enforces SYSTEM_ADMIN and active status")
    void testAdminAuthMiddlewareEnforcement() {
        AdminAuthMiddleware middleware = new AdminAuthMiddleware(jwtService);

        User regularUser = new User("user@landrop.local", "user", "User", null);
        String userToken = jwtService.generateAccessToken(regularUser);

        User adminUser = new User(
            UUID.randomUUID(), "admin@landrop.local", "admin", "Admin", null,
            PasswordHasher.hash("pass"), User.STATUS_ACTIVE, User.ROLE_SYSTEM_ADMIN, Instant.now(), Instant.now()
        );
        String adminToken = jwtService.generateAccessToken(adminUser);

        // Test non-admin token throws ForbiddenException (403)
        assertThrows(ForbiddenException.class, () -> {
            middleware.requireSystemAdmin(createMockExchange("Bearer " + userToken));
        });

        // Test missing authorization header throws UnauthorizedException (401)
        assertThrows(UnauthorizedException.class, () -> {
            middleware.requireSystemAdmin(createMockExchange(null));
        });

        // Test valid SYSTEM_ADMIN token succeeds
        JwtClaims claims = middleware.requireSystemAdmin(createMockExchange("Bearer " + adminToken));
        assertNotNull(claims);
        assertTrue(claims.isSystemAdmin());
        assertEquals("admin", claims.username());
    }

    // =========================================================================
    // 2. Server Configuration Management Tests (03A.5 - 03A.7)
    // =========================================================================

    @Test
    @DisplayName("03A.5 - 03A.7: ServerConfigManager handles Current vs Pending, validations and persistence")
    void testServerConfigManager() throws Exception {
        Path cfgFile = tempDir.resolve("server-config.json");
        ServerConfig initial = new ServerConfig("0.0.0.0", 3000, 3001, 1, 5000L, 15000L, "development", "INFO");
        ServerConfigManager mgr = new ServerConfigManager(initial, cfgFile);

        assertFalse(mgr.isRequiresRestart());
        assertEquals(3000, mgr.getCurrentConfig().getHttpPort());
        assertEquals(3001, mgr.getCurrentConfig().getWsPort());

        // Test validation: invalid port
        assertThrows(ValidationException.class, () -> {
            mgr.updateConfig(Map.of("httpPort", 70000), "admin");
        });

        // Test validation: same ports
        assertThrows(ValidationException.class, () -> {
            mgr.updateConfig(Map.of("httpPort", 4000, "wsPort", 4000), "admin");
        });

        // Test validation: heartbeat timeout <= interval
        assertThrows(ValidationException.class, () -> {
            mgr.updateConfig(Map.of("heartbeatIntervalMs", 5000L, "heartbeatTimeoutMs", 4000L), "admin");
        });

        // Test valid port update sets requiresRestart and saves file
        ServerConfig updated = mgr.updateConfig(Map.of("httpPort", 3100, "wsPort", 3101), "admin");
        assertTrue(mgr.isRequiresRestart());
        assertEquals(3100, updated.getHttpPort());
        assertEquals(3000, mgr.getCurrentConfig().getHttpPort(), "Runtime currentConfig remains active");
        assertEquals(3100, mgr.getPendingConfig().getHttpPort(), "Pending config holds new port");
        assertEquals(1, updated.getProtocolVersion(), "Protocol version remains strictly 1");

        // Verify persisted file exists and contains updated port
        assertTrue(Files.exists(cfgFile));
        JsonNode persisted = JSON.readTree(cfgFile.toFile());
        assertEquals(3100, persisted.path("httpPort").asInt());
        assertEquals(1, persisted.path("protocolVersion").asInt());
    }

    // =========================================================================
    // 3. Operational Logging Tests (03A.15, 03A.21, 03A.22)
    // =========================================================================

    @Test
    @DisplayName("03A.15: OperationalLogService ring buffer maintains 1000 items and sanitizes credentials")
    void testOperationalLogServiceRingBuffer() {
        OperationalLogService logService = new OperationalLogService(10); // Small buffer for testing rollover

        // Insert 15 entries
        for (int i = 1; i <= 15; i++) {
            logService.info("TEST_EVENT", "admin", "target-" + i, "details for " + i + " password=secret" + i);
        }

        assertEquals(10, logService.size(), "Buffer size capped at capacity");

        List<OperationalLogEntry> logs = logService.getLogs(50, 0, null, null, null);
        assertEquals(10, logs.size());

        // Most recent first
        assertEquals("target-15", logs.get(0).target());
        // Verify sanitization masked password
        assertTrue(logs.get(0).details().contains("password=***"));
        assertFalse(logs.get(0).details().contains("secret15"));

        // Test search filter
        List<OperationalLogEntry> searched = logService.getLogs(50, 0, null, null, "target-14");
        assertEquals(1, searched.size());
        assertEquals("target-14", searched.get(0).target());
    }

    // =========================================================================
    // 4. Dynamic Network Interfaces Tests (03A.4)
    // =========================================================================

    @Test
    @DisplayName("03A.4: NetworkInterfaceInspector inspects real system network interfaces")
    void testNetworkInterfaceInspector() {
        var all = NetworkInterfaceInspector.inspectAll("0.0.0.0");
        assertNotNull(all);
        assertFalse(all.isEmpty(), "Host must have at least one network interface (e.g. loopback or LAN)");

        var lanOnly = NetworkInterfaceInspector.inspectLanInterfaces("0.0.0.0");
        assertNotNull(lanOnly);
        // Each LAN interface must not be loopback and must be up
        for (var iface : lanOnly) {
            assertTrue(iface.isUp());
            assertFalse(iface.isLoopback());
            assertFalse(iface.isVirtual());
        }
    }

    // =========================================================================
    // 5. Admin Control Center Service & Client Management (03A.8, 03A.12, 03A.13)
    // =========================================================================

    @Test
    @DisplayName("03A.12 & 03A.13: Client registry management and administrative disconnect")
    void testClientManagementAndDisconnect() {
        ClientRegistry registry = new ClientRegistry();
        TransferCoordinator coordinator = new TransferCoordinator(registry);
        ServerConfigManager cfgMgr = new ServerConfigManager(new ServerConfig("0.0.0.0", 3000, 3001, 1, 5000L, 15000L, "test", "INFO"));
        OperationalLogService logService = new OperationalLogService();

        AdminControlCenterService adminService = new AdminControlCenterService(
            null, null, registry, coordinator, cfgMgr, logService
        );

        // Disconnect on empty registry returns false
        assertFalse(adminService.disconnectClient("non-existent-id", "test", "admin"));

        // Verify client list is empty
        assertTrue(adminService.listClients().isEmpty());

        // Verify Server Info returns complete JVM/System metadata
        Map<String, Object> info = adminService.getServerInfo("127.0.0.1", 3000, 3001);
        assertEquals("LANDrop Server", info.get("serverName"));
        assertEquals(1, info.get("protocolVersion"));
        assertEquals("RUNNING", info.get("status"));
        assertTrue(info.containsKey("usedMemoryBytes"));
        assertTrue(info.containsKey("osName"));
    }

    @Test
    @DisplayName("03A.9 & 03A.10: User management disable/enable lifecycle and session revocation")
    void testUserLifecycleDisableEnableAndSessionRevocation() throws Exception {
        ServerConfig config = new ServerConfig("127.0.0.1", 0, 0, 1, 1000L, 3000L, "test", "INFO");
        bootstrap = new ServerBootstrap(config);
        bootstrap.start();

        org.junit.jupiter.api.Assumptions.assumeTrue(bootstrap.getUserRepository() != null, "PostgreSQL repository required for DB lifecycle test");

        UserRepository userRepo = bootstrap.getUserRepository();
        RefreshTokenRepository tokenRepo = bootstrap.getRefreshTokenRepository();
        AdminControlCenterService controlCenter = bootstrap.getAdminControlCenterService();

        // 1. Create a test user
        String uniqueUser = "testuser_" + System.currentTimeMillis();
        User user = userRepo.create(new User(
            uniqueUser + "@landrop.local", uniqueUser, "Test User", null, PasswordHasher.hash("pass123")
        ));
        assertEquals(User.STATUS_ACTIVE, user.status());

        // 2. Create a session for this user
        RefreshToken session = tokenRepo.create(new RefreshToken(
            user.id(), "hash_" + System.currentTimeMillis(), Instant.now().plusSeconds(3600)
        ));
        assertFalse(session.revoked());

        // 3. Admin disables user
        boolean disabled = controlCenter.disableUser(user.id(), UUID.randomUUID(), "admin");
        assertTrue(disabled);

        // Verify status in DB is DISABLED
        User disabledUser = userRepo.findById(user.id()).orElseThrow();
        assertEquals(User.STATUS_DISABLED, disabledUser.status());
        assertTrue(disabledUser.isDisabled());

        // Verify session was revoked automatically
        RefreshToken refreshedSession = tokenRepo.findById(session.id()).orElseThrow();
        assertTrue(refreshedSession.revoked(), "Active session must be revoked when user is disabled");

        // 4. Test self-disabling prevention
        UUID actingAdminId = user.id();
        assertThrows(ValidationException.class, () -> {
            controlCenter.disableUser(user.id(), actingAdminId, "admin");
        }, "Admin cannot disable their own account");

        // 5. Admin enables user
        boolean enabled = controlCenter.enableUser(user.id(), "admin");
        assertTrue(enabled);

        User activeUser = userRepo.findById(user.id()).orElseThrow();
        assertEquals(User.STATUS_ACTIVE, activeUser.status());
        assertTrue(activeUser.isActive());
    }

    @Test
    @DisplayName("03A.25: System admin can manage groups and memberships through the control service")
    void testWorkspaceDatabaseManagement() throws Exception {
        ServerConfig config = new ServerConfig("127.0.0.1", 0, 0, 1, 1000L, 3000L, "test", "INFO");
        bootstrap = new ServerBootstrap(config);
        bootstrap.start();
        org.junit.jupiter.api.Assumptions.assumeTrue(bootstrap.getUserRepository() != null, "PostgreSQL repository required for DB lifecycle test");

        UserRepository users = bootstrap.getUserRepository();
        String suffix = Long.toString(System.currentTimeMillis());
        User owner = users.create(new User("group-owner-" + suffix + "@landrop.local", "group_owner_" + suffix,
                "Group Owner", null, PasswordHasher.hash("pass123")));
        User member = users.create(new User("group-member-" + suffix + "@landrop.local", "group_member_" + suffix,
                "Group Member", null, PasswordHasher.hash("pass123")));
        AdminControlCenterService control = bootstrap.getAdminControlCenterService();

        AdminWorkspaceDto created = control.createWorkspace(Map.of(
                "name", "Admin Managed Group " + suffix,
                "slug", "admin-managed-group-" + suffix,
                "description", "Created by the system-admin data manager",
                "ownerId", owner.id().toString()), "system-admin");
        assertEquals(1, created.memberCount());
        assertEquals(owner.id(), created.ownerId());
        assertTrue(control.getDashboard().workspaceCount() >= 1);

        AdminWorkspaceDto updated = control.updateWorkspace(created.id(), Map.of("name", "Renamed Admin Group", "description", "Updated"), "system-admin");
        assertEquals("Renamed Admin Group", updated.name());
        AdminWorkspaceMemberDto added = control.upsertWorkspaceMember(created.id(), Map.of("userId", member.id().toString(), "role", "ADMIN"), "system-admin");
        assertEquals("ADMIN", added.role());
        assertEquals(2, control.listWorkspaceMembers(created.id()).size());
        assertThrows(ValidationException.class, () -> control.removeWorkspaceMember(created.id(), owner.id(), "system-admin"));
        assertTrue(control.removeWorkspaceMember(created.id(), member.id(), "system-admin"));
        assertTrue(control.deleteWorkspace(created.id(), "system-admin"));
    }

    @Test
    @DisplayName("03A.26: Workspace CRUD routes require a system-admin token and persist changes")
    void testWorkspaceManagementHttpRoutes() throws Exception {
        ServerConfig config = new ServerConfig("127.0.0.1", 0, 0, 1, 1000L, 3000L, "test", "INFO");
        bootstrap = new ServerBootstrap(config);
        bootstrap.start();
        org.junit.jupiter.api.Assumptions.assumeTrue(bootstrap.getUserRepository() != null, "PostgreSQL repository required for DB route test");

        UserRepository users = bootstrap.getUserRepository();
        User admin = users.findAll().stream().filter(User::isSystemAdmin).findFirst().orElseThrow();
        String suffix = Long.toString(System.currentTimeMillis());
        User owner = users.create(new User("route-owner-" + suffix + "@landrop.local", "route_owner_" + suffix,
                "Route Owner", null, PasswordHasher.hash("pass123")));
        String token = bootstrap.getJwtService().generateAccessToken(admin);
        String baseUrl = "http://127.0.0.1:" + bootstrap.getHttpServer().getPort();

        String payload = JSON.writeValueAsString(Map.of("name", "Route Managed Group", "slug", "route-group-" + suffix,
                "ownerId", owner.id().toString()));
        HttpResponse<String> created = httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/admin/workspaces"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, created.statusCode());
        UUID workspaceId = UUID.fromString(JSON.readTree(created.body()).path("id").asText());

        HttpResponse<String> listed = httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/admin/workspaces?search=route-group"))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, listed.statusCode());
        assertTrue(JSON.readTree(listed.body()).toString().contains("route-group-" + suffix));

        HttpResponse<String> deleted = httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/admin/workspaces/" + workspaceId))
                .header("Authorization", "Bearer " + token).DELETE().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, deleted.statusCode());
    }

    // =========================================================================
    // 6. End-to-End Server Control Center Integration with ServerBootstrap (03A.8–03A.24)
    // =========================================================================

    @Test
    @DisplayName("03A End-to-End: Admin Console, Dashboard, RBAC, Client Disconnect and Logs")
    void testEndToEndControlCenter() throws Exception {
        ServerConfig config = new ServerConfig("127.0.0.1", 0, 0, 1, 1000L, 3000L, "test", "INFO");
        bootstrap = new ServerBootstrap(config);
        bootstrap.start();

        int httpPort = bootstrap.getHttpServer().getPort();
        int wsPort = bootstrap.getRealtimeServer().getPort();
        String baseUrl = "http://127.0.0.1:" + httpPort;

        // Old web-console bookmarks receive a retirement response, including HTML navigation requests.
        for (String path : List.of("/admin", "/admin/", "/admin/users")) {
            HttpRequest uiReq = HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).header("Accept", "text/html").GET().build();
            HttpResponse<String> uiRes = httpClient.send(uiReq, HttpResponse.BodyHandlers.ofString());
            assertEquals(410, uiRes.statusCode());
            assertTrue(uiRes.body().contains("ADMIN_WEB_REMOVED"));
        }

        // 2. Resolve or create system admin user
        User adminUser;
        if (bootstrap.getUserRepository() != null) {
            var existingAdmin = bootstrap.getUserRepository().findAll().stream()
                .filter(User::isSystemAdmin).findFirst();
            if (existingAdmin.isPresent()) {
                adminUser = existingAdmin.get();
            } else {
                adminUser = bootstrap.getUserRepository().create(new User(
                    "admin@test.local", "superadmin", "Super Admin", null,
                    PasswordHasher.hash("adminpass")
                ).withSystemRole(User.ROLE_SYSTEM_ADMIN));
            }
        } else {
            adminUser = new User(
                UUID.randomUUID(), "admin@test.local", "superadmin", "Super Admin", null,
                PasswordHasher.hash("adminpass"), User.STATUS_ACTIVE, User.ROLE_SYSTEM_ADMIN, Instant.now(), Instant.now()
            );
        }

        User regularUser = new User("user@test.local", "normaluser", "Normal User", null);
        JwtService serverJwt = bootstrap.getAdminAuthMiddleware().getJwtService();
        String adminToken = serverJwt.generateAccessToken(adminUser);
        String userToken = serverJwt.generateAccessToken(regularUser);

        // 3. Verify RBAC on /api/admin/dashboard:
        // Case A: Unauthenticated -> 401
        HttpRequest noAuthReq = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/api/admin/dashboard")).GET().build();
        HttpResponse<String> noAuthRes = httpClient.send(noAuthReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, noAuthRes.statusCode());

        // Case B: Regular user -> 403 Forbidden
        HttpRequest userAuthReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/admin/dashboard"))
                .header("Authorization", "Bearer " + userToken)
                .GET().build();
        HttpResponse<String> userAuthRes = httpClient.send(userAuthReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, userAuthRes.statusCode());

        // Case C: System Admin -> 200 OK
        HttpRequest adminAuthReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/admin/dashboard"))
                .header("Authorization", "Bearer " + adminToken)
                .GET().build();
        HttpResponse<String> adminAuthRes = httpClient.send(adminAuthReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, adminAuthRes.statusCode());

        JsonNode dashboardJson = JSON.readTree(adminAuthRes.body());
        assertEquals("RUNNING", dashboardJson.path("status").asText());
        assertTrue(dashboardJson.has("uptimeSeconds"));
        assertEquals(0, dashboardJson.path("connectedClients").asInt());

        // 4. Verify /api/server/info (or /api/admin/server/info)
        HttpRequest infoReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/server/info"))
                .header("Authorization", "Bearer " + adminToken)
                .GET().build();
        HttpResponse<String> infoRes = httpClient.send(infoReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, infoRes.statusCode());
        JsonNode infoJson = JSON.readTree(infoRes.body());
        assertEquals("RUNNING", infoJson.path("status").asText());
        assertEquals(1, infoJson.path("protocolVersion").asInt());
        assertEquals(httpPort, infoJson.path("httpPort").asInt());
        assertEquals(wsPort, infoJson.path("wsPort").asInt());

        // 5. Verify /api/server/interfaces returns interfaces
        HttpRequest ifaceReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/server/interfaces"))
                .header("Authorization", "Bearer " + adminToken)
                .GET().build();
        HttpResponse<String> ifaceRes = httpClient.send(ifaceReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, ifaceRes.statusCode());
        JsonNode ifaceJson = JSON.readTree(ifaceRes.body());
        assertTrue(ifaceJson.isArray());

        // 6. Connect a WebSocket client and verify it appears in /api/admin/clients
        try (FoundationWsClient client = FoundationWsClient.connect(wsPort)) {
            client.sendJson(Map.of(
                "type", "hello",
                "protocolVersion", 1,
                "clientInstanceId", "inst-admin-test-client",
                "deviceName", "Admin Test Workstation",
                "platform", "Windows"
            ));
            JsonNode welcome = client.receive(3000);
            assertNotNull(welcome);
            String connectionId = welcome.path("connectionId").asText();

            // Query /api/admin/clients
            HttpRequest clientsReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/admin/clients"))
                    .header("Authorization", "Bearer " + adminToken)
                    .GET().build();
            HttpResponse<String> clientsRes = httpClient.send(clientsReq, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, clientsRes.statusCode());
            JsonNode clientsArray = JSON.readTree(clientsRes.body());
            assertTrue(clientsArray.isArray());
            assertEquals(1, clientsArray.size());
            assertEquals("Admin Test Workstation", clientsArray.get(0).path("deviceName").asText());
            assertEquals(connectionId, clientsArray.get(0).path("connectionId").asText());

            // 7. Administratively disconnect client via POST /api/admin/clients/{id}/disconnect
            HttpRequest dcReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/admin/clients/" + connectionId + "/disconnect"))
                    .header("Authorization", "Bearer " + adminToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"Terminated by compliance officer\"}"))
                    .build();
            HttpResponse<String> dcRes = httpClient.send(dcReq, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, dcRes.statusCode());

            // Client socket should receive close frame
            int closeCode = client.awaitClose(3000);
            assertEquals(4001, closeCode);

            // Client should now be gone from registry
            assertTrue(bootstrap.getClientRegistry().get(connectionId).isEmpty());
        }

        // 8. Verify /api/server/config GET and POST
        HttpRequest cfgGetReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/server/config"))
                .header("Authorization", "Bearer " + adminToken)
                .GET().build();
        HttpResponse<String> cfgGetRes = httpClient.send(cfgGetReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, cfgGetRes.statusCode());
        JsonNode cfgJson = JSON.readTree(cfgGetRes.body());
        assertTrue(cfgJson.has("current"));
        assertTrue(cfgJson.has("pending"));
        assertFalse(cfgJson.path("requiresRestart").asBoolean());

        // Update config via POST
        HttpRequest cfgPostReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/server/config"))
                .header("Authorization", "Bearer " + adminToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"httpPort\":8080,\"wsPort\":8081}"))
                .build();
        HttpResponse<String> cfgPostRes = httpClient.send(cfgPostReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, cfgPostRes.statusCode());
        JsonNode updatedCfg = JSON.readTree(cfgPostRes.body());
        assertTrue(updatedCfg.path("requiresRestart").asBoolean(), "Port change must mark requiresRestart: true");
        assertEquals(8080, updatedCfg.path("pending").path("httpPort").asInt());

        // 9. Verify /api/admin/logs captures recorded audit actions
        HttpRequest logsReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/admin/logs?limit=50"))
                .header("Authorization", "Bearer " + adminToken)
                .GET().build();
        HttpResponse<String> logsRes = httpClient.send(logsReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, logsRes.statusCode());
        JsonNode logsJson = JSON.readTree(logsRes.body());
        assertTrue(logsJson.isArray());
        assertTrue(logsJson.size() >= 2, "Must contain SERVER_STARTED, CLIENT_CONNECTED, ADMIN_CLIENT_DISCONNECT, CONFIG_CHANGED");

        boolean hasAdminDc = false;
        boolean hasConfigChange = false;
        for (JsonNode l : logsJson) {
            if ("ADMIN_CLIENT_DISCONNECT".equals(l.path("eventType").asText())) hasAdminDc = true;
            if ("CONFIG_CHANGED".equals(l.path("eventType").asText())) hasConfigChange = true;
        }
        assertTrue(hasAdminDc, "Logs must record ADMIN_CLIENT_DISCONNECT");
        assertTrue(hasConfigChange, "Logs must record CONFIG_CHANGED");

        // 10. Verify /health and /status continue to pass 100% without regression
        HttpRequest healthReq = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/health")).GET().build();
        HttpResponse<String> healthRes = httpClient.send(healthReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, healthRes.statusCode());
        assertEquals("ok", JSON.readTree(healthRes.body()).path("status").asText());
    }

    // =========================================================================
    // Helper Mock Exchange
    // =========================================================================

    private HttpExchange createMockExchange(String authHeader) {
        com.sun.net.httpserver.Headers headers = new com.sun.net.httpserver.Headers();
        if (authHeader != null) {
            headers.set("Authorization", authHeader);
        }
        return new DummyHttpExchange(headers);
    }

    private static class DummyHttpExchange extends HttpExchange {
        private final com.sun.net.httpserver.Headers requestHeaders;
        private final com.sun.net.httpserver.Headers responseHeaders = new com.sun.net.httpserver.Headers();

        DummyHttpExchange(com.sun.net.httpserver.Headers requestHeaders) {
            this.requestHeaders = requestHeaders;
        }

        @Override public com.sun.net.httpserver.Headers getRequestHeaders() { return requestHeaders; }
        @Override public com.sun.net.httpserver.Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return URI.create("/api/admin/dashboard"); }
        @Override public String getRequestMethod() { return "GET"; }
        @Override public com.sun.net.httpserver.HttpContext getHttpContext() { return null; }
        @Override public void close() {}
        @Override public java.io.InputStream getRequestBody() { return java.io.InputStream.nullInputStream(); }
        @Override public java.io.OutputStream getResponseBody() { return java.io.OutputStream.nullOutputStream(); }
        @Override public void sendResponseHeaders(int rCode, long responseLength) {}
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 12345); }
        @Override public int getResponseCode() { return 200; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 8080); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) {}
        @Override public void setStreams(java.io.InputStream i, java.io.OutputStream o) {}
        @Override public com.sun.net.httpserver.HttpPrincipal getPrincipal() { return null; }
    }
}
