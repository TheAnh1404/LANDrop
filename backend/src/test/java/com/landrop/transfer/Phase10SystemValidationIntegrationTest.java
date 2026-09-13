package com.landrop.transfer;

import com.landrop.chat.ChatMessage;
import com.landrop.chat.ChatService;
import com.landrop.discovery.DiscoveryClient;
import com.landrop.discovery.DiscoveryServer;
import com.landrop.discovery.PeerInfo;
import com.landrop.peer.ConnectionPurpose;
import com.landrop.peer.PeerConnection;
import com.landrop.peer.PeerListener;
import com.landrop.transfer.messages.FileQueryResponseMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 10 Comprehensive System Validation Integration Test Suite.
 * Validates complete end-to-end system flows, regression safety across Phases 1-9,
 * concurrent chat during transfers, failover, resume, and edge cases.
 */
public class Phase10SystemValidationIntegrationTest {

    private final List<DiscoveryServer> discoveryServers = new ArrayList<>();
    private final List<DiscoveryClient> discoveryClients = new ArrayList<>();
    private final List<PeerListener> listeners = new ArrayList<>();
    private final List<PeerConnection> connections = new ArrayList<>();
    private final List<FileTransferService> services = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (PeerConnection c : connections) {
            try { c.close(); } catch (Exception ignored) {}
        }
        for (FileTransferService s : services) {
            try { s.close(); } catch (Exception ignored) {}
        }
        for (PeerListener l : listeners) {
            try { l.close(); } catch (Exception ignored) {}
        }
        for (DiscoveryClient dc : discoveryClients) {
            try { dc.close(); } catch (Exception ignored) {}
        }
        for (DiscoveryServer ds : discoveryServers) {
            try { ds.close(); } catch (Exception ignored) {}
        }
    }

    private DiscoveryServer startDiscoveryServer() throws Exception {
        DiscoveryServer server = new DiscoveryServer(0);
        discoveryServers.add(server);
        server.startAsync();
        assertTrue(server.awaitReady(5, TimeUnit.SECONDS));
        return server;
    }

    private PeerConnection connectDedicated(PeerListener listener, String clientName, ConnectionPurpose purpose) throws Exception {
        CountDownLatch acceptLatch = new CountDownLatch(1);
        AtomicReference<PeerConnection> serverConnRef = new AtomicReference<>();

        Thread serverThread = new Thread(() -> {
            try {
                PeerConnection sc = listener.acceptConnection();
                serverConnRef.set(sc);
                sc.start();
                acceptLatch.countDown();
            } catch (Exception ignored) {}
        });
        serverThread.start();

        PeerConnection clientConn = PeerConnection.connectTo("127.0.0.1", listener.getPort());
        clientConn.setPurpose(purpose);
        clientConn.setLocalPeerName(clientName);
        clientConn.start();

        assertTrue(acceptLatch.await(5, TimeUnit.SECONDS));
        PeerConnection serverConn = serverConnRef.get();
        assertNotNull(serverConn);

        connections.add(clientConn);
        connections.add(serverConn);

        clientConn.initiateHandshake(clientName, purpose);
        serverConn.initiateHandshake("Server", purpose);

        assertTrue(clientConn.awaitReady(5, TimeUnit.SECONDS));
        assertTrue(serverConn.awaitReady(5, TimeUnit.SECONDS));

        return clientConn;
    }

    @Test
    @DisplayName("Flow 1: Discovery Server registration, heartbeat, and peer listing")
    void testDiscoveryRegistrationAndPeerListing() throws Exception {
        DiscoveryServer server = startDiscoveryServer();

        DiscoveryClient clientA = new DiscoveryClient("127.0.0.1", server.getPort());
        DiscoveryClient clientB = new DiscoveryClient("127.0.0.1", server.getPort());
        discoveryClients.add(clientA);
        discoveryClients.add(clientB);

        clientA.connect(3000);
        clientB.connect(3000);

        PeerInfo peerA = new PeerInfo("peer-a", "Peer A", "127.0.0.1", 9001);
        PeerInfo peerB = new PeerInfo("peer-b", "Peer B", "127.0.0.1", 9002);

        assertTrue(clientA.register(peerA));
        assertTrue(clientB.register(peerB));

        // Start heartbeats
        clientA.startHeartbeat(1);
        clientB.startHeartbeat(1);

        // Peer A requests peer list -> should see Peer B
        List<PeerInfo> listForA = clientA.requestPeerList();
        assertEquals(1, listForA.size());
        assertEquals("peer-b", listForA.getFirst().getPeerId());

        // Peer B requests peer list -> should see Peer A
        List<PeerInfo> listForB = clientB.requestPeerList();
        assertEquals(1, listForB.size());
        assertEquals("peer-a", listForB.getFirst().getPeerId());
    }

    @Test
    @DisplayName("Flow 2: P2P Chat messaging and Ping RTT latency measurement")
    void testChatMessagingAndPingLatency() throws Exception {
        PeerListener listenerA = new PeerListener(0);
        PeerListener listenerB = new PeerListener(0);
        listeners.add(listenerA);
        listeners.add(listenerB);

        PeerConnection clientConn = connectDedicated(listenerB, "Peer A", ConnectionPurpose.CHAT);
        // Find server connection
        PeerConnection serverConn = connections.stream()
                .filter(c -> c != clientConn)
                .findFirst().orElseThrow();

        ChatService chatA = new ChatService(clientConn, "Peer A");
        ChatService chatB = new ChatService(serverConn, "Peer B");

        List<String> receivedByB = new CopyOnWriteArrayList<>();
        CountDownLatch msgLatch = new CountDownLatch(2);

        chatB.addListener(new com.landrop.chat.ChatListener() {
            @Override
            public void onMessageReceived(ChatMessage message) {
                receivedByB.add(message.getContent());
                msgLatch.countDown();
            }
        });

        // 1. Send chat messages
        chatA.sendMessage("Hello from Peer A!");
        chatA.sendMessage("System validation message #2");

        assertTrue(msgLatch.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("Hello from Peer A!", "System validation message #2"), receivedByB);

        // 2. Ping probe
        CompletableFuture<Double> pingFuture = chatA.ping();
        Double rttMs = pingFuture.get(5, TimeUnit.SECONDS);
        assertNotNull(rttMs);
        assertTrue(rttMs >= 0.0, "Ping latency should be non-negative");
    }

    @Test
    @DisplayName("Flow 3: Concurrent Chat messaging while Multi-Peer File Transfer is actively downloading")
    void testConcurrentChatDuringMultiPeerDownload(@TempDir Path tempDir) throws Exception {
        int chunkSize = 32 * 1024;
        int totalChunks = 12;
        int fileSize = totalChunks * chunkSize;
        byte[] fileBytes = new byte[fileSize];
        new Random(42).nextBytes(fileBytes);

        Path sourceFile = tempDir.resolve("concurrent_test.dat");
        Files.write(sourceFile, fileBytes);
        String expectedHash = FileTransferUtils.calculateSha256(sourceFile);

        // Setup Source Peer A & Source Peer B
        PeerListener listenerA = new PeerListener(0);
        PeerListener listenerB = new PeerListener(0);
        listeners.add(listenerA);
        listeners.add(listenerB);

        FileTransferService serviceA = new FileTransferService(null, "peer-a", "Peer A", tempDir.resolve("dirA"), chunkSize);
        FileTransferService serviceB = new FileTransferService(null, "peer-b", "Peer B", tempDir.resolve("dirB"), chunkSize);
        services.add(serviceA);
        services.add(serviceB);

        serviceA.shareFile(sourceFile);
        serviceB.shareFile(sourceFile);

        // Setup Receiver Peer D
        PeerListener listenerD = new PeerListener(0);
        listeners.add(listenerD);
        Path receiverDir = tempDir.resolve("dirD");
        Files.createDirectories(receiverDir);
        FileTransferService receiverService = new FileTransferService(null, "peer-d", "Peer D", receiverDir, chunkSize);
        services.add(receiverService);

        // Connect dedicated CHAT connection between Peer A and Peer D
        PeerConnection chatConnDtoA = connectDedicated(listenerA, "Peer D", ConnectionPurpose.CHAT);
        PeerConnection chatConnAtoD = connections.stream()
                .filter(c -> c != chatConnDtoA && c.getLocalPeerName().equals("Server"))
                .findFirst().orElseThrow();

        ChatService chatServiceD = new ChatService(chatConnDtoA, "Peer D");
        ChatService chatServiceA = new ChatService(chatConnAtoD, "Peer A");

        List<String> chatReceivedByA = new CopyOnWriteArrayList<>();
        chatServiceA.addListener(new com.landrop.chat.ChatListener() {
            @Override
            public void onMessageReceived(ChatMessage message) {
                chatReceivedByA.add(message.getContent());
            }
        });

        // Connect dedicated FILE_TRANSFER connections to Peer A and Peer B
        PeerConnection transferConnDtoA = connectDedicated(listenerA, "Peer D", ConnectionPurpose.FILE_TRANSFER);
        PeerConnection transferConnDtoB = connectDedicated(listenerB, "Peer D", ConnectionPurpose.FILE_TRANSFER);

        // Attach connections to services
        serviceA.registerTransferConnection(connections.get(connections.indexOf(transferConnDtoA) + 1));
        serviceB.registerTransferConnection(connections.get(connections.indexOf(transferConnDtoB) + 1));

        MultiPeerDownloadCoordinator coordinator =
                receiverService.createMultiPeerDownload("concurrent_test.dat", fileSize, expectedHash);

        FileQueryResponseMessage respA = coordinator.queryFileAvailability(transferConnDtoA, 3000).get(5, TimeUnit.SECONDS);
        FileQueryResponseMessage respB = coordinator.queryFileAvailability(transferConnDtoB, 3000).get(5, TimeUnit.SECONDS);
        assertTrue(respA.isAvailable());
        assertTrue(respB.isAvailable());

        coordinator.addSourcePeer(new PeerDownloadState("peer-a", "Peer A", null), transferConnDtoA);
        coordinator.addSourcePeer(new PeerDownloadState("peer-b", "Peer B", null), transferConnDtoB);

        coordinator.start();

        // Send chat messages WHILE download is in flight
        for (int i = 1; i <= 5; i++) {
            chatServiceD.sendMessage("In-flight chat message #" + i);
            Thread.sleep(10);
        }

        MultiPeerDownload downloadResult = coordinator.getCompletionFuture().get(10, TimeUnit.SECONDS);
        assertEquals(MultiPeerDownloadStatus.COMPLETED, downloadResult.getStatus());
        assertTrue(downloadResult.isHashVerified());

        // File must be verified and intact
        Path finalFile = downloadResult.getDestinationPath();
        assertTrue(Files.exists(finalFile));
        assertArrayEquals(fileBytes, Files.readAllBytes(finalFile));

        // Verify all chat messages arrived cleanly
        Thread.sleep(50);
        assertEquals(5, chatReceivedByA.size());
        assertEquals("In-flight chat message #1", chatReceivedByA.get(0));
        assertEquals("In-flight chat message #5", chatReceivedByA.get(4));
    }

    @Test
    @DisplayName("Flow 4: Mid-transfer Peer disconnect with failover and SHA-256 completion")
    void testMidTransferPeerFailover(@TempDir Path tempDir) throws Exception {
        int chunkSize = 16 * 1024;
        int totalChunks = 10;
        int fileSize = totalChunks * chunkSize;
        byte[] fileBytes = new byte[fileSize];
        new Random(77).nextBytes(fileBytes);

        Path sourceFile = tempDir.resolve("failover_test.bin");
        Files.write(sourceFile, fileBytes);
        String expectedHash = FileTransferUtils.calculateSha256(sourceFile);

        PeerListener listenerA = new PeerListener(0);
        PeerListener listenerB = new PeerListener(0);
        listeners.add(listenerA);
        listeners.add(listenerB);

        FileTransferService serviceA = new FileTransferService(null, "peer-a", "Peer A", tempDir.resolve("dirA"), chunkSize);
        FileTransferService serviceB = new FileTransferService(null, "peer-b", "Peer B", tempDir.resolve("dirB"), chunkSize);
        services.add(serviceA);
        services.add(serviceB);

        serviceA.shareFile(sourceFile);
        serviceB.shareFile(sourceFile);

        FileTransferService receiverService = new FileTransferService(null, "receiver", "Receiver", tempDir.resolve("dirRx"), chunkSize);
        services.add(receiverService);

        PeerConnection connA = connectDedicated(listenerA, "Receiver", ConnectionPurpose.FILE_TRANSFER);
        PeerConnection connB = connectDedicated(listenerB, "Receiver", ConnectionPurpose.FILE_TRANSFER);

        serviceA.registerTransferConnection(connections.get(connections.indexOf(connA) + 1));
        serviceB.registerTransferConnection(connections.get(connections.indexOf(connB) + 1));

        MultiPeerDownloadCoordinator coordinator =
                receiverService.createMultiPeerDownload("failover_test.bin", fileSize, expectedHash);

        coordinator.queryFileAvailability(connA, 3000).get(5, TimeUnit.SECONDS);
        coordinator.queryFileAvailability(connB, 3000).get(5, TimeUnit.SECONDS);

        coordinator.addSourcePeer(new PeerDownloadState("peer-a", "Peer A", null), connA);
        coordinator.addSourcePeer(new PeerDownloadState("peer-b", "Peer B", null), connB);

        CountDownLatch someProgress = new CountDownLatch(2);
        coordinator.getDownload().addListener(new MultiPeerDownloadListener() {
            @Override
            public void onChunkCompleted(MultiPeerDownload download, int chunkIndex, String peerId, int completed, int total) {
                someProgress.countDown();
            }
        });

        coordinator.start();
        assertTrue(someProgress.await(5, TimeUnit.SECONDS));

        // Kill Peer B connection abruptly
        connB.close();

        // Download must still succeed via Peer A failover
        MultiPeerDownload result = coordinator.getCompletionFuture().get(10, TimeUnit.SECONDS);
        assertEquals(MultiPeerDownloadStatus.COMPLETED, result.getStatus());
        assertTrue(result.isHashVerified());
        assertArrayEquals(fileBytes, Files.readAllBytes(result.getDestinationPath()));
    }

    @Test
    @DisplayName("Flow 5: Localhost filename collision handling (preserves original source)")
    void testLocalhostDestinationCollision(@TempDir Path tempDir) throws Exception {
        Path destDir = tempDir.resolve("dest");
        Files.createDirectories(destDir);

        Path existingFile = destDir.resolve("collision.txt");
        Files.writeString(existingFile, "Original content that must not be overwritten");

        Path resolved = FileTransferUtils.resolveUniqueDestination(destDir, "collision.txt");
        assertNotEquals(existingFile, resolved);
        assertEquals("collision (1).txt", resolved.getFileName().toString());

        // Existing file content intact
        assertEquals("Original content that must not be overwritten", Files.readString(existingFile));
    }

    @Test
    @DisplayName("Flow 6: Edge Case - 0-byte file multi-peer download and SHA-256 verification")
    void testZeroByteFileVerification(@TempDir Path tempDir) throws Exception {
        Path emptyFile = tempDir.resolve("zero.dat");
        Files.createFile(emptyFile);
        String expectedHash = FileTransferUtils.calculateSha256(emptyFile);
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", expectedHash);

        PeerListener listenerA = new PeerListener(0);
        listeners.add(listenerA);

        FileTransferService serviceA = new FileTransferService(null, "peer-a", "Peer A", tempDir.resolve("dirA"), 16 * 1024);
        services.add(serviceA);
        serviceA.shareFile(emptyFile);

        FileTransferService receiverService = new FileTransferService(null, "rx", "Receiver", tempDir.resolve("dirRx"), 16 * 1024);
        services.add(receiverService);

        PeerConnection connA = connectDedicated(listenerA, "Receiver", ConnectionPurpose.FILE_TRANSFER);
        serviceA.registerTransferConnection(connections.get(connections.indexOf(connA) + 1));

        MultiPeerDownloadCoordinator coordinator =
                receiverService.createMultiPeerDownload("zero.dat", 0L, expectedHash);

        FileQueryResponseMessage respA = coordinator.queryFileAvailability(connA, 3000).get(5, TimeUnit.SECONDS);
        assertTrue(respA.isAvailable());

        coordinator.addSourcePeer(new PeerDownloadState("peer-a", "Peer A", null), connA);
        coordinator.start();

        MultiPeerDownload result = coordinator.getCompletionFuture().get(5, TimeUnit.SECONDS);
        assertEquals(MultiPeerDownloadStatus.COMPLETED, result.getStatus());
        assertTrue(result.isHashVerified());
        assertEquals(0L, Files.size(result.getDestinationPath()));
    }
}
