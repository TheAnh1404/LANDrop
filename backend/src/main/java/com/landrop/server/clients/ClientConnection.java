package com.landrop.server.clients;

import org.java_websocket.WebSocket;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe representation of an active client connection on the LANDrop Server.
 * Supports authentication binding to User, Session, and Device (FOUNDATION-04A.8).
 */
public final class ClientConnection {

    private final String connectionId;
    private final String clientInstanceId;
    private final String deviceName;
    private final String remoteAddress;
    private final Instant connectedAt;
    private final int protocolVersion;
    private final String clientVersion;
    private final String platform;
    private final String osVersion;
    private final String architecture;
    private final WebSocket socket;

    private final AtomicReference<Instant> lastHeartbeatAt;
    private final AtomicReference<ClientStatus> status;

    // Authentication & Identity Binding (FOUNDATION-04A)
    private final AtomicReference<UUID> userId = new AtomicReference<>(null);
    private final AtomicReference<String> username = new AtomicReference<>(null);
    private final AtomicReference<String> deviceId = new AtomicReference<>(null);
    private final AtomicReference<String> sessionId = new AtomicReference<>(null);
    private final AtomicReference<Instant> authenticatedAt = new AtomicReference<>(null);
    private final AtomicReference<Instant> accessTokenExpiresAt = new AtomicReference<>(null);
    private final AtomicReference<Instant> lastDeviceSeenSyncAt = new AtomicReference<>(Instant.EPOCH);
    private final AtomicReference<Instant> lastSessionSeenSyncAt = new AtomicReference<>(Instant.EPOCH);

    public ClientConnection(String connectionId,
                            String clientInstanceId,
                            String deviceName,
                            String remoteAddress,
                            Instant connectedAt,
                            int protocolVersion,
                            String clientVersion,
                            String platform,
                            String osVersion,
                            String architecture,
                            WebSocket socket) {
        this.connectionId = Objects.requireNonNull(connectionId, "connectionId cannot be null");
        this.clientInstanceId = Objects.requireNonNull(clientInstanceId, "clientInstanceId cannot be null");
        this.deviceName = deviceName != null ? deviceName : "Unknown Device";
        this.remoteAddress = remoteAddress != null ? remoteAddress : "Unknown Address";
        this.connectedAt = connectedAt != null ? connectedAt : Instant.now();
        this.protocolVersion = protocolVersion;
        this.clientVersion = clientVersion != null ? clientVersion : "unknown";
        this.platform = platform != null ? platform : "unknown";
        this.osVersion = osVersion != null ? osVersion : "unknown";
        this.architecture = architecture != null ? architecture : "unknown";
        this.socket = socket;
        this.lastHeartbeatAt = new AtomicReference<>(this.connectedAt);
        this.status = new AtomicReference<>(ClientStatus.ACTIVE);
    }

    public String connectionId() {
        return connectionId;
    }

    public String clientInstanceId() {
        return clientInstanceId;
    }

    public String deviceName() {
        return deviceName;
    }

    public String remoteAddress() {
        return remoteAddress;
    }

    public Instant connectedAt() {
        return connectedAt;
    }

    public Instant lastHeartbeatAt() {
        return lastHeartbeatAt.get();
    }

    public void updateHeartbeat() {
        this.lastHeartbeatAt.set(Instant.now());
        this.status.compareAndSet(ClientStatus.STALE, ClientStatus.ACTIVE);
    }

    public int protocolVersion() {
        return protocolVersion;
    }

    public String clientVersion() {
        return clientVersion;
    }

    public String platform() {
        return platform;
    }

    public String osVersion() { return osVersion; }

    public String architecture() { return architecture; }

    public WebSocket socket() {
        return socket;
    }

    public ClientStatus status() {
        return status.get();
    }

    public void setStatus(ClientStatus newStatus) {
        this.status.set(newStatus);
    }

    public boolean isOpen() {
        return socket != null && socket.isOpen() && status.get() != ClientStatus.DISCONNECTED;
    }

    public void sendText(String text) {
        if (socket != null && socket.isOpen()) {
            socket.send(text);
        }
    }

    public void close(int code, String reason) {
        this.status.set(ClientStatus.DISCONNECTED);
        if (socket != null && socket.isOpen()) {
            socket.close(code, reason);
        }
    }

    // =========================================================================
    // Authentication & Identity Binding Methods (FOUNDATION-04A.8)
    // =========================================================================

    public boolean isAuthenticated() {
        return userId.get() != null;
    }

    public UUID userId() {
        return userId.get();
    }

    public String username() {
        return username.get();
    }

    public String deviceId() {
        return deviceId.get();
    }

    public String sessionId() {
        return sessionId.get();
    }

    public Instant authenticatedAt() {
        return authenticatedAt.get();
    }

    public Instant accessTokenExpiresAt() {
        return accessTokenExpiresAt.get();
    }

    public void setAccessTokenExpiresAt(Instant expiresAt) {
        accessTokenExpiresAt.set(expiresAt);
    }

    public void bindIdentity(UUID userId, String username, String deviceId, String sessionId) {
        this.username.set(username);
        this.deviceId.set(deviceId);
        this.sessionId.set(sessionId);
        this.authenticatedAt.set(Instant.now());
        this.userId.set(userId);
    }

    public void unbindIdentity() {
        this.userId.set(null);
        this.username.set(null);
        this.deviceId.set(null);
        this.sessionId.set(null);
        this.authenticatedAt.set(null);
        this.accessTokenExpiresAt.set(null);
    }

    public boolean shouldUpdateDeviceLastSeen(long throttleMs) {
        Instant now = Instant.now();
        Instant lastSync = lastDeviceSeenSyncAt.get();
        return java.time.Duration.between(lastSync, now).toMillis() >= throttleMs;
    }

    public void markDeviceLastSeenUpdated() {
        this.lastDeviceSeenSyncAt.set(Instant.now());
    }

    public boolean shouldUpdateSessionLastSeen(long throttleMs) {
        return java.time.Duration.between(lastSessionSeenSyncAt.get(), Instant.now()).toMillis() >= throttleMs;
    }

    public void markSessionLastSeenUpdated() {
        this.lastSessionSeenSyncAt.set(Instant.now());
    }

    @Override
    public String toString() {
        return "ClientConnection{" +
                "connectionId='" + connectionId + '\'' +
                ", clientInstanceId='" + clientInstanceId + '\'' +
                ", deviceName='" + deviceName + '\'' +
                ", userId=" + userId.get() +
                ", deviceId='" + deviceId.get() + '\'' +
                ", sessionId='" + sessionId.get() + '\'' +
                ", remoteAddress='" + remoteAddress + '\'' +
                ", status=" + status.get() +
                '}';
    }
}
