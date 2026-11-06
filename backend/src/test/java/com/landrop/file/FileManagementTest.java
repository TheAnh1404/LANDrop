package com.landrop.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.auth.security.JwtService;
import com.landrop.auth.server.AuthHttpServer;
import com.landrop.auth.server.AuthMiddleware;
import com.landrop.database.model.*;
import com.landrop.database.repository.*;
import com.landrop.database.service.WorkspaceService;
import com.landrop.file.dto.*;
import com.landrop.file.exception.FileException;
import com.landrop.file.service.FileManagementService;
import com.landrop.file.service.TransferCoordinationService;
import com.landrop.realtime.RealtimeServer;
import com.landrop.realtime.RealtimeStore;
import com.landrop.realtime.SocketAuthenticator;
import com.landrop.realtime.WsProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end unit and integration tests verifying Phase 18A requirements:
 * workspace-aware file/folder hierarchy, metadata registration, safe projections,
 * soft delete, cycle prevention, User -> Device -> Peer mapping,
 * transfer request lifecycle (request, accept, reject, progress, complete),
 * same-user multi-device transfer, and workspace authorization boundaries.
 */
class FileManagementTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JwtService JWT = new JwtService("access-key-32-chars-for-testing!", "refresh-key-32-chars-testing!!", 900, 3600);

    private final User userAnh = testUser("anh");
    private final User userKen = testUser("ken");
    private final User userOutsider = testUser("outsider");

    private final UUID workspaceId = UUID.randomUUID();
    private final UUID otherWorkspaceId = UUID.randomUUID();

    private InMemoryDataStore store;
    private FileManagementService fileService;
    private TransferCoordinationService transferService;
    private RealtimeServer realtimeServer;
    private com.sun.net.httpserver.HttpServer httpServer;
    private HttpClient http;
    private final List<WsProbe> probes = new ArrayList<>();

    private static User testUser(String name) {
        return new User(UUID.randomUUID(), name + "@example.com", name, name.toUpperCase(), null, null, Instant.now(), Instant.now());
    }

    @BeforeEach
    void setUp() throws Exception {
        store = new InMemoryDataStore();
        store.users.put(userAnh.id(), userAnh);
        store.users.put(userKen.id(), userKen);
        store.users.put(userOutsider.id(), userOutsider);

        store.memberships.put(workspaceId + ":" + userAnh.id(), WorkspaceRole.OWNER);
        store.memberships.put(workspaceId + ":" + userKen.id(), WorkspaceRole.MEMBER);
        store.memberships.put(otherWorkspaceId + ":" + userOutsider.id(), WorkspaceRole.OWNER);

        var userRepo = new UserRepository(null) {
            @Override public Optional<User> findById(UUID id) { return Optional.ofNullable(store.users.get(id)); }
        };

        // Initialize Realtime Server
        realtimeServer = new RealtimeServer(
            new InetSocketAddress("127.0.0.1", 0),
            SocketAuthenticator.jwt(JWT, userRepo),
            new RealtimeStore() {
                @Override public boolean canAccess(UUID u, UUID ch) { return true; }
                @Override public Saved save(UUID u, UUID c, UUID cl, String txt) { return null; }
                @Override public Page history(UUID u, UUID c, int l, String b) { return null; }
            }
        );
        realtimeServer.start();
        realtimeServer.awaitReady();

        // In-memory File and Transfer services wired to realtime server
        fileService = new FileManagementService(null, store.fileRepo, store.folderRepo, null) {
            @Override
            public WorkspaceRole requireWorkspaceMember(UUID wsId, UUID uId) {
                WorkspaceRole role = store.memberships.get(wsId + ":" + uId);
                if (role == null || role == WorkspaceRole.GUEST) {
                    throw new FileException(404, "WORKSPACE_NOT_FOUND", "Workspace not found.");
                }
                return role;
            }
        };

        transferService = new TransferCoordinationService(null, store.transferRepo, store.fileRepo, store.deviceRepo, fileService, realtimeServer);
        realtimeServer.setTransferService(transferService);

        // HTTP Server
        // Mount only the production routes under test; avoid unrelated database-backed defaults.
        var middleware = new AuthMiddleware(JWT);
        var mapper = AuthHttpServer.createObjectMapper();
        httpServer = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var workspaceService = new WorkspaceService(null) {
            @Override public WorkspaceView create(UUID userId, String name, String description) {
                var w = new Workspace(workspaceId, name, "slug", description, userId, Instant.now(), Instant.now());
                return new WorkspaceView(w, WorkspaceRole.OWNER);
            }
        };
        var unusedChat = new com.landrop.chat.service.ChannelChatService(() -> {
            throw new SQLException("Chat is outside this fixture");
        }, null);
        var fileRoutes = new com.landrop.file.server.FileHttpServer(middleware, fileService, transferService, mapper);
        new com.landrop.workspace.http.WorkspaceHttpServer(middleware, workspaceService, unusedChat,
                fileRoutes, mapper).register(httpServer);
        fileRoutes.register(httpServer);
        httpServer.start();
        http = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (var p : probes) p.close();
        probes.clear();
        if (httpServer != null) httpServer.stop(0);
        if (realtimeServer != null) realtimeServer.stop(0);
    }

    // =========================================================================
    // 18A.3 & 18A.4: File Listing, Detail, and Access Controls
    // =========================================================================

    @Test
    void test1_workspaceMemberCanListFiles_nonMemberDenied() throws Exception {
        // Create root file
        var f1 = fileService.createFileMetadata(userAnh.id(), workspaceId, "readme.txt", 100, "text/plain", "hash1", null, "LAN_ONLY");
        // Create folder and nested file
        var folder = fileService.createFolder(userAnh.id(), workspaceId, "Docs", null);
        var f2 = fileService.createFileMetadata(userAnh.id(), workspaceId, "spec.pdf", 2048, "application/pdf", "hash2", folder.id(), "LAN_ONLY");

        // Member (Ken) lists all files
        var resAll = httpSend("GET", "/api/workspaces/" + workspaceId + "/files", userKen, null);
        assertEquals(200, resAll.statusCode());
        JsonNode bodyAll = JSON.readTree(resAll.body()).path("data");
        assertEquals(2, bodyAll.size());

        // Member (Ken) lists root files only
        var resRoot = httpSend("GET", "/api/workspaces/" + workspaceId + "/files?rootOnly=true", userKen, null);
        assertEquals(200, resRoot.statusCode());
        JsonNode bodyRoot = JSON.readTree(resRoot.body()).path("data");
        assertEquals(1, bodyRoot.size());
        assertEquals("readme.txt", bodyRoot.get(0).path("name").asText());

        // Member (Ken) lists files inside folder Docs
        var resFolder = httpSend("GET", "/api/workspaces/" + workspaceId + "/files?folderId=" + folder.id(), userKen, null);
        assertEquals(200, resFolder.statusCode());
        JsonNode bodyFolder = JSON.readTree(resFolder.body()).path("data");
        assertEquals(1, bodyFolder.size());
        assertEquals("spec.pdf", bodyFolder.get(0).path("name").asText());

        // File detail GET /api/files/:fileId
        var resDetail = httpSend("GET", "/api/files/" + f1.id(), userKen, null);
        assertEquals(200, resDetail.statusCode());
        JsonNode detail = JSON.readTree(resDetail.body()).path("data");
        assertEquals("readme.txt", detail.path("name").asText());
        assertEquals(100, detail.path("size").asLong());
        assertEquals("hash1", detail.path("hash").asText());

        // Non-member (Outsider) cannot list workspace files -> 404
        var resOutsider = httpSend("GET", "/api/workspaces/" + workspaceId + "/files", userOutsider, null);
        assertEquals(404, resOutsider.statusCode());

        // Non-member cannot get file detail -> 404
        var resOutsiderDetail = httpSend("GET", "/api/files/" + f1.id(), userOutsider, null);
        assertEquals(404, resOutsiderDetail.statusCode());
    }

    // =========================================================================
    // 18A.5: Folder Management, Hierarchy, and Cycle Prevention
    // =========================================================================

    @Test
    void test2_folderCreationNestingAndCyclePrevention() throws Exception {
        // Create root folder A
        var resA = httpSend("POST", "/api/workspaces/" + workspaceId + "/folders", userAnh, Map.of("name", "FolderA"));
        assertEquals(201, resA.statusCode());
        UUID idA = UUID.fromString(JSON.readTree(resA.body()).path("data").path("id").asText());

        // Create nested folder B under A
        var resB = httpSend("POST", "/api/workspaces/" + workspaceId + "/folders", userAnh, Map.of("name", "FolderB", "parentId", idA.toString()));
        assertEquals(201, resB.statusCode());
        UUID idB = UUID.fromString(JSON.readTree(resB.body()).path("data").path("id").asText());

        // Create sub-sub folder C under B
        var resC = httpSend("POST", "/api/workspaces/" + workspaceId + "/folders", userAnh, Map.of("name", "FolderC", "parentId", idB.toString()));
        assertEquals(201, resC.statusCode());
        UUID idC = UUID.fromString(JSON.readTree(resC.body()).path("data").path("id").asText());

        // Rename folder B
        var resRename = httpSend("PATCH", "/api/folders/" + idB, userAnh, Map.of("name", "FolderB_Renamed"));
        assertEquals(200, resRename.statusCode());
        assertEquals("FolderB_Renamed", JSON.readTree(resRename.body()).path("data").path("name").asText());

        // Cycle Prevention: Attempting to move A into C (descendant) must be rejected
        var resCycle = httpSend("PATCH", "/api/folders/" + idA, userAnh, Map.of("parentId", idC.toString()));
        assertEquals(400, resCycle.statusCode());
        assertTrue(resCycle.body().contains("cycle"));

        // Attempting to move A into itself must be rejected
        var resSelf = httpSend("PATCH", "/api/folders/" + idA, userAnh, Map.of("parentId", idA.toString()));
        assertEquals(400, resSelf.statusCode());

        // Non-member cannot edit folder
        var resOutsider = httpSend("PATCH", "/api/folders/" + idA, userOutsider, Map.of("name", "Hacked"));
        assertEquals(404, resOutsider.statusCode());
    }

    // =========================================================================
    // 18A.6 & 18A.15: File Metadata Registration, Uploader Binding, Rename & Move
    // =========================================================================

    @Test
    void test3_fileMetadataRegistrationUploaderDerivedAndRenameMove() throws Exception {
        var folder1 = fileService.createFolder(userAnh.id(), workspaceId, "F1", null);
        var folder2 = fileService.createFolder(userAnh.id(), workspaceId, "F2", null);

        // Register file via REST POST /api/workspaces/:workspaceId/files
        var resCreate = httpSend("POST", "/api/workspaces/" + workspaceId + "/files", userAnh, Map.of(
            "name", "design.pdf",
            "size", 10240,
            "mimeType", "application/pdf",
            "hash", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "folderId", folder1.id().toString()
        ));
        assertEquals(201, resCreate.statusCode());
        JsonNode data = JSON.readTree(resCreate.body()).path("data");
        UUID fileId = UUID.fromString(data.path("id").asText());

        // Uploader must be caller's authenticated user ID, NOT client input
        assertEquals(userAnh.id().toString(), data.path("uploaderId").asText());
        assertEquals("design.pdf", data.path("name").asText());
        assertEquals("LAN_ONLY", data.path("storageType").asText());

        // Rename file
        var resRename = httpSend("PATCH", "/api/files/" + fileId, userAnh, Map.of("name", "design_final.pdf"));
        assertEquals(200, resRename.statusCode());
        assertEquals("design_final.pdf", JSON.readTree(resRename.body()).path("data").path("name").asText());

        // Move file to folder2
        var resMove = httpSend("PATCH", "/api/files/" + fileId, userAnh, Map.of("folderId", folder2.id().toString()));
        assertEquals(200, resMove.statusCode());
        assertEquals(folder2.id().toString(), JSON.readTree(resMove.body()).path("data").path("folderId").asText());
    }

    // =========================================================================
    // 18A.14: Soft Delete File and Folder
    // =========================================================================

    @Test
    void test4_softDeleteFileAndFolder() throws Exception {
        var file = fileService.createFileMetadata(userAnh.id(), workspaceId, "temp.log", 50, "text/plain", "h1", null, "LAN_ONLY");
        var folder = fileService.createFolder(userAnh.id(), workspaceId, "TempFolder", null);

        // Soft delete file
        var resDelFile = httpSend("DELETE", "/api/files/" + file.id(), userAnh, null);
        assertEquals(200, resDelFile.statusCode());
        JsonNode fileDeleted = JSON.readTree(resDelFile.body()).path("data");
        assertTrue(fileDeleted.hasNonNull("deletedAt"));

        // File is excluded from default workspace list
        var resList = httpSend("GET", "/api/workspaces/" + workspaceId + "/files", userAnh, null);
        JsonNode list = JSON.readTree(resList.body()).path("data");
        assertTrue(list.isEmpty());

        // Updating soft-deleted file is rejected
        var resPatchDeleted = httpSend("PATCH", "/api/files/" + file.id(), userAnh, Map.of("name", "revived.log"));
        assertEquals(400, resPatchDeleted.statusCode());

        // Soft delete folder
        var resDelFolder = httpSend("DELETE", "/api/folders/" + folder.id(), userAnh, null);
        assertEquals(200, resDelFolder.statusCode());
        assertTrue(JSON.readTree(resDelFolder.body()).path("data").hasNonNull("deletedAt"));
    }

    // =========================================================================
    // 18A.8 & 18A.9: User -> Device -> Peer Mapping (Multi-Device)
    // =========================================================================

    @Test
    void test5_userDevicePeerMappingAndMultiDevice() throws Exception {
        // User Anh registers Device 1 (Laptop)
        var resDev1 = httpSend("POST", "/api/devices", userAnh, Map.of(
            "deviceId", "anh-laptop",
            "deviceName", "Anh's MacBook",
            "peerId", "peer-anh-laptop",
            "ipAddress", "192.168.1.10",
            "port", 9001
        ));
        assertEquals(200, resDev1.statusCode());
        JsonNode dev1 = JSON.readTree(resDev1.body()).path("data");
        assertEquals("anh-laptop", dev1.path("deviceId").asText());
        assertEquals("peer-anh-laptop", dev1.path("peerId").asText());

        // User Anh registers Device 2 (Desktop)
        var resDev2 = httpSend("POST", "/api/devices", userAnh, Map.of(
            "deviceId", "anh-desktop",
            "deviceName", "Anh's PC",
            "peerId", "peer-anh-desktop",
            "ipAddress", "192.168.1.11",
            "port", 9002
        ));
        assertEquals(200, resDev2.statusCode());

        // List devices for User Anh
        var resList = httpSend("GET", "/api/devices", userAnh, null);
        assertEquals(200, resList.statusCode());
        JsonNode devices = JSON.readTree(resList.body()).path("data");
        assertEquals(2, devices.size());

        // Ken also checks Anh's devices
        var resKenGetAnhDevices = httpSend("GET", "/api/users/" + userAnh.id() + "/devices", userKen, null);
        assertEquals(200, resKenGetAnhDevices.statusCode());
        assertEquals(2, JSON.readTree(resKenGetAnhDevices.body()).path("data").size());
    }

    // =========================================================================
    // 18A.10, 18A.11, 18A.12, 18A.18: Multi-User Transfer Lifecycle & Realtime Events
    // =========================================================================

    @Test
    void test6_multiUserTransferRequestAcceptProgressComplete() throws Exception {
        // User Anh registers Device A; User Ken registers Device B
        transferService.registerDevice(userAnh.id(), "dev-anh", "Anh Laptop", "peer-a", "192.168.1.5", 9000);
        transferService.registerDevice(userKen.id(), "dev-ken", "Ken Laptop", "peer-b", "192.168.1.6", 9000);

        // Ken connects to WebSocket
        var kenWs = WsProbe.openRoom(realtimeServer, token(userKen), "dev-ken");
        probes.add(kenWs);
        kenWs.event("connected");

        // Anh connects to WebSocket
        var anhWs = WsProbe.openRoom(realtimeServer, token(userAnh), "dev-anh");
        probes.add(anhWs);
        anhWs.event("connected");

        // Anh creates a file
        var file = fileService.createFileMetadata(userAnh.id(), workspaceId, "design.pdf", 5_000_000, "application/pdf", "sha-design-123", null, "LAN_ONLY");

        // Anh creates transfer request to Ken
        var resReq = httpSend("POST", "/api/transfers", userAnh, Map.of(
            "fileId", file.id().toString(),
            "receiverUserId", userKen.id().toString(),
            "senderDeviceId", "dev-anh",
            "receiverDeviceId", "dev-ken"
        ));
        assertEquals(201, resReq.statusCode());
        JsonNode transferJson = JSON.readTree(resReq.body()).path("data");
        UUID transferId = UUID.fromString(transferJson.path("id").asText());
        assertEquals("PENDING", transferJson.path("status").asText());

        // Ken receives realtime event: transfer:request
        var reqEvent = kenWs.event("transfer:request");
        assertEquals(transferId.toString(), reqEvent.path("id").asText());

        // Ken accepts the transfer
        var resAccept = httpSend("POST", "/api/transfers/" + transferId + "/accept", userKen, Map.of("receiverDeviceId", "dev-ken"));
        assertEquals(200, resAccept.statusCode());
        assertEquals("ACCEPTED", JSON.readTree(resAccept.body()).path("data").path("status").asText());

        // Anh receives realtime event: transfer:accepted
        var acceptEvent = anhWs.event("transfer:accepted");
        assertEquals(transferId.toString(), acceptEvent.path("id").asText());

        // Progress update during transfer (auto-transitions ACCEPTED -> TRANSFERRING)
        httpSend("POST", "/api/transfers/" + transferId + "/progress", userAnh, Map.of(
            "bytesTransferred", 2_500_000,
            "totalBytes", 5_000_000,
            "speed", 1024000.0
        ));

        // Both receive started event upon auto-transition to TRANSFERRING
        var startA = anhWs.event("transfer:started");
        assertEquals(transferId.toString(), startA.path("id").asText());
        var startB = kenWs.event("transfer:started");
        assertEquals(transferId.toString(), startB.path("id").asText());

        // Both receive progress event
        var progA = anhWs.event("transfer:progress");
        assertEquals(transferId.toString(), progA.path("transferId").asText());
        assertEquals(2500000, progA.path("bytesTransferred").asLong());

        var progB = kenWs.event("transfer:progress");
        assertEquals(transferId.toString(), progB.path("transferId").asText());

        // Complete transfer
        var resComp = httpSend("POST", "/api/transfers/" + transferId + "/complete", userAnh, null);
        assertEquals(200, resComp.statusCode());
        assertEquals("COMPLETED", JSON.readTree(resComp.body()).path("data").path("status").asText());

        // Both receive completion event
        var compA = anhWs.event("transfer:completed");
        assertEquals("COMPLETED", compA.path("status").asText());
        var compB = kenWs.event("transfer:completed");
        assertEquals("COMPLETED", compB.path("status").asText());
    }

    // =========================================================================
    // 18A.11: Transfer Rejection and Cancellation
    // =========================================================================

    @Test
    void test7_transferRejectionAndCancellation() throws Exception {
        var file = fileService.createFileMetadata(userAnh.id(), workspaceId, "temp.bin", 100, "application/octet-stream", "h", null, "LAN_ONLY");

        // Anh creates transfer request
        var t1 = transferService.requestTransfer(userAnh.id(), file.id(), userKen.id(), null, null);
        assertEquals("PENDING", t1.status());

        // Ken rejects transfer
        var resReject = httpSend("POST", "/api/transfers/" + t1.id() + "/reject", userKen, Map.of("reason", "Disk full"));
        assertEquals(200, resReject.statusCode());
        assertEquals("REJECTED", JSON.readTree(resReject.body()).path("data").path("status").asText());

        // Anh creates another transfer request and cancels it
        var t2 = transferService.requestTransfer(userAnh.id(), file.id(), userKen.id(), null, null);
        var resCancel = httpSend("POST", "/api/transfers/" + t2.id() + "/cancel", userAnh, Map.of("reason", "User aborted"));
        assertEquals(200, resCancel.statusCode());
        assertEquals("CANCELLED", JSON.readTree(resCancel.body()).path("data").path("status").asText());
    }

    // =========================================================================
    // 18A.19: Same-User Multi-Device Transfer
    // =========================================================================

    @Test
    void test8_sameUserMultiDeviceTransfer() throws Exception {
        transferService.registerDevice(userAnh.id(), "anh-laptop", "MacBook", "peer-laptop", "10.0.0.1", 9001);
        transferService.registerDevice(userAnh.id(), "anh-desktop", "PC", "peer-desktop", "10.0.0.2", 9002);

        var desktopWs = WsProbe.openRoom(realtimeServer, token(userAnh), "anh-desktop");
        probes.add(desktopWs);
        desktopWs.event("connected");

        var laptopWs = WsProbe.openRoom(realtimeServer, token(userAnh), "anh-laptop");
        probes.add(laptopWs);
        laptopWs.event("connected");

        var file = fileService.createFileMetadata(userAnh.id(), workspaceId, "notes.sync", 500, "text/plain", "hsync", null, "LAN_ONLY");

        // Anh Laptop transfers to Anh Desktop
        var t = transferService.requestTransfer(userAnh.id(), file.id(), userAnh.id(), "anh-laptop", "anh-desktop");
        assertEquals("PENDING", t.status());
        assertEquals("anh-laptop", t.senderDeviceId());
        assertEquals("anh-desktop", t.receiverDeviceId());

        // Desktop receives the request
        var event = desktopWs.event("transfer:request");
        assertEquals(t.id().toString(), event.path("id").asText());

        // Desktop accepts
        var accepted = transferService.acceptTransfer(userAnh.id(), t.id(), "anh-desktop");
        assertEquals("ACCEPTED", accepted.status());
    }

    // =========================================================================
    // 18A.13: Workspace Transfer Authorization Boundaries
    // =========================================================================

    @Test
    void test9_workspaceTransferAuthorizationBoundaries() throws Exception {
        var file = fileService.createFileMetadata(userAnh.id(), workspaceId, "secret.dat", 100, "application/octet-stream", "h", null, "LAN_ONLY");

        // Outsider attempts to initiate transfer of workspace file -> 404
        var resOutsider = httpSend("POST", "/api/transfers", userOutsider, Map.of(
            "fileId", file.id().toString(),
            "receiverUserId", userKen.id().toString()
        ));
        assertEquals(404, resOutsider.statusCode());

        // Member attempts to transfer workspace file to outsider (not in workspace) -> 404
        var resToOutsider = httpSend("POST", "/api/transfers", userAnh, Map.of(
            "fileId", file.id().toString(),
            "receiverUserId", userOutsider.id().toString()
        ));
        assertEquals(404, resToOutsider.statusCode());
    }

    @Test
    void test10_oversizedBodyRejectedWith413() throws Exception {
        // Send a body larger than 64KB (70KB)
        String largePadding = "x".repeat(70 * 1024);
        var res = httpSend("POST", "/api/transfers", userAnh, Map.of(
            "fileId", UUID.randomUUID().toString(),
            "receiverUserId", userKen.id().toString(),
            "padding", largePadding
        ));
        assertEquals(413, res.statusCode(), "Payload larger than MAX_BODY_BYTES must return HTTP 413");
        assertTrue(res.body().contains("PAYLOAD_TOO_LARGE"), "Error code should be PAYLOAD_TOO_LARGE: " + res.body());
    }

    // =========================================================================
    // HTTP Helpers
    // =========================================================================

    private HttpResponse<String> httpSend(String method, String path, User user, Object body) throws Exception {
        var builder = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + httpServer.getAddress().getPort() + path))
            .header("Authorization", "Bearer " + token(user))
            .header("Content-Type", "application/json");

        if ("POST".equalsIgnoreCase(method)) {
            builder.POST(body != null ? HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)) : HttpRequest.BodyPublishers.noBody());
        } else if ("PATCH".equalsIgnoreCase(method)) {
            builder.method("PATCH", body != null ? HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)) : HttpRequest.BodyPublishers.noBody());
        } else if ("DELETE".equalsIgnoreCase(method)) {
            builder.DELETE();
        } else {
            builder.GET();
        }

        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String token(User user) {
        return JWT.generateAccessToken(user);
    }

    // =========================================================================
    // In-Memory Test Stores
    // =========================================================================

    static class InMemoryDataStore {
        final Map<UUID, User> users = new ConcurrentHashMap<>();
        final Map<String, WorkspaceRole> memberships = new ConcurrentHashMap<>();
        final Map<UUID, FileRecord> files = new ConcurrentHashMap<>();
        final Map<UUID, Folder> folders = new ConcurrentHashMap<>();
        final Map<String, Device> devices = new ConcurrentHashMap<>();
        final Map<UUID, TransferRecord> transfers = new ConcurrentHashMap<>();

        final FileRepository fileRepo = new FileRepository(null) {
            @Override public FileRecord create(FileRecord f) {
                UUID id = f.id() != null ? f.id() : UUID.randomUUID();
                FileRecord r = new FileRecord(id, f.workspaceId(), f.userId(), f.folderId(), f.name(), f.originalName(), f.size(), f.mimeType(), f.sha256(), f.storageType(), f.storageKey(), Instant.now(), Instant.now(), null);
                files.put(id, r);
                return r;
            }
            @Override public Optional<FileRecord> findById(UUID id) { return Optional.ofNullable(files.get(id)); }
            @Override public List<FileRecord> findByWorkspaceId(UUID wsId, UUID folderId, boolean isRootOnly, boolean includeDeleted) {
                return files.values().stream()
                    .filter(f -> f.workspaceId().equals(wsId))
                    .filter(f -> isRootOnly ? f.folderId() == null : (folderId == null || folderId.equals(f.folderId())))
                    .filter(f -> includeDeleted || f.deletedAt() == null)
                    .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                    .toList();
            }
            @Override public FileRecord update(UUID id, String name, UUID folderId, boolean updateFolder) {
                FileRecord ex = files.get(id);
                if (ex == null) return null;
                FileRecord updated = new FileRecord(ex.id(), ex.workspaceId(), ex.userId(),
                    updateFolder ? folderId : ex.folderId(),
                    name != null ? name : ex.name(),
                    ex.originalName(), ex.size(), ex.mimeType(), ex.sha256(), ex.storageType(), ex.storageKey(), ex.createdAt(), Instant.now(), ex.deletedAt());
                files.put(id, updated);
                return updated;
            }
            @Override public FileRecord softDelete(UUID id) {
                FileRecord ex = files.get(id);
                if (ex == null) return null;
                FileRecord del = new FileRecord(ex.id(), ex.workspaceId(), ex.userId(), ex.folderId(), ex.name(), ex.originalName(), ex.size(), ex.mimeType(), ex.sha256(), ex.storageType(), ex.storageKey(), ex.createdAt(), Instant.now(), Instant.now());
                files.put(id, del);
                return del;
            }
        };

        final FolderRepository folderRepo = new FolderRepository(null) {
            @Override public Folder create(Folder f) {
                UUID id = f.id() != null ? f.id() : UUID.randomUUID();
                Folder r = new Folder(id, f.workspaceId(), f.name(), f.parentFolderId(), f.createdById(), Instant.now(), Instant.now(), null);
                folders.put(id, r);
                return r;
            }
            @Override public Optional<Folder> findById(UUID id) { return Optional.ofNullable(folders.get(id)); }
            @Override public List<Folder> findByWorkspaceId(UUID wsId, UUID parentId, boolean isRootOnly, boolean includeDeleted) {
                return folders.values().stream()
                    .filter(f -> f.workspaceId().equals(wsId))
                    .filter(f -> isRootOnly ? f.parentFolderId() == null : (parentId == null || parentId.equals(f.parentFolderId())))
                    .filter(f -> includeDeleted || f.deletedAt() == null)
                    .sorted(Comparator.comparing(Folder::name))
                    .toList();
            }
            @Override public Folder update(UUID id, String name, UUID parentId, boolean updateParent) {
                Folder ex = folders.get(id);
                if (ex == null) return null;
                Folder updated = new Folder(ex.id(), ex.workspaceId(), name != null ? name : ex.name(),
                    updateParent ? parentId : ex.parentFolderId(), ex.createdById(), ex.createdAt(), Instant.now(), ex.deletedAt());
                folders.put(id, updated);
                return updated;
            }
            @Override public Folder softDelete(UUID id) {
                Folder ex = folders.get(id);
                if (ex == null) return null;
                Folder del = new Folder(ex.id(), ex.workspaceId(), ex.name(), ex.parentFolderId(), ex.createdById(), ex.createdAt(), Instant.now(), Instant.now());
                folders.put(id, del);
                return del;
            }
            @Override public boolean isDescendantOrSelf(UUID potDescendant, UUID ancestor) {
                if (potDescendant == null || ancestor == null) return false;
                if (potDescendant.equals(ancestor)) return true;
                UUID curr = potDescendant;
                Set<UUID> visited = new HashSet<>();
                while (curr != null) {
                    if (!visited.add(curr)) break;
                    Folder f = folders.get(curr);
                    if (f == null) break;
                    if (ancestor.equals(f.parentFolderId())) return true;
                    curr = f.parentFolderId();
                }
                return false;
            }
        };

        final DeviceRepository deviceRepo = new DeviceRepository(null) {
            @Override public Device upsert(Device d) {
                devices.put(d.id(), d);
                return d;
            }
            @Override public Optional<Device> findById(String id) { return Optional.ofNullable(devices.get(id)); }
            @Override public List<Device> findByUserId(UUID uId) {
                return devices.values().stream().filter(d -> d.userId().equals(uId)).toList();
            }
        };

        final TransferRepository transferRepo = new TransferRepository(null) {
            @Override public TransferRecord create(TransferRecord t) {
                UUID id = t.id() != null ? t.id() : UUID.randomUUID();
                TransferRecord r = new TransferRecord(id, t.fileId(), t.senderUserId(), t.receiverUserId(), t.senderDeviceId(), t.receiverDeviceId(), t.status(), Instant.now(), t.startedAt(), t.completedAt(), t.failedAt(), t.errorMessage());
                transfers.put(id, r);
                return r;
            }
            @Override public Optional<TransferRecord> findById(UUID id) { return Optional.ofNullable(transfers.get(id)); }
            @Override public TransferRecord updateStatus(UUID id, String status, Instant start, Instant comp, Instant fail, String err) {
                TransferRecord ex = transfers.get(id);
                if (ex == null) return null;
                TransferRecord updated = new TransferRecord(ex.id(), ex.fileId(), ex.senderUserId(), ex.receiverUserId(), ex.senderDeviceId(), ex.receiverDeviceId(), status, ex.createdAt(),
                    start != null ? start : ex.startedAt(), comp != null ? comp : ex.completedAt(), fail != null ? fail : ex.failedAt(), err != null ? err : ex.errorMessage());
                transfers.put(id, updated);
                return updated;
            }
        };
    }
}
