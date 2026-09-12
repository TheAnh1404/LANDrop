package com.landrop.transfer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Thread-safe manager tracking the lifecycle state, peer assignments,
 * and completion progress of all chunks for a file download.
 */
public class ChunkManager {

    private final int totalChunks;
    private final ChunkState[] chunkStates;
    private final BitSet completedChunks;
    private final Map<Integer, String> assignedPeers;
    private final Object lock = new Object();

    public ChunkManager(int totalChunks) {
        if (totalChunks < 0) {
            throw new IllegalArgumentException("totalChunks cannot be negative: " + totalChunks);
        }
        this.totalChunks = totalChunks;
        this.chunkStates = new ChunkState[totalChunks];
        Arrays.fill(this.chunkStates, ChunkState.MISSING);
        this.completedChunks = new BitSet(totalChunks);
        this.assignedPeers = new HashMap<>();
    }

    /**
     * Constructs a ChunkManager pre-initialized with already completed chunks (e.g. for resume).
     *
     * @param totalChunks total number of chunks
     * @param initialCompletedBitSet bitmap of already completed chunks
     */
    public ChunkManager(int totalChunks, BitSet initialCompletedBitSet) {
        this(totalChunks);
        if (initialCompletedBitSet != null) {
            synchronized (lock) {
                for (int i = 0; i < totalChunks; i++) {
                    if (initialCompletedBitSet.get(i)) {
                        chunkStates[i] = ChunkState.COMPLETED;
                        completedChunks.set(i);
                    }
                }
            }
        }
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    /**
     * Marks a chunk as requested by an active peer.
     *
     * @param chunkIndex 0-based chunk index
     * @param peerId the unique identifier of the requesting peer
     * @return true if successfully transitioned to REQUESTED, false if already completed or assigned
     */
    public boolean markRequested(int chunkIndex, String peerId) {
        checkIndex(chunkIndex);
        synchronized (lock) {
            if (chunkStates[chunkIndex] == ChunkState.COMPLETED) {
                return false;
            }
            if (chunkStates[chunkIndex] == ChunkState.REQUESTED || chunkStates[chunkIndex] == ChunkState.TRANSFERRING) {
                // If already assigned to the same peer, allow re-requesting
                if (!Objects.equals(assignedPeers.get(chunkIndex), peerId)) {
                    return false; // Assigned to another active peer!
                }
            }
            chunkStates[chunkIndex] = ChunkState.REQUESTED;
            assignedPeers.put(chunkIndex, peerId);
            return true;
        }
    }

    /**
     * Overload markRequested for single-argument compatibility.
     */
    public boolean markRequested(int chunkIndex) {
        return markRequested(chunkIndex, "default");
    }

    /**
     * Marks a chunk as actively transferring data.
     *
     * @param chunkIndex 0-based chunk index
     * @return true if transitioned to TRANSFERRING
     */
    public boolean markTransferring(int chunkIndex) {
        checkIndex(chunkIndex);
        synchronized (lock) {
            if (chunkStates[chunkIndex] == ChunkState.COMPLETED) {
                return false;
            }
            chunkStates[chunkIndex] = ChunkState.TRANSFERRING;
            return true;
        }
    }

    /**
     * Marks a chunk as successfully received and verified on disk.
     *
     * @param chunkIndex 0-based chunk index
     * @param peerId the peer that delivered this chunk
     * @return true if newly completed, false if it was already marked COMPLETED
     */
    public boolean markCompleted(int chunkIndex, String peerId) {
        checkIndex(chunkIndex);
        synchronized (lock) {
            if (completedChunks.get(chunkIndex)) {
                return false; // Duplicate completion protection
            }
            chunkStates[chunkIndex] = ChunkState.COMPLETED;
            completedChunks.set(chunkIndex);
            if (peerId != null) {
                assignedPeers.put(chunkIndex, peerId);
            }
            return true;
        }
    }

    public boolean markCompleted(int chunkIndex) {
        return markCompleted(chunkIndex, null);
    }

    /**
     * Marks a chunk as failed and resets it to MISSING so it can be reassigned.
     *
     * @param chunkIndex 0-based chunk index
     * @param peerId optional peer ID reporting failure
     * @return true if marked failed and reset
     */
    public boolean markFailed(int chunkIndex, String peerId) {
        checkIndex(chunkIndex);
        synchronized (lock) {
            if (completedChunks.get(chunkIndex)) {
                return false; // Cannot fail an already completed chunk
            }
            chunkStates[chunkIndex] = ChunkState.MISSING;
            assignedPeers.remove(chunkIndex);
            return true;
        }
    }

    public boolean markFailed(int chunkIndex) {
        return markFailed(chunkIndex, null);
    }

    /**
     * Releases all uncompleted chunks assigned to a peer, returning them to MISSING state.
     * Used when a peer disconnects or encounters an unrecoverable error.
     *
     * @param peerId the peer ID whose chunks should be released
     * @return list of chunk indices that were released back to MISSING
     */
    public List<Integer> releasePeerChunks(String peerId) {
        if (peerId == null) return Collections.emptyList();
        List<Integer> released = new ArrayList<>();
        synchronized (lock) {
            for (int i = 0; i < totalChunks; i++) {
                if (!completedChunks.get(i) && Objects.equals(assignedPeers.get(i), peerId)) {
                    chunkStates[i] = ChunkState.MISSING;
                    assignedPeers.remove(i);
                    released.add(i);
                }
            }
        }
        return released;
    }

    public boolean isCompleted(int chunkIndex) {
        checkIndex(chunkIndex);
        synchronized (lock) {
            return completedChunks.get(chunkIndex);
        }
    }

    public boolean isInProgress(int chunkIndex) {
        checkIndex(chunkIndex);
        synchronized (lock) {
            return chunkStates[chunkIndex] == ChunkState.REQUESTED || chunkStates[chunkIndex] == ChunkState.TRANSFERRING;
        }
    }

    public ChunkState getChunkState(int chunkIndex) {
        checkIndex(chunkIndex);
        synchronized (lock) {
            return chunkStates[chunkIndex];
        }
    }

    public String getAssignedPeer(int chunkIndex) {
        checkIndex(chunkIndex);
        synchronized (lock) {
            return assignedPeers.get(chunkIndex);
        }
    }

    public int getCompletedChunkCount() {
        synchronized (lock) {
            return completedChunks.cardinality();
        }
    }

    public int getRemainingChunkCount() {
        synchronized (lock) {
            return totalChunks - completedChunks.cardinality();
        }
    }

    /**
     * Returns the index of the first uncompleted chunk (regardless of whether it's requested).
     *
     * @return 0-based chunk index, or -1 if all chunks are completed
     */
    public int getFirstMissingChunk() {
        synchronized (lock) {
            int next = completedChunks.nextClearBit(0);
            return next < totalChunks ? next : -1;
        }
    }

    /**
     * Finds the next available unassigned chunk in MISSING state ready for scheduling.
     *
     * @return 0-based chunk index, or -1 if no chunk is in MISSING state
     */
    public int getNextAvailableChunk() {
        synchronized (lock) {
            for (int i = 0; i < totalChunks; i++) {
                if (chunkStates[i] == ChunkState.MISSING) {
                    return i;
                }
            }
            return -1;
        }
    }

    /**
     * Returns a snapshot list of all chunk indices currently in MISSING state.
     */
    public List<Integer> getMissingChunks() {
        List<Integer> list = new ArrayList<>();
        synchronized (lock) {
            for (int i = 0; i < totalChunks; i++) {
                if (chunkStates[i] == ChunkState.MISSING) {
                    list.add(i);
                }
            }
        }
        return list;
    }

    public boolean isComplete() {
        if (totalChunks == 0) return true;
        synchronized (lock) {
            return completedChunks.cardinality() >= totalChunks;
        }
    }

    public BitSet getCompletedChunksBitSet() {
        synchronized (lock) {
            return (BitSet) completedChunks.clone();
        }
    }

    public double getProgressPercentage() {
        if (totalChunks == 0) return 100.0;
        synchronized (lock) {
            return (completedChunks.cardinality() * 100.0) / totalChunks;
        }
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= totalChunks) {
            throw new IndexOutOfBoundsException("Chunk index out of bounds: " + index + " (total: " + totalChunks + ")");
        }
    }

    @Override
    public String toString() {
        synchronized (lock) {
            return "ChunkManager{" +
                    "total=" + totalChunks +
                    ", completed=" + completedChunks.cardinality() +
                    ", progress=" + String.format("%.1f", getProgressPercentage()) + "%" +
                    '}';
        }
    }
}
