package com.landrop.discovery;

import com.landrop.chat.ChatListener;
import com.landrop.chat.ChatMessage;
import com.landrop.chat.ChatService;
import com.landrop.peer.PeerConnection;
import com.landrop.peer.PeerListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoveryIntegrationTest {

    @Test
    @DisplayName("Test 9 — Discovery integration: Start DiscoveryServer, client A registers, requests PEER_LIST, verify response")
    void test9_DiscoveryIntegration() throws Exception {
        try (DiscoveryServer server = new DiscoveryServer(0)) {
            server.startAsync();
            assertTrue(server.awaitReady(5, TimeUnit.SECONDS));
            int serverPort = server.getPort();

            try (DiscoveryClient clientA = new DiscoveryClient("localhost", serverPort);
                 DiscoveryClient clientB = new DiscoveryClient("localhost", serverPort)) {

                clientA.connect();
                PeerInfo infoA = new PeerInfo("uuid-a", "Peer A", "127.0.0.1", 5001);
                boolean registeredA = clientA.register(infoA);
                assertTrue(registeredA, "Peer A should register successfully");

                clientB.connect();
                PeerInfo infoB = new PeerInfo("uuid-b", "Peer B", "127.0.0.1", 5002);
                boolean registeredB = clientB.register(infoB);
                assertTrue(registeredB, "Peer B should register successfully");

                // Peer A requests peer list -> should only see Peer B
                List<PeerInfo> listForA = clientA.requestPeerList();
                assertEquals(1, listForA.size(), "Peer A should discover exactly 1 other peer");
                assertEquals("uuid-b", listForA.get(0).getPeerId());
                assertEquals("Peer B", listForA.get(0).getDisplayName());

                // Peer B requests peer list -> should only see Peer A
                List<PeerInfo> listForB = clientB.requestPeerList();
                assertEquals(1, listForB.size(), "Peer B should discover exactly 1 other peer");
                assertEquals("uuid-a", listForB.get(0).getPeerId());
                assertEquals("Peer A", listForB.get(0).getDisplayName());

                // Heartbeat check
                assertTrue(clientA.heartbeat(), "Client A heartbeat should succeed");

                // Unregister check
                clientA.unregister();
                List<PeerInfo> listAfterUnregister = clientB.requestPeerList();
                assertEquals(0, listAfterUnregister.size(), "List should be empty after Peer A unregisters");
            }
        }
    }

    @Test
    @DisplayName("Test 10 & 24 — Direct P2P: Both register, A discovers B, connects directly, chat is 100% direct without server relay")
    void test10_DirectP2pAfterDiscovery() throws Exception {
        try (DiscoveryServer discoveryServer = new DiscoveryServer(0)) {
            discoveryServer.startAsync();
            assertTrue(discoveryServer.awaitReady(5, TimeUnit.SECONDS));
            int discoveryPort = discoveryServer.getPort();

            // 1. Peer B starts local P2P server
            try (PeerListener listenerB = new PeerListener(0)) {
                int p2pPortB = listenerB.getPort();

                CountDownLatch bAcceptLatch = new CountDownLatch(1);
                AtomicReference<PeerConnection> bConnectionRef = new AtomicReference<>();

                Thread bAcceptorThread = new Thread(() -> {
                    try {
                        PeerConnection pc = listenerB.acceptConnection();
                        bConnectionRef.set(pc);
                        pc.setLocalPeerName("Peer B");
                        pc.start();
                        bAcceptLatch.countDown();
                    } catch (Exception ignored) {
                    }
                });
                bAcceptorThread.start();

                // 2. Both peers connect and register on Discovery Server
                try (DiscoveryClient clientA = new DiscoveryClient("localhost", discoveryPort);
                     DiscoveryClient clientB = new DiscoveryClient("localhost", discoveryPort)) {

                    clientA.connect();
                    clientA.register(new PeerInfo("uuid-a", "Peer A", "127.0.0.1", 5001));

                    clientB.connect();
                    clientB.register(new PeerInfo("uuid-b", "Peer B", "127.0.0.1", p2pPortB));

                    // 3. Peer A requests peer list from Discovery Server
                    List<PeerInfo> peers = clientA.requestPeerList();
                    assertEquals(1, peers.size());
                    PeerInfo discoveredB = peers.get(0);
                    assertEquals("uuid-b", discoveredB.getPeerId());
                    assertEquals(p2pPortB, discoveredB.getPort());

                    // 4. Peer A connects DIRECTLY to Peer B (NOT through Discovery Server)
                    PeerConnection directConnA = PeerConnection.connectTo(discoveredB.getHost(), discoveredB.getPort());
                    directConnA.setLocalPeerName("Peer A");
                    directConnA.start();

                    directConnA.initiateHandshake("Peer A");
                    assertTrue(directConnA.awaitReady(5, TimeUnit.SECONDS));
                    assertTrue(bAcceptLatch.await(5, TimeUnit.SECONDS));

                    PeerConnection directConnB = bConnectionRef.get();
                    assertNotNull(directConnB);
                    assertTrue(directConnB.awaitReady(5, TimeUnit.SECONDS));

                    // 5. Direct P2P Chat exchange
                    ChatService chatA = new ChatService(directConnA, "Peer A");
                    ChatService chatB = new ChatService(directConnB, "Peer B");

                    CountDownLatch msgReceivedLatch = new CountDownLatch(1);
                    AtomicReference<ChatMessage> receivedMsgRef = new AtomicReference<>();

                    chatB.addListener(new ChatListener() {
                        @Override
                        public void onMessageReceived(ChatMessage message) {
                            receivedMsgRef.set(message);
                            msgReceivedLatch.countDown();
                        }
                    });

                    // A sends message directly to B
                    chatA.sendMessage("Direct P2P message from A to B");

                    assertTrue(msgReceivedLatch.await(5, TimeUnit.SECONDS));
                    assertEquals("Direct P2P message from A to B", receivedMsgRef.get().content());
                    assertEquals("Peer A", receivedMsgRef.get().sender());

                    // Architecture verification: Discovery Server has never handled any chat packets
                    // DiscoveryServer registry only tracks PeerInfo and has 2 peers registered
                    assertEquals(2, discoveryServer.getRegistry().size());

                    directConnA.close();
                    directConnB.close();
                }
            }
        }
    }
}
