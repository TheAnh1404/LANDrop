package com.landrop.transfer;

/**
 * Event listener interface for multi-peer download progress and state transitions.
 */
public interface MultiPeerDownloadListener {

    default void onStatusChanged(MultiPeerDownload download, MultiPeerDownloadStatus oldStatus, MultiPeerDownloadStatus newStatus) {}

    default void onPeerDiscovered(MultiPeerDownload download, PeerDownloadState peer) {}

    default void onChunkCompleted(MultiPeerDownload download, int chunkIndex, String peerId, int completed, int total) {}

    default void onProgress(MultiPeerDownload download, long transferredBytes, long totalBytes, double speedBytesPerSec, String etaString) {}

    default void onPeerFailed(MultiPeerDownload download, String peerId, String reason) {}

    default void onPeerReconnected(MultiPeerDownload download, String peerId) {}

    default void onVerifying(MultiPeerDownload download) {}

    default void onCompleted(MultiPeerDownload download) {}

    default void onFailed(MultiPeerDownload download, String reason) {}
}
