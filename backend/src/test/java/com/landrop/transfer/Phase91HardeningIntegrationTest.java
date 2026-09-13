package com.landrop.transfer;

import com.landrop.chat.ChatListener;
import com.landrop.chat.ChatMessage;
import com.landrop.chat.ChatService;
import com.landrop.peer.ConnectionPurpose;
import com.landrop.peer.PeerConnection;
import com.landrop.peer.PeerListener;
import com.landrop.protocol.MessageType;
import com.landrop.protocol.Packet;
import com.landrop.transfer.messages.FileQueryMessage;
import com.landrop.transfer.messages.FileQueryResponseMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class Phase91HardeningIntegrationTest {

    private final List<PeerListener> listeners = new ArrayList<>();
    private final List<PeerConnection> connections = new ArrayList<>();
    private final List<FileTransferService> transferServices = new ArrayList<>();
    private final List<ChatService> chatServices = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (PeerConnection c : connections) {
            try { c.close(); } catch (Exception ignored) {}
        }
        for (FileTransferService s : transferServices) {
            try { s.close(); } catch (Exception ignored) {}
        }
        for (ChatService cs : chatServices) {
            try { cs.close(); } catch (Exception ignored) {}
        }
        for (PeerListener l : listeners) {
            try { l.close(); } catch (Exception ignored) {}
        }
    }

    private record ConnectionPair(PeerConnection client, PeerConnection server) {}

    private ConnectionPair createConnectedPair(String clientName, String serverName, ConnectionPurpose purpose) throws Exception {
        PeerListener listener = new PeerListener(0);
        listeners.add(listener);

        CountDownLatch acceptLatch = new CountDownLatch(1);
        AtomicReference<PeerConnection> serverConnRef = new AtomicReference<>();

        Thread serverThread = new Thread(() -> {
            try {
                PeerConnection sc = listener.acceptConnection();
                serverConnRef.set(sc);
                sc.setLocalPeerName(serverName);
                sc.setPurpose(purpose);
                sc.start();
                acceptLatch.countDown();
            } catch (Exception ignored) {
            }
        });
        serverThread.start();

        PeerConnection clientConn = PeerConnection.connectTo("localhost", listener.getPort());
        clientConn.setLocalPeerName(clientName);
        clientConn.setPurpose(purpose);
        clientConn.start();

        assertTrue(acceptLatch.await(5, TimeUnit.SECONDS));
        PeerConnection serverConn = serverConnRef.get();
        assertNotNull(serverConn);

        connections.add(clientConn);
        connections.add(serverConn);

        clientConn.initiateHandshake(clientName, purpose);
        serverConn.initiateHandshake(serverName, purpose);
        assertTrue(clientConn.awaitReady(5, TimeUnit.SECONDS));
        assertTrue(serverConn.awaitReady(5, TimeUnit.SECONDS));

        return new ConnectionPair(clientConn, serverConn);
    }

    @Test
    @DisplayName("Issue 1: FileTransferService initializes cleanly at startup without active connection")
    void testStartupInitializationWithoutActiveConnection(@TempDir Path tempDir) {
        Path downloadDir = tempDir.resolve("downloads");
        FileTransferService service = new FileTransferService(null, "peer-d", "Peer D", downloadDir, 32 * 1024);
        transferServices.add(service);

        assertNull(service.getConnection(), "Connection should initially be null");
        assertTrue(service.getTransferConnections().isEmpty(), "Transfer connections should initially be empty");

        // Can scan directory without throwing
        int scanned = service.scanSharedDirectory();
        assertEquals(0, scanned);

        // Can create MultiPeerDownloadCoordinator without throwing
        MultiPeerDownloadCoordinator coordinator = service.createMultiPeerDownload(
                "test.bin",
                1024,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
        assertNotNull(coordinator);
        assertEquals(MultiPeerDownloadStatus.PENDING, coordinator.getDownload().getStatus());
    }

    @Test
    @DisplayName("Issue 2: Startup scanned file is immediately discoverable via FILE_QUERY")
    void testFileQueryFindsStartupIndexedFile(@TempDir Path tempDir) throws Exception {
        Path sharedDir = tempDir.resolve("shared");
        Files.createDirectories(sharedDir);

        byte[] payload = "Startup indexed data for testing discovery".getBytes(StandardCharsets.UTF_8);
        Path testFile = sharedDir.resolve("shared_doc.txt");
        Files.write(testFile, payload);
        String expectedHash = FileTransferUtils.calculateSha256(testFile);

        // Peer A initializes and scans folder at startup
        FileTransferService serviceA = new FileTransferService(null, "peer-a", "Peer A", sharedDir, 16 * 1024);
        transferServices.add(serviceA);
        int indexed = serviceA.scanSharedDirectory();
        assertEquals(1, indexed);

        // Receiver connects with FILE_TRANSFER purpose
        ConnectionPair pair = createConnectedPair("Peer D", "Peer A", ConnectionPurpose.FILE_TRANSFER);
        serviceA.registerTransferConnection(pair.server());

        // Peer D sends FILE_QUERY
        FileQueryMessage query = new FileQueryMessage(
                "query-1",
                "shared_doc.txt",
                payload.length,
                expectedHash,
                "Peer D",
                "Peer D"
        );

        CompletableFuture<FileQueryResponseMessage> queryFuture = new CompletableFuture<>();
        pair.client().registerHandler(MessageType.FILE_QUERY_RESPONSE, packet -> {
            try {
                queryFuture.complete(FileQueryResponseMessage.fromBytes(packet.getPayload()));
            } catch (Exception e) {
                queryFuture.completeExceptionally(e);
            }
        });

        long reqId = pair.client().getRequestIdGenerator().nextId();
        pair.client().send(Packet.of(MessageType.FILE_QUERY, reqId, query.toBytes()));

        FileQueryResponseMessage resp = queryFuture.get(5, TimeUnit.SECONDS);
        assertTrue(resp.isAvailable(), "Indexed file should be reported as available");
        assertEquals(expectedHash, resp.getFileSha256());
        assertEquals(payload.length, resp.getFileSize());
        assertEquals("peer-a", resp.getResponderPeerId());
    }

    @Test
    @DisplayName("Issue 3: ConnectionPurpose correctly negotiated (CHAT vs FILE_TRANSFER)")
    void testConnectionPurposeNegotiation() throws Exception {
        ConnectionPair chatPair = createConnectedPair("Alice", "Bob", ConnectionPurpose.CHAT);
        assertEquals(ConnectionPurpose.CHAT, chatPair.client().getPurpose());
        assertEquals(ConnectionPurpose.CHAT, chatPair.client().getRemotePurpose());
        assertEquals(ConnectionPurpose.CHAT, chatPair.server().getPurpose());
        assertEquals(ConnectionPurpose.CHAT, chatPair.server().getRemotePurpose());

        ConnectionPair transferPair = createConnectedPair("Alice", "Bob", ConnectionPurpose.FILE_TRANSFER);
        assertEquals(ConnectionPurpose.FILE_TRANSFER, transferPair.client().getPurpose());
        assertEquals(ConnectionPurpose.FILE_TRANSFER, transferPair.client().getRemotePurpose());
        assertEquals(ConnectionPurpose.FILE_TRANSFER, transferPair.server().getPurpose());
        assertEquals(ConnectionPurpose.FILE_TRANSFER, transferPair.server().getRemotePurpose());
    }

    @Test
    @DisplayName("Issue 3: Transfer connection does not replace or close active chat connection")
    void testTransferConnectionDoesNotReplaceChatConnection(@TempDir Path tempDir) throws Exception {
        ConnectionPair chatPair = createConnectedPair("Alice", "Bob", ConnectionPurpose.CHAT);
        FileTransferService serviceBob = new FileTransferService(null, "bob", "Bob", tempDir);
        transferServices.add(serviceBob);

        // Bob attaches primary chat connection
        serviceBob.attachConnection(chatPair.server());
        assertSame(chatPair.server(), serviceBob.getConnection(), "Primary connection should be chat connection");

        // Alice opens a second, dedicated FILE_TRANSFER connection to Bob
        ConnectionPair transferPair = createConnectedPair("Alice", "Bob", ConnectionPurpose.FILE_TRANSFER);
        serviceBob.registerTransferConnection(transferPair.server());

        // Verify chat connection remains active and primary
        assertSame(chatPair.server(), serviceBob.getConnection(), "Primary chat connection must remain unchanged");
        assertTrue(chatPair.client().isConnected(), "Alice chat connection must remain open");
        assertTrue(chatPair.server().isConnected(), "Bob chat connection must remain open");
        assertTrue(serviceBob.getTransferConnections().contains(transferPair.server()), "Transfer connection registered");
    }

    @Test
    @DisplayName("Issue 3: Chat remains usable while file transfer connections are active")
    void testChatRemainsUsableDuringTransfer(@TempDir Path tempDir) throws Exception {
        ConnectionPair chatPair = createConnectedPair("Alice", "Bob", ConnectionPurpose.CHAT);
        ChatService chatAlice = new ChatService(chatPair.client(), "Alice");
        ChatService chatBob = new ChatService(chatPair.server(), "Bob");
        chatServices.add(chatAlice);
        chatServices.add(chatBob);

        BlockingQueue<ChatMessage> receivedByBob = new LinkedBlockingQueue<>();
        chatBob.addListener(new ChatListener() {
            @Override
            public void onMessageReceived(ChatMessage message) {
                receivedByBob.offer(message);
            }
        });

        // Dedicated transfer connection
        ConnectionPair transferPair = createConnectedPair("Alice", "Bob", ConnectionPurpose.FILE_TRANSFER);
        FileTransferService transferBob = new FileTransferService(null, "bob", "Bob", tempDir);
        transferServices.add(transferBob);
        transferBob.registerTransferConnection(transferPair.server());

        // Send chat message while transfer connection is active
        chatAlice.sendMessage("Hello Bob, transfer is starting in background!");
        ChatMessage received = receivedByBob.poll(5, TimeUnit.SECONDS);
        assertNotNull(received);
        assertEquals("Hello Bob, transfer is starting in background!", received.getContent());
        assertEquals("Alice", received.getSender());

        // Send reply back
        BlockingQueue<ChatMessage> receivedByAlice = new LinkedBlockingQueue<>();
        chatAlice.addListener(new ChatListener() {
            @Override
            public void onMessageReceived(ChatMessage message) {
                receivedByAlice.offer(message);
            }
        });
        chatBob.sendMessage("Got it Alice, chat works fine!");
        ChatMessage reply = receivedByAlice.poll(5, TimeUnit.SECONDS);
        assertNotNull(reply);
        assertEquals("Got it Alice, chat works fine!", reply.getContent());
    }

    @Test
    @DisplayName("Issue 3: Disconnecting transfer connection does not close chat connection")
    void testTransferDisconnectDoesNotCloseChat(@TempDir Path tempDir) throws Exception {
        ConnectionPair chatPair = createConnectedPair("Alice", "Bob", ConnectionPurpose.CHAT);
        ChatService chatAlice = new ChatService(chatPair.client(), "Alice");
        ChatService chatBob = new ChatService(chatPair.server(), "Bob");
        chatServices.add(chatAlice);
        chatServices.add(chatBob);

        ConnectionPair transferPair = createConnectedPair("Alice", "Bob", ConnectionPurpose.FILE_TRANSFER);
        FileTransferService transferBob = new FileTransferService(null, "bob", "Bob", tempDir);
        transferServices.add(transferBob);
        transferBob.registerTransferConnection(transferPair.server());

        // Abruptly close transfer connection
        transferPair.client().close();
        Thread.sleep(100); // Allow disconnect handler to run

        assertFalse(transferPair.client().isConnected());
        assertFalse(serviceContains(transferBob, transferPair.server()));

        // Chat must still be fully connected and operational
        assertTrue(chatPair.client().isConnected(), "Alice chat must remain connected");
        assertTrue(chatPair.server().isConnected(), "Bob chat must remain connected");

        CountDownLatch chatLatch = new CountDownLatch(1);
        chatBob.addListener(new ChatListener() {
            @Override
            public void onMessageReceived(ChatMessage message) {
                if ("Chat survives transfer close!".equals(message.getContent())) {
                    chatLatch.countDown();
                }
            }
        });
        chatAlice.sendMessage("Chat survives transfer close!");
        assertTrue(chatLatch.await(5, TimeUnit.SECONDS), "Chat message should be delivered after transfer closed");
    }

    private boolean serviceContains(FileTransferService service, PeerConnection conn) {
        return service.getTransferConnections().contains(conn);
    }

    @Test
    @DisplayName("Localhost Collision: Destination file name avoids overwriting existing file")
    void testLocalhostCollisionDestinationResolution(@TempDir Path tempDir) throws Exception {
        Path existing = tempDir.resolve("demo.mp4");
        Files.writeString(existing, "original content");

        // Service resolving destination should produce "demo (1).mp4"
        Path resolved = FileTransferUtils.resolveUniqueDestination(tempDir, "demo.mp4");
        assertEquals("demo (1).mp4", resolved.getFileName().toString());

        // MultiPeerDownload creates unique destination path without modifying source
        FileTransferService service = new FileTransferService(null, "receiver", "Receiver", tempDir, 16 * 1024);
        transferServices.add(service);

        MultiPeerDownloadCoordinator coordinator = service.createMultiPeerDownload(
                "demo.mp4",
                100,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );

        assertEquals("demo (1).mp4", coordinator.getDownload().getDestinationPath().getFileName().toString());
        assertEquals("demo (1).mp4.landrop.part", coordinator.getDownload().getPartialFilePath().getFileName().toString());
    }

    @Test
    @DisplayName("End-to-End: Multi-peer download with startup-indexed file, chat, and failover")
    void testEndToEndMultiPeerDownloadWithChatAndFailover(@TempDir Path tempDir) throws Exception {
        int chunkSize = 16 * 1024;
        int totalChunks = 8;
        int fileSize = totalChunks * chunkSize;
        byte[] originalBytes = new byte[fileSize];
        new Random(42).nextBytes(originalBytes);

        // 1. Peer A and Peer B directories with same shared file
        Path dirA = tempDir.resolve("peerA_files");
        Path dirB = tempDir.resolve("peerB_files");
        Files.createDirectories(dirA);
        Files.createDirectories(dirB);

        Path fileA = dirA.resolve("shared_video.mkv");
        Path fileB = dirB.resolve("shared_video.mkv");
        Files.write(fileA, originalBytes);
        Files.write(fileB, originalBytes);
        String expectedHash = FileTransferUtils.calculateSha256(fileA);

        FileTransferService serviceA = new FileTransferService(null, "peer-a", "Peer A", dirA, chunkSize);
        FileTransferService serviceB = new FileTransferService(null, "peer-b", "Peer B", dirB, chunkSize);
        serviceA.setAutoReconnectEnabled(false);
        serviceB.setAutoReconnectEnabled(false);
        transferServices.add(serviceA);
        transferServices.add(serviceB);

        // Startup scan for both
        assertEquals(1, serviceA.scanSharedDirectory());
        assertEquals(1, serviceB.scanSharedDirectory());

        // 2. Receiver (Peer D)
        Path receiverDir = tempDir.resolve("receiver_downloads");
        FileTransferService receiverService = new FileTransferService(null, "peer-d", "Peer D", receiverDir, chunkSize);
        transferServices.add(receiverService);

        // 3. Setup CHAT connection between Peer D and Peer A
        ConnectionPair chatPair = createConnectedPair("Peer D", "Peer A", ConnectionPurpose.CHAT);
        ChatService chatD = new ChatService(chatPair.client(), "Peer D");
        ChatService chatA = new ChatService(chatPair.server(), "Peer A");
        chatServices.add(chatD);
        chatServices.add(chatA);
        serviceA.attachConnection(chatPair.server());

        // 4. Setup dedicated FILE_TRANSFER connections to Peer A and Peer B
        ConnectionPair transferPairA = createConnectedPair("Peer D", "Peer A", ConnectionPurpose.FILE_TRANSFER);
        ConnectionPair transferPairB = createConnectedPair("Peer D", "Peer B", ConnectionPurpose.FILE_TRANSFER);
        serviceA.registerTransferConnection(transferPairA.server());
        serviceB.registerTransferConnection(transferPairB.server());

        // 5. Query file availability
        MultiPeerDownloadCoordinator coordinator =
                receiverService.createMultiPeerDownload("shared_video.mkv", fileSize, expectedHash);

        FileQueryResponseMessage respA = coordinator.queryFileAvailability(transferPairA.client(), 3000).get(5, TimeUnit.SECONDS);
        FileQueryResponseMessage respB = coordinator.queryFileAvailability(transferPairB.client(), 3000).get(5, TimeUnit.SECONDS);
        assertTrue(respA.isAvailable());
        assertTrue(respB.isAvailable());

        PeerDownloadState stateA = new PeerDownloadState("peer-a", "Peer A", null);
        PeerDownloadState stateB = new PeerDownloadState("peer-b", "Peer B", null);

        coordinator.addSourcePeer(stateA, transferPairA.client());
        coordinator.addSourcePeer(stateB, transferPairB.client());

        // 6. Monitor progress and disconnect Peer B after at least 1 chunk
        CountDownLatch bCompletedChunkLatch = new CountDownLatch(1);
        coordinator.getDownload().addListener(new MultiPeerDownloadListener() {
            @Override
            public void onChunkCompleted(MultiPeerDownload download, int chunkIndex, String peerId, int completed, int total) {
                if ("peer-b".equals(peerId)) {
                    bCompletedChunkLatch.countDown();
                }
            }
        });

        // Track chat receipt during download
        BlockingQueue<ChatMessage> chatQueue = new LinkedBlockingQueue<>();
        chatA.addListener(new ChatListener() {
            @Override
            public void onMessageReceived(ChatMessage message) {
                chatQueue.offer(message);
            }
        });

        coordinator.start();

        // Send chat message while downloading
        chatD.sendMessage("Chat message mid-transfer!");
        ChatMessage receivedChat = chatQueue.poll(5, TimeUnit.SECONDS);
        assertNotNull(receivedChat);
        assertEquals("Chat message mid-transfer!", receivedChat.getContent());

        // Wait for Peer B to complete a chunk then kill Peer B
        if (bCompletedChunkLatch.await(2, TimeUnit.SECONDS)) {
            transferPairB.client().close();
        } else {
            transferPairB.client().close();
        }

        // 7. Await completion
        MultiPeerDownload result = coordinator.getCompletionFuture().get(10, TimeUnit.SECONDS);
        assertEquals(MultiPeerDownloadStatus.COMPLETED, result.getStatus());
        assertTrue(result.isHashVerified());

        Path finalFile = coordinator.getDownload().getDestinationPath();
        assertTrue(Files.exists(finalFile));
        assertArrayEquals(originalBytes, Files.readAllBytes(finalFile));

        // 8. Verify chat connection survived download and peer B disconnect
        assertTrue(chatPair.client().isConnected());
        assertTrue(chatPair.server().isConnected());
    }
}
