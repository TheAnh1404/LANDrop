package com.landrop.discovery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry maintaining currently active and registered peers.
 * Supports peer registration, presence discovery, heartbeat tracking, and stale peer cleanup.
 */
public class PeerRegistry {

    public static final long DEFAULT_TIMEOUT_MS = 15_000L;

    private final long timeoutMs;
    private final Map<String, PeerInfo> peers = new ConcurrentHashMap<>();

    /**
     * Record summarizing the outcome of a peer registration attempt.
     *
     * @param success whether registration succeeded
     * @param message human-readable status explanation
     */
    public record RegistrationResult(boolean success, String message) {}

    /**
     * Constructs a PeerRegistry with the default 15-second peer timeout.
     */
    public PeerRegistry() {
        this(DEFAULT_TIMEOUT_MS);
    }

    /**
     * Constructs a PeerRegistry with a custom peer timeout limit in milliseconds.
     *
     * @param timeoutMs timeout limit in milliseconds
     */
    public PeerRegistry(long timeoutMs) {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive: " + timeoutMs);
        }
        this.timeoutMs = timeoutMs;
    }

    /**
     * Registers a new peer or replaces a stale registration.
     * Rejects attempts to register a duplicate peer ID if that peer is currently active and online.
     *
     * @param peer the peer information to register
     * @return RegistrationResult indicating success or reason for failure
     */
    public synchronized RegistrationResult register(PeerInfo peer) {
        if (peer == null || !peer.isValid()) {
            return new RegistrationResult(false, "Invalid peer registration parameters");
        }

        long now = System.currentTimeMillis();
        PeerInfo existing = peers.get(peer.getPeerId());
        if (existing != null) {
            boolean isStale = (now - existing.getLastSeen() > timeoutMs) || existing.getStatus() == PeerStatus.OFFLINE;
            if (!isStale) {
                return new RegistrationResult(false, "Peer ID already registered and currently active");
            }
        }

        peer.setStatus(PeerStatus.ONLINE);
        peer.setLastSeen(now);
        peers.put(peer.getPeerId(), peer);
        return new RegistrationResult(true, "Registered successfully");
    }

    /**
     * Updates the lastSeen timestamp and presence status of a registered peer.
     *
     * @param peerId the unique peer identifier
     * @return true if peer was found and updated, false otherwise
     */
    public synchronized boolean updateHeartbeat(String peerId) {
        if (peerId == null || peerId.isBlank()) {
            return false;
        }
        PeerInfo peer = peers.get(peerId.trim());
        if (peer != null) {
            peer.setLastSeen(System.currentTimeMillis());
            peer.setStatus(PeerStatus.ONLINE);
            return true;
        }
        return false;
    }

    /**
     * Unregisters and removes a peer from the registry.
     *
     * @param peerId the unique peer identifier
     * @return the removed PeerInfo, or null if peer was not registered
     */
    public synchronized PeerInfo unregister(String peerId) {
        if (peerId == null || peerId.isBlank()) {
            return null;
        }
        return peers.remove(peerId.trim());
    }

    /**
     * Returns a list of all currently online and active peers, explicitly excluding the requester.
     *
     * @param excludePeerId the peer ID of the requesting peer to exclude from results
     * @return list of other active PeerInfo records
     */
    public List<PeerInfo> getPeerList(String excludePeerId) {
        List<PeerInfo> result = new ArrayList<>();
        long now = System.currentTimeMillis();
        String excluded = excludePeerId != null ? excludePeerId.trim() : "";

        for (PeerInfo peer : peers.values()) {
            if (peer.getStatus() == PeerStatus.ONLINE &&
                    (now - peer.getLastSeen() <= timeoutMs) &&
                    !peer.getPeerId().equals(excluded)) {
                result.add(peer);
            }
        }
        return result;
    }

    /**
     * Scans the registry and removes any peers that have not reported a heartbeat within the timeout threshold.
     *
     * @return list of removed stale peers
     */
    public synchronized List<PeerInfo> cleanupStalePeers() {
        return cleanupStalePeers(this.timeoutMs);
    }

    /**
     * Scans the registry and removes any peers that have exceeded the specified timeout threshold.
     *
     * @param thresholdMs timeout limit in milliseconds
     * @return list of removed stale peers
     */
    public synchronized List<PeerInfo> cleanupStalePeers(long thresholdMs) {
        List<PeerInfo> removed = new ArrayList<>();
        long now = System.currentTimeMillis();

        peers.entrySet().removeIf(entry -> {
            PeerInfo peer = entry.getValue();
            boolean isStale = (now - peer.getLastSeen() > thresholdMs);
            if (isStale) {
                peer.setStatus(PeerStatus.OFFLINE);
                removed.add(peer);
                return true;
            }
            return false;
        });

        return removed;
    }

    /**
     * Looks up a registered peer by ID.
     *
     * @param peerId the peer ID
     * @return the PeerInfo, or null if not found
     */
    public PeerInfo getPeer(String peerId) {
        return peerId != null ? peers.get(peerId.trim()) : null;
    }

    /**
     * Returns the total count of currently tracked peers in the registry.
     *
     * @return registry size
     */
    public int size() {
        return peers.size();
    }

    /**
     * Clears all peers from the registry.
     */
    public void clear() {
        peers.clear();
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }
}
