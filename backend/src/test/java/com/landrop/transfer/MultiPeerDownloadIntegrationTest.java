package com.landrop.transfer;

import com.landrop.peer.PeerConnection;
import com.landrop.peer.PeerListener;
import com.landrop.protocol.MessageType;
import com.landrop.protocol.Packet;
import com.landrop.transfer.messages.ChunkRequestMessage;
import com.landrop.transfer.messages.ChunkResponseMessage;
import com.landrop.transfer.messages.FileQueryResponseMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class MultiPeerDownloadIntegrationTest {

    private final List<PeerListener> listeners = new ArrayList<>();
    private final List<PeerConnection> connections = new ArrayList<>();
    private final List<FileTransferService> services = new ArrayList<>();

    private record PeerNode(String id, String name, PeerListener listener, FileTransferService service) {}

    private PeerNode createPeer(String id, String name, Path baseDir, int chunkSize) throws IOException {
        PeerListener listener = new PeerListener(0);
        listeners.add(listener);

        FileTransferService service = new FileTransferService(null, id, name, baseDir, chunkSize);
        service.setAutoReconnectEnabled(false);
        services.add(service);

        return new PeerNode(id, name, listener, service);
    }

    private PeerConnection connectPeers(PeerNode serverNode, String clientName) throws Exception {
        CountDownLatch acceptLatch = new CountDownLatch(1);
        AtomicReference<PeerConnection> serverConnRef = new AtomicReference<>();

        Thread serverThread = new Thread(() -> {
            try {
                PeerConnection sc = serverNode.listener.acceptConnection();
                serverConnRef.set(sc);
                sc.setLocalPeerName(serverNode.name);
                sc.start();
                serverNode.service.attachConnection(sc);
                acceptLatch.countDown();
            } catch (Exception ignored) {
            }
        });
        serverThread.start();

        PeerConnection clientConn = PeerConnection.connectTo("localhost", serverNode.listener.getPort());
        clientConn.setLocalPeerName(clientName);
        clientConn.start();

        assertTrue(acceptLatch.await(5, TimeUnit.SECONDS));
        PeerConnection serverConn = serverConnRef.get();
        assertNotNull(serverConn);

        connections.add(clientConn);
        connections.add(serverConn);

        clientConn.initiateHandshake(clientName);
        serverConn.initiateHandshake(serverNode.name);
        assertTrue(clientConn.awaitReady(5, TimeUnit.SECONDS));
        assertTrue(serverConn.awaitReady(5, TimeUnit.SECONDS));

        return clientConn;
    }

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
    }

    @Test
    @DisplayName("Scenario 1 & 2: 3 peers provide the same file -> Chunks distributed and verified")
    void testThreePeersConcurrentDownload(@TempDir Path tempDir) throws Exception {
        int chunkSize = 32 * 1024; // 32 KB
        int totalChunks = 12;      // 12 chunks
        int fileSize = totalChunks * chunkSize;
        byte[] originalBytes = new byte[fileSize];
        new Random(100).nextBytes(originalBytes);

        Path sourceFile = tempDir.resolve("multi_source.bin");
        Files.write(sourceFile, originalBytes);
        String expectedHash = FileTransferUtils.calculateSha256(sourceFile);

        // 1. Create Peer A, B, C
        PeerNode nodeA = createPeer("peer-a", "Peer A", tempDir.resolve("dirA"), chunkSize);
        PeerNode nodeB = createPeer("peer-b", "Peer B", tempDir.resolve("dirB"), chunkSize);
        PeerNode nodeC = createPeer("peer-c", "Peer C", tempDir.resolve("dirC"), chunkSize);

        // Share the source file on Peer A, B, C
        nodeA.service.shareFile(sourceFile);
        nodeB.service.shareFile(sourceFile);
        nodeC.service.shareFile(sourceFile);

        // 2. Create Receiver (Peer D)
        Path downloadDir = tempDir.resolve("receiver_downloads");
        Files.createDirectories(downloadDir);
        FileTransferService receiverService = new FileTransferService(null, "peer-d", "Peer D", downloadDir, chunkSize);
        services.add(receiverService);

        // 3. Connect Receiver to Peer A, B, C
        PeerConnection connToA = connectPeers(nodeA, "Peer D");
        PeerConnection connToB = connectPeers(nodeB, "Peer D");
        PeerConnection connToC = connectPeers(nodeC, "Peer D");

        MultiPeerDownloadCoordinator coordinator =
                receiverService.createMultiPeerDownload("multi_source.bin", fileSize, expectedHash);
        MultiPeerDownload download = coordinator.getDownload();

        // 4. Query availability
        FileQueryResponseMessage respA = coordinator.queryFileAvailability(connToA, 3000).get(5, TimeUnit.SECONDS);
        FileQueryResponseMessage respB = coordinator.queryFileAvailability(connToB, 3000).get(5, TimeUnit.SECONDS);
        FileQueryResponseMessage respC = coordinator.queryFileAvailability(connToC, 3000).get(5, TimeUnit.SECONDS);

        assertTrue(respA.isAvailable());
        assertTrue(respB.isAvailable());
        assertTrue(respC.isAvailable());

        // 5. Add all 3 source peers
        PeerDownloadState stateA = new PeerDownloadState("peer-a", "Peer A", null);
        PeerDownloadState stateB = new PeerDownloadState("peer-b", "Peer B", null);
        PeerDownloadState stateC = new PeerDownloadState("peer-c", "Peer C", null);

        coordinator.addSourcePeer(stateA, connToA);
        coordinator.addSourcePeer(stateB, connToB);
        coordinator.addSourcePeer(stateC, connToC);

        // 6. Start download
        coordinator.start();

        // 7. Await completion
        MultiPeerDownload result = coordinator.getCompletionFuture().get(10, TimeUnit.SECONDS);
        assertEquals(MultiPeerDownloadStatus.COMPLETED, result.getStatus());
        assertTrue(result.isHashVerified());
        assertEquals(expectedHash, result.getVerifiedSha256());

        // Verify all 3 peers contributed chunks
        assertTrue(stateA.getCompletedChunks() > 0, "Peer A should have downloaded chunks");
        assertTrue(stateB.getCompletedChunks() > 0, "Peer B should have downloaded chunks");
        assertTrue(stateC.getCompletedChunks() > 0, "Peer C should have downloaded chunks");
        assertEquals(totalChunks, stateA.getCompletedChunks() + stateB.getCompletedChunks() + stateC.getCompletedChunks());

        // Verify assembled file on disk
        Path finalFile = download.getDestinationPath();
        assertTrue(Files.exists(finalFile));
        assertEquals(fileSize, Files.size(finalFile));
        assertArrayEquals(originalBytes, Files.readAllBytes(finalFile));
        assertFalse(Files.exists(download.getPartialFilePath()), ".part file must be removed after move");
    }

    @Test
    @DisplayName("Scenario 6: Only one peer has the file -> Downloads cleanly (backward compatibility)")
    void testSinglePeerDownloadBackwardCompatibility(@TempDir Path tempDir) throws Exception {
        int chunkSize = 16 * 1024;
        int fileSize = 4 * chunkSize;
        byte[] originalBytes = new byte[fileSize];
        new Random(42).nextBytes(originalBytes);

        Path sourceFile = tempDir.resolve("single_peer.bin");
        Files.write(sourceFile, originalBytes);
        String expectedHash = FileTransferUtils.calculateSha256(sourceFile);

        PeerNode nodeA = createPeer("peer-a", "Peer A", tempDir.resolve("dirA"), chunkSize);
        nodeA.service.shareFile(sourceFile);

        Path downloadDir = tempDir.resolve("downloads_single");
        FileTransferService receiverService = new FileTransferService(null, "peer-rx", "Receiver", downloadDir, chunkSize);
        services.add(receiverService);

        PeerConnection connToA = connectPeers(nodeA, "Receiver");

        MultiPeerDownloadCoordinator coordinator =
                receiverService.createMultiPeerDownload("single_peer.bin", fileSize, expectedHash);
        FileQueryResponseMessage respA = coordinator.queryFileAvailability(connToA, 3000).get(5, TimeUnit.SECONDS);
        assertTrue(respA.isAvailable());

        PeerDownloadState stateA = new PeerDownloadState("peer-a", "Peer A", null);
        coordinator.addSourcePeer(stateA, connToA);

        coordinator.start();
        MultiPeerDownload result = coordinator.getCompletionFuture().get(5, TimeUnit.SECONDS);

        assertEquals(MultiPeerDownloadStatus.COMPLETED, result.getStatus());
        assertEquals(4, stateA.getCompletedChunks());
        assertEquals(expectedHash, result.getVerifiedSha256());
    }

    @Test
    @DisplayName("Scenario 3: Peer disconnects while downloading -> Other peers take over and complete")
    void testPeerFailureFailover(@TempDir Path tempDir) throws Exception {
        int chunkSize = 32 * 1024;
        int totalChunks = 8;
        int fileSize = totalChunks * chunkSize;
        byte[] originalBytes = new byte[fileSize];
        new Random(88).nextBytes(originalBytes);

        Path sourceFile = tempDir.resolve("failover_source.bin");
        Files.write(sourceFile, originalBytes);
        String expectedHash = FileTransferUtils.calculateSha256(sourceFile);

        PeerNode nodeA = createPeer("peer-a", "Peer A", tempDir.resolve("dirA"), chunkSize);
        PeerNode nodeB = createPeer("peer-b", "Peer B", tempDir.resolve("dirB"), chunkSize);
        nodeA.service.shareFile(sourceFile);
        nodeB.service.shareFile(sourceFile);

        Path downloadDir = tempDir.resolve("downloads_failover");
        FileTransferService receiverService = new FileTransferService(null, "receiver", "Receiver", downloadDir, chunkSize);
        services.add(receiverService);

        PeerConnection connToA = connectPeers(nodeA, "Receiver");
        PeerConnection connToB = connectPeers(nodeB, "Receiver");

        MultiPeerDownloadCoordinator coordinator =
                receiverService.createMultiPeerDownload("failover_source.bin", fileSize, expectedHash);

        FileQueryResponseMessage respA = coordinator.queryFileAvailability(connToA, 3000).get(5, TimeUnit.SECONDS);
        FileQueryResponseMessage respB = coordinator.queryFileAvailability(connToB, 3000).get(5, TimeUnit.SECONDS);
        assertTrue(respA.isAvailable());
        assertTrue(respB.isAvailable());

        PeerDownloadState stateA = new PeerDownloadState("peer-a", "Peer A", null);
        PeerDownloadState stateB = new PeerDownloadState("peer-b", "Peer B", null);

        coordinator.addSourcePeer(stateA, connToA);
        coordinator.addSourcePeer(stateB, connToB);

        CountDownLatch bCompletedOneChunk = new CountDownLatch(1);

        coordinator.getDownload().addListener(new MultiPeerDownloadListener() {
            @Override
            public void onChunkCompleted(MultiPeerDownload download, int chunkIndex, String peerId, int completed, int total) {
                if ("peer-b".equals(peerId)) {
                    bCompletedOneChunk.countDown();
                }
            }
        });

        coordinator.start();

        // Wait until Peer B completes at least 1 chunk, then sever connection to Peer B
        assertTrue(bCompletedOneChunk.await(5, TimeUnit.SECONDS));
        connToB.close();

        // The download must still complete via Peer A!
        MultiPeerDownload result = coordinator.getCompletionFuture().get(10, TimeUnit.SECONDS);
        assertEquals(MultiPeerDownloadStatus.COMPLETED, result.getStatus());
        assertTrue(result.isHashVerified());
        assertFalse(stateB.isConnected(), "Peer B should be marked disconnected");

        Path finalFile = coordinator.getDownload().getDestinationPath();
        assertTrue(Files.exists(finalFile));
        assertArrayEquals(originalBytes, Files.readAllBytes(finalFile));
    }

    @Test
    @DisplayName("Scenario 7: No peer has the file -> Graceful rejection")
    void testNoPeerHasFileGracefulFailure(@TempDir Path tempDir) throws Exception {
        int chunkSize = 16 * 1024;
        PeerNode nodeA = createPeer("peer-a", "Peer A", tempDir.resolve("dirA"), chunkSize);
        // Notice: nodeA does NOT share the requested file!

        Path downloadDir = tempDir.resolve("downloads_none");
        FileTransferService receiverService = new FileTransferService(null, "receiver", "Receiver", downloadDir, chunkSize);
        services.add(receiverService);

        PeerConnection connToA = connectPeers(nodeA, "Receiver");

        MultiPeerDownloadCoordinator coordinator =
                receiverService.createMultiPeerDownload("unknown.iso", 100000L,
                        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

        FileQueryResponseMessage resp = coordinator.queryFileAvailability(connToA, 2000).get(3, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertFalse(resp.isAvailable());
        assertEquals(FileQueryResponseMessage.REASON_FILE_NOT_FOUND, resp.getReason());
    }

    @Test
    @DisplayName("Scenario 9: SHA-256 integrity mismatch -> FAILED and .part file deleted")
    void testSha256MismatchFailsTransfer(@TempDir Path tempDir) throws Exception {
        int chunkSize = 16 * 1024;
        int fileSize = 2 * chunkSize;
        byte[] originalBytes = new byte[fileSize];
        new Random(99).nextBytes(originalBytes);

        Path sourceFile = tempDir.resolve("corrupted_source.bin");
        Files.write(sourceFile, originalBytes);

        // Intentionally provide wrong expected SHA-256
        String wrongExpectedHash = "0000000000000000000000000000000000000000000000000000000000000000";

        PeerNode nodeA = createPeer("peer-a", "Peer A", tempDir.resolve("dirA"), chunkSize);
        nodeA.service.shareFile(sourceFile);

        Path downloadDir = tempDir.resolve("downloads_mismatch");
        FileTransferService receiverService = new FileTransferService(null, "receiver", "Receiver", downloadDir, chunkSize);
        services.add(receiverService);

        PeerConnection connToA = connectPeers(nodeA, "Receiver");

        MultiPeerDownloadCoordinator coordinator =
                receiverService.createMultiPeerDownload("corrupted_source.bin", fileSize, wrongExpectedHash);

        // Explicitly register on nodeA for this transferId
        nodeA.service.registerActiveTransfer(coordinator.getDownload().getTransferId(), sourceFile, chunkSize);

        PeerDownloadState stateA = new PeerDownloadState("peer-a", "Peer A", null);
        coordinator.addSourcePeer(stateA, connToA);

        coordinator.start();

        assertThrows(Exception.class, () -> coordinator.getCompletionFuture().get(5, TimeUnit.SECONDS));

        MultiPeerDownload dl = coordinator.getDownload();
        assertEquals(MultiPeerDownloadStatus.FAILED, dl.getStatus());
        assertTrue(dl.getErrorMessage().contains("SHA-256 hash mismatch") || dl.getErrorMessage().contains("integrity"));

        // Verify .part file was deleted
        assertFalse(Files.exists(dl.getPartialFilePath()), "Partial file must be deleted upon integrity failure");
        assertFalse(Files.exists(dl.getDestinationPath()), "Corrupted file must never be promoted to destination");
    }

    @Test
    @DisplayName("Scenario 4: Reconnecting / late peer joins in-progress multi-peer download")
    void testLatePeerJoinsInProgressDownload(@TempDir Path tempDir) throws Exception {
        int chunkSize = 16 * 1024;
        int totalChunks = 16;
        int fileSize = totalChunks * chunkSize;
        byte[] originalBytes = new byte[fileSize];
        new Random(44).nextBytes(originalBytes);

        Path sourceFile = tempDir.resolve("late_join.bin");
        Files.write(sourceFile, originalBytes);
        String expectedHash = FileTransferUtils.calculateSha256(sourceFile);

        PeerNode nodeA = createPeer("peer-a", "Peer A", tempDir.resolve("dirA"), chunkSize);
        PeerNode nodeB = createPeer("peer-b", "Peer B", tempDir.resolve("dirB"), chunkSize);
        nodeA.service.shareFile(sourceFile);
        nodeB.service.shareFile(sourceFile);

        Path downloadDir = tempDir.resolve("downloads_late");
        FileTransferService receiverService = new FileTransferService(null, "receiver", "Receiver", downloadDir, chunkSize);
        services.add(receiverService);

        PeerConnection connToA = connectPeers(nodeA, "Receiver");
        PeerConnection connToB = connectPeers(nodeB, "Receiver");

        MultiPeerDownloadCoordinator coordinator =
                receiverService.createMultiPeerDownload("late_join.bin", fileSize, expectedHash);

        FileQueryResponseMessage respA = coordinator.queryFileAvailability(connToA, 3000).get(5, TimeUnit.SECONDS);
        FileQueryResponseMessage respB = coordinator.queryFileAvailability(connToB, 3000).get(5, TimeUnit.SECONDS);
        assertTrue(respA.isAvailable());
        assertTrue(respB.isAvailable());

        PeerDownloadState stateA = new PeerDownloadState("peer-a", "Peer A", null);
        PeerDownloadState stateB = new PeerDownloadState("peer-b", "Peer B", null);

        // Start with only Peer A
        coordinator.addSourcePeer(stateA, connToA);

        CountDownLatch twoChunksFromA = new CountDownLatch(2);
        coordinator.getDownload().addListener(new MultiPeerDownloadListener() {
            @Override
            public void onChunkCompleted(MultiPeerDownload download, int chunkIndex, String peerId, int completed, int total) {
                if ("peer-a".equals(peerId)) {
                    twoChunksFromA.countDown();
                }
            }
        });

        coordinator.start();

        assertTrue(twoChunksFromA.await(5, TimeUnit.SECONDS));

        // Now Peer B joins mid-download!
        coordinator.addSourcePeer(stateB, connToB);

        // Await completion
        MultiPeerDownload result = coordinator.getCompletionFuture().get(10, TimeUnit.SECONDS);
        assertEquals(MultiPeerDownloadStatus.COMPLETED, result.getStatus());
        assertTrue(result.isHashVerified());

        // Both peers should have participated
        assertTrue(stateA.getCompletedChunks() >= 2, "Peer A should have completed at least 2 chunks");
        assertTrue(stateB.getCompletedChunks() > 0, "Late Peer B should have contributed chunks");
        assertEquals(totalChunks, stateA.getCompletedChunks() + stateB.getCompletedChunks());

        Path finalFile = coordinator.getDownload().getDestinationPath();
        assertTrue(Files.exists(finalFile));
        assertArrayEquals(originalBytes, Files.readAllBytes(finalFile));
    }

    @Test
    @DisplayName("Scenario 8: All source peers disconnect -> Transfer fails")
    void testAllPeersDisconnectFailsTransfer(@TempDir Path tempDir) throws Exception {
        int chunkSize = 32 * 1024;
        int totalChunks = 10;
        int fileSize = totalChunks * chunkSize;
        byte[] originalBytes = new byte[fileSize];
        new Random(55).nextBytes(originalBytes);

        Path sourceFile = tempDir.resolve("all_disconnect.bin");
        Files.write(sourceFile, originalBytes);
        String expectedHash = FileTransferUtils.calculateSha256(sourceFile);

        PeerNode nodeA = createPeer("peer-a", "Peer A", tempDir.resolve("dirA"), chunkSize);
        nodeA.service.shareFile(sourceFile);

        Path downloadDir = tempDir.resolve("downloads_disconnect");
        FileTransferService receiverService = new FileTransferService(null, "receiver", "Receiver", downloadDir, chunkSize);
        services.add(receiverService);

        PeerConnection connToA = connectPeers(nodeA, "Receiver");

        MultiPeerDownloadCoordinator coordinator =
                receiverService.createMultiPeerDownload("all_disconnect.bin", fileSize, expectedHash);

        FileQueryResponseMessage respA = coordinator.queryFileAvailability(connToA, 3000).get(5, TimeUnit.SECONDS);
        assertTrue(respA.isAvailable());

        PeerDownloadState stateA = new PeerDownloadState("peer-a", "Peer A", null);
        coordinator.addSourcePeer(stateA, connToA);

        CountDownLatch oneChunk = new CountDownLatch(1);
        coordinator.getDownload().addListener(new MultiPeerDownloadListener() {
            @Override
            public void onChunkCompleted(MultiPeerDownload download, int chunkIndex, String peerId, int completed, int total) {
                oneChunk.countDown();
            }
        });

        coordinator.start();
        assertTrue(oneChunk.await(5, TimeUnit.SECONDS));

        // Sever Peer A connection
        connToA.close();

        // Download future must fail
        assertThrows(Exception.class, () -> coordinator.getCompletionFuture().get(5, TimeUnit.SECONDS));
        assertEquals(MultiPeerDownloadStatus.FAILED, coordinator.getDownload().getStatus());
        assertTrue(coordinator.getDownload().getErrorMessage().contains("All source peers disconnected") ||
                coordinator.getDownload().getErrorMessage().contains("disconnected"));
    }

    @Test
    @DisplayName("Edge Case: 0-byte file multi-peer download completes immediately with verified SHA-256")
    void testZeroByteFileMultiPeerDownload(@TempDir Path tempDir) throws Exception {
        Path emptySourceFile = tempDir.resolve("empty.bin");
        Files.createFile(emptySourceFile);
        String expectedHash = FileTransferUtils.calculateSha256(emptySourceFile);

        PeerNode nodeA = createPeer("peer-a", "Peer A", tempDir.resolve("dirA"), 16 * 1024);
        nodeA.service.shareFile(emptySourceFile);

        Path downloadDir = tempDir.resolve("downloads_empty");
        FileTransferService receiverService = new FileTransferService(null, "receiver", "Receiver", downloadDir, 16 * 1024);
        services.add(receiverService);

        PeerConnection connToA = connectPeers(nodeA, "Receiver");

        MultiPeerDownloadCoordinator coordinator =
                receiverService.createMultiPeerDownload("empty.bin", 0L, expectedHash);

        FileQueryResponseMessage respA = coordinator.queryFileAvailability(connToA, 3000).get(5, TimeUnit.SECONDS);
        assertTrue(respA.isAvailable());

        PeerDownloadState stateA = new PeerDownloadState("peer-a", "Peer A", null);
        coordinator.addSourcePeer(stateA, connToA);

        coordinator.start();

        MultiPeerDownload result = coordinator.getCompletionFuture().get(5, TimeUnit.SECONDS);
        assertEquals(MultiPeerDownloadStatus.COMPLETED, result.getStatus());
        assertTrue(result.isHashVerified());
        assertEquals(expectedHash, result.getVerifiedSha256());

        Path finalFile = result.getDestinationPath();
        assertTrue(Files.exists(finalFile));
        assertEquals(0L, Files.size(finalFile));
    }
}
