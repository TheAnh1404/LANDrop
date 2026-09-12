package com.landrop.transfer;

import com.landrop.discovery.PeerInfo;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks the state, progress, and performance metrics of an individual source peer
 * participating in a multi-peer download session.
 */
public class PeerDownloadState {

    private final String peerId;
    private final String peerDisplayName;
    private final PeerInfo peerInfo;

    private volatile boolean available = true;
    private volatile boolean connected = true;
    private volatile long lastActivity = System.currentTimeMillis();

    private final AtomicInteger activeChunks = new AtomicInteger(0);
    private final AtomicInteger completedChunks = new AtomicInteger(0);
    private final AtomicInteger failedChunks = new AtomicInteger(0);
    private final AtomicInteger assignedChunkCount = new AtomicInteger(0);
    private final AtomicLong bytesReceived = new AtomicLong(0L);

    public PeerDownloadState(String peerId, String peerDisplayName, PeerInfo peerInfo) {
        this.peerId = Objects.requireNonNull(peerId, "peerId cannot be null");
        this.peerDisplayName = peerDisplayName != null ? peerDisplayName : peerId;
        this.peerInfo = peerInfo;
    }

    public static PeerDownloadState fromPeerInfo(PeerInfo peerInfo) {
        Objects.requireNonNull(peerInfo, "peerInfo cannot be null");
        return new PeerDownloadState(peerInfo.getPeerId(), peerInfo.getDisplayName(), peerInfo);
    }

    public String getPeerId() {
        return peerId;
    }

    public String getPeerDisplayName() {
        return peerDisplayName;
    }

    public PeerInfo getPeerInfo() {
        return peerInfo;
    }

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public boolean isConnected() {
        return connected;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
        recordActivity();
    }

    public long getLastActivity() {
        return lastActivity;
    }

    public void recordActivity() {
        this.lastActivity = System.currentTimeMillis();
    }

    public int getActiveChunks() {
        return activeChunks.get();
    }

    public int incrementActiveChunks() {
        recordActivity();
        assignedChunkCount.incrementAndGet();
        return activeChunks.incrementAndGet();
    }

    public int decrementActiveChunks() {
        recordActivity();
        return activeChunks.updateAndGet(curr -> Math.max(0, curr - 1));
    }

    public void resetActiveChunks() {
        activeChunks.set(0);
    }

    public int getCompletedChunks() {
        return completedChunks.get();
    }

    public int incrementCompletedChunks() {
        recordActivity();
        decrementActiveChunks();
        return completedChunks.incrementAndGet();
    }

    public int getFailedChunks() {
        return failedChunks.get();
    }

    public int incrementFailedChunks() {
        recordActivity();
        decrementActiveChunks();
        return failedChunks.incrementAndGet();
    }

    public int getAssignedChunkCount() {
        return assignedChunkCount.get();
    }

    public long getBytesReceived() {
        return bytesReceived.get();
    }

    public void addBytesReceived(long bytes) {
        if (bytes > 0) {
            bytesReceived.addAndGet(bytes);
            recordActivity();
        }
    }

    @Override
    public String toString() {
        return "PeerDownloadState{" +
                "peerId='" + peerId + '\'' +
                ", name='" + peerDisplayName + '\'' +
                ", connected=" + connected +
                ", active=" + activeChunks.get() +
                ", completed=" + completedChunks.get() +
                ", failed=" + failedChunks.get() +
                '}';
    }
}
