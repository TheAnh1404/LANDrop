package com.landrop.transfer;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe model representing an active, pending, or completed P2P file transfer.
 */
public class FileTransfer {

    private final String transferId;
    private final String fileName;
    private final long fileSize;
    private final FileTransferDirection direction;
    private final String remotePeerId;
    private final String remotePeerName;
    private final long startTimeMs;

    private final AtomicLong transferredBytes = new AtomicLong(0);
    private final AtomicReference<FileTransferStatus> status;

    private volatile Path localPath;
    private volatile long endTimeMs = 0;
    private volatile double speedBytesPerSec = 0.0;
    private volatile String errorMessage = "";

    public FileTransfer(
            String transferId,
            String fileName,
            long fileSize,
            FileTransferDirection direction,
            String remotePeerId,
            String remotePeerName,
            Path localPath) {

        this.transferId = Objects.requireNonNull(transferId, "transferId cannot be null");
        this.fileName = FileTransferUtils.sanitizeFilename(fileName);
        this.fileSize = Math.max(0, fileSize);
        this.direction = Objects.requireNonNull(direction, "direction cannot be null");
        this.remotePeerId = remotePeerId != null ? remotePeerId : "";
        this.remotePeerName = remotePeerName != null && !remotePeerName.isBlank() ? remotePeerName : "Peer";
        this.localPath = localPath;
        this.startTimeMs = System.currentTimeMillis();
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
