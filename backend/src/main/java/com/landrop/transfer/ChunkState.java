package com.landrop.transfer;

/**
 * Lifecycle state of an individual file chunk in a multi-peer file transfer.
 */
public enum ChunkState {
    /**
     * Chunk has not yet been requested or received, and is available for scheduling.
     */
    MISSING,

    /**
     * Chunk has been assigned to a peer and a request has been dispatched.
     */
    REQUESTED,

    /**
     * Chunk data is currently in transit / being streamed from the peer.
     */
    TRANSFERRING,

    /**
     * Chunk data was successfully received, validated, and written to disk.
     */
    COMPLETED,

    /**
     * Chunk download failed or timed out and will be reset to MISSING for reassignment.
     */
    FAILED
}
