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
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FOUNDATION-02A: SERVER CLIENT LAN INTEGRATION GATE TEST
 * Validates real LAN binding, health check, contract correlation,
 * server restart with reconnect, duplicate client instance policy,
 * and transfer signaling resilience.
 */
class LanIntegrationGateTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private ServerBootstrap bootstrap;

    @AfterEach
    void tearDown() {
        if (bootstrap != null && bootstrap.isRunning()) {
            bootstrap.stop();
        }
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    // =========================================================================
    // 1. LAN IP Resolution & Banner Tests
    // =========================================================================

    @Test
    void testLanIpResolutionAndStatusReport() throws Exception {
        List<String> lanIps = ServerBootstrap.resolveLanIpv4Addresses();
        assertNotNull(lanIps);

        int httpPort = findFreePort();
        int wsPort = findFreePort();

        ServerConfig config = new ServerConfig(
            "0.0.0.0", // bind all LAN interfaces
            httpPort,
            wsPort,
            ProtocolConstants.PROTOCOL_VERSION,
            ProtocolConstants.DEFAULT_HEARTBEAT_INTERVAL_MS,
            ProtocolConstants.DEFAULT_HEARTBEAT_TIMEOUT_MS,
            "test-lan",
            "INFO"
        );

        bootstrap = new ServerBootstrap(config);
        bootstrap.start();
        assertTrue(bootstrap.isRunning());

        // Verify GET /health
        HttpRequest healthReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + httpPort + "/health"))
                .GET()
                .build();
        HttpResponse<String> healthRes = http.send(healthReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, healthRes.statusCode());
        JsonNode healthJson = JSON.readTree(healthRes.body());
        assertEquals("ok", healthJson.path("status").asText());
        assertEquals(ProtocolConstants.SERVER_VERSION, healthJson.path("serverVersion").asText());
        assertEquals(1, healthJson.path("protocolVersion").asInt());

        // Verify GET /status contains LAN IP addresses
        HttpRequest statusReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + httpPort + "/status"))
                .GET()
                .build();
        HttpResponse<String> statusRes = http.send(statusReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, statusRes.statusCode());
        JsonNode statusJson = JSON.readTree(statusRes.body());
        assertEquals("RUNNING", statusJson.path("serverStatus").asText());
        assertTrue(statusJson.has("lanAddresses"));
    }

    // =========================================================================
    // 2. Echo Round-Trip Correlation (No RequestId Drift)
    // =========================================================================

    @Test
    void testMultipleEchoRoundTripsWithStrictRequestIdCorrelation() throws Exception {
        int httpPort = findFreePort();
        int wsPort = findFreePort();

        ServerConfig config = new ServerConfig("127.0.0.1", httpPort, wsPort, 1, 15000L, 45000L, "test", "INFO");
        bootstrap = new ServerBootstrap(config);
        bootstrap.start();

        try (FoundationWsClient client = FoundationWsClient.connect(wsPort)) {
            client.sendJson(Map.of(
                "type", "hello",
                "protocolVersion", 1,
                "clientInstanceId", "echo-loop-tester"
            ));
            JsonNode welcome = client.receive(3000);
            assertNotNull(welcome);
            assertEquals("welcome", welcome.path("type").asText());

            // Send 20 consecutive echo messages with distinct request IDs
            for (int i = 1; i <= 20; i++) {
                String reqId = "req-echo-id-" + i;
                String testMsg = "Ping message #" + i;

                client.sendJson(Map.of(
                    "type", "echo",
                    "requestId", reqId,
                    "payload", Map.of("message", testMsg)
                ));

                JsonNode ack = client.receive(3000);
                assertNotNull(ack, "Expected echo_ack for iteration " + i);
                assertEquals("echo_ack", ack.path("type").asText());
                assertEquals(reqId, ack.path("requestId").asText(), "RequestId must correlate strictly");
                assertEquals(testMsg, ack.path("payload").path("message").asText());
                assertTrue(ack.path("payload").has("serverTimestamp"));
            }
        }
    }

    // =========================================================================
    // 3. Continuous Heartbeat Stream (No Duplicate Connections, No Leaks)
    // =========================================================================

    @Test
    void testContinuousHeartbeatsMaintainSingleActiveSession() throws Exception {
        int httpPort = findFreePort();
        int wsPort = findFreePort();

        ServerConfig config = new ServerConfig("127.0.0.1", httpPort, wsPort, 1, 15000L, 45000L, "test", "INFO");
        bootstrap = new ServerBootstrap(config);
        bootstrap.start();

        try (FoundationWsClient client = FoundationWsClient.connect(wsPort)) {
            client.sendJson(Map.of(
                "type", "hello",
                "protocolVersion", 1,
                "clientInstanceId", "hb-stream-tester"
            ));
            JsonNode welcome = client.receive(3000);
            String connId = welcome.path("connectionId").asText();

            ClientConnection conn = bootstrap.getClientRegistry().get(connId).orElseThrow();

            // Send 5 heartbeats with short delays
            for (int i = 1; i <= 5; i++) {
                Thread.sleep(30);
                String hbReqId = "hb-stream-" + i;
                client.sendJson(Map.of(
                    "type", "heartbeat",
                    "requestId", hbReqId
                ));

                JsonNode ack = client.receive(3000);
                assertNotNull(ack);
                assertEquals("heartbeat_ack", ack.path("type").asText());
                assertEquals(hbReqId, ack.path("requestId").asText());

                // Registry must still have exactly 1 connection
                assertEquals(1, bootstrap.getClientRegistry().size());
                assertEquals(ClientStatus.ACTIVE, conn.status());
            }
        }
    }

    // =========================================================================
    // 4. Server Restart & Client Reconnect Flow
    // =========================================================================

    @Test
    void testServerRestartFlowWithClientReconnection() throws Exception {
        int httpPort = findFreePort();
        int wsPort = findFreePort();

        ServerConfig config = new ServerConfig("127.0.0.1", httpPort, wsPort, 1, 15000L, 45000L, "test", "INFO");
        bootstrap = new ServerBootstrap(config);
        bootstrap.start();

        // 1. Initial connection
        FoundationWsClient client = FoundationWsClient.connect(wsPort);
        client.sendJson(Map.of(
            "type", "hello",
            "protocolVersion", 1,
            "clientInstanceId", "restart-tester-client"
        ));
        JsonNode welcome1 = client.receive(3000);
        assertNotNull(welcome1);
        assertEquals("welcome", welcome1.path("type").asText());
        String initialConnId = welcome1.path("connectionId").asText();
        assertTrue(bootstrap.getClientRegistry().get(initialConnId).isPresent());

        // 2. Server restarts cleanly
        bootstrap.stop();
        assertFalse(bootstrap.isRunning());

        // Client detects closure
        client.close();

        // 3. Server starts back up on the SAME ports
        bootstrap = new ServerBootstrap(config);
        bootstrap.start();
        assertTrue(bootstrap.isRunning());

        // 4. Client reconnects without requiring manual intervention
        try (FoundationWsClient reconnectedClient = FoundationWsClient.connect(wsPort)) {
            reconnectedClient.sendJson(Map.of(
                "type", "hello",
                "protocolVersion", 1,
                "clientInstanceId", "restart-tester-client"
            ));

            JsonNode welcome2 = reconnectedClient.receive(3000);
            assertNotNull(welcome2);
            assertEquals("welcome", welcome2.path("type").asText());
            String newConnId = welcome2.path("connectionId").asText();
            assertNotEquals(initialConnId, newConnId);

            // Verify echo works on new connection
            reconnectedClient.sendJson(Map.of(
                "type", "echo",
                "requestId", "after-restart-echo",
                "payload", Map.of("message", "back online")
            ));
            JsonNode ack = reconnectedClient.receive(3000);
            assertNotNull(ack);
            assertEquals("echo_ack", ack.path("type").asText());
            assertEquals("back online", ack.path("payload").path("message").asText());

            // Registry now holds 1 active entry
            assertEquals(1, bootstrap.getClientRegistry().size());
        }
    }

    // =========================================================================
    // 5. Duplicate Client Instance ID Policy
    // =========================================================================

    @Test
    void testDuplicateClientInstanceIdPolicySupersedesStale() throws Exception {
        int httpPort = findFreePort();
        int wsPort = findFreePort();

        ServerConfig config = new ServerConfig("127.0.0.1", httpPort, wsPort, 1, 15000L, 45000L, "test", "INFO");
        bootstrap = new ServerBootstrap(config);
        bootstrap.start();

        String sharedClientInstanceId = "singleton-workstation-id";

        // Session 1 connects
        try (FoundationWsClient session1 = FoundationWsClient.connect(wsPort)) {
            session1.sendJson(Map.of(
                "type", "hello",
                "protocolVersion", 1,
                "clientInstanceId", sharedClientInstanceId,
                "deviceName", "Session 1"
            ));
            JsonNode w1 = session1.receive(3000);
            String connId1 = w1.path("connectionId").asText();

            // Session 2 connects with identical clientInstanceId (simulating app relaunch or Wi-Fi flip)
            try (FoundationWsClient session2 = FoundationWsClient.connect(wsPort)) {
                session2.sendJson(Map.of(
                    "type", "hello",
                    "protocolVersion", 1,
                    "clientInstanceId", sharedClientInstanceId,
                    "deviceName", "Session 2"
                ));
                JsonNode w2 = session2.receive(3000);
                String connId2 = w2.path("connectionId").asText();

                assertNotEquals(connId1, connId2);

                // Session 1 must receive clean closure
                int s1CloseCode = session1.awaitClose(3000);
                assertEquals(1000, s1CloseCode);

                // Session 2 is the ONLY active entry in registry for this instance
                assertEquals(1, bootstrap.getClientRegistry().size());
                Optional<ClientConnection> active = bootstrap.getClientRegistry().findByInstanceId(sharedClientInstanceId);
                assertTrue(active.isPresent());
                assertEquals(connId2, active.get().connectionId());
                assertEquals("Session 2", active.get().deviceName());

                // Session 3 connects again with same ID
                try (FoundationWsClient session3 = FoundationWsClient.connect(wsPort)) {
                    session3.sendJson(Map.of(
                        "type", "hello",
                        "protocolVersion", 1,
                        "clientInstanceId", sharedClientInstanceId,
                        "deviceName", "Session 3"
                    ));
                    JsonNode w3 = session3.receive(3000);
                    String connId3 = w3.path("connectionId").asText();

                    int s2CloseCode = session2.awaitClose(3000);
                    assertEquals(1000, s2CloseCode);

                    assertEquals(1, bootstrap.getClientRegistry().size());
                    assertEquals(connId3, bootstrap.getClientRegistry().findByInstanceId(sharedClientInstanceId).orElseThrow().connectionId());
                }
            }
        }
    }

    // =========================================================================
    // 6. Transfer Reject & Cancel Lifecycle Tests
    // =========================================================================

    @Test
    void testTransferRejectAndCancelControlFlows() throws Exception {
        int httpPort = findFreePort();
        int wsPort = findFreePort();

        ServerConfig config = new ServerConfig("127.0.0.1", httpPort, wsPort, 1, 15000L, 45000L, "test", "INFO");
        bootstrap = new ServerBootstrap(config);
        bootstrap.start();

        try (FoundationWsClient sender = FoundationWsClient.connect(wsPort);
             FoundationWsClient receiver = FoundationWsClient.connect(wsPort)) {

            sender.sendJson(Map.of("type", "hello", "protocolVersion", 1, "clientInstanceId", "sender-control"));
            sender.receive(3000);

            receiver.sendJson(Map.of("type", "hello", "protocolVersion", 1, "clientInstanceId", "receiver-control"));
            JsonNode wR = receiver.receive(3000);
            String receiverConnId = wR.path("connectionId").asText();

            // Scenario A: Receiver REJECTS transfer
            sender.sendJson(Map.of(
                "type", "transfer.request",
                "payload", Map.of(
                    "receiverConnectionId", receiverConnId,
                    "fileName", "declined_file.zip",
                    "fileSize", 4096
                )
            ));

            JsonNode sAck1 = sender.receive(3000);
            String tx1Id = sAck1.path("payload").path("transferId").asText();
            receiver.receive(3000); // drain incoming request

            // Receiver rejects
            receiver.sendJson(Map.of(
                "type", "transfer.reject",
                "payload", Map.of("transferId", tx1Id, "reason", "Storage full")
            ));

            receiver.receive(3000); // status ack to receiver
            JsonNode rejectNotice = sender.receive(3000);
            assertNotNull(rejectNotice);
            assertEquals("transfer.reject", rejectNotice.path("type").asText());
            assertEquals(tx1Id, rejectNotice.path("payload").path("transferId").asText());
            assertEquals("Storage full", rejectNotice.path("payload").path("reason").asText());

            TransferRecord rec1 = bootstrap.getTransferCoordinator().getTransfer(tx1Id).orElseThrow();
            assertEquals(TransferStatus.REJECTED, rec1.status());

            // Scenario B: Sender CANCELS in-flight transfer
            sender.sendJson(Map.of(
                "type", "transfer.request",
                "payload", Map.of(
                    "receiverConnectionId", receiverConnId,
                    "fileName", "cancelled_file.mov",
                    "fileSize", 10240
                )
            ));

            JsonNode sAck2 = sender.receive(3000);
            String tx2Id = sAck2.path("payload").path("transferId").asText();
            receiver.receive(3000); // drain incoming request

            // Sender cancels
            sender.sendJson(Map.of(
                "type", "transfer.cancel",
                "payload", Map.of("transferId", tx2Id, "reason", "User aborted")
            ));

            sender.receive(3000); // status ack to sender
            JsonNode cancelNotice = receiver.receive(3000);
            assertNotNull(cancelNotice);
            assertEquals("transfer.cancel", cancelNotice.path("type").asText());
            assertEquals(tx2Id, cancelNotice.path("payload").path("transferId").asText());
            assertEquals("User aborted", cancelNotice.path("payload").path("reason").asText());

            TransferRecord rec2 = bootstrap.getTransferCoordinator().getTransfer(tx2Id).orElseThrow();
            assertEquals(TransferStatus.CANCELLED, rec2.status());
        }
    }

    // =========================================================================
    // 7. Malformed File Metadata Validation & Security Guardrails
    // =========================================================================

    @Test
    void testFileMetadataValidationGuardrails() throws Exception {
        int httpPort = findFreePort();
        int wsPort = findFreePort();

        ServerConfig config = new ServerConfig("127.0.0.1", httpPort, wsPort, 1, 15000L, 45000L, "test", "INFO");
        bootstrap = new ServerBootstrap(config);
        bootstrap.start();

        try (FoundationWsClient sender = FoundationWsClient.connect(wsPort);
             FoundationWsClient receiver = FoundationWsClient.connect(wsPort)) {

            sender.sendJson(Map.of("type", "hello", "protocolVersion", 1, "clientInstanceId", "sec-sender"));
            sender.receive(3000);

            receiver.sendJson(Map.of("type", "hello", "protocolVersion", 1, "clientInstanceId", "sec-receiver"));
            JsonNode wR = receiver.receive(3000);
            String rConnId = wR.path("connectionId").asText();

            // Case A: Negative file size
            sender.sendJson(Map.of(
                "type", "transfer.request",
                "payload", Map.of("receiverConnectionId", rConnId, "fileName", "bad.dat", "fileSize", -50)
            ));
            JsonNode err1 = sender.receive(3000);
            assertEquals("error", err1.path("type").asText());
            assertEquals(ProtocolConstants.ERR_INVALID_PAYLOAD, err1.path("error").path("code").asText());

            // Case B: Path traversal filename
            sender.sendJson(Map.of(
                "type", "transfer.request",
                "payload", Map.of("receiverConnectionId", rConnId, "fileName", "../../windows/system32/cmd.exe", "fileSize", 100)
            ));
            JsonNode ack2 = sender.receive(3000);
            // Server sanitizes base name to cmd.exe, preventing directory traversal
            assertEquals("transfer.status", ack2.path("type").asText());
            assertEquals("cmd.exe", ack2.path("payload").path("fileName").asText());

            // Case C: Invalid SHA-256 hash (non-hex or wrong length)
            sender.sendJson(Map.of(
                "type", "transfer.request",
                "payload", Map.of("receiverConnectionId", rConnId, "fileName", "test.bin", "fileSize", 100, "sha256", "NOT-A-VALID-HASH")
            ));
            JsonNode err3 = sender.receive(3000);
            assertEquals("error", err3.path("type").asText());
            assertEquals(ProtocolConstants.ERR_INVALID_PAYLOAD, err3.path("error").path("code").asText());
        }
    }
}
