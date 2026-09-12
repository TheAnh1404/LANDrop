package com.landrop.transfer;

/**
 * Status enumeration for a logical multi-peer file download session.
 */
public enum MultiPeerDownloadStatus {
    /**
     * Download has been initiated but not yet scheduled.
     */
    PENDING,

    /**
     * Querying known peers via FILE_QUERY to discover who has the exact file available.
     */
    DISCOVERING_PEERS,

    /**
     * Establishing connections, initializing partial file, and preparing workers.
     */
    PREPARING,

    /**
     * Actively downloading chunks concurrently from one or more peers.
     */
    DOWNLOADING,

    /**
     * All chunks received; calculating and verifying streaming SHA-256 integrity.
     */
    VERIFYING,

    /**
     * Integrity verified and file atomically moved to final destination.
     */
    COMPLETED,

    /**
     * Download failed (e.g. no peers available, integrity mismatch, disk error).
     */
    FAILED,

    /**
     * Transfer was cancelled by user.
     */
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    public String getLabel() {
        return switch (this) {
            case PENDING -> "Pending";
            case DISCOVERING_PEERS -> "Discovering";
            case PREPARING -> "Preparing";
            case DOWNLOADING -> "Downloading";
            case VERIFYING -> "Verifying";
            case COMPLETED -> "Completed";
            case FAILED -> "Failed";
            case CANCELLED -> "Cancelled";
        };
    }
}
