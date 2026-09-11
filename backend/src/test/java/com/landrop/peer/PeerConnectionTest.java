package com.landrop.peer;

import com.landrop.protocol.MessageType;
import com.landrop.protocol.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerConnectionTest {

    @Test
    @DisplayName("Verify connection lifecycle and state transitions")
    void testConnectionStates() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();

            CountDownLatch acceptedLatch = new CountDownLatch(1);
            AtomicReference<PeerConnection> serverConnRef = new AtomicReference<>();

            Thread serverThread = new Thread(() -> {
                try {
                    PeerConnection sc = new PeerConnection(serverSocket.accept());
                    serverConnRef.set(sc);
                    sc.setLocalPeerName("Peer B");
                    sc.start();
                    acceptedLatch.countDown();
                } catch (IOException ignored) {
                }
            });
            serverThread.start();

            PeerConnection clientConn = PeerConnection.connectTo("localhost", port);
            clientConn.setLocalPeerName("Peer A");
            assertEquals(ConnectionState.CONNECTED, clientConn.getState());
            clientConn.start();

            assertTrue(acceptedLatch.await(5, TimeUnit.SECONDS));
            PeerConnection serverConn = serverConnRef.get();
            assertNotNull(serverConn);
            assertEquals(ConnectionState.CONNECTED, serverConn.getState());

            // Perform HELLO handshake
            clientConn.initiateHandshake("Peer A");

            assertTrue(clientConn.awaitReady(5, TimeUnit.SECONDS));
            assertTrue(serverConn.awaitReady(5, TimeUnit.SECONDS));

            assertEquals(ConnectionState.READY, clientConn.getState());
            assertEquals(ConnectionState.READY, serverConn.getState());
            assertEquals("Peer B", clientConn.getRemotePeerName());
            assertEquals("Peer A", serverConn.getRemotePeerName());

            // Disconnect client
            CountDownLatch disconnectLatch = new CountDownLatch(1);
            serverConn.addListener(new ConnectionListener() {
                @Override
                public void onDisconnected() {
                    disconnectLatch.countDown();
                }
            });

            clientConn.close();
            assertEquals(ConnectionState.DISCONNECTED, clientConn.getState());
            assertFalse(clientConn.isConnected());

            assertTrue(disconnectLatch.await(5, TimeUnit.SECONDS), "Server connection should detect disconnect");
            assertEquals(ConnectionState.DISCONNECTED, serverConn.getState());

            serverConn.close();
        }
    }

    @Test
    @DisplayName("Verify thread-safe sending without stream corruption under concurrent writes")
    void testThreadSafeSending() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            int messageCount = 100;
            CountDownLatch receiveLatch = new CountDownLatch(messageCount);

            Thread serverThread = new Thread(() -> {
                try {
                    PeerConnection sc = new PeerConnection(serverSocket.accept());
                    sc.registerHandler(MessageType.CHAT_MESSAGE, packet -> receiveLatch.countDown());
                    sc.start();
                } catch (Exception ignored) {
                }
            });
            serverThread.start();

            PeerConnection clientConn = PeerConnection.connectTo("localhost", port);
            clientConn.start();

            int threadCount = 10;
            int perThread = messageCount / threadCount;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch sendLatch = new CountDownLatch(threadCount);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < perThread; i++) {
                            long id = threadId * 1000L + i;
                            Packet p = Packet.text(MessageType.CHAT_MESSAGE, id, "Thread " + threadId + " msg " + i);
                            clientConn.send(p);
                        }
                    } catch (IOException ignored) {
                    } finally {
                        sendLatch.countDown();
                    }
                });
            }

            assertTrue(sendLatch.await(5, TimeUnit.SECONDS));
            executor.shutdown();

            assertTrue(receiveLatch.await(5, TimeUnit.SECONDS), "All 100 packets must arrive without stream corruption");

            clientConn.close();
        }
    }

    @Test
    @DisplayName("Verify PING sends request and receives PONG with measured latency")
    void testPingPongLatency() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();

            Thread serverThread = new Thread(() -> {
                try {
                    PeerConnection sc = new PeerConnection(serverSocket.accept());
                    sc.start();
                } catch (Exception ignored) {
                }
            });
            serverThread.start();

            PeerConnection clientConn = PeerConnection.connectTo("localhost", port);
            clientConn.start();

            CompletableFuture<Double> latencyFuture = clientConn.ping();
            Double latencyMs = latencyFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(latencyMs);
            assertTrue(latencyMs >= 0.0, "Latency must be non-negative");

            clientConn.close();
        }
    }
}
