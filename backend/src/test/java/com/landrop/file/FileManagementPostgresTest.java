package com.landrop.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.auth.repository.RefreshTokenRepository;
import com.landrop.auth.security.JwtService;
import com.landrop.auth.server.AuthHttpServer;
import com.landrop.auth.server.AuthMiddleware;
import com.landrop.auth.service.AuthService;
import com.landrop.chat.service.ChannelChatService;
import com.landrop.database.DatabaseConfig;
import com.landrop.database.DatabaseManager;
import com.landrop.database.repository.*;
import com.landrop.database.service.WorkspaceService;
import com.landrop.file.dto.FileDto;
import com.landrop.file.dto.FolderDto;
import com.landrop.file.dto.TransferDto;
import com.landrop.file.service.FileManagementService;
import com.landrop.file.service.TransferCoordinationService;
import com.landrop.realtime.JdbcRealtimeStore;
import com.landrop.realtime.RealtimeServer;
import com.landrop.realtime.SocketAuthenticator;
import com.landrop.realtime.WsProbe;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 18A: File Management + LAN Transfer Backend
 * Comprehensive live integration suite against Neon PostgreSQL in an isolated temporary schema.
 */
@EnabledIfSystemProperty(named = "landrop.test.postgres", matches = "true")
class FileManagementPostgresTest {

    private static final ObjectMapper JSON = AuthHttpServer.createObjectMapper();
    private static final String PASSWORD = "TestPassword-123!";
    private static final String SCHEMA = "landrop_file_test_" + UUID.randomUUID().toString().replace("-", "");

    private static DatabaseManager db;
    private static AuthHttpServer httpServer;
    private static RealtimeServer realtimeServer;
    private static FileManagementService fileService;
    private static TransferCoordinationService transferService;
    private static ChannelChatService chatService;
    private static HttpClient http;
    private static boolean schemaCreated;

    private static String ownerToken, memberToken, outsiderToken;
    private static UUID ownerId, memberId, outsiderId;
    private static UUID workspaceId, channelId;

    private final List<WsProbe> probes = new ArrayList<>();

    @BeforeAll
    static void setup() throws Exception {
        String url = DatabaseConfig.getDatabaseUrl();
        assertNotNull(url, "DATABASE_URL must be configured.");
        URI configured = URI.create(url.replaceFirst("^jdbc:", ""));
        if (configured.getHost().endsWith(".neon.tech") && configured.getHost().contains("-pooler.")) {
            url = url.replace(configured.getHost(), configured.getHost().replace("-pooler.", "."));
        }

        try (DatabaseManager publicDb = DatabaseManager.forSchema(url, "public");
             var c = publicDb.getConnection();
             var s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + SCHEMA);
            schemaCreated = true;
        }

        db = DatabaseManager.forSchema(url, SCHEMA);
        new com.landrop.database.migration.DatabaseMigrator(db).migrate();
        var users = new UserRepository(db);
        var jwt = new JwtService("file-access-key-32-chars-test!", "file-refresh-key-32-chars-test!", 900, 3600);
        var auth = new AuthService(users, new RefreshTokenRepository(db), jwt);
        var wsRepo = new WorkspaceRepository(db);
        var wsService = new WorkspaceService(wsRepo);

        var fileRepo = new FileRepository(db);
        var folderRepo = new FolderRepository(db);
        var devRepo = new DeviceRepository(db);
        var transferRepo = new TransferRepository(db);

        var realtimeStore = new JdbcRealtimeStore(db);
        realtimeServer = new RealtimeServer(
            new InetSocketAddress("127.0.0.1", 0),
            SocketAuthenticator.jwt(jwt, users),
            realtimeStore
        );
        realtimeServer.start();
        realtimeServer.awaitReady();

        fileService = new FileManagementService(db, fileRepo, folderRepo, wsRepo);
        transferService = new TransferCoordinationService(db, transferRepo, fileRepo, devRepo, fileService, realtimeServer);

        chatService = new ChannelChatService(db, realtimeServer);
        httpServer = new AuthHttpServer(0, auth, new AuthMiddleware(jwt), wsService, chatService, fileService, transferService);
        httpServer.start();
        http = HttpClient.newHttpClient();

        registerUsers();
        setupFixtures();
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (realtimeServer != null) realtimeServer.stop(1000);
        if (httpServer != null) httpServer.stop(0);
        if (db != null) {
            try {
                if (schemaCreated) {
                    try (var c = db.getConnection(); var s = c.createStatement()) {
                        s.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
                    }
                }
            } finally {
                db.close();
            }
        }
    }

    @AfterEach
    void tearDown() {
        for (var p : probes) p.close();
        probes.clear();
    }

    private static void registerUsers() throws Exception {
        ownerToken = register("fowner").path("accessToken").asText();
        memberToken = register("fmember").path("accessToken").asText();
        outsiderToken = register("foutsider").path("accessToken").asText();

        var userRepo = new UserRepository(db);
        ownerId = userRepo.findByEmail("fowner@example.com").orElseThrow().id();
        memberId = userRepo.findByEmail("fmember@example.com").orElseThrow().id();
        outsiderId = userRepo.findByEmail("foutsider@example.com").orElseThrow().id();
    }

    private static void setupFixtures() throws Exception {
        workspaceId = UUID.randomUUID();
        channelId = UUID.randomUUID();

        try (var conn = db.getConnection()) {
            try (var ps = conn.prepareStatement("INSERT INTO workspaces (id, name, slug, owner_id) VALUES (?, 'File Workspace', 'file-ws', ?)")) {
                ps.setObject(1, workspaceId); ps.setObject(2, ownerId); ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, ?::\"WorkspaceRole\")")) {
                ps.setObject(1, workspaceId); ps.setObject(2, ownerId); ps.setString(3, "OWNER"); ps.executeUpdate();
                ps.setObject(1, workspaceId); ps.setObject(2, memberId); ps.setString(3, "MEMBER"); ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("INSERT INTO channels (id, workspace_id, name, topic) VALUES (?, ?, 'general', 'General Chat')")) {
                ps.setObject(1, channelId); ps.setObject(2, workspaceId); ps.executeUpdate();
            }
        }
    }

    private static JsonNode register(String name) throws Exception {
        return request("POST", "/api/auth/register", "",
                Map.of("username", name, "displayName", name.toUpperCase(), "email", name + "@example.com", "password", PASSWORD), 201);
    }

    private static JsonNode request(String method, String path, String token, Object body, int expectedStatus) throws Exception {
        var res = raw(method, path, token, body);
        assertEquals(expectedStatus, res.statusCode(), "HTTP " + method + " " + path + " failed: " + res.body());
        return res.body().isBlank() ? null : JSON.readTree(res.body());
    }

    private static HttpResponse<String> raw(String method, String path, String token, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpServer.getPort() + path));
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private WsProbe connectRoom(String token, String device) {
        var probe = WsProbe.openRoom(realtimeServer, token, device);
        probes.add(probe);
        return probe;
    }

    // =========================================================================
    // 1. Schema & Migration Verification
    // =========================================================================

    @Test
    void test1_schemaAndMigrationV5Verification() throws Exception {
        try (Connection c = db.getConnection()) {
            var meta = c.getMetaData();

            // Verify folders columns
            var folderCols = new HashSet<String>();
            try (ResultSet rs = meta.getColumns(null, SCHEMA, "folders", null)) {
                while (rs.next()) folderCols.add(rs.getString("COLUMN_NAME").toLowerCase());
            }
            assertTrue(folderCols.contains("created_by_id"), "folders must contain created_by_id");
            assertTrue(folderCols.contains("deleted_at"), "folders must contain deleted_at");

            // Verify files columns
            var fileCols = new HashSet<String>();
            try (ResultSet rs = meta.getColumns(null, SCHEMA, "files", null)) {
                while (rs.next()) fileCols.add(rs.getString("COLUMN_NAME").toLowerCase());
            }
            assertTrue(fileCols.contains("storage_type"), "files must contain storage_type");
            assertTrue(fileCols.contains("original_name"), "files must contain original_name");
            assertTrue(fileCols.contains("storage_path"), "files must contain storage_path");
            assertTrue(fileCols.contains("deleted_at"), "files must contain deleted_at");

            // Verify devices table
            var deviceCols = new HashSet<String>();
            try (ResultSet rs = meta.getColumns(null, SCHEMA, "devices", null)) {
                while (rs.next()) deviceCols.add(rs.getString("COLUMN_NAME").toLowerCase());
            }
            assertTrue(deviceCols.contains("id"), "devices must have id");
            assertTrue(deviceCols.contains("user_id"), "devices must have user_id");
            assertTrue(deviceCols.contains("peer_id"), "devices must have peer_id");

            // Verify transfers table
            var transferCols = new HashSet<String>();
            try (ResultSet rs = meta.getColumns(null, SCHEMA, "transfers", null)) {
                while (rs.next()) transferCols.add(rs.getString("COLUMN_NAME").toLowerCase());
            }
            assertTrue(transferCols.contains("id"), "transfers must have id");
            assertTrue(transferCols.contains("file_id"), "transfers must have file_id");
            assertTrue(transferCols.contains("sender_user_id"), "transfers must have sender_user_id");
            assertTrue(transferCols.contains("receiver_user_id"), "transfers must have receiver_user_id");
            assertTrue(transferCols.contains("status"), "transfers must have status");
        }
    }

    // =========================================================================
    // 2. Folder Hierarchy & Cycle Detection
    // =========================================================================

    @Test
    void test2_folderHierarchyAndCycleDetection() throws Exception {
        // Create root folder
        var resRoot = request("POST", "/api/workspaces/" + workspaceId + "/folders", ownerToken, Map.of("name", "Documents"), 201);
        UUID rootId = UUID.fromString(resRoot.path("data").path("id").asText());
        assertEquals("Documents", resRoot.path("data").path("name").asText());

        // Create subfolder
        var resSub = request("POST", "/api/workspaces/" + workspaceId + "/folders", memberToken, Map.of(
            "name", "Projects",
            "parentFolderId", rootId.toString()
        ), 201);
        UUID subId = UUID.fromString(resSub.path("data").path("id").asText());
        assertEquals(rootId.toString(), resSub.path("data").path("parentId").asText());

        // Cycle detection: moving parent into child must fail (400)
        var resCycle = raw("PATCH", "/api/folders/" + rootId, ownerToken, Map.of("parentFolderId", subId.toString()));
        assertEquals(400, resCycle.statusCode());

        // List folders in workspace
        var resList = request("GET", "/api/workspaces/" + workspaceId + "/folders", memberToken, null, 200);
        assertTrue(resList.path("data").size() >= 2);

        // Soft delete subfolder
        var resDel = request("DELETE", "/api/folders/" + subId, memberToken, null, 200);
        assertNotNull(resDel.path("data").path("deletedAt").asText(null));
    }

    // =========================================================================
    // 3. File Metadata Lifecycle & Soft Delete
    // =========================================================================

    @Test
    void test3_fileMetadataLifecycleAndSoftDelete() throws Exception {
        // Create file in workspace
        var resFile = request("POST", "/api/workspaces/" + workspaceId + "/files", memberToken, Map.of(
            "name", "report.pdf",
            "size", 1048576,
            "mimeType", "application/pdf",
            "hash", "sha256-mock-pg-hash",
            "storageType", "LAN_ONLY"
        ), 201);
        UUID fileId = UUID.fromString(resFile.path("data").path("id").asText());
        assertEquals("report.pdf", resFile.path("data").path("name").asText());
        assertEquals("LAN_ONLY", resFile.path("data").path("storageType").asText());

        // Get file
        var resGet = request("GET", "/api/files/" + fileId, ownerToken, null, 200);
        assertEquals(fileId.toString(), resGet.path("data").path("id").asText());

        // Rename file
        var resPatch = request("PATCH", "/api/files/" + fileId, memberToken, Map.of("name", "report_final.pdf"), 200);
        assertEquals("report_final.pdf", resPatch.path("data").path("name").asText());

        // List files in workspace
        var resList = request("GET", "/api/workspaces/" + workspaceId + "/files", ownerToken, null, 200);
        assertTrue(resList.path("data").size() >= 1);

        // Soft delete file
        var resDel = request("DELETE", "/api/files/" + fileId, memberToken, null, 200);
        assertNotNull(resDel.path("data").path("deletedAt").asText(null));

        // Verify excluded from default list
        var resListAfter = request("GET", "/api/workspaces/" + workspaceId + "/files", ownerToken, null, 200);
        for (JsonNode n : resListAfter.path("data")) {
            assertNotEquals(fileId.toString(), n.path("id").asText());
        }

        // Verify included when includeDeleted=true
        var resListInc = request("GET", "/api/workspaces/" + workspaceId + "/files?includeDeleted=true", ownerToken, null, 200);
        boolean found = false;
        for (JsonNode n : resListInc.path("data")) {
            if (fileId.toString().equals(n.path("id").asText())) {
                found = true;
                assertFalse(n.path("deletedAt").isNull());
                break;
            }
        }
        assertTrue(found, "Soft-deleted file must appear when includeDeleted=true");
    }

    // =========================================================================
    // 4. Device Registration & Lookup
    // =========================================================================

    @Test
    void test4_deviceRegistrationAndLookup() throws Exception {
        // Register device for Owner
        var resDevOwner = request("POST", "/api/devices/register", ownerToken, Map.of(
            "deviceId", "dev-pg-owner",
            "deviceName", "Owner MacBook Pro",
            "peerId", "peer-owner-123",
            "ipAddress", "192.168.1.150",
            "port", 9001
        ), 200);
        assertEquals("dev-pg-owner", resDevOwner.path("data").path("deviceId").asText());
        assertEquals("peer-owner-123", resDevOwner.path("data").path("peerId").asText());

        // Lookup devices for Owner
        var resUserDevices = request("GET", "/api/users/" + ownerId + "/devices", memberToken, null, 200);
        assertTrue(resUserDevices.path("data").size() >= 1);
        assertEquals("dev-pg-owner", resUserDevices.path("data").get(0).path("deviceId").asText());

        // Lookup device by peerId
        var resByPeer = request("GET", "/api/devices/peer/peer-owner-123", memberToken, null, 200);
        assertEquals("dev-pg-owner", resByPeer.path("data").path("deviceId").asText());
    }

    // =========================================================================
    // 5. End-to-End Transfer Lifecycle with Realtime WebSocket
    // =========================================================================

    @Test
    void test5_e2eTransferLifecycleWithRealtime() throws Exception {
        // Register devices
        request("POST", "/api/devices/register", ownerToken, Map.of("deviceId", "dev-rt-owner", "deviceName", "Owner PC"), 200);
        request("POST", "/api/devices/register", memberToken, Map.of("deviceId", "dev-rt-member", "deviceName", "Member PC"), 200);

        // Open WebSockets and consume connected event
        var ownerWs = connectRoom(ownerToken, "dev-rt-owner");
        ownerWs.event("connected");
        var memberWs = connectRoom(memberToken, "dev-rt-member");
        memberWs.event("connected");

        // Create file to transfer
        var resFile = request("POST", "/api/workspaces/" + workspaceId + "/files", ownerToken, Map.of(
            "name", "presentation.key",
            "size", 20_000_000,
            "mimeType", "application/octet-stream",
            "hash", "hash-key-presentation"
        ), 201);
        UUID fileId = UUID.fromString(resFile.path("data").path("id").asText());

        // Owner initiates transfer request to Member
        var resReq = request("POST", "/api/transfers", ownerToken, Map.of(
            "fileId", fileId.toString(),
            "receiverUserId", memberId.toString(),
            "senderDeviceId", "dev-rt-owner"
        ), 201);
        UUID transferId = UUID.fromString(resReq.path("data").path("id").asText());
        assertEquals("PENDING", resReq.path("data").path("status").asText());

        // Member receives realtime transfer:request
        var reqEvent = memberWs.event("transfer:request");
        assertEquals(transferId.toString(), reqEvent.path("id").asText());

        // Member accepts transfer
        var resAccept = request("POST", "/api/transfers/" + transferId + "/accept", memberToken, Map.of("receiverDeviceId", "dev-rt-member"), 200);
        assertEquals("ACCEPTED", resAccept.path("data").path("status").asText());

        // Owner receives realtime transfer:accepted
        var acceptEvent = ownerWs.event("transfer:accepted");
        assertEquals(transferId.toString(), acceptEvent.path("id").asText());

        // Progress update (auto-advances to TRANSFERRING)
        request("POST", "/api/transfers/" + transferId + "/progress", ownerToken, Map.of(
            "bytesTransferred", 10_000_000,
            "totalBytes", 20_000_000,
            "speed", 2048000.0
        ), 200);

        // Both receive transfer:started and transfer:progress
        var startOwner = ownerWs.event("transfer:started");
        assertEquals(transferId.toString(), startOwner.path("id").asText());
        var startMember = memberWs.event("transfer:started");
        assertEquals(transferId.toString(), startMember.path("id").asText());

        var progOwner = ownerWs.event("transfer:progress");
        assertEquals(transferId.toString(), progOwner.path("transferId").asText());
        var progMember = memberWs.event("transfer:progress");
        assertEquals(transferId.toString(), progMember.path("transferId").asText());

        // Complete transfer
        var resComp = request("POST", "/api/transfers/" + transferId + "/complete", ownerToken, null, 200);
        assertEquals("COMPLETED", resComp.path("data").path("status").asText());

        // Both receive transfer:completed
        var compOwner = ownerWs.event("transfer:completed");
        assertEquals("COMPLETED", compOwner.path("status").asText());
        var compMember = memberWs.event("transfer:completed");
        assertEquals("COMPLETED", compMember.path("status").asText());

        // Verify in Neon PostgreSQL table directly
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT status, started_at, completed_at FROM transfers WHERE id = ?")) {
            ps.setObject(1, transferId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("COMPLETED", rs.getString("status"));
                assertNotNull(rs.getTimestamp("started_at"));
                assertNotNull(rs.getTimestamp("completed_at"));
            }
        }
    }

    // =========================================================================
    // 6. Transfer Rejection & Cancellation
    // =========================================================================

    @Test
    void test6_transferRejectionAndCancellation() throws Exception {
        var resFile = request("POST", "/api/workspaces/" + workspaceId + "/files", ownerToken, Map.of(
            "name", "temp_artifact.tar.gz",
            "size", 1024,
            "mimeType", "application/gzip"
        ), 201);
        UUID fileId = UUID.fromString(resFile.path("data").path("id").asText());

        // Case A: Rejection
        var resReq1 = request("POST", "/api/transfers", ownerToken, Map.of(
            "fileId", fileId.toString(),
            "receiverUserId", memberId.toString()
        ), 201);
        UUID t1 = UUID.fromString(resReq1.path("data").path("id").asText());

        var resReject = request("POST", "/api/transfers/" + t1 + "/reject", memberToken, Map.of("reason", "Not enough disk space"), 200);
        assertEquals("REJECTED", resReject.path("data").path("status").asText());

        // Verify in database
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT status, error_message FROM transfers WHERE id = ?")) {
            ps.setObject(1, t1);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("REJECTED", rs.getString("status"));
                assertEquals("Not enough disk space", rs.getString("error_message"));
            }
        }

        // Case B: Cancellation
        var resReq2 = request("POST", "/api/transfers", ownerToken, Map.of(
            "fileId", fileId.toString(),
            "receiverUserId", memberId.toString()
        ), 201);
        UUID t2 = UUID.fromString(resReq2.path("data").path("id").asText());

        var resCancel = request("POST", "/api/transfers/" + t2 + "/cancel", ownerToken, null, 200);
        assertEquals("CANCELLED", resCancel.path("data").path("status").asText());

        // Verify cannot accept a cancelled transfer
        var resAcceptCancelled = raw("POST", "/api/transfers/" + t2 + "/accept", memberToken, null);
        assertEquals(400, resAcceptCancelled.statusCode());
    }

    // =========================================================================
    // 7. Same-User Multi-Device Transfer
    // =========================================================================

    @Test
    void test7_sameUserMultiDeviceTransfer() throws Exception {
        var resFile = request("POST", "/api/workspaces/" + workspaceId + "/files", ownerToken, Map.of(
            "name", "sync_database.sqlite",
            "size", 5000,
            "mimeType", "application/octet-stream"
        ), 201);
        UUID fileId = UUID.fromString(resFile.path("data").path("id").asText());

        // Owner sends to self, specifying source device and target device
        var resReq = request("POST", "/api/transfers", ownerToken, Map.of(
            "fileId", fileId.toString(),
            "receiverUserId", ownerId.toString(),
            "senderDeviceId", "dev-laptop",
            "receiverDeviceId", "dev-phone"
        ), 201);
        UUID transferId = UUID.fromString(resReq.path("data").path("id").asText());
        assertEquals(ownerId.toString(), resReq.path("data").path("senderUserId").asText());
        assertEquals(ownerId.toString(), resReq.path("data").path("receiverUserId").asText());

        // Owner accepts on target device
        var resAccept = request("POST", "/api/transfers/" + transferId + "/accept", ownerToken, Map.of("receiverDeviceId", "dev-phone"), 200);
        assertEquals("ACCEPTED", resAccept.path("data").path("status").asText());
    }

    // =========================================================================
    // 8. Authorization & Security Boundaries
    // =========================================================================

    @Test
    void test8_authorizationAndSecurityBoundaries() throws Exception {
        var resFile = request("POST", "/api/workspaces/" + workspaceId + "/files", ownerToken, Map.of(
            "name", "classified.pdf",
            "size", 1000,
            "mimeType", "application/pdf"
        ), 201);
        UUID fileId = UUID.fromString(resFile.path("data").path("id").asText());

        // Outsider cannot access workspace files
        var resOutsiderFiles = raw("GET", "/api/workspaces/" + workspaceId + "/files", outsiderToken, null);
        assertEquals(404, resOutsiderFiles.statusCode());

        // Outsider cannot access workspace folders
        var resOutsiderFolders = raw("GET", "/api/workspaces/" + workspaceId + "/folders", outsiderToken, null);
        assertEquals(404, resOutsiderFolders.statusCode());

        // Outsider cannot initiate transfer of workspace file
        var resOutsiderTransfer = raw("POST", "/api/transfers", outsiderToken, Map.of(
            "fileId", fileId.toString(),
            "receiverUserId", memberId.toString()
        ));
        assertEquals(404, resOutsiderTransfer.statusCode());

        // Member cannot initiate transfer to outsider not belonging to workspace
        var resToOutsider = raw("POST", "/api/transfers", memberToken, Map.of(
            "fileId", fileId.toString(),
            "receiverUserId", outsiderId.toString()
        ));
        assertEquals(404, resToOutsider.statusCode());
    }

    // =========================================================================
    // 9. Existing Chat & Workspace Regression Check
    // =========================================================================

    @Test
    void test9_existingChatAndWorkspaceRegression() throws Exception {
        // Send a persistent chat message to channel
        var resMsg = request("POST", "/api/channels/" + channelId + "/messages", ownerToken, Map.of(
            "content", "File transfer ready for testing!"
        ), 201);
        assertNotNull(resMsg.path("data").path("id").asText());

        // Get channel message history
        var resHistory = request("GET", "/api/channels/" + channelId + "/messages", memberToken, null, 200);
        assertTrue(resHistory.path("data").size() >= 1);
        assertEquals("File transfer ready for testing!", resHistory.path("data").get(0).path("content").asText());
    }
}
