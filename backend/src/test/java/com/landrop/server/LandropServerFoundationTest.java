package com.landrop.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.server.bootstrap.ServerBootstrap;
import com.landrop.server.clients.ClientConnection;
import com.landrop.server.clients.ClientStatus;
import com.landrop.server.config.ServerConfig;
import com.landrop.server.protocol.ProtocolConstants;
import com.landrop.server.transfers.TransferRecord;
import com.landrop.server.transfers.TransferStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end unit and integration tests validating LANDrop Server Stable Core (FOUNDATION-01A).
 */
class LandropServerFoundationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ServerBootstrap bootstrap;
    private int httpPort;
    private int wsPort;
    private HttpClient http;

    @BeforeEach
    void setUp() throws Exception {
        http = HttpClient.newHttpClient();
        // Bind to 0.0.0.0 with dynamic ports (0) to guarantee zero collisions
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

        httpPort = bootstrap.getHttpServer().getPort();
        wsPort = bootstrap.getRealtimeServer().getPort();
    }

    @AfterEach
    void tearDown() {
        if (bootstrap != null && bootstrap.isRunning()) {
            bootstrap.stop();
        }
    }

    // =========================================================================
    // 1. HTTP Server & Health Check Tests
    // =========================================================================

    @Test
    void testServerStartAndHealthCheck() throws Exception {
        assertTrue(bootstrap.isRunning(), "Server should be marked running");

        // Test GET /health
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + httpPort + "/health"))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("*", response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        JsonNode json = JSON.readTree(response.body());
        assertEquals("ok", json.path("status").asText());
        assertEquals(ProtocolConstants.SERVER_VERSION, json.path("serverVersion").asText());
        assertEquals(ProtocolConstants.PROTOCOL_VERSION, json.path("protocolVersion").asInt());
        assertTrue(json.has("uptime"), "Should contain uptime in seconds");

        // Test GET /status
        HttpRequest statusReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + httpPort + "/status"))
                .GET()
                .build();
        HttpResponse<String> statusRes = http.send(statusReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, statusRes.statusCode());
        assertEquals("*", statusRes.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        JsonNode statusJson = JSON.readTree(statusRes.body());
        assertEquals("RUNNING", statusJson.path("serverStatus").asText());
        assertEquals(0, statusJson.path("connectedClients").asInt());
    }

    @Test
    void testBrowserHealthPreflight() throws Exception {
        for (String route : new String[]{"/health", "/api/health", "/status", "/api/status"}) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + httpPort + route))
                    .header("Origin", "http://localhost:3000")
                    .header("Access-Control-Request-Method", "GET")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(204, response.statusCode(), route);
            assertEquals("", response.body(), route);
            assertEquals("*", response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
            assertEquals("GET, OPTIONS", response.headers().firstValue("Access-Control-Allow-Methods").orElseThrow());
        }
    }

    @Test
    void testRootRedirectAndApiCatalog() throws Exception {
        java.net.http.HttpClient noRedirectClient = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .build();
        HttpRequest rootReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + httpPort + "/"))
                .GET()
                .build();
        HttpResponse<String> rootRes = noRedirectClient.send(rootReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, rootRes.statusCode());
        assertTrue(rootRes.body().contains("LANDrop"));

        HttpRequest adminTargetReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + httpPort + "/?target=admin"))
                .GET()
                .build();
        HttpResponse<String> adminTargetRes = noRedirectClient.send(adminTargetReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(410, adminTargetRes.statusCode());
        assertTrue(adminTargetRes.body().contains("ADMIN_WEB_REMOVED"));
        assertTrue(adminTargetRes.headers().firstValue("Location").isEmpty());

        HttpRequest apiReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + httpPort + "/api"))
                .GET()
                .build();
        HttpResponse<String> apiRes = http.send(apiReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, apiRes.statusCode());
        JsonNode apiJson = JSON.readTree(apiRes.body());
        assertEquals("UP", apiJson.path("status").asText());
        assertTrue(apiJson.path("endpoints").has("health"));
    }

    @Test
    void testClientConnectRoute() throws Exception {
        HttpRequest connectReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + httpPort + "/connect?server=http%3A%2F%2F192.168.1.230%3A3000"))
                .GET()
                .build();
        HttpResponse<String> connectRes = http.send(connectReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, connectRes.statusCode());
        assertTrue(connectRes.body().contains("LANDrop"));
        assertTrue(connectRes.headers().firstValue("Content-Type").orElse("").contains("text/html"));

        HttpRequest clientReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + httpPort + "/client"))
                .GET()
                .build();
        HttpResponse<String> clientRes = http.send(clientReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, clientRes.statusCode());
        assertTrue(clientRes.body().contains("LANDrop"));
    }

    @Test
    void testServerNetworkBindingConfig() {
        // Verify default config allows 0.0.0.0 binding
        ServerConfig defaultConfig = ServerConfig.load();
        assertNotNull(defaultConfig.getHost());
        assertFalse(defaultConfig.getHost().isBlank());
        assertEquals(1, defaultConfig.getProtocolVersion());
    }

    // =========================================================================
    // 2. WebSocket Handshake Tests
    // =========================================================================

    @Test
    void testWebSocketHandshakeSuccess() throws Exception {
        try (FoundationWsClient client = FoundationWsClient.connect(wsPort)) {
            // Send HELLO
            client.sendJson(Map.of(
                "type", "hello",
                "protocolVersion", ProtocolConstants.PROTOCOL_VERSION,
                "clientVersion", "1.0.0",
                "clientInstanceId", "instance-test-1",
                "deviceName", "Dev Laptop",
                "platform", "Windows"
            ));

            JsonNode welcome = client.receive(3000);
            assertNotNull(welcome, "Expected WELCOME response");
            assertEquals("welcome", welcome.path("type").asText());
            assertEquals(1, welcome.path("protocolVersion").asInt());
            assertEquals(ProtocolConstants.SERVER_VERSION, welcome.path("serverVersion").asText());
            assertTrue(welcome.path("heartbeatIntervalMs").asLong() > 0);

            String connectionId = welcome.path("connectionId").asText();
            assertFalse(connectionId.isBlank());

            // Verify client registry contains connection
            Optional<ClientConnection> regConn = bootstrap.getClientRegistry().get(connectionId);
            assertTrue(regConn.isPresent());
            assertEquals("instance-test-1", regConn.get().clientInstanceId());
            assertEquals("Dev Laptop", regConn.get().deviceName());
            assertEquals(ClientStatus.ACTIVE, regConn.get().status());
        }
    }

    @Test
    void testWebSocketHandshakeUnsupportedProtocolRejected() throws Exception {
        try (FoundationWsClient client = FoundationWsClient.connect(wsPort)) {
            // Send HELLO with unsupported protocol version 99
            client.sendJson(Map.of(
                "type", "hello",
                "protocolVersion", 99,
                "clientVersion", "1.0.0",
                "clientInstanceId", "instance-bad-proto"
            ));

            JsonNode error = client.receive(3000);
            assertNotNull(error, "Expected error response for unsupported protocol");
            assertEquals("error", error.path("type").asText());
            assertEquals(ProtocolConstants.ERR_PROTOCOL_VERSION_UNSUPPORTED, error.path("code").asText());

            // Verify connection was closed by server
            int closeCode = client.awaitClose(3000);
            assertTrue(closeCode == 1002 || closeCode == 1008);
        }
    }

    @Test
    void testMessageBeforeHandshakeRejected() throws Exception {
        try (FoundationWsClient client = FoundationWsClient.connect(wsPort)) {
            // Send echo before hello
            client.sendJson(Map.of(
                "type", "echo",
                "requestId", "pre-handshake-1",
                "payload", Map.of("message", "sneak in")
            ));

            JsonNode error = client.receive(3000);
            assertNotNull(error);
            assertEquals("error", error.path("type").asText());

            int closeCode = client.awaitClose(3000);
            assertTrue(closeCode == 1008 || closeCode == 1002);
        }
    }

    // =========================================================================
    // 3. Realtime Echo & Heartbeat Tests
    // =========================================================================

    @Test
    void testEchoAndEchoAck() throws Exception {
        try (FoundationWsClient client = FoundationWsClient.connect(wsPort)) {
            // Handshake
            client.sendJson(Map.of(
                "type", "hello",
                "protocolVersion", 1,
                "clientInstanceId", "echo-test"
            ));
            client.receive(3000);

            // Send Echo
            client.sendJson(Map.of(
                "type", "echo",
                "requestId", "req-echo-123",
                "payload", Map.of("message", "hello server test")
            ));

            JsonNode echoAck = client.receive(3000);
            assertNotNull(echoAck);
            assertEquals("echo_ack", echoAck.path("type").asText());
            assertEquals("req-echo-123", echoAck.path("requestId").asText());
            assertEquals("hello server test", echoAck.path("payload").path("message").asText());
            assertTrue(echoAck.path("payload").has("serverTimestamp"));
        }
    }

    @Test
    void testHeartbeatUpdatesLastSeen() throws Exception {
        try (FoundationWsClient client = FoundationWsClient.connect(wsPort)) {
            // Handshake
            client.sendJson(Map.of(
                "type", "hello",
                "protocolVersion", 1,
                "clientInstanceId", "hb-test"
            ));
            JsonNode welcome = client.receive(3000);
            String connId = welcome.path("connectionId").asText();

            ClientConnection conn = bootstrap.getClientRegistry().get(connId).orElseThrow();
            Instant initialHb = conn.lastHeartbeatAt();

            Thread.sleep(50);

            // Send heartbeat
            client.sendJson(Map.of(
                "type", "heartbeat",
                "requestId", "hb-1"
            ));

            JsonNode ack = client.receive(3000);
            assertNotNull(ack);
            assertEquals("heartbeat_ack", ack.path("type").asText());
            assertEquals("hb-1", ack.path("requestId").asText());

            assertTrue(conn.lastHeartbeatAt().isAfter(initialHb), "Heartbeat timestamp should advance");
        }
    }

    @Test
    void testHeartbeatTimeoutCleanup() throws Exception {
        // Start a dedicated test server with a fast timeout (300ms)
        ServerConfig fastConfig = new ServerConfig(
            "127.0.0.1", 0, 0, 1, 100L, 300L, "test", "INFO"
        );
        ServerBootstrap fastBootstrap = new ServerBootstrap(fastConfig);
        fastBootstrap.start();

        try (FoundationWsClient client = FoundationWsClient.connect(fastBootstrap.getRealtimeServer().getPort())) {
            client.sendJson(Map.of(
                "type", "hello",
                "protocolVersion", 1,
                "clientInstanceId", "timeout-tester"
            ));
            JsonNode welcome = client.receive(3000);
            String connId = welcome.path("connectionId").asText();

            // Client sits idle without sending heartbeats
            int closeCode = client.awaitClose(4000);
            assertEquals(4000, closeCode, "Server should close socket with heartbeat timeout code (4000)");

            // Verify connection was cleaned from registry
            assertTrue(fastBootstrap.getClientRegistry().get(connId).isEmpty(),
                    "Connection should be removed from client registry after timeout");
        } finally {
            fastBootstrap.stop();
        }
    }

    @Test
    void testClientDisconnectCleanup() throws Exception {
        String connId;
        try (FoundationWsClient client = FoundationWsClient.connect(wsPort)) {
            client.sendJson(Map.of(
                "type", "hello",
                "protocolVersion", 1,
                "clientInstanceId", "disc-test"
            ));
            JsonNode welcome = client.receive(3000);
            connId = welcome.path("connectionId").asText();
            assertTrue(bootstrap.getClientRegistry().get(connId).isPresent());
        }

        // Wait brief moment for onClose callback
        Thread.sleep(150);
        assertTrue(bootstrap.getClientRegistry().get(connId).isEmpty(),
                "Client should be removed from registry on normal disconnect");
    }

    @Test
    void testReconnectFriendlySupersedesOldConnection() throws Exception {
        try (FoundationWsClient client1 = FoundationWsClient.connect(wsPort)) {
            client1.sendJson(Map.of(
                "type", "hello",
                "protocolVersion", 1,
                "clientInstanceId", "same-device-instance"
            ));
            JsonNode w1 = client1.receive(3000);
            String connId1 = w1.path("connectionId").asText();

            // Connect a second client with the SAME instance ID (reconnect after blip)
            try (FoundationWsClient client2 = FoundationWsClient.connect(wsPort)) {
                client2.sendJson(Map.of(
                    "type", "hello",
                    "protocolVersion", 1,
                    "clientInstanceId", "same-device-instance"
                ));
                JsonNode w2 = client2.receive(3000);
                String connId2 = w2.path("connectionId").asText();

                assertNotEquals(connId1, connId2);

                // Verify client1 received close frame
                int closeCode = client1.awaitClose(3000);
                assertEquals(1000, closeCode);

                // Verify client2 is active in registry
                Optional<ClientConnection> active = bootstrap.getClientRegistry().findByInstanceId("same-device-instance");
                assertTrue(active.isPresent());
                assertEquals(connId2, active.get().connectionId());
            }
        }
    }

    // =========================================================================
    // 4. Resilience & Error Handling Tests
    // =========================================================================

    @Test
    void testUnknownEventDoesNotCrashServer() throws Exception {
        try (FoundationWsClient client = FoundationWsClient.connect(wsPort)) {
            client.sendJson(Map.of("type", "hello", "protocolVersion", 1, "clientInstanceId", "unknown-test"));
            client.receive(3000);

            // Send unknown event
            client.sendJson(Map.of(
                "type", "totally.fake.event.that.does.not.exist",
                "requestId", "unknown-1"
            ));

            JsonNode error = client.receive(3000);
            assertNotNull(error);
            assertEquals("error", error.path("type").asText());
            assertEquals(ProtocolConstants.ERR_UNKNOWN_EVENT, error.path("error").path("code").asText());

            // Server is still alive; send echo to confirm
            client.sendJson(Map.of("type", "echo", "requestId", "after-unknown", "payload", Map.of("message", "alive")));
            JsonNode echoAck = client.receive(3000);
            assertNotNull(echoAck);
            assertEquals("echo_ack", echoAck.path("type").asText());
        }
    }

    @Test
    void testInvalidJsonDoesNotCrashServer() throws Exception {
        try (FoundationWsClient client = FoundationWsClient.connect(wsPort)) {
            client.sendJson(Map.of("type", "hello", "protocolVersion", 1, "clientInstanceId", "json-test"));
            client.receive(3000);

            // Send broken raw text
            client.sendRaw("THIS IS NOT JSON {{{ [[[ %%%");

            JsonNode error = client.receive(3000);
            assertNotNull(error);
            assertEquals("error", error.path("type").asText());

            // Confirm server is still functioning
            client.sendJson(Map.of("type", "echo", "requestId", "after-bad-json", "payload", Map.of("message", "survived")));
            JsonNode echoAck = client.receive(3000);
            assertNotNull(echoAck);
            assertEquals("echo_ack", echoAck.path("type").asText());
        }
    }

    // =========================================================================
    // 5. Transfer Coordinator Foundation Tests
    // =========================================================================

    @Test
    void testTransferRequestAcceptLifecycle() throws Exception {
        SignalingTestSupport signaling = new SignalingTestSupport(bootstrap);
        try (FoundationWsClient sender = FoundationWsClient.connect(wsPort);
             FoundationWsClient receiver = FoundationWsClient.connect(wsPort)) {

            // Handshake Sender
            sender.sendJson(Map.of("type", "hello", "protocolVersion", 1, "clientInstanceId", "sender-inst", "deviceName", "Sender Mac"));
            JsonNode wSender = sender.receive(3000);
            String senderConnId = wSender.path("connectionId").asText();
            signaling.authenticate(sender);

            // Handshake Receiver
            receiver.sendJson(Map.of("type", "hello", "protocolVersion", 1, "clientInstanceId", "receiver-inst", "deviceName", "Receiver PC"));
            JsonNode wReceiver = receiver.receive(3000);
            String receiverConnId = wReceiver.path("connectionId").asText();
            signaling.authenticate(receiver);

            // 1. Sender initiates transfer request
            sender.sendJson(Map.of(
                "type", "transfer.request",
                "requestId", "req-tx-1",
                "payload", Map.of(
                    "workspaceId", signaling.workspace.id().toString(),
                    "receiverConnectionId", receiverConnId,
                    "fileName", "important_report.pdf",
                    "fileSize", 2048576L,
                    "sha256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                )
            ));

            // Sender receives REQUESTED status ack
            JsonNode senderAck = sender.receive(3000);
            assertNotNull(senderAck);
            assertEquals("transfer.status", senderAck.path("type").asText());
            String transferId = senderAck.path("payload").path("transferId").asText();
            assertEquals("REQUESTED", senderAck.path("payload").path("status").asText());

            // Receiver receives transfer request
            JsonNode incomingReq = receiver.receive(3000);
            assertNotNull(incomingReq);
            assertEquals("transfer.request", incomingReq.path("type").asText());
            assertEquals(transferId, incomingReq.path("payload").path("transferId").asText());
            assertEquals("important_report.pdf", incomingReq.path("payload").path("fileName").asText());

            // 2. Receiver accepts transfer
            receiver.sendJson(Map.of(
                "type", "transfer.accept",
                "requestId", "accept-tx-1",
                "payload", Map.of("transferId", transferId)
            ));

            // Receiver gets status ACCEPTED
            JsonNode receiverAck = receiver.receive(3000);
            assertEquals("transfer.status", receiverAck.path("type").asText());
            assertEquals("ACCEPTED", receiverAck.path("payload").path("status").asText());

            // Sender receives notification that transfer was accepted
            JsonNode senderNotification = sender.receive(3000);
            assertNotNull(senderNotification);
            assertEquals("transfer.accept", senderNotification.path("type").asText());
            assertEquals(transferId, senderNotification.path("payload").path("transferId").asText());

            // Verify state in TransferCoordinator
            TransferRecord record = bootstrap.getTransferCoordinator().getTransfer(transferId).orElseThrow();
            assertEquals(TransferStatus.ACCEPTED, record.status());
            assertEquals("important_report.pdf", record.fileName());
            assertEquals(2048576L, record.fileSize());
        }
    }

    @Test
    void testTransferTargetReceiverNotFound() throws Exception {
        SignalingTestSupport signaling = new SignalingTestSupport(bootstrap);
        try (FoundationWsClient sender = FoundationWsClient.connect(wsPort)) {
            sender.sendJson(Map.of("type", "hello", "protocolVersion", 1, "clientInstanceId", "sender-ghost"));
            sender.receive(3000);
            signaling.authenticate(sender);

            // Request transfer to non-existent connection ID
            sender.sendJson(Map.of(
                "type", "transfer.request",
                "requestId", "req-ghost",
                "payload", Map.of(
                    "workspaceId", signaling.workspace.id().toString(),
                    "receiverConnectionId", "non-existent-conn-id-9999",
                    "fileName", "ghost.txt",
                    "fileSize", 100
                )
            ));

            JsonNode error = sender.receive(3000);
            assertNotNull(error);
            assertEquals("error", error.path("type").asText());
            assertEquals(ProtocolConstants.ERR_RECEIVER_NOT_FOUND, error.path("error").path("code").asText());
        }
    }

    @Test
    void testTransferCancelledWhenPeerDisconnects() throws Exception {
        SignalingTestSupport signaling = new SignalingTestSupport(bootstrap);
        try (FoundationWsClient sender = FoundationWsClient.connect(wsPort);
             FoundationWsClient receiver = FoundationWsClient.connect(wsPort)) {

            sender.sendJson(Map.of("type", "hello", "protocolVersion", 1, "clientInstanceId", "s-dc"));
            sender.receive(3000);
            signaling.authenticate(sender);

            receiver.sendJson(Map.of("type", "hello", "protocolVersion", 1, "clientInstanceId", "r-dc"));
            JsonNode wR = receiver.receive(3000);
            String rConnId = wR.path("connectionId").asText();
            signaling.authenticate(receiver);

            // Sender requests transfer
            sender.sendJson(Map.of(
                "type", "transfer.request",
                "payload", Map.of(
                    "workspaceId", signaling.workspace.id().toString(),
                    "receiverConnectionId", rConnId,
                    "fileName", "will_cancel.dat",
                    "fileSize", 500
                )
            ));

            JsonNode sAck = sender.receive(3000);
            String txId = sAck.path("payload").path("transferId").asText();
            receiver.receive(3000); // drain incoming request

            // Now Sender abruptly disconnects
            sender.close();

            // Receiver should be notified of cancellation
            JsonNode cancelNotice = receiver.receive(3000);
            assertNotNull(cancelNotice, "Receiver should be notified that transfer was cancelled due to disconnect");
            assertEquals("transfer.cancel", cancelNotice.path("type").asText());
            assertEquals(txId, cancelNotice.path("payload").path("transferId").asText());

            // Transfer in coordinator should be CANCELLED
            TransferRecord tx = bootstrap.getTransferCoordinator().getTransfer(txId).orElseThrow();
            assertEquals(TransferStatus.CANCELLED, tx.status());
        }
    }

    // =========================================================================
    // 6. Graceful Shutdown & Lifecycle Tests
    // =========================================================================

    @Test
    void testGracefulShutdownStopsSubsystemsCleanly() {
        assertTrue(bootstrap.isRunning());
        bootstrap.stop();
        assertFalse(bootstrap.isRunning(), "Server should be stopped");
        assertEquals(0, bootstrap.getClientRegistry().size());
    }
}
