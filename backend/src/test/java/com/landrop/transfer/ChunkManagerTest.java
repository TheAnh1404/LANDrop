package com.landrop.transfer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class ChunkManagerTest {

    @Test
    @DisplayName("Verify ChunkManager initial state and basic properties")
    void testInitialState() {
        ChunkManager cm = new ChunkManager(10);
        assertEquals(10, cm.getTotalChunks());
        assertEquals(0, cm.getCompletedChunkCount());
        assertEquals(10, cm.getRemainingChunkCount());
        assertEquals(0, cm.getFirstMissingChunk());
        assertEquals(0, cm.getNextAvailableChunk());
        assertFalse(cm.isComplete());
        assertEquals(0.0, cm.getProgressPercentage(), 0.001);

        for (int i = 0; i < 10; i++) {
            assertEquals(ChunkState.MISSING, cm.getChunkState(i));
            assertFalse(cm.isCompleted(i));
            assertFalse(cm.isInProgress(i));
            assertNull(cm.getAssignedPeer(i));
        }
    }

    @Test
    @DisplayName("Verify ChunkManager markRequested transitions and assignment")
    void testMarkRequested() {
        ChunkManager cm = new ChunkManager(5);

        assertTrue(cm.markRequested(0, "peer-a"));
        assertEquals(ChunkState.REQUESTED, cm.getChunkState(0));
        assertTrue(cm.isInProgress(0));
        assertFalse(cm.isCompleted(0));
        assertEquals("peer-a", cm.getAssignedPeer(0));

        // Attempting to assign same chunk to another peer should fail
        assertFalse(cm.markRequested(0, "peer-b"));
        assertEquals("peer-a", cm.getAssignedPeer(0));

        // Re-requesting by same peer should succeed
        assertTrue(cm.markRequested(0, "peer-a"));

        // Next available chunk should now be 1
        assertEquals(1, cm.getNextAvailableChunk());
        // But first missing chunk is still 0 (since 0 is not yet COMPLETED)
        assertEquals(0, cm.getFirstMissingChunk());
    }

    @Test
    @DisplayName("Verify ChunkManager markCompleted and duplicate completion protection")
    void testMarkCompleted() {
        ChunkManager cm = new ChunkManager(4);
        cm.markRequested(0, "peer-a");
        cm.markRequested(1, "peer-b");

        assertTrue(cm.markCompleted(0, "peer-a"));
        assertEquals(ChunkState.COMPLETED, cm.getChunkState(0));
        assertTrue(cm.isCompleted(0));
        assertFalse(cm.isInProgress(0));
        assertEquals(1, cm.getCompletedChunkCount());
        assertEquals(3, cm.getRemainingChunkCount());
        assertEquals(25.0, cm.getProgressPercentage(), 0.001);

        // Duplicate completion must return false and not corrupt state
        assertFalse(cm.markCompleted(0, "peer-c"));
        assertEquals(1, cm.getCompletedChunkCount());

        // Complete remaining chunks
        assertTrue(cm.markCompleted(1, "peer-b"));
        assertTrue(cm.markCompleted(2, "peer-a"));
        assertTrue(cm.markCompleted(3, "peer-b"));

        assertTrue(cm.isComplete());
        assertEquals(4, cm.getCompletedChunkCount());
        assertEquals(0, cm.getRemainingChunkCount());
        assertEquals(-1, cm.getFirstMissingChunk());
        assertEquals(-1, cm.getNextAvailableChunk());
        assertEquals(100.0, cm.getProgressPercentage(), 0.001);
    }

    @Test
    @DisplayName("Verify ChunkManager markFailed resets chunk to MISSING")
    void testMarkFailed() {
        ChunkManager cm = new ChunkManager(3);
        cm.markRequested(1, "peer-a");
        assertEquals(ChunkState.REQUESTED, cm.getChunkState(1));
        assertEquals("peer-a", cm.getAssignedPeer(1));

        assertTrue(cm.markFailed(1, "peer-a"));
        assertEquals(ChunkState.MISSING, cm.getChunkState(1));
        assertNull(cm.getAssignedPeer(1));
        assertFalse(cm.isInProgress(1));

        // Now chunk 1 is available again
        assertTrue(cm.markRequested(1, "peer-b"));
        assertEquals("peer-b", cm.getAssignedPeer(1));
    }

    @Test
    @DisplayName("Verify releasePeerChunks when peer disconnects")
    void testReleasePeerChunks() {
        ChunkManager cm = new ChunkManager(6);
        cm.markRequested(0, "peer-a");
        cm.markRequested(1, "peer-b");
        cm.markRequested(2, "peer-a");
        cm.markCompleted(0, "peer-a"); // chunk 0 is completed

        // peer-a disconnects
        List<Integer> released = cm.releasePeerChunks("peer-a");

        // Only uncompleted chunks of peer-a should be released (chunk 2)
        assertEquals(List.of(2), released);
        assertEquals(ChunkState.COMPLETED, cm.getChunkState(0));
        assertEquals(ChunkState.REQUESTED, cm.getChunkState(1));
        assertEquals(ChunkState.MISSING, cm.getChunkState(2));
        assertNull(cm.getAssignedPeer(2));

        // Next available chunk should find chunk 2 first
        assertEquals(2, cm.getNextAvailableChunk());
    }

    @Test
    @DisplayName("Verify constructor with initial BitSet for resume support")
    void testInitialBitSetResume() {
        BitSet bitSet = new BitSet();
        bitSet.set(0);
        bitSet.set(2);

        ChunkManager cm = new ChunkManager(4, bitSet);
        assertEquals(2, cm.getCompletedChunkCount());
        assertTrue(cm.isCompleted(0));
        assertFalse(cm.isCompleted(1));
        assertTrue(cm.isCompleted(2));
        assertFalse(cm.isCompleted(3));

        assertEquals(1, cm.getFirstMissingChunk());
        assertEquals(1, cm.getNextAvailableChunk());
    }

    @Test
    @DisplayName("Verify ChunkManager thread safety under heavy concurrent operations")
    void testThreadSafety() throws InterruptedException {
        int totalChunks = 500;
        ChunkManager cm = new ChunkManager(totalChunks);
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successfulCompletions = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final String peerId = "peer-" + t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    while (!cm.isComplete()) {
                        int chunk = cm.getNextAvailableChunk();
                        if (chunk < 0) break;

                        if (cm.markRequested(chunk, peerId)) {
                            // Simulate processing
                            Thread.yield();
                            if (cm.markCompleted(chunk, peerId)) {
                                successfulCompletions.incrementAndGet();
                            }
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        assertTrue(cm.isComplete());
        assertEquals(totalChunks, cm.getCompletedChunkCount());
        assertEquals(totalChunks, successfulCompletions.get());
    }

    @Test
    @DisplayName("Verify ChunkManager save to .landrop-meta and load from meta file")
    void testMetaFilePersistence(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        java.nio.file.Path metaPath = tempDir.resolve("sample_file.landrop-meta");

        ChunkManager cm = new ChunkManager(100);
        cm.markCompleted(0);
        cm.markCompleted(5);
        cm.markCompleted(42);
        cm.markCompleted(99);

        cm.saveToMetaFile(metaPath);
        assertTrue(java.nio.file.Files.exists(metaPath));

        ChunkManager loaded = ChunkManager.loadFromMetaFile(metaPath);
        assertNotNull(loaded);
        assertEquals(100, loaded.getTotalChunks());
        assertEquals(4, loaded.getCompletedChunkCount());
        assertTrue(loaded.isCompleted(0));
        assertTrue(loaded.isCompleted(5));
        assertTrue(loaded.isCompleted(42));
        assertTrue(loaded.isCompleted(99));
        assertFalse(loaded.isCompleted(1));
        assertFalse(loaded.isCompleted(41));

        BitSet bitSet = ChunkManager.loadBitSetFromMetaFile(metaPath);
        assertNotNull(bitSet);
        assertEquals(4, bitSet.cardinality());
        assertTrue(bitSet.get(42));
    }

    @Test
    @DisplayName("Verify dynamic chunk sizing for large files")
    void testDynamicChunkSizing() {
        // Files <= 500MB use 1MB default
        assertEquals(1024 * 1024, FileChunk.determineOptimalChunkSize(100 * 1024 * 1024L));
        assertEquals(1024 * 1024, FileChunk.determineOptimalChunkSize(500 * 1024 * 1024L));

        // Files > 500MB and <= 2GB use 1MB
        assertEquals(1024 * 1024, FileChunk.determineOptimalChunkSize(1000 * 1024 * 1024L));
        assertEquals(1024 * 1024, FileChunk.determineOptimalChunkSize(2L * 1024 * 1024 * 1024L));

        // Files > 2GB use 2MB
        assertEquals(2 * 1024 * 1024, FileChunk.determineOptimalChunkSize(3L * 1024 * 1024 * 1024L));
        assertEquals(2 * 1024 * 1024, FileChunk.determineOptimalChunkSize(50L * 1024 * 1024 * 1024L));
    }
}
