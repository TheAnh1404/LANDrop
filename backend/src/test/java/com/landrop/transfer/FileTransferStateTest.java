package com.landrop.transfer;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class FileTransferStateTest {

    @Test
    void testPhase8LifecycleStates() {
        FileTransfer transfer = new FileTransfer(
                "tx-state",
                "doc.pdf",
                5 * 1024 * 1024L,
                FileTransferDirection.INCOMING,
                "peer-b",
                "Peer B",
                Path.of("/tmp/doc.pdf"),
                1024 * 1024,
                5,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );

        assertEquals(FileTransferStatus.PENDING, transfer.getStatus());
        assertFalse(transfer.isTerminal());

        // INTERRUPTED state
        transfer.setStatus(FileTransferStatus.INTERRUPTED);
        assertEquals(FileTransferStatus.INTERRUPTED, transfer.getStatus());
        assertTrue(transfer.isInterrupted());
        assertFalse(transfer.isTerminal(), "INTERRUPTED must not be a terminal state");

        // RECONNECTING state
        transfer.setStatus(FileTransferStatus.RECONNECTING);
        assertEquals(FileTransferStatus.RECONNECTING, transfer.getStatus());
        assertTrue(transfer.isReconnecting());
        assertFalse(transfer.isTerminal(), "RECONNECTING must not be a terminal state");

        // RESUMING state
        transfer.setStatus(FileTransferStatus.RESUMING);
        assertEquals(FileTransferStatus.RESUMING, transfer.getStatus());
        assertTrue(transfer.isResuming());
        assertFalse(transfer.isTerminal(), "RESUMING must not be a terminal state");

        // Terminal states
        transfer.setStatus(FileTransferStatus.COMPLETED);
        assertTrue(transfer.isTerminal());
        assertTrue(transfer.isCompleted());
    }

    @Test
    void testChunkCompletionAndMissingChunkDetection() {
        FileTransfer transfer = new FileTransfer(
                "tx-chunks",
                "large.iso",
                10 * 1024 * 1024L,
                FileTransferDirection.INCOMING,
                "peer-c",
                "Peer C",
                Path.of("/tmp/large.iso"),
                1024 * 1024,
                10,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );

        // Initially no chunks completed
        assertEquals(0, transfer.getCompletedChunkCount());
        assertEquals(0, transfer.getFirstMissingChunk());
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), transfer.getMissingChunks());

        // Mark chunks 0, 1 completed
        transfer.markChunkCompleted(0);
        transfer.markChunkCompleted(1);
        assertEquals(2, transfer.getCompletedChunkCount());
        assertTrue(transfer.isChunkCompleted(0));
        assertTrue(transfer.isChunkCompleted(1));
        assertFalse(transfer.isChunkCompleted(2));
        assertEquals(2, transfer.getFirstMissingChunk());

        // Non-contiguous chunks: mark chunk 4 and 6
        transfer.markChunkCompleted(4);
        transfer.markChunkCompleted(6);
        assertEquals(4, transfer.getCompletedChunkCount());
        assertTrue(transfer.isChunkCompleted(4));
        assertTrue(transfer.isChunkCompleted(6));
        assertFalse(transfer.isChunkCompleted(2));
        assertFalse(transfer.isChunkCompleted(3));
        assertFalse(transfer.isChunkCompleted(5));

        // First missing chunk should be 2
        assertEquals(2, transfer.getFirstMissingChunk());
        assertEquals(List.of(2, 3, 5, 7, 8, 9), transfer.getMissingChunks());

        // Test clone of BitSet
        BitSet copy = transfer.getCompletedChunksBitSet();
        assertTrue(copy.get(0));
        assertTrue(copy.get(1));
        assertFalse(copy.get(2));
        assertTrue(copy.get(4));

        // Complete all remaining
        for (int i = 0; i < 10; i++) {
            transfer.markChunkCompleted(i);
        }
        assertEquals(10, transfer.getCompletedChunkCount());
        assertEquals(-1, transfer.getFirstMissingChunk());
        assertTrue(transfer.getMissingChunks().isEmpty());
    }

    @Test
    void testProgressTextAndRetryCounters() {
        FileTransfer transfer = new FileTransfer(
                "tx-ui",
                "data.bin",
                5 * 1024 * 1024L,
                FileTransferDirection.INCOMING,
                "peer-d",
                "Peer D",
                Path.of("/tmp/data.bin"),
                1024 * 1024,
                5,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );

        // Normal progress
        transfer.markChunkCompleted(0);
        transfer.markChunkCompleted(1);
        assertEquals("Chunk 2 / 5", transfer.getChunkProgressText());

        // Interrupted
        transfer.setStatus(FileTransferStatus.INTERRUPTED);
        assertEquals("Chunk 2 / 5", transfer.getChunkProgressText());

        // Reconnecting
        transfer.setStatus(FileTransferStatus.RECONNECTING);
        transfer.setReconnectAttempt(2);
        transfer.setMaxReconnectAttempts(5);
        assertEquals("Reconnecting... (Attempt 2 / 5)", transfer.getChunkProgressText());

        // Resuming
        transfer.setStatus(FileTransferStatus.RESUMING);
        assertEquals("Resuming from chunk 2 / 5", transfer.getChunkProgressText());

        // Chunk retry
        transfer.setStatus(FileTransferStatus.TRANSFERRING);
        transfer.setChunkRetryIndex(2);
        transfer.setChunkRetryAttempt(2);
        transfer.setMaxChunkRetries(3);
        assertEquals("Retrying chunk 2... (2 / 3)", transfer.getChunkProgressText());
    }

    @Test
    void testReconnectDelaysBackoffSchedule() {
        long[] delays = FileTransferService.RECONNECT_DELAYS_MS;
        assertEquals(5, delays.length);
        assertEquals(1000L, delays[0]);
        assertEquals(2000L, delays[1]);
        assertEquals(4000L, delays[2]);
        assertEquals(8000L, delays[3]);
        assertEquals(10000L, delays[4]);
    }
}
