package com.landrop.peer;

import java.util.Objects;
import java.util.UUID;

/**
 * Represents the stable runtime identity of a peer in the LANDrop network.
 * Generates a unique UUID identifier upon creation.
 */
public class PeerIdentity {

    private final String peerId;
    private final String displayName;

    /**
     * Constructs a new PeerIdentity with a random UUID and the specified display name.
     *
     * @param displayName the human-readable display name of the peer
     */
    public PeerIdentity(String displayName) {
        this(UUID.randomUUID().toString(), displayName);
    }

    /**
     * Constructs a PeerIdentity with an explicit peer ID and display name.
     *
     * @param peerId the unique peer identifier
     * @param displayName the human-readable display name
     */
    public PeerIdentity(String peerId, String displayName) {
        if (peerId == null || peerId.isBlank()) {
            throw new IllegalArgumentException("peerId cannot be null or blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName cannot be null or blank");
        }
        this.peerId = peerId.trim();
        this.displayName = displayName.trim();
    }

    public String getPeerId() {
        return peerId;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PeerIdentity that)) return false;
        return Objects.equals(peerId, that.peerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(peerId);
    }

    @Override
    public String toString() {
        return "PeerIdentity{" +
                "peerId='" + peerId + '\'' +
                ", displayName='" + displayName + '\'' +
                '}';
    }
}
