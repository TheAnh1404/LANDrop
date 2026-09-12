package com.landrop.transfer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class ChunkSchedulerTest {

    @Test
    @DisplayName("Verify round-robin assignment across available peers with max active limit")
    void testRoundRobinScheduling() {
        ChunkManager cm = new ChunkManager(10);
        ChunkScheduler scheduler = new ChunkScheduler(2); // max 2 active per peer

        PeerDownloadState p1 = new PeerDownloadState("peer-1", "Peer 1", null);
        PeerDownloadState p2 = new PeerDownloadState("peer-2", "Peer 2", null);
        PeerDownloadState p3 = new PeerDownloadState("peer-3", "Peer 3", null);
        List<PeerDownloadState> peers = List.of(p1, p2, p3);

        // Initial schedule: each peer gets 2 chunks (total 6 chunks)
        List<ChunkScheduler.ChunkAssignment> assignments = scheduler.scheduleNextAssignments(cm, peers);
        assertEquals(6, assignments.size());

        // Check active counts
        assertEquals(2, p1.getActiveChunks());
        assertEquals(2, p2.getActiveChunks());
        assertEquals(2, p3.getActiveChunks());

        // Verify no duplicates
        Set<Integer> assignedChunks = new HashSet<>();
        for (var a : assignments) {
            assertTrue(assignedChunks.add(a.chunkIndex()), "Duplicate chunk assigned: " + a.chunkIndex());
        }
        assertEquals(6, assignedChunks.size());

        // Further scheduling call while all peers are saturated should return 0 assignments
        List<ChunkScheduler.ChunkAssignment> empty = scheduler.scheduleNextAssignments(cm, peers);
        assertTrue(empty.isEmpty());

        // Peer 1 finishes chunk 0
        scheduler.handleChunkCompleted(assignments.get(0).chunkIndex(), "peer-1", cm, p1);
        assertEquals(1, p1.getActiveChunks());
        assertEquals(1, p1.getCompletedChunks());

        // Now scheduler should assign chunk 6 to Peer 1
        List<ChunkScheduler.ChunkAssignment> next = scheduler.scheduleNextAssignments(cm, peers);
        assertEquals(1, next.size());
        assertEquals("peer-1", next.get(0).peerId());
        assertEquals(6, next.get(0).chunkIndex());
        assertEquals(2, p1.getActiveChunks());
    }

    @Test
    @DisplayName("Verify peer failure and reassignment of unfinished chunks")
    void testPeerFailureAndReassignment() {
        ChunkManager cm = new ChunkManager(6);
        ChunkScheduler scheduler = new ChunkScheduler(2);

        PeerDownloadState p1 = new PeerDownloadState("peer-1", "Peer 1", null);
        PeerDownloadState p2 = new PeerDownloadState("peer-2", "Peer 2", null);
        List<PeerDownloadState> peers = List.of(p1, p2);

        // Initial schedule: p1 gets 2, p2 gets 2
        scheduler.scheduleNextAssignments(cm, peers);
        assertEquals(2, p1.getActiveChunks());
        assertEquals(2, p2.getActiveChunks());

        // Peer 2 disconnects!
        List<Integer> released = scheduler.handlePeerDisconnected("peer-2", cm, p2);
        assertFalse(p2.isConnected());
        assertEquals(0, p2.getActiveChunks());
        assertEquals(2, released.size());

        // Peer 1 completes one of its chunks
        int p1Chunk = cm.getAssignedPeer(0).equals("peer-1") ? 0 : 1;
        scheduler.handleChunkCompleted(p1Chunk, "peer-1", cm, p1);
        assertEquals(1, p1.getActiveChunks());

        // Now schedule with only peer-1 available -> peer-1 should receive one of peer-2's released chunks
        List<ChunkScheduler.ChunkAssignment> reassigned = scheduler.scheduleNextAssignments(cm, peers);
        assertEquals(1, reassigned.size());
        assertEquals("peer-1", reassigned.get(0).peerId());
        assertTrue(released.contains(reassigned.get(0).chunkIndex()));
    }

    @Test
    @DisplayName("Verify reconnected peer becomes available and receives new chunks")
    void testPeerRecovery() {
        ChunkManager cm = new ChunkManager(6);
        ChunkScheduler scheduler = new ChunkScheduler(2);

        PeerDownloadState p1 = new PeerDownloadState("peer-1", "Peer 1", null);
        PeerDownloadState p2 = new PeerDownloadState("peer-2", "Peer 2", null);
        p2.setConnected(false); // peer 2 starts disconnected

        List<PeerDownloadState> peers = List.of(p1, p2);

        // Schedule with only p1
        scheduler.scheduleNextAssignments(cm, peers);
        assertEquals(2, p1.getActiveChunks());
        assertEquals(0, p2.getActiveChunks());

        // Peer 2 reconnects!
        p2.setConnected(true);
        p2.setAvailable(true);

        // Scheduler should now assign chunks to peer 2
        List<ChunkScheduler.ChunkAssignment> p2Assignments = scheduler.scheduleNextAssignments(cm, peers);
        assertEquals(2, p2Assignments.size());
        assertEquals("peer-2", p2Assignments.get(0).peerId());
        assertEquals("peer-2", p2Assignments.get(1).peerId());
        assertEquals(2, p2.getActiveChunks());
    }

    @Test
    @DisplayName("Verify single peer download behavior (backward compatibility)")
    void testSinglePeerScheduling() {
        ChunkManager cm = new ChunkManager(5);
        ChunkScheduler scheduler = new ChunkScheduler(2);

        PeerDownloadState p1 = new PeerDownloadState("peer-1", "Peer 1", null);
        List<PeerDownloadState> peers = List.of(p1);

        int completed = 0;
        while (!cm.isComplete()) {
            List<ChunkScheduler.ChunkAssignment> assignments = scheduler.scheduleNextAssignments(cm, peers);
            for (var a : assignments) {
                scheduler.handleChunkCompleted(a.chunkIndex(), "peer-1", cm, p1);
                completed++;
            }
        }

        assertTrue(cm.isComplete());
        assertEquals(5, completed);
        assertEquals(5, p1.getCompletedChunks());
    }
}
