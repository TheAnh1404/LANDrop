package com.landrop.transfer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Thread-safe scheduler responsible for distributing missing file chunks across
 * available source peers using a bounded round-robin strategy.
 */
public class ChunkScheduler {

    public static final int DEFAULT_MAX_ACTIVE_CHUNKS_PER_PEER = 2;

    private volatile int maxActiveChunksPerPeer = DEFAULT_MAX_ACTIVE_CHUNKS_PER_PEER;
    private int roundRobinIndex = 0;
    private final Object lock = new Object();

    /**
     * Immutable assignment pair mapping a file chunk index to a specific peer.
     */
    public record ChunkAssignment(String peerId, int chunkIndex) {
        public ChunkAssignment {
            Objects.requireNonNull(peerId, "peerId cannot be null");
            if (chunkIndex < 0) {
                throw new IllegalArgumentException("chunkIndex cannot be negative");
            }
        }
    }

    public ChunkScheduler() {
    }

    public ChunkScheduler(int maxActiveChunksPerPeer) {
        setMaxActiveChunksPerPeer(maxActiveChunksPerPeer);
    }

    public int getMaxActiveChunksPerPeer() {
        return maxActiveChunksPerPeer;
    }

    public void setMaxActiveChunksPerPeer(int maxActiveChunksPerPeer) {
        this.maxActiveChunksPerPeer = Math.max(1, maxActiveChunksPerPeer);
    }

    /**
     * Computes the next batch of chunk assignments for all candidate peers.
     * Respects the maximum active chunk limit per peer and round-robin fairness.
     *
     * @param chunkManager the chunk manager tracking file chunk states
     * @param peers collection of source peer states
     * @return list of new chunk assignments
     */
    public List<ChunkAssignment> scheduleNextAssignments(ChunkManager chunkManager, Collection<PeerDownloadState> peers) {
        Objects.requireNonNull(chunkManager, "chunkManager cannot be null");
        if (peers == null || peers.isEmpty()) {
            return Collections.emptyList();
        }

        List<ChunkAssignment> assignments = new ArrayList<>();

        synchronized (lock) {
            // Filter eligible peers: connected and available
            List<PeerDownloadState> eligiblePeers = new ArrayList<>();
            for (PeerDownloadState p : peers) {
                if (p.isConnected() && p.isAvailable()) {
                    eligiblePeers.add(p);
                }
            }

            if (eligiblePeers.isEmpty()) {
                return assignments;
            }

            int peerCount = eligiblePeers.size();
            boolean assignedAny = true;

            while (assignedAny) {
                assignedAny = false;

                // Attempt to assign one chunk per round across eligible peers
                for (int i = 0; i < peerCount; i++) {
                    int idx = (roundRobinIndex + i) % peerCount;
                    PeerDownloadState peer = eligiblePeers.get(idx);

                    if (peer.getActiveChunks() < maxActiveChunksPerPeer) {
                        int nextChunk = chunkManager.getNextAvailableChunk();
                        if (nextChunk < 0) {
                            // No more missing chunks available!
                            return assignments;
                        }

                        boolean marked = chunkManager.markRequested(nextChunk, peer.getPeerId());
                        if (marked) {
                            peer.incrementActiveChunks();
                            assignments.add(new ChunkAssignment(peer.getPeerId(), nextChunk));
                            roundRobinIndex = (idx + 1) % peerCount;
                            assignedAny = true;
                        }
                    }
                }
            }
        }

        return assignments;
    }

    /**
     * Handles peer disconnection or unrecoverable error by marking the peer disconnected,
     * resetting active chunks, and releasing all assigned uncompleted chunks back to MISSING state.
     *
     * @param peerId the disconnected peer ID
     * @param chunkManager the chunk manager
     * @param peerState optional peer state model
     * @return list of chunk indices returned to MISSING state
     */
    public List<Integer> handlePeerDisconnected(String peerId, ChunkManager chunkManager, PeerDownloadState peerState) {
        if (peerId == null || chunkManager == null) {
            return Collections.emptyList();
        }

        synchronized (lock) {
            if (peerState != null) {
                peerState.setConnected(false);
                peerState.resetActiveChunks();
            }
            return chunkManager.releasePeerChunks(peerId);
        }
    }

    /**
     * Handles completion of a chunk by a peer.
     */
    public void handleChunkCompleted(int chunkIndex, String peerId, ChunkManager chunkManager, PeerDownloadState peerState) {
        synchronized (lock) {
            if (chunkManager != null) {
                chunkManager.markCompleted(chunkIndex, peerId);
            }
            if (peerState != null) {
                peerState.incrementCompletedChunks();
            }
        }
    }

    /**
     * Handles failure of a chunk by a peer, resetting the chunk to MISSING.
     */
    public void handleChunkFailed(int chunkIndex, String peerId, ChunkManager chunkManager, PeerDownloadState peerState) {
        synchronized (lock) {
            if (chunkManager != null) {
                chunkManager.markFailed(chunkIndex, peerId);
            }
            if (peerState != null) {
                peerState.incrementFailedChunks();
            }
        }
    }
}
