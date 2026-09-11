package com.landrop.network;

import com.landrop.protocol.MessageType;
import com.landrop.protocol.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests verifying TCP communication between PeerServer and PeerClient
 * using the LANDrop Phase 2 custom binary protocol.
 */
class PeerNetworkTest {

    @Test
    @DisplayName("Verify end-to-end Phase 2 protocol flow: HELLO, CHAT_MESSAGE, PING/PONG")
    void testPhase2EndToEndFlow() throws Exception {
        try (PeerServer server = new PeerServer(0)) {
            server.startAsync();
            assertTrue(server.awaitReady(5, TimeUnit.SECONDS), "Server should bind within 5 seconds");

            int port = server.getPort();
            assertTrue(port > 0);
            assertTrue(server.isRunning());

            PeerClient client = new PeerClient("localhost", port);
            List<Packet> responses = client.executePhase2Flow("Hello from Peer A");

            assertEquals(3, responses.size(), "Should receive 3 responses for HELLO, CHAT, and PING");

            // 1. HELLO
            Packet helloResp = responses.get(0);
            assertEquals(MessageType.HELLO, helloResp.getType());
            assertEquals(1L, helloResp.getRequestId());
            assertEquals("LANDrop Peer B", helloResp.payloadAsString());

            // 2. CHAT_MESSAGE
            Packet chatResp = responses.get(1);
            assertEquals(MessageType.CHAT_MESSAGE, chatResp.getType());
            assertEquals(2L, chatResp.getRequestId());
            assertEquals("Hello from Peer B", chatResp.payloadAsString());

            // 3. PONG
            Packet pongResp = responses.get(2);
            assertEquals(MessageType.PONG, pongResp.getType());
            assertEquals(3L, pongResp.getRequestId());
            assertEquals(0, pongResp.getPayloadLength());

            server.stop();
            assertFalse(server.isRunning());
        }
    }

    @Test
    @DisplayName("Verify individual packet exchange: single sendPacket calls")
    void testIndividualPacketExchanges() throws Exception {
        try (PeerServer server = new PeerServer(0)) {
            server.startAsync();
            assertTrue(server.awaitReady(5, TimeUnit.SECONDS));

            PeerClient client = new PeerClient("127.0.0.1", server.getPort());

            // Send individual HELLO
            Packet helloReq = Packet.text(MessageType.HELLO, 101L, "Greeting");
            Packet helloResp = client.sendPacket(helloReq);
            assertNotNull(helloResp);
            assertEquals(MessageType.HELLO, helloResp.getType());
            assertEquals(101L, helloResp.getRequestId());
            assertEquals("LANDrop Peer B", helloResp.payloadAsString());

            // Send individual CHAT_MESSAGE
            Packet chatReq = Packet.text(MessageType.CHAT_MESSAGE, 202L, "Custom Chat");
            Packet chatResp = client.sendPacket(chatReq);
            assertNotNull(chatResp);
            assertEquals(MessageType.CHAT_MESSAGE, chatResp.getType());
            assertEquals(202L, chatResp.getRequestId());
            assertEquals("Hello from Peer B", chatResp.payloadAsString());

            // Send individual PING
            Packet pingReq = Packet.empty(MessageType.PING, 303L);
            Packet pingResp = client.sendPacket(pingReq);
            assertNotNull(pingResp);
            assertEquals(MessageType.PONG, pingResp.getType());
            assertEquals(303L, pingResp.getRequestId());

            server.stop();
        }
    }

    @Test
    @DisplayName("Verify custom packet response handler on PeerServer")
    void testCustomPacketHandler() throws Exception {
        try (PeerServer server = new PeerServer(0, req ->
                Packet.text(MessageType.CHAT_MESSAGE, req.getRequestId(), "ECHO: " + req.payloadAsString().toUpperCase()))) {
            server.startAsync();
            assertTrue(server.awaitReady(5, TimeUnit.SECONDS));

            PeerClient client = new PeerClient("localhost", server.getPort());
            Packet response = client.sendPacket(Packet.text(MessageType.CHAT_MESSAGE, 55L, "ping"));

            assertNotNull(response);
            assertEquals(MessageType.CHAT_MESSAGE, response.getType());
            assertEquals(55L, response.getRequestId());
            assertEquals("ECHO: PING", response.payloadAsString());

            server.stop();
        }
    }

    @Test
    @DisplayName("Verify client throws ConnectException gracefully when server is offline")
    void testClientConnectionRefused() throws IOException {
        int offlinePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            offlinePort = socket.getLocalPort();
        }

        PeerClient client = new PeerClient("localhost", offlinePort);
        assertThrows(ConnectException.class, () -> client.executePhase2Flow("test"));
    }

    @Test
    @DisplayName("Verify server handles port already in use gracefully")
    void testServerPortConflict() throws Exception {
        try (PeerServer server1 = new PeerServer(0)) {
            server1.startAsync();
            assertTrue(server1.awaitReady(5, TimeUnit.SECONDS));
            int port = server1.getPort();

            try (PeerServer server2 = new PeerServer(port)) {
                server2.start();
                assertFalse(server2.isRunning(), "Second server should fail to bind and stop running");
            }

            server1.stop();
        }
    }

    @Test
    @DisplayName("Verify multiple concurrent clients communicating with one server simultaneously")
    void testConcurrentClients() throws Exception {
        int clientCount = 5;
        try (PeerServer server = new PeerServer(0)) {
            server.startAsync();
            assertTrue(server.awaitReady(5, TimeUnit.SECONDS));
            int port = server.getPort();

            ExecutorService executor = Executors.newFixedThreadPool(clientCount);
            CountDownLatch latch = new CountDownLatch(clientCount);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int i = 0; i < clientCount; i++) {
                final int clientId = i + 1;
                executor.submit(() -> {
                    try {
                        PeerClient client = new PeerClient("localhost", port);
                        List<Packet> responses = client.executePhase2Flow("Client " + clientId);
                        if (responses.size() == 3 &&
                                responses.get(0).getType() == MessageType.HELLO &&
                                responses.get(1).getType() == MessageType.CHAT_MESSAGE &&
                                responses.get(2).getType() == MessageType.PONG) {
                            successCount.incrementAndGet();
                        }
                    } catch (IOException ignored) {
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS));
            executor.shutdown();

            assertEquals(clientCount, successCount.get(), "All concurrent clients should succeed");
            server.stop();
        }
    }
}
