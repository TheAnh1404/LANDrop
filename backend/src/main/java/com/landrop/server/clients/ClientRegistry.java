package com.landrop.server.clients;

import com.landrop.server.logging.ServerEvent;
import com.landrop.server.logging.ServerEventLogger;
import com.landrop.server.protocol.EventEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Thread-safe runtime Client Registry managing active client connections (FOUNDATION-01A, FOUNDATION-04A).
 * Maintains bidirectional mapping between runtime connections, client instance IDs,
 * device IDs, session IDs, and user identities.
 */
public class ClientRegistry {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, ClientConnection> connectionsById = new ConcurrentHashMap<>();
    private final Map<String, String> instanceToConnectionId = new ConcurrentHashMap<>();
    private final Map<String, String> deviceToConnectionId = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> userToConnectionIds = new ConcurrentHashMap<>();
    private final Map<String, String> sessionToConnectionId = new ConcurrentHashMap<>();

    /**
     * Registers a new client connection. Reconnect-friendly: if an existing connection
     * has the same clientInstanceId, the old socket is cleanly superseded.
     */
    public void register(ClientConnection connection) {
        Objects.requireNonNull(connection, "connection cannot be null");

        connectionsById.put(connection.connectionId(), connection);

        // HELLO supplies an untrusted installation ID. It must not displace an
        // authenticated connection until the replacement proves session/device ownership.
        String previousConnId = instanceToConnectionId.get(connection.clientInstanceId());
        ClientConnection previous = previousConnId != null ? connectionsById.get(previousConnId) : null;
        if (previous != null && previous.isAuthenticated()) {
            return;
        }
        previousConnId = instanceToConnectionId.put(connection.clientInstanceId(), connection.connectionId());
        if (previousConnId != null && !previousConnId.equals(connection.connectionId())) {
            supersede(previousConnId, connection);
        }
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

    public Optional<ClientConnection> findByDeviceId(String deviceId) {
        if (deviceId == null) return Optional.empty();
        String connId = deviceToConnectionId.get(deviceId);
        if (connId == null) return Optional.empty();
        return get(connId);
    }

    public Optional<ClientConnection> findBySessionId(String sessionId) {
        if (sessionId == null) return Optional.empty();
        String connId = sessionToConnectionId.get(sessionId);
        if (connId == null) return Optional.empty();
        return get(connId);
    }

    public List<ClientConnection> findByUserId(UUID userId) {
        if (userId == null) return List.of();
        Set<String> connIds = userToConnectionIds.get(userId);
        if (connIds == null || connIds.isEmpty()) return List.of();

        List<ClientConnection> result = new ArrayList<>();
        for (String connId : connIds) {
            ClientConnection c = connectionsById.get(connId);
            if (c != null && c.isOpen()) {
                result.add(c);
            }
        }
        return result;
    }

    public boolean isDeviceOnline(String deviceId) {
        if (deviceId == null) return false;
        String connId = deviceToConnectionId.get(deviceId);
        if (connId == null) return false;
        ClientConnection c = connectionsById.get(connId);
        return c != null && c.isOpen();
    }

    public boolean isInstanceOnline(String clientInstanceId) {
        if (clientInstanceId == null) return false;
        String connId = instanceToConnectionId.get(clientInstanceId);
        if (connId == null) return false;
        ClientConnection c = connectionsById.get(connId);
        return c != null && c.isOpen();
    }

    public boolean isUserOnline(UUID userId) {
        if (userId == null) return false;
        Set<String> connIds = userToConnectionIds.get(userId);
        if (connIds == null || connIds.isEmpty()) return false;
        for (String connId : connIds) {
            ClientConnection c = connectionsById.get(connId);
            if (c != null && c.isOpen()) return true;
        }
        return false;
    }

    /**
     * Binds a client connection to User, Device, and Session identity (FOUNDATION-04A.8).
     */
    public void bindIdentity(String connectionId, UUID userId, String username, String deviceId, String sessionId) {
        ClientConnection conn = connectionsById.get(connectionId);
        if (conn == null) return;

        // Clean any previous binding for this connection
        cleanupIdentityIndices(conn);

        String previousConnId = instanceToConnectionId.put(conn.clientInstanceId(), connectionId);
        if (previousConnId != null && !previousConnId.equals(connectionId)) {
            supersede(previousConnId, conn);
        }

        conn.bindIdentity(userId, username, deviceId, sessionId);

        if (deviceId != null && !deviceId.isBlank()) {
            deviceToConnectionId.put(deviceId, connectionId);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            sessionToConnectionId.put(sessionId, connectionId);
        }
        if (userId != null) {
            userToConnectionIds.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(connectionId);
        }

        ServerEventLogger.log(ServerEvent.HANDSHAKE_OK, connectionId,
                "Bound connection to User: " + username + " (" + userId + "), Device: " + deviceId + ", Session: " + sessionId);
    }

    private void supersede(String previousConnId, ClientConnection replacement) {
        ClientConnection oldConn = connectionsById.remove(previousConnId);
        if (oldConn != null) {
            cleanupIdentityIndices(oldConn);
            oldConn.close(1000, "RECONNECTED_FROM_NEW_SESSION");
            ServerEventLogger.log(ServerEvent.CLIENT_RECONNECTED, replacement.connectionId(),
                    "Client instance '" + replacement.clientInstanceId() + "' reconnected; superseded prior session: " + previousConnId);
        }
    }

    /**
     * Unbinds identity from connection.
     */
    public void unbindIdentity(String connectionId) {
        ClientConnection conn = connectionsById.get(connectionId);
        if (conn != null) {
            cleanupIdentityIndices(conn);
            conn.unbindIdentity();
        }
    }

    public int disconnectUser(UUID userId, String reasonCode, String reasonMessage) {
        if (userId == null) return 0;
        List<ClientConnection> connections = findByUserId(userId);
        int disconnectedCount = 0;
        for (ClientConnection conn : connections) {
            try {
                closeForRevocation(conn, 4003, reasonCode, reasonMessage);
                remove(conn.connectionId());
                disconnectedCount++;
            } catch (Exception ignored) {}
        }
        return disconnectedCount;
    }

    public boolean disconnectSession(String sessionId, String reasonCode, String reasonMessage) {
        if (sessionId == null || sessionId.isBlank()) return false;
        Optional<ClientConnection> connOpt = findBySessionId(sessionId);
        if (connOpt.isPresent()) {
            ClientConnection conn = connOpt.get();
            closeForRevocation(conn, 4002, reasonCode, reasonMessage);
            remove(conn.connectionId());
            return true;
        }
        return false;
    }

    private void closeForRevocation(ClientConnection conn, int closeCode, String reasonCode, String reasonMessage) {
        String message = reasonMessage != null ? reasonMessage : "Authentication session ended";
        String code = reasonCode != null ? reasonCode : "SESSION_REVOKED";
        try {
            conn.sendText(JSON.writeValueAsString(EventEnvelope.error(null, code, message)));
        } catch (Exception ignored) {
            // The close code still conveys the terminal reason if the socket cannot send.
        }
        conn.close(closeCode, code + ": " + message);
    }

    public Optional<ClientConnection> remove(String connectionId) {
        if (connectionId == null) return Optional.empty();
        ClientConnection removed = connectionsById.remove(connectionId);
        if (removed != null) {
            removed.setStatus(ClientStatus.DISCONNECTED);
            instanceToConnectionId.remove(removed.clientInstanceId(), connectionId);
            cleanupIdentityIndices(removed);
        }
        return Optional.ofNullable(removed);
    }

    private void cleanupIdentityIndices(ClientConnection conn) {
        if (conn == null) return;
        String devId = conn.deviceId();
        if (devId != null) {
            deviceToConnectionId.remove(devId, conn.connectionId());
        }
        String sessId = conn.sessionId();
        if (sessId != null) {
            sessionToConnectionId.remove(sessId, conn.connectionId());
        }
        UUID uId = conn.userId();
        if (uId != null) {
            Set<String> set = userToConnectionIds.get(uId);
            if (set != null) {
                set.remove(conn.connectionId());
                if (set.isEmpty()) {
                    userToConnectionIds.remove(uId, set);
                }
            }
        }
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
        deviceToConnectionId.clear();
        userToConnectionIds.clear();
        sessionToConnectionId.clear();
    }
}
