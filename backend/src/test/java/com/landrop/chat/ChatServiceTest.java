package com.landrop.chat;

import com.landrop.peer.ConnectionState;
import com.landrop.peer.PeerConnection;
import com.landrop.peer.PeerListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit and feature tests for ChatService fulfilling Phase 3 specifications.
 */
class ChatServiceTest {

    private PeerListener listener;
    private PeerConnection serverConn;
    private PeerConnection clientConn;
    private ChatService serviceA; // Client side (Peer A)
    private ChatService serviceB; // Server side (Peer B)

    @BeforeEach
    void setUp() throws Exception {
        listener = new PeerListener(0);
        int port = listener.getPort();

        CountDownLatch acceptLatch = new CountDownLatch(1);
        AtomicReference<PeerConnection> serverConnRef = new AtomicReference<>();

        Thread serverThread = new Thread(() -> {
            try {
                PeerConnection sc = listener.acceptConnection();
                serverConnRef.set(sc);
                sc.setLocalPeerName("Peer B");
                sc.start();
                acceptLatch.countDown();
            } catch (Exception ignored) {
            }
        });
        serverThread.start();

        clientConn = PeerConnection.connectTo("localhost", port);
        clientConn.setLocalPeerName("Peer A");
        clientConn.start();

        assertTrue(acceptLatch.await(5, TimeUnit.SECONDS), "Server must accept peer connection");
        serverConn = serverConnRef.get();

        // Perform HELLO handshake
        clientConn.initiateHandshake("Peer A");
        assertTrue(clientConn.awaitReady(5, TimeUnit.SECONDS));
        assertTrue(serverConn.awaitReady(5, TimeUnit.SECONDS));

        serviceA = new ChatService(clientConn, "Peer A");
        serviceB = new ChatService(serverConn, "Peer B");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (serviceA != null) serviceA.close();
        if (serviceB != null) serviceB.close();
        if (listener != null) listener.close();
    }

    @Test
    @DisplayName("Test 1 — Chat message send/receive: Peer A sends 'Hello Peer B', Peer B receives exactly")
    void test1_ChatMessageSendReceive() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ChatMessage> receivedMsg = new AtomicReference<>();

        serviceB.addListener(new ChatListener() {
            @Override
            public void onMessageReceived(ChatMessage message) {
                receivedMsg.set(message);
                latch.countDown();
            }
        });

        ChatMessage sent = serviceA.sendMessage("Hello Peer B");

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Peer B should receive the chat message");
        assertNotNull(receivedMsg.get());
        assertEquals("Hello Peer B", receivedMsg.get().content());
        assertEquals("Peer A", receivedMsg.get().sender());
        assertEquals(sent.requestId(), receivedMsg.get().requestId());
    }

    @Test
    @DisplayName("Test 2 — Multiple messages: Message 1, 2, 3 arrive in strict chronological order")
    void test2_MultipleMessagesInOrder() throws Exception {
        int messageCount = 3;
        CountDownLatch latch = new CountDownLatch(messageCount);
        List<ChatMessage> receivedList = new ArrayList<>();

        serviceB.addListener(new ChatListener() {
            @Override
            public void onMessageReceived(ChatMessage message) {
                synchronized (receivedList) {
                    receivedList.add(message);
                }
                latch.countDown();
            }
        });

        serviceA.sendMessage("Message 1");
        serviceA.sendMessage("Message 2");
        serviceA.sendMessage("Message 3");

        assertTrue(latch.await(5, TimeUnit.SECONDS), "All 3 messages should be received");
        assertEquals(3, receivedList.size());
        assertEquals("Message 1", receivedList.get(0).content());
        assertEquals("Message 2", receivedList.get(1).content());
        assertEquals("Message 3", receivedList.get(2).content());
    }

    @Test
    @DisplayName("Test 3 — Bidirectional: Peer A -> Peer B ('Hello B') and Peer B -> Peer A ('Hello A')")
    void test3_BidirectionalMessages() throws Exception {
        CountDownLatch aLatch = new CountDownLatch(1);
        CountDownLatch bLatch = new CountDownLatch(1);

        AtomicReference<ChatMessage> msgReceivedByA = new AtomicReference<>();
        AtomicReference<ChatMessage> msgReceivedByB = new AtomicReference<>();

        serviceA.addListener(new ChatListener() {
            @Override
            public void onMessageReceived(ChatMessage message) {
                msgReceivedByA.set(message);
                aLatch.countDown();
            }
        });

        serviceB.addListener(new ChatListener() {
            @Override
            public void onMessageReceived(ChatMessage message) {
                msgReceivedByB.set(message);
                bLatch.countDown();
            }
        });

        // Peer A -> Peer B
        serviceA.sendMessage("Hello B");
        // Peer B -> Peer A
        serviceB.sendMessage("Hello A");

        assertTrue(bLatch.await(5, TimeUnit.SECONDS), "Peer B should receive message from Peer A");
        assertTrue(aLatch.await(5, TimeUnit.SECONDS), "Peer A should receive message from Peer B");

        assertEquals("Hello B", msgReceivedByB.get().content());
        assertEquals("Peer A", msgReceivedByB.get().sender());

        assertEquals("Hello A", msgReceivedByA.get().content());
        assertEquals("Peer B", msgReceivedByA.get().sender());
    }

    @Test
    @DisplayName("Test 4 — Async receive: Peer A receives from Peer B without Peer A sending first")
    void test4_AsyncReceiveWithoutSendingFirst() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ChatMessage> received = new AtomicReference<>();

        serviceA.addListener(new ChatListener() {
            @Override
            public void onMessageReceived(ChatMessage message) {
                received.set(message);
                latch.countDown();
            }
        });

        // Peer A does NOT send any request; Peer B asynchronously initiates chat
        serviceB.sendMessage("Unsolicited message from B to A");

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Peer A should asynchronously receive message from Peer B");
        assertNotNull(received.get());
        assertEquals("Unsolicited message from B to A", received.get().content());
        assertEquals("Peer B", received.get().sender());
    }

    @Test
    @DisplayName("Test 5 — Empty message: Reject or ignore empty/blank user messages")
    void test5_EmptyMessageRejection() {
        assertThrows(IllegalArgumentException.class, () -> serviceA.sendMessage(""));
        assertThrows(IllegalArgumentException.class, () -> serviceA.sendMessage("   "));
        assertThrows(IllegalArgumentException.class, () -> serviceA.sendMessage(null));
    }

    @Test
    @DisplayName("Test 6 — Disconnect: Close Peer B, Peer A must detect disconnect")
    void test6_DisconnectDetection() throws Exception {
        CountDownLatch disconnectLatch = new CountDownLatch(1);

        serviceA.addListener(new ChatListener() {
            @Override
            public void onChatDisconnected() {
                disconnectLatch.countDown();
            }
        });

        assertTrue(serviceA.isConnected());
        assertTrue(serviceB.isConnected());

        // Close Peer B
        serviceB.close();

        assertTrue(disconnectLatch.await(5, TimeUnit.SECONDS), "Peer A must detect peer disconnect");
        assertFalse(serviceA.isConnected(), "Peer A state should transition to disconnected");
        assertEquals(ConnectionState.DISCONNECTED, clientConn.getState());
    }

    @Test
    @DisplayName("Test 7 — PING/PONG: PING requestId=N results in PONG requestId=N and latency measured")
    void test7_PingPongLatency() throws Exception {
        CompletableFuture<Double> pingFuture = serviceA.ping();
        Double latency = pingFuture.get(5, TimeUnit.SECONDS);

        assertNotNull(latency, "Latency must not be null");
        assertTrue(latency >= 0.0, "Latency must be non-negative: " + latency);
    }

    @Test
    @DisplayName("Test 8 — Request IDs: Send multiple messages and verify all IDs are unique")
    void test8_UniqueRequestIds() throws Exception {
        Set<Long> requestIds = new HashSet<>();
        int count = 10;

        for (int i = 0; i < count; i++) {
            ChatMessage msg = serviceA.sendMessage("Message " + i);
            requestIds.add(msg.requestId());
        }

        assertEquals(count, requestIds.size(), "Each sent message must have a unique request ID");
    }
}
