package com.landrop.transfer;

/**
 * Event listener interface for P2P file transfer lifecycle notifications.
 */
public interface FileTransferListener {

    /**
     * Called on receiving peer when an incoming file offer is received.
     *
     * @param transfer the pending incoming transfer
     */
    default void onTransferOffered(FileTransfer transfer) {}

    /**
     * Called when transfer has been accepted and streaming begins.
     *
     * @param transfer the active transfer
     */
    default void onTransferStarted(FileTransfer transfer) {}

    /**
     * Called periodically as file bytes are transferred.
     *
     * @param transfer the active transfer
     * @param transferredBytes bytes transferred so far
     * @param totalBytes total expected file size in bytes
     * @param speedBytesPerSec current transfer rate
     */
    default void onTransferProgress(FileTransfer transfer, long transferredBytes, long totalBytes, double speedBytesPerSec) {}

    /**
     * Called when all chunks have been received and SHA-256 integrity verification begins.
     *
     * @param transfer the transfer entering verification
     */
    default void onTransferVerifying(FileTransfer transfer) {}

    /**
     * Called when the file transfer successfully completes and destination is verified.
     *
     * @param transfer the completed transfer
     */
    default void onTransferCompleted(FileTransfer transfer) {}

    /**
     * Called when the remote peer rejects the transfer offer.
     *
     * @param transfer the rejected transfer
     * @param reason optional rejection explanation
     */
    default void onTransferRejected(FileTransfer transfer, String reason) {}

    /**
     * Called when an active transfer is interrupted by network disconnection or socket error.
     *
     * @param transfer the interrupted transfer
     * @param reason explanation of interruption
     */
    default void onTransferInterrupted(FileTransfer transfer, String reason) {}

    /**
     * Called when the service attempts to reconnect to the remote peer.
     *
     * @param transfer the transfer being reconnected
     * @param attempt current attempt number (1-based)
     * @param maxAttempts maximum allowed reconnect attempts
     */
    default void onTransferReconnecting(FileTransfer transfer, int attempt, int maxAttempts) {}

    /**
     * Called when reconnection succeeds and resume handshake begins.
     *
     * @param transfer the transfer resuming
     * @param fromChunk the first missing chunk index resuming from
     * @param totalChunks total number of chunks
     */
    default void onTransferResuming(FileTransfer transfer, int fromChunk, int totalChunks) {}

    /**
     * Called when an individual chunk request times out or fails and is retried.
     *
     * @param transfer the active transfer
     * @param chunkIndex the index of the chunk being retried
     * @param attempt current retry attempt (1-based)
     * @param maxAttempts maximum chunk retries
     */
    default void onTransferChunkRetrying(FileTransfer transfer, int chunkIndex, int attempt, int maxAttempts) {}

    /**
     * Called when the transfer fails due to network, disk I/O, or protocol errors.
     *
     * @param transfer the failed transfer
     * @param reason error description
     */
    default void onTransferFailed(FileTransfer transfer, String reason) {}
}
