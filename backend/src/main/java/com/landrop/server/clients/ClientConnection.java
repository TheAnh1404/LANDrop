package com.landrop.server.clients;

import org.java_websocket.WebSocket;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe representation of an active client connection on the LANDrop Server.
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
    private final WebSocket socket;

    private final AtomicReference<Instant> lastHeartbeatAt;
    private final AtomicReference<ClientStatus> status;

    public ClientConnection(String connectionId,
                            String clientInstanceId,
                            String deviceName,
                            String remoteAddress,
                            Instant connectedAt,
                            int protocolVersion,
                            String clientVersion,
                            String platform,
                            WebSocket socket) {
        this.connectionId = Objects.requireNonNull(connectionId, "connectionId cannot be null");
        this.clientInstanceId = Objects.requireNonNull(clientInstanceId, "clientInstanceId cannot be null");
        this.deviceName = deviceName != null ? deviceName : "Unknown Device";
        this.remoteAddress = remoteAddress != null ? remoteAddress : "Unknown Address";
        this.connectedAt = connectedAt != null ? connectedAt : Instant.now();
        this.protocolVersion = protocolVersion;
        this.clientVersion = clientVersion != null ? clientVersion : "unknown";
        this.platform = platform != null ? platform : "unknown";
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

    @Override
    public String toString() {
        return "ClientConnection{" +
                "connectionId='" + connectionId + '\'' +
                ", clientInstanceId='" + clientInstanceId + '\'' +
                ", deviceName='" + deviceName + '\'' +
                ", remoteAddress='" + remoteAddress + '\'' +
                ", status=" + status.get() +
                '}';
    }
}
