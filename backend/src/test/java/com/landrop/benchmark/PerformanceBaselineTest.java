package com.landrop.benchmark;

import com.landrop.discovery.DiscoveryServer;
import com.landrop.peer.ConnectionPurpose;
import com.landrop.peer.PeerConnection;
import com.landrop.peer.PeerListener;
import com.landrop.transfer.ChunkManager;
import com.landrop.transfer.FileChunk;
import com.landrop.transfer.FileTransferService;
import com.landrop.transfer.FileTransferUtils;
import com.landrop.transfer.MultiPeerDownload;
import com.landrop.transfer.MultiPeerDownloadCoordinator;
import com.landrop.transfer.PeerDownloadState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 11 Performance Baseline & Throughput Measurement.
 * <p>
 * Measures single-peer vs multi-peer download performance, chunk distribution,
 * throughput (MB/s), and mid-transfer failover recovery under reproducible local TCP conditions.
 */
public class PerformanceBaselineTest {

    private final List<AutoCloseable> closeables = new ArrayList<>();

    @BeforeEach
    void setUp() {}

    @AfterEach
    void tearDown() {
        for (AutoCloseable c : closeables) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
        closeables.clear();
    }

    private static class MockSourcePeer implements AutoCloseable {
        final String peerId;
        final String displayName;
        final PeerListener listener;
        final FileTransferService transferService;
        final List<PeerConnection> connections = new ArrayList<>();
        final Thread acceptThread;
        final AtomicBoolean running = new AtomicBoolean(true);

        MockSourcePeer(String peerId, String displayName, Path sharedDir, int chunkSize) throws IOException {
            this.peerId = peerId;
            this.displayName = displayName;
            this.listener = new PeerListener(0);
            this.transferService = new FileTransferService(null, peerId, displayName, sharedDir, chunkSize);

            this.acceptThread = Thread.ofVirtual().start(() -> {
                while (running.get() && !listener.isClosed()) {
                    try {
                        PeerConnection conn = listener.acceptConnection();
                        conn.setLocalPeerName(displayName);
                        conn.setPurpose(ConnectionPurpose.FILE_TRANSFER);
                        conn.start();
                        transferService.attachConnection(conn);
                        synchronized (connections) {
                            connections.add(conn);
                        }
                    } catch (Exception e) {
                        if (!running.get()) break;
                    }
                }
            });
        }

        int getPort() {
            return listener.getPort();
        }

        @Override
        public void close() {
            running.set(false);
            try {
                listener.close();
            } catch (Exception ignored) {
            }
            synchronized (connections) {
                for (PeerConnection c : connections) {
                    c.close();
                }
            }
            transferService.close();
        }
    }

    private byte[] createTestFile(Path path, int sizeBytes) throws IOException {
        byte[] data = new byte[sizeBytes];
        Random rnd = new Random(42L);
        rnd.nextBytes(data);
        Files.write(path, data);
        return data;
    }

    @Test
    @DisplayName("Measure Single-Peer vs Multi-Peer Download Throughput and Chunk Distribution")
    void testPerformanceBaselineSingleVsMultiPeer(@TempDir Path tempDir) throws Exception {
        int testFileSize = 2 * 1024 * 1024; // 2 MiB
        int chunkSize = 64 * 1024; // 64 KB -> 32 chunks

        Path sourceDirA = tempDir.resolve("peerA_share");
        Path sourceDirB = tempDir.resolve("peerB_share");
        Path destDirSingle = tempDir.resolve("receiver_single");
        Path destDirMulti = tempDir.resolve("receiver_multi");
        Files.createDirectories(sourceDirA);
        Files.createDirectories(sourceDirB);
        Files.createDirectories(destDirSingle);
        Files.createDirectories(destDirMulti);

        Path fileA = sourceDirA.resolve("benchmark_data.bin");
        Path fileB = sourceDirB.resolve("benchmark_data.bin");
        byte[] content = createTestFile(fileA, testFileSize);
        Files.copy(fileA, fileB);

        String expectedSha256 = FileTransferUtils.calculateSha256(fileA);

        MockSourcePeer peerA = new MockSourcePeer("peer-a", "Peer A", sourceDirA, chunkSize);
        MockSourcePeer peerB = new MockSourcePeer("peer-b", "Peer B", sourceDirB, chunkSize);
        closeables.add(peerA);
        closeables.add(peerB);

        peerA.transferService.shareFile(fileA);
        peerB.transferService.shareFile(fileB);

        // ==========================================
        // 1. Single-Peer Benchmark (Peer A -> Receiver)
        // ==========================================
        String singleTxId = UUID.randomUUID().toString();
        Path singleDest = destDirSingle.resolve("benchmark_single.bin");
        MultiPeerDownload singleDownload = new MultiPeerDownload(
                singleTxId, "benchmark_data.bin", testFileSize, chunkSize, expectedSha256, singleDest);
        MultiPeerDownloadCoordinator singleCoord = new MultiPeerDownloadCoordinator(singleDownload);
        closeables.add(singleCoord);

        PeerConnection connA1 = PeerConnection.connectTo("127.0.0.1", peerA.getPort());
        connA1.setPurpose(ConnectionPurpose.FILE_TRANSFER);
        connA1.start();
        connA1.initiateHandshake("Receiver", ConnectionPurpose.FILE_TRANSFER);
        assertTrue(connA1.awaitReady(5, TimeUnit.SECONDS));

        var respA1 = singleCoord.queryFileAvailability(connA1, 3000).get(5, TimeUnit.SECONDS);
        assertTrue(respA1.isAvailable());

        singleCoord.addSourcePeer(new PeerDownloadState("peer-a", "Peer A", null), connA1);

        long startSingle = System.nanoTime();
        singleCoord.start();
        MultiPeerDownload completedSingle = singleCoord.getCompletionFuture().get(10, TimeUnit.SECONDS);
        long elapsedSingleMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startSingle);

        double singleSizeMb = testFileSize / (1024.0 * 1024.0);
        double singleThroughputMBs = singleSizeMb / (Math.max(1, elapsedSingleMs) / 1000.0);

        assertTrue(completedSingle.isTerminal());
        assertEquals(32, completedSingle.getCompletedChunkCount());
        assertEquals(expectedSha256, FileTransferUtils.calculateSha256(singleDest));

        // ==========================================
        // 2. Multi-Peer Benchmark (Peer A + Peer B -> Receiver)
        // ==========================================
        String multiTxId = UUID.randomUUID().toString();
        Path multiDest = destDirMulti.resolve("benchmark_multi.bin");
        MultiPeerDownload multiDownload = new MultiPeerDownload(
                multiTxId, "benchmark_data.bin", testFileSize, chunkSize, expectedSha256, multiDest);
        MultiPeerDownloadCoordinator multiCoord = new MultiPeerDownloadCoordinator(multiDownload);
        closeables.add(multiCoord);

        PeerConnection connA2 = PeerConnection.connectTo("127.0.0.1", peerA.getPort());
        connA2.setPurpose(ConnectionPurpose.FILE_TRANSFER);
        connA2.start();
        connA2.initiateHandshake("Receiver", ConnectionPurpose.FILE_TRANSFER);
        assertTrue(connA2.awaitReady(5, TimeUnit.SECONDS));

        PeerConnection connB2 = PeerConnection.connectTo("127.0.0.1", peerB.getPort());
        connB2.setPurpose(ConnectionPurpose.FILE_TRANSFER);
        connB2.start();
        connB2.initiateHandshake("Receiver", ConnectionPurpose.FILE_TRANSFER);
        assertTrue(connB2.awaitReady(5, TimeUnit.SECONDS));

        var respA2 = multiCoord.queryFileAvailability(connA2, 3000).get(5, TimeUnit.SECONDS);
        var respB2 = multiCoord.queryFileAvailability(connB2, 3000).get(5, TimeUnit.SECONDS);
        assertTrue(respA2.isAvailable());
        assertTrue(respB2.isAvailable());

        PeerDownloadState stateA = new PeerDownloadState("peer-a", "Peer A", null);
        PeerDownloadState stateB = new PeerDownloadState("peer-b", "Peer B", null);
        multiCoord.addSourcePeer(stateA, connA2);
        multiCoord.addSourcePeer(stateB, connB2);

        long startMulti = System.nanoTime();
        multiCoord.start();
        MultiPeerDownload completedMulti = multiCoord.getCompletionFuture().get(10, TimeUnit.SECONDS);
        long elapsedMultiMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startMulti);

        double multiThroughputMBs = singleSizeMb / (Math.max(1, elapsedMultiMs) / 1000.0);

        assertTrue(completedMulti.isTerminal());
        assertEquals(32, completedMulti.getCompletedChunkCount());
        assertEquals(expectedSha256, FileTransferUtils.calculateSha256(multiDest));

        int chunksFromA = stateA.getCompletedChunks();
        int chunksFromB = stateB.getCompletedChunks();
        assertEquals(32, chunksFromA + chunksFromB);
        assertTrue(chunksFromA > 0, "Peer A should have contributed chunks");
        assertTrue(chunksFromB > 0, "Peer B should have contributed chunks");

        System.out.println("==================================================");
        System.out.println("LANDrop Phase 11 Performance Baseline Results");
        System.out.println("==================================================");
        System.out.printf("Test File Size: %d bytes (%.1f MiB)%n", testFileSize, singleSizeMb);
        System.out.printf("Chunk Size: %d bytes (Total Chunks: 32)%n", chunkSize);
        System.out.println("--------------------------------------------------");
        System.out.printf("Single-Peer Transfer Time: %d ms (Throughput: %.2f MB/s)%n", elapsedSingleMs, singleThroughputMBs);
        System.out.printf("Multi-Peer (2 Peers) Time: %d ms (Throughput: %.2f MB/s)%n", elapsedMultiMs, multiThroughputMBs);
        System.out.printf("Chunk Distribution: Peer A = %d chunks, Peer B = %d chunks%n", chunksFromA, chunksFromB);
        System.out.println("SHA-256 Integrity Verification: 100% Match");
        System.out.println("==================================================");
    }

    @Test
    @DisplayName("Measure Mid-Transfer Failover Recovery Latency and Integrity")
    void testMultiPeerFailoverPerformance(@TempDir Path tempDir) throws Exception {
        int testFileSize = 1024 * 1024; // 1 MiB
        int chunkSize = 32 * 1024; // 32 KB -> 32 chunks

        Path sourceDirA = tempDir.resolve("peerA_share");
        Path sourceDirB = tempDir.resolve("peerB_share");
        Path destDir = tempDir.resolve("receiver_failover");
        Files.createDirectories(sourceDirA);
        Files.createDirectories(sourceDirB);
        Files.createDirectories(destDir);

        Path fileA = sourceDirA.resolve("failover_test.bin");
        Path fileB = sourceDirB.resolve("failover_test.bin");
        createTestFile(fileA, testFileSize);
        Files.copy(fileA, fileB);

        String expectedSha256 = FileTransferUtils.calculateSha256(fileA);

        MockSourcePeer peerA = new MockSourcePeer("peer-a", "Peer A", sourceDirA, chunkSize);
        MockSourcePeer peerB = new MockSourcePeer("peer-b", "Peer B", sourceDirB, chunkSize);
        closeables.add(peerA);
        closeables.add(peerB);

        peerA.transferService.shareFile(fileA);
        peerB.transferService.shareFile(fileB);

        String txId = UUID.randomUUID().toString();
        Path destFile = destDir.resolve("failover_result.bin");
        MultiPeerDownload download = new MultiPeerDownload(
                txId, "failover_test.bin", testFileSize, chunkSize, expectedSha256, destFile);
        MultiPeerDownloadCoordinator coord = new MultiPeerDownloadCoordinator(download);
        closeables.add(coord);

        PeerConnection connA = PeerConnection.connectTo("127.0.0.1", peerA.getPort());
        connA.setPurpose(ConnectionPurpose.FILE_TRANSFER);
        connA.start();
        connA.initiateHandshake("Receiver", ConnectionPurpose.FILE_TRANSFER);
        assertTrue(connA.awaitReady(5, TimeUnit.SECONDS));

        PeerConnection connB = PeerConnection.connectTo("127.0.0.1", peerB.getPort());
        connB.setPurpose(ConnectionPurpose.FILE_TRANSFER);
        connB.start();
        connB.initiateHandshake("Receiver", ConnectionPurpose.FILE_TRANSFER);
        assertTrue(connB.awaitReady(5, TimeUnit.SECONDS));

        var respA = coord.queryFileAvailability(connA, 3000).get(5, TimeUnit.SECONDS);
        var respB = coord.queryFileAvailability(connB, 3000).get(5, TimeUnit.SECONDS);
        assertTrue(respA.isAvailable());
        assertTrue(respB.isAvailable());

        PeerDownloadState stateA = new PeerDownloadState("peer-a", "Peer A", null);
        PeerDownloadState stateB = new PeerDownloadState("peer-b", "Peer B", null);
        coord.addSourcePeer(stateA, connA);
        coord.addSourcePeer(stateB, connB);

        long start = System.nanoTime();

        // Register listener to kill Peer B mid-transfer after 4 chunks
        download.addListener(new com.landrop.transfer.MultiPeerDownloadListener() {
            private final AtomicBoolean killed = new AtomicBoolean(false);

            @Override
            public void onChunkCompleted(MultiPeerDownload dl, int chunkIndex, String peerId, int completedChunks, int totalChunks) {
                if (completedChunks >= 4 && killed.compareAndSet(false, true)) {
                    // Abruptly sever Peer B
                    connB.close();
                    peerB.close();
                }
            }
        });

        coord.start();
        MultiPeerDownload completed = coord.getCompletionFuture().get(10, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(completed.isTerminal());
        assertEquals(32, completed.getCompletedChunkCount());
        assertEquals(expectedSha256, FileTransferUtils.calculateSha256(destFile));

        System.out.println("--------------------------------------------------");
        System.out.printf("Mid-Transfer Failover Recovery: Total Time = %d ms (Peer B severed at chunk 4)%n", elapsedMs);
        System.out.printf("Chunks completed by surviving Peer A: %d, Peer B before drop: %d%n",
                stateA.getCompletedChunks(), stateB.getCompletedChunks());
        System.out.println("Failover Integrity: 100% SHA-256 Validated");
        System.out.println("==================================================");
    }
}
