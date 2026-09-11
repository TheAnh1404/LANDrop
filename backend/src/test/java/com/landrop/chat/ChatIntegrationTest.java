package com.landrop.chat;

import com.landrop.peer.ConnectionState;
import com.landrop.peer.PeerConnection;
import com.landrop.peer.PeerListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end localhost integration test validating the complete Phase 3 flow:
 * HELLO Handshake -> Bidirectional Chat -> PING/PONG -> Graceful Disconnect.
 */
class ChatIntegrationTest {

    @Test
    @DisplayName("Localhost integration test: HELLO -> CHAT A to B -> CHAT B to A -> PING/PONG -> Disconnect")
    void testCompleteLocalhostFlow() throws Exception {
        // Step 1: Start Peer B server on dynamic available port
        try (PeerListener serverListener = new PeerListener(0)) {
            int port = serverListener.getPort();
            assertTrue(port > 0, "Bound port must be valid positive integer");

            CountDownLatch serverAcceptLatch = new CountDownLatch(1);
            AtomicReference<PeerConnection> serverConnectionRef = new AtomicReference<>();

            Thread serverThread = new Thread(() -> {
                try {
                    PeerConnection sc = serverListener.acceptConnection();
                    serverConnectionRef.set(sc);
                    sc.setLocalPeerName("Peer B");
                    sc.start();
                    serverAcceptLatch.countDown();
                } catch (Exception ignored) {
                }
            });
            serverThread.start();

            // Step 2: Connect Peer A
            PeerConnection clientConnection = PeerConnection.connectTo("localhost", port);
            clientConnection.setLocalPeerName("Peer A");
            clientConnection.start();

            assertTrue(serverAcceptLatch.await(5, TimeUnit.SECONDS), "Server must accept connection");
            PeerConnection serverConnection = serverConnectionRef.get();
            assertNotNull(serverConnection);

            // Step 3: Perform HELLO Handshake
            clientConnection.initiateHandshake("Peer A");
            assertTrue(clientConnection.awaitReady(5, TimeUnit.SECONDS), "Client must reach READY state");
            assertTrue(serverConnection.awaitReady(5, TimeUnit.SECONDS), "Server must reach READY state");

            assertEquals(ConnectionState.READY, clientConnection.getState());
            assertEquals(ConnectionState.READY, serverConnection.getState());
            assertEquals("Peer B", clientConnection.getRemotePeerName());
            assertEquals("Peer A", serverConnection.getRemotePeerName());

            // Initialize Chat Services
            ChatService chatA = new ChatService(clientConnection, "Peer A");
            ChatService chatB = new ChatService(serverConnection, "Peer B");

            CountDownLatch aToBLatch = new CountDownLatch(1);
            CountDownLatch bToALatch = new CountDownLatch(1);
            AtomicReference<ChatMessage> msgReceivedAtB = new AtomicReference<>();
            AtomicReference<ChatMessage> msgReceivedAtA = new AtomicReference<>();

            chatB.addListener(new ChatListener() {
                @Override
                public void onMessageReceived(ChatMessage message) {
                    msgReceivedAtB.set(message);
                    aToBLatch.countDown();
                }
            });

            chatA.addListener(new ChatListener() {
                @Override
                public void onMessageReceived(ChatMessage message) {
                    msgReceivedAtA.set(message);
                    bToALatch.countDown();
                }
            });

            // Step 4: CHAT_MESSAGE A -> B
            ChatMessage sentFromA = chatA.sendMessage("Hello from Peer A to Peer B!");
            assertTrue(aToBLatch.await(5, TimeUnit.SECONDS), "Message from A to B must be received");
            assertEquals("Hello from Peer A to Peer B!", msgReceivedAtB.get().content());
            assertEquals("Peer A", msgReceivedAtB.get().sender());
            assertEquals(sentFromA.requestId(), msgReceivedAtB.get().requestId());

            // Step 5: CHAT_MESSAGE B -> A
            ChatMessage sentFromB = chatB.sendMessage("Hello from Peer B back to Peer A!");
            assertTrue(bToALatch.await(5, TimeUnit.SECONDS), "Message from B to A must be received");
            assertEquals("Hello from Peer B back to Peer A!", msgReceivedAtA.get().content());
            assertEquals("Peer B", msgReceivedAtA.get().sender());
            assertEquals(sentFromB.requestId(), msgReceivedAtA.get().requestId());

            // Step 6: PING -> PONG
            CompletableFuture<Double> pingFuture = chatA.ping();
            Double latencyMs = pingFuture.get(5, TimeUnit.SECONDS);
            assertNotNull(latencyMs);
            assertTrue(latencyMs >= 0.0, "Latency in ms must be non-negative: " + latencyMs);

            // Step 7: Graceful Disconnect
            CountDownLatch disconnectAtBLatch = new CountDownLatch(1);
            chatB.addListener(new ChatListener() {
                @Override
                public void onChatDisconnected() {
                    disconnectAtBLatch.countDown();
                }
            });

            chatA.close();
            assertTrue(disconnectAtBLatch.await(5, TimeUnit.SECONDS), "Peer B should detect disconnect from Peer A");
            assertFalse(chatA.isConnected());
            assertFalse(chatB.isConnected());

            chatB.close();
        }
    }
}
