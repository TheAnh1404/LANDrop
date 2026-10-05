package com.landrop.transfer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe model representing an active, pending, or completed P2P file transfer.
 * Extended in Phase 7 to support chunk tracking and SHA-256 integrity verification.
 */
public class FileTransfer {

    private final String transferId;
    private final String fileName;
    private final long fileSize;
    private final FileTransferDirection direction;
    private final String remotePeerId;
    private final String remotePeerName;
    private final long startTimeMs;
    private final int chunkSize;
    private final int totalChunks;

    private final AtomicLong transferredBytes = new AtomicLong(0);
    private final AtomicInteger completedChunks = new AtomicInteger(0);
    private final AtomicReference<FileTransferStatus> status;

    private volatile Path localPath;
    private volatile int currentChunk = 0;
    private volatile String fileSha256 = "";
    private volatile String verifiedSha256 = "";
    private volatile boolean hashVerified = false;
    private volatile long endTimeMs = 0;
    private volatile double speedBytesPerSec = 0.0;
    private volatile String errorMessage = "";

    private final BitSet completedChunksBitSet = new BitSet();
    private volatile int reconnectAttempt = 0;
    private volatile int maxReconnectAttempts = 5;
    private volatile int chunkRetryIndex = -1;
    private volatile int chunkRetryAttempt = 0;
    private volatile int maxChunkRetries = 3;

    public FileTransfer(
            String transferId,
            String fileName,
            long fileSize,
            FileTransferDirection direction,
            String remotePeerId,
            String remotePeerName,
            Path localPath) {
        this(transferId, fileName, fileSize, direction, remotePeerId, remotePeerName, localPath,
                1024 * 1024, FileChunk.calculateTotalChunks(fileSize, 1024 * 1024), "");
    }

    public FileTransfer(
            String transferId,
            String fileName,
            long fileSize,
            FileTransferDirection direction,
            String remotePeerId,
            String remotePeerName,
            Path localPath,
            int chunkSize,
            int totalChunks,
            String fileSha256) {

        this.transferId = Objects.requireNonNull(transferId, "transferId cannot be null");
        this.fileName = FileTransferUtils.sanitizeFilename(fileName);
        this.fileSize = Math.max(0, fileSize);
        this.direction = Objects.requireNonNull(direction, "direction cannot be null");
        this.remotePeerId = remotePeerId != null ? remotePeerId : "";
        this.remotePeerName = remotePeerName != null && !remotePeerName.isBlank() ? remotePeerName : "Peer";
        this.localPath = localPath;
        this.startTimeMs = System.currentTimeMillis();
        this.chunkSize = chunkSize > 0 ? chunkSize : 1024 * 1024;
        this.totalChunks = totalChunks >= 0 ? totalChunks : FileChunk.calculateTotalChunks(this.fileSize, this.chunkSize);
        this.fileSha256 = fileSha256 != null ? fileSha256.trim().toLowerCase() : "";
        this.status = new AtomicReference<>(FileTransferStatus.PENDING);
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

    public FileTransferDirection getDirection() {
        return direction;
    }

    public String getRemotePeerId() {
        return remotePeerId;
    }

    public String getRemotePeerName() {
        return remotePeerName;
    }

    public Path getLocalPath() {
        return localPath;
    }

    public Path getDestinationPath() {
        return localPath;
    }

    public void setLocalPath(Path localPath) {
        this.localPath = localPath;
    }

    public long getStartTimeMs() {
        return startTimeMs;
    }

    public long getEndTimeMs() {
        return endTimeMs;
    }

    public void setEndTimeMs(long endTimeMs) {
        this.endTimeMs = endTimeMs;
    }

    public long getTransferredBytes() {
        return transferredBytes.get();
    }

    public void setTransferredBytes(long bytes) {
        this.transferredBytes.set(Math.min(fileSize, Math.max(0, bytes)));
    }

    public void addTransferredBytes(long delta) {
        this.transferredBytes.addAndGet(delta);
    }

    public FileTransferStatus getStatus() {
        return status.get();
    }

    public boolean transitionStatus(FileTransferStatus expected, FileTransferStatus newStatus) {
        boolean changed = status.compareAndSet(expected, newStatus);
        if (changed && newStatus.isTerminal()) {
            this.endTimeMs = System.currentTimeMillis();
        }
        return changed;
    }

    public void setStatus(FileTransferStatus newStatus) {
        this.status.set(newStatus);
        if (newStatus.isTerminal()) {
            this.endTimeMs = System.currentTimeMillis();
        }
    }

    public double getSpeedBytesPerSec() {
        return speedBytesPerSec;
    }

    public void setSpeedBytesPerSec(double speedBytesPerSec) {
        this.speedBytesPerSec = Math.max(0.0, speedBytesPerSec);
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage != null ? errorMessage : "";
    }

    /**
     * Calculates current progress ratio between 0.0 and 1.0.
     *
     * @return progress fraction
     */
    public double getProgress() {
        if (fileSize <= 0) {
            return status.get() == FileTransferStatus.COMPLETED ? 1.0 : 0.0;
        }
        return Math.min(1.0, Math.max(0.0, (double) transferredBytes.get() / fileSize));
    }

    /**
     * Calculates progress percentage from 0 to 100.
     *
     * @return percentage integer
     */
    public int getProgressPercent() {
        return (int) Math.round(getProgress() * 100.0);
    }

    public String getFormattedSize() {
        return FileTransferUtils.formatFileSize(fileSize);
    }

    public String getFormattedTransferred() {
        return FileTransferUtils.formatFileSize(transferredBytes.get());
    }

    public String getFormattedSpeed() {
        return FileTransferUtils.formatSpeed(speedBytesPerSec);
    }

    public boolean isTerminal() {
        return status.get().isTerminal();
    }

    public boolean isCompleted() {
        return status.get() == FileTransferStatus.COMPLETED;
    }

    public boolean isTransferring() {
        return status.get() == FileTransferStatus.TRANSFERRING;
    }

    public boolean isVerifying() {
        return status.get() == FileTransferStatus.VERIFYING;
    }

    public boolean isInterrupted() {
        return status.get() == FileTransferStatus.INTERRUPTED;
    }

    public boolean isReconnecting() {
        return status.get() == FileTransferStatus.RECONNECTING;
    }

    public boolean isResuming() {
        return status.get() == FileTransferStatus.RESUMING;
    }

    public synchronized void markChunkCompleted(int chunkIndex) {
        if (chunkIndex >= 0) {
            completedChunksBitSet.set(chunkIndex);
            this.completedChunks.set(completedChunksBitSet.cardinality());
        }
    }

    public synchronized boolean isChunkCompleted(int chunkIndex) {
        return chunkIndex >= 0 && completedChunksBitSet.get(chunkIndex);
    }

    public synchronized int getCompletedChunkCount() {
        return completedChunksBitSet.cardinality();
    }

    public synchronized int getFirstMissingChunk() {
        int nextClear = completedChunksBitSet.nextClearBit(0);
        return nextClear < totalChunks ? nextClear : -1;
    }

    public synchronized List<Integer> getMissingChunks() {
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < totalChunks; i++) {
            if (!completedChunksBitSet.get(i)) {
                missing.add(i);
            }
        }
        return missing;
    }

    public synchronized BitSet getCompletedChunksBitSet() {
        return (BitSet) completedChunksBitSet.clone();
    }

    public synchronized void setCompletedChunksBitSet(BitSet bitSet) {
        completedChunksBitSet.clear();
        if (bitSet != null) {
            completedChunksBitSet.or(bitSet);
        }
        this.completedChunks.set(completedChunksBitSet.cardinality());
    }

    public int getReconnectAttempt() {
        return reconnectAttempt;
    }

    public void setReconnectAttempt(int reconnectAttempt) {
        this.reconnectAttempt = reconnectAttempt;
    }

    public int getMaxReconnectAttempts() {
        return maxReconnectAttempts;
    }

    public void setMaxReconnectAttempts(int maxReconnectAttempts) {
        this.maxReconnectAttempts = maxReconnectAttempts;
    }

    public int getChunkRetryIndex() {
        return chunkRetryIndex;
    }

    public void setChunkRetryIndex(int chunkRetryIndex) {
        this.chunkRetryIndex = chunkRetryIndex;
    }

    public int getChunkRetryAttempt() {
        return chunkRetryAttempt;
    }

    public void setChunkRetryAttempt(int chunkRetryAttempt) {
        this.chunkRetryAttempt = chunkRetryAttempt;
    }

    public int getMaxChunkRetries() {
        return maxChunkRetries;
    }

    public void setMaxChunkRetries(int maxChunkRetries) {
        this.maxChunkRetries = maxChunkRetries;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public int getCompletedChunks() {
        return completedChunks.get();
    }

    public void setCompletedChunks(int count) {
        this.completedChunks.set(Math.min(totalChunks, Math.max(0, count)));
    }

    public int incrementCompletedChunks() {
        return this.completedChunks.incrementAndGet();
    }

    public int getCurrentChunk() {
        return currentChunk;
    }

    public void setCurrentChunk(int currentChunk) {
        this.currentChunk = currentChunk;
    }

    public String getFileSha256() {
        return fileSha256;
    }

    public void setFileSha256(String fileSha256) {
        this.fileSha256 = fileSha256 != null ? fileSha256.trim().toLowerCase() : "";
    }

    public String getVerifiedSha256() {
        return verifiedSha256;
    }

    public void setVerifiedSha256(String verifiedSha256) {
        this.verifiedSha256 = verifiedSha256 != null ? verifiedSha256.trim().toLowerCase() : "";
    }

    public boolean isHashVerified() {
        return hashVerified;
    }

    public void setHashVerified(boolean hashVerified) {
        this.hashVerified = hashVerified;
    }

    public String getShortenedSha256() {
        String hash = !verifiedSha256.isBlank() ? verifiedSha256 : fileSha256;
        return FileTransferUtils.shortenHash(hash);
    }

    public String getChunkProgressText() {
        if (isReconnecting()) {
            return "Reconnecting... (Attempt " + reconnectAttempt + " / " + maxReconnectAttempts + ")";
        }
        if (isResuming()) {
            int missing = getFirstMissingChunk();
            int from = missing >= 0 ? missing : 0;
            return "Resuming from chunk " + from + " / " + totalChunks;
        }
        if (chunkRetryAttempt > 0 && chunkRetryIndex >= 0) {
            return "Retrying chunk " + chunkRetryIndex + "... (" + chunkRetryAttempt + " / " + maxChunkRetries + ")";
        }
        if (totalChunks <= 0) {
            return "0 / 0 chunks";
        }
        return "Chunk " + Math.min(totalChunks, completedChunks.get()) + " / " + totalChunks;
    }

    @Override
    public String toString() {
        return "FileTransfer{" +
                "id='" + transferId + '\'' +
                ", file='" + fileName + '\'' +
                ", size=" + getFormattedSize() +
                ", transferred=" + getFormattedTransferred() +
                " (" + getProgressPercent() + "%)" +
                ", direction=" + direction +
                ", status=" + status.get() +
                ", peer=" + remotePeerName +
                '}';
    }
}
