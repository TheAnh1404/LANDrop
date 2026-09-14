package com.landrop.realtime;

import org.java_websocket.WebSocket;
import java.time.Instant;
import java.util.*;

/** Atomic add/remove operations ensure closing one device never removes another device. */
public final class ConnectionRegistry {
    public record Connection(UUID socketId, SocketPrincipal principal, UUID channelId,
                             String deviceLabel, Instant connectedAt, WebSocket socket) { }
    private final Map<UUID, Map<UUID, Connection>> users = new HashMap<>();
    public synchronized void add(Connection connection) {
        users.computeIfAbsent(connection.principal().userId(), ignored -> new HashMap<>())
                .put(connection.socketId(), connection);
    }
    public synchronized void remove(Connection connection) {
        var devices = users.get(connection.principal().userId());
        if (devices == null) return;
        devices.remove(connection.socketId());
        if (devices.isEmpty()) users.remove(connection.principal().userId());
    }
    public synchronized int count(UUID userId) { return users.getOrDefault(userId, Map.of()).size(); }
    public synchronized List<Connection> snapshot() {
        return users.values().stream().flatMap(m -> m.values().stream()).toList();
    }
}
