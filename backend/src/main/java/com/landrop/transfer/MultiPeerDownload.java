package com.landrop.transfer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Orchestrates a logical multi-peer file download session across multiple source peers.
 * Tracks overall file progress, peer assignments, transfer speed, and ETA.
 */
public class MultiPeerDownload {

    private final String transferId;
    private final String fileName;
    private final long fileSize;
    private final int chunkSize;
    private final int totalChunks;
    private final String expectedSha256;

    private final Path destinationPath;
    private final Path partialFilePath;

    private final ChunkManager chunkManager;
    private final ChunkScheduler chunkScheduler;
    private final Map<String, PeerDownloadState> sourcePeers = new ConcurrentHashMap<>();
    private final List<MultiPeerDownloadListener> listeners = new CopyOnWriteArrayList<>();

    private volatile MultiPeerDownloadStatus status = MultiPeerDownloadStatus.PENDING;
    private volatile String errorMessage;
    private volatile boolean hashVerified = false;
    private volatile String verifiedSha256;

    private final AtomicLong transferredBytes = new AtomicLong(0L);
    private volatile double speedBytesPerSec = 0.0;
    private volatile String etaString = "ETA --";

    // Speed calculation sliding window
    private volatile long lastSpeedCalcTime = System.currentTimeMillis();
    private volatile long lastSpeedCalcBytes = 0L;

    public MultiPeerDownload(
            String transferId,
            String fileName,
            long fileSize,
            int chunkSize,
            String expectedSha256,
            Path destinationPath) {
        this.transferId = Objects.requireNonNull(transferId, "transferId cannot be null");
        this.fileName = Objects.requireNonNull(fileName, "fileName cannot be null");
        this.fileSize = fileSize;
        this.chunkSize = chunkSize > 0 ? chunkSize : FileTransferService.DEFAULT_CHUNK_SIZE;
        this.totalChunks = FileChunk.calculateTotalChunks(fileSize, this.chunkSize);
        this.expectedSha256 = Objects.requireNonNull(expectedSha256, "expectedSha256 cannot be null");
        this.destinationPath = Objects.requireNonNull(destinationPath, "destinationPath cannot be null");
        this.partialFilePath = destinationPath.resolveSibling(fileName + ".landrop.part");

        this.chunkManager = new ChunkManager(this.totalChunks);
        this.chunkScheduler = new ChunkScheduler();
    }

    public String getTransferId() {
        return transferId;
    }

    public String getFileName() {
        return fileName;
    }

    public long getFileSize() {
        return fileSize;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public String getExpectedSha256() {
        return expectedSha256;
    }

    public Path getDestinationPath() {
        return destinationPath;
    }

    public Path getPartialFilePath() {
        return partialFilePath;
    }

    public ChunkManager getChunkManager() {
        return chunkManager;
    }

    public ChunkScheduler getChunkScheduler() {
        return chunkScheduler;
    }

    public MultiPeerDownloadStatus getStatus() {
        return status;
    }

    public void setStatus(MultiPeerDownloadStatus newStatus) {
        MultiPeerDownloadStatus oldStatus = this.status;
        this.status = newStatus;
        if (oldStatus != newStatus) {
            for (MultiPeerDownloadListener l : listeners) {
                try {
                    l.onStatusChanged(this, oldStatus, newStatus);
                    if (newStatus == MultiPeerDownloadStatus.VERIFYING) {
                        l.onVerifying(this);
                    } else if (newStatus == MultiPeerDownloadStatus.COMPLETED) {
                        l.onCompleted(this);
                    } else if (newStatus == MultiPeerDownloadStatus.FAILED) {
                        l.onFailed(this, errorMessage);
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    public void notifyChunkCompleted(int chunkIndex, String peerId, int completed, int total) {
        for (MultiPeerDownloadListener l : listeners) {
            try {
                l.onChunkCompleted(this, chunkIndex, peerId, completed, total);
            } catch (Exception ignored) {
            }
        }
    }

    public void notifyPeerFailed(String peerId, String reason) {
        for (MultiPeerDownloadListener l : listeners) {
            try {
                l.onPeerFailed(this, peerId, reason);
            } catch (Exception ignored) {
            }
        }
    }

    public void notifyPeerReconnected(String peerId) {
        for (MultiPeerDownloadListener l : listeners) {
            try {
                l.onPeerReconnected(this, peerId);
            } catch (Exception ignored) {
            }
        }
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public boolean isHashVerified() {
        return hashVerified;
    }

    public void setHashVerified(boolean hashVerified) {
        this.hashVerified = hashVerified;
    }

    public String getVerifiedSha256() {
        return verifiedSha256;
    }

    public void setVerifiedSha256(String verifiedSha256) {
        this.verifiedSha256 = verifiedSha256;
    }

    public long getTransferredBytes() {
        return transferredBytes.get();
    }

    public double getSpeedBytesPerSec() {
        return speedBytesPerSec;
    }

    public String getEtaString() {
        return etaString;
    }

    public double getProgressPercentage() {
        if (totalChunks == 0) return 100.0;
        return (chunkManager.getCompletedChunkCount() * 100.0) / totalChunks;
    }

    public int getCompletedChunkCount() {
        return chunkManager.getCompletedChunkCount();
    }

    public void registerSourcePeer(PeerDownloadState peerState) {
        if (peerState != null) {
            sourcePeers.put(peerState.getPeerId(), peerState);
            for (MultiPeerDownloadListener l : listeners) {
                try {
                    l.onPeerDiscovered(this, peerState);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public PeerDownloadState getSourcePeer(String peerId) {
        return sourcePeers.get(peerId);
    }

    public Collection<PeerDownloadState> getSourcePeers() {
        return Collections.unmodifiableCollection(sourcePeers.values());
    }

    public List<PeerDownloadState> getConnectedSourcePeers() {
        List<PeerDownloadState> list = new ArrayList<>();
        for (PeerDownloadState p : sourcePeers.values()) {
            if (p.isConnected() && p.isAvailable()) {
                list.add(p);
            }
        }
        return list;
    }

    /**
     * Updates byte transfer count and recalculates transfer speed and ETA.
     */
    public void recordBytesReceived(long bytes) {
        if (bytes <= 0) return;
        long total = transferredBytes.addAndGet(bytes);

        long now = System.currentTimeMillis();
        long elapsed = now - lastSpeedCalcTime;

        if (elapsed >= 500) { // update speed calculation every 500ms
            long bytesSince = total - lastSpeedCalcBytes;
            double currentSpeed = (bytesSince * 1000.0) / elapsed;
            // Smooth speed using moving average
            this.speedBytesPerSec = speedBytesPerSec <= 0 ? currentSpeed : (speedBytesPerSec * 0.7) + (currentSpeed * 0.3);
            this.lastSpeedCalcTime = now;
            this.lastSpeedCalcBytes = total;

            // Recalculate ETA
            long remainingBytes = Math.max(0L, fileSize - total);
            if (speedBytesPerSec > 1024 && remainingBytes > 0) {
                long remainingSec = (long) (remainingBytes / speedBytesPerSec);
                this.etaString = formatEta(remainingSec);
            } else if (remainingBytes == 0) {
                this.etaString = "ETA 00:00";
            } else {
                this.etaString = "ETA --";
            }

            for (MultiPeerDownloadListener l : listeners) {
                try {
                    l.onProgress(this, total, fileSize, speedBytesPerSec, etaString);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public static String formatEta(long totalSeconds) {
        if (totalSeconds < 0) return "ETA --";
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(java.util.Locale.US, "ETA %02d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format(java.util.Locale.US, "ETA %02d:%02d", minutes, seconds);
        }
    }

    public static String formatSpeed(double speedBytesPerSec) {
        if (speedBytesPerSec <= 0) return "0 B/s";
        if (speedBytesPerSec >= 1024 * 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1f GB/s", speedBytesPerSec / (1024.0 * 1024 * 1024));
        } else if (speedBytesPerSec >= 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1f MB/s", speedBytesPerSec / (1024.0 * 1024));
        } else if (speedBytesPerSec >= 1024) {
            return String.format(java.util.Locale.US, "%.1f KB/s", speedBytesPerSec / 1024.0);
        } else {
            return String.format(java.util.Locale.US, "%.0f B/s", speedBytesPerSec);
        }
    }

    public String getFormattedSize() {
        return FileTransferUtils.formatFileSize(fileSize);
    }

    public String getFormattedTransferred() {
        return FileTransferUtils.formatFileSize(transferredBytes.get());
    }

    public String getFormattedSpeed() {
        return formatSpeed(speedBytesPerSec);
    }

    public String getShortenedSha256() {
        String hash = (verifiedSha256 != null && !verifiedSha256.isBlank()) ? verifiedSha256 : expectedSha256;
        return FileTransferUtils.shortenHash(hash);
    }

    public void addListener(MultiPeerDownloadListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(MultiPeerDownloadListener listener) {
        listeners.remove(listener);
    }

    public List<MultiPeerDownloadListener> getListeners() {
        return listeners;
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }
}
