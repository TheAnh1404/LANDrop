package com.landrop.server.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.landrop.server.FoundationWsClient;
import com.landrop.server.bootstrap.ServerBootstrap;
import com.landrop.server.config.ServerConfig;
import com.landrop.server.protocol.ProtocolConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebRTCSignalingRelayTest {

    private ServerBootstrap bootstrap;
    private int wsPort;

    @BeforeEach
    void setUp() throws Exception {
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
        wsPort = bootstrap.getRealtimeServer().getPort();
    }

    @AfterEach
    void tearDown() {
        if (bootstrap != null) {
            bootstrap.stop();
        }
    }

    @Test
    void testWebRTCOfferAnswerAndCandidateRelay() throws Exception {
        try (FoundationWsClient clientA = FoundationWsClient.connect(wsPort);
             FoundationWsClient clientB = FoundationWsClient.connect(wsPort)) {

            // 1. Hello handshakes
            clientA.sendJson(Map.of(
                "type", "hello",
                "protocolVersion", 1,
                "clientInstanceId", "peer-browser-a"
            ));
            JsonNode welcomeA = clientA.receive(3000);
            String connIdA = welcomeA.path("connectionId").asText();

            clientB.sendJson(Map.of(
                "type", "hello",
                "protocolVersion", 1,
                "clientInstanceId", "peer-browser-b"
            ));
            JsonNode welcomeB = clientB.receive(3000);
            String connIdB = welcomeB.path("connectionId").asText();

            // 2. Transfer request from A to B
            clientA.sendJson(Map.of(
                "type", ProtocolConstants.EVENT_TRANSFER_REQUEST,
                "requestId", "req-1",
                "payload", Map.of(
                    "receiverConnectionId", connIdB,
                    "fileName", "vacation_video.mp4",
                    "fileSize", 104857600L,
                    "sha256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                )
            ));

            // Client A gets transfer status PENDING
            JsonNode statusA = clientA.receive(3000);
            assertEquals(ProtocolConstants.EVENT_TRANSFER_STATUS, statusA.path("type").asText());
            String transferId = statusA.path("payload").path("transferId").asText();
            assertNotNull(transferId);

            // Client B gets transfer.request
            JsonNode transferReqB = clientB.receive(3000);
            assertEquals(ProtocolConstants.EVENT_TRANSFER_REQUEST, transferReqB.path("type").asText());

            // 3. Client B accepts transfer
            clientB.sendJson(Map.of(
                "type", ProtocolConstants.EVENT_TRANSFER_ACCEPT,
                "requestId", "req-2",
                "payload", Map.of("transferId", transferId)
            ));

            // Client B gets status ACCEPTED
            JsonNode statusB = clientB.receive(3000);
            assertEquals(ProtocolConstants.EVENT_TRANSFER_STATUS, statusB.path("type").asText());

            // Client A gets transfer.accept event
            JsonNode acceptEvtA = clientA.receive(3000);
            assertEquals(ProtocolConstants.EVENT_TRANSFER_ACCEPT, acceptEvtA.path("type").asText());

            // 4. Client A sends webrtc.offer to Client B
            clientA.sendJson(Map.of(
                "type", ProtocolConstants.EVENT_WEBRTC_OFFER,
                "requestId", "sig-1",
                "payload", Map.of(
                    "transferId", transferId,
                    "sdp", Map.of("type", "offer", "sdp", "v=0\r\no=alice ...")
                )
            ));

            // Client A receives ack
            JsonNode offerAckA = clientA.receive(3000);
            assertEquals("webrtc.offer_ack", offerAckA.path("type").asText());

            // Client B receives the forwarded webrtc.offer!
            JsonNode offerB = clientB.receive(3000);
            assertEquals(ProtocolConstants.EVENT_WEBRTC_OFFER, offerB.path("type").asText());
            assertEquals(connIdA, offerB.path("payload").path("fromConnectionId").asText());
            assertEquals("offer", offerB.path("payload").path("sdp").path("type").asText());

            // 5. Client B replies with webrtc.answer to Client A
            clientB.sendJson(Map.of(
                "type", ProtocolConstants.EVENT_WEBRTC_ANSWER,
                "requestId", "sig-2",
                "payload", Map.of(
                    "transferId", transferId,
                    "sdp", Map.of("type", "answer", "sdp", "v=0\r\no=bob ...")
                )
            ));

            // Client B receives ack
            JsonNode answerAckB = clientB.receive(3000);
            assertEquals("webrtc.answer_ack", answerAckB.path("type").asText());

            // Client A receives the forwarded webrtc.answer!
            JsonNode answerA = clientA.receive(3000);
            assertEquals(ProtocolConstants.EVENT_WEBRTC_ANSWER, answerA.path("type").asText());
            assertEquals(connIdB, answerA.path("payload").path("fromConnectionId").asText());
            assertEquals("answer", answerA.path("payload").path("sdp").path("type").asText());

            // 6. Client A sends an ICE candidate
            clientA.sendJson(Map.of(
                "type", ProtocolConstants.EVENT_WEBRTC_CANDIDATE,
                "payload", Map.of(
                    "transferId", transferId,
                    "candidate", Map.of("candidate", "candidate:1 1 UDP ...", "sdpMid", "0", "sdpMLineIndex", 0)
                )
            ));

            // Client B receives ICE candidate
            JsonNode candB = clientB.receive(3000);
            assertEquals(ProtocolConstants.EVENT_WEBRTC_CANDIDATE, candB.path("type").asText());
            assertEquals("candidate:1 1 UDP ...", candB.path("payload").path("candidate").path("candidate").asText());
        }
    }
}
