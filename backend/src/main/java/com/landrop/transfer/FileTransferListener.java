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
     * Called when the transfer fails due to network, disk I/O, or protocol errors.
     *
     * @param transfer the failed transfer
     * @param reason error description
     */
    default void onTransferFailed(FileTransfer transfer, String reason) {}
}
