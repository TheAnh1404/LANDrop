package com.landrop.discovery;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Model representing registered peer presence and network connection parameters.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PeerInfo {

    @JsonProperty("peerId")
    private String peerId;

    @JsonProperty("displayName")
    private String displayName;

    @JsonProperty("host")
    private String host;

    @JsonProperty("port")
    private int port;

    @JsonProperty("lastSeen")
    private long lastSeen;

    @JsonProperty("status")
    private PeerStatus status;

    /**
     * Default constructor for JSON deserialization.
     */
    public PeerInfo() {
    }

    /**
     * Convenience constructor initializing lastSeen to current time and status to ONLINE.
     *
     * @param peerId the unique peer identifier
     * @param displayName the human-readable display name
     * @param host the reachable host or IP address for P2P connection
     * @param port the listening TCP port for P2P connection
     */
    public PeerInfo(String peerId, String displayName, String host, int port) {
        this(peerId, displayName, host, port, System.currentTimeMillis(), PeerStatus.ONLINE);
    }

    /**
     * Full constructor for PeerInfo.
     *
     * @param peerId the unique peer identifier
     * @param displayName the human-readable display name
     * @param host the reachable host or IP address
     * @param port the listening TCP port
     * @param lastSeen timestamp in epoch milliseconds
     * @param status the current presence status
     */
    public PeerInfo(String peerId, String displayName, String host, int port, long lastSeen, PeerStatus status) {
        this.peerId = peerId;
        this.displayName = displayName;
        this.host = host;
        this.port = port;
        this.lastSeen = lastSeen;
        this.status = status != null ? status : PeerStatus.ONLINE;
    }

    public String getPeerId() {
        return peerId;
    }

    public void setPeerId(String peerId) {
        this.peerId = peerId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public long getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(long lastSeen) {
        this.lastSeen = lastSeen;
    }

    public PeerStatus getStatus() {
        return status;
    }

    public void setStatus(PeerStatus status) {
        this.status = status;
    }

    /**
     * Validates whether this PeerInfo contains acceptable parameters for registration.
     *
     * @return true if valid, false otherwise
     */
    public boolean isValid() {
        return peerId != null && !peerId.isBlank() &&
                displayName != null && !displayName.isBlank() &&
                host != null && !host.isBlank() &&
                port >= 1 && port <= 65535;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PeerInfo peerInfo)) return false;
        return Objects.equals(peerId, peerInfo.peerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(peerId);
    }

    @Override
    public String toString() {
        return "PeerInfo{" +
                "peerId='" + peerId + '\'' +
                ", displayName='" + displayName + '\'' +
                ", host='" + host + '\'' +
                ", port=" + port +
                ", lastSeen=" + lastSeen +
                ", status=" + status +
                '}';
    }
}
