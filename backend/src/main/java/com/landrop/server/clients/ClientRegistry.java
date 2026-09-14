package com.landrop.server.clients;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Thread-safe runtime Client Registry managing active client connections.
 * Pure in-memory state; fully decoupled from cloud database identity.
 */
public class ClientRegistry {

    private final Map<String, ClientConnection> connectionsById = new ConcurrentHashMap<>();
    private final Map<String, String> instanceToConnectionId = new ConcurrentHashMap<>();

    /**
     * Registers a new client connection. Reconnect-friendly: if an existing connection
     * has the same clientInstanceId, the old socket is cleanly superseded.
     */
    public void register(ClientConnection connection) {
        Objects.requireNonNull(connection, "connection cannot be null");

        // Reconnect-friendly: supersede prior session with same clientInstanceId
        String previousConnId = instanceToConnectionId.put(connection.clientInstanceId(), connection.connectionId());
        if (previousConnId != null && !previousConnId.equals(connection.connectionId())) {
            ClientConnection oldConn = connectionsById.remove(previousConnId);
            if (oldConn != null && oldConn.isOpen()) {
                oldConn.close(1000, "RECONNECTED_FROM_NEW_SESSION");
            }
        }

        connectionsById.put(connection.connectionId(), connection);
    }

    public Optional<ClientConnection> get(String connectionId) {
        if (connectionId == null) return Optional.empty();
        return Optional.ofNullable(connectionsById.get(connectionId));
    }

    public Optional<ClientConnection> findByInstanceId(String clientInstanceId) {
        if (clientInstanceId == null) return Optional.empty();
        String connId = instanceToConnectionId.get(clientInstanceId);
        if (connId == null) return Optional.empty();
        return get(connId);
    }

    public Optional<ClientConnection> remove(String connectionId) {
        if (connectionId == null) return Optional.empty();
        ClientConnection removed = connectionsById.remove(connectionId);
        if (removed != null) {
            removed.setStatus(ClientStatus.DISCONNECTED);
            instanceToConnectionId.remove(removed.clientInstanceId(), connectionId);
        }
        return Optional.ofNullable(removed);
    }

    public Collection<ClientConnection> allConnections() {
        return Collections.unmodifiableCollection(connectionsById.values());
    }

    public List<ClientConnection> activeConnections() {
        return connectionsById.values().stream()
                .filter(c -> c.status() == ClientStatus.ACTIVE && c.isOpen())
                .toList();
    }

    public int size() {
        return connectionsById.size();
    }

    /**
     * Sweeps connections for heartbeat timeouts.
     * Marks timed-out connections as STALE and notifies the listener.
     */
    public List<ClientConnection> sweepTimeouts(long timeoutMs, Consumer<ClientConnection> onTimeout) {
        Instant now = Instant.now();
        List<ClientConnection> timedOut = new ArrayList<>();

        for (ClientConnection conn : connectionsById.values()) {
            if (conn.status() == ClientStatus.DISCONNECTED) {
                continue;
            }
            long elapsed = Duration.between(conn.lastHeartbeatAt(), now).toMillis();
            if (elapsed > timeoutMs) {
                conn.setStatus(ClientStatus.STALE);
                timedOut.add(conn);
                if (onTimeout != null) {
                    onTimeout.accept(conn);
                }
            }
        }
        return timedOut;
    }

    public void clear() {
        connectionsById.clear();
        instanceToConnectionId.clear();
    }
}
