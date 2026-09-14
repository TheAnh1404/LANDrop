package com.landrop.realtime;

import org.java_websocket.WebSocket;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Atomic add/remove operations ensure closing one device never removes another device. */
public final class ConnectionRegistry {
    public record Connection(
        UUID socketId,
        SocketPrincipal principal,
        UUID channelId,
        String deviceLabel,
        Instant connectedAt,
        WebSocket socket,
        Set<UUID> rooms
    ) {
        public Connection(UUID socketId, SocketPrincipal principal, UUID channelId,
                          String deviceLabel, Instant connectedAt, WebSocket socket) {
            this(socketId, principal, channelId, deviceLabel, connectedAt, socket,
                 channelId != null ? initRooms(channelId) : ConcurrentHashMap.newKeySet());
        }

        private static Set<UUID> initRooms(UUID channelId) {
            Set<UUID> set = ConcurrentHashMap.newKeySet();
            set.add(channelId);
            return set;
        }

        public boolean isInRoom(UUID ch) {
            return rooms.contains(ch);
        }

        public void joinRoom(UUID ch) {
            rooms.add(ch);
        }

        public void leaveRoom(UUID ch) {
            rooms.remove(ch);
        }
    }

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

    public synchronized int count(UUID userId) {
        return users.getOrDefault(userId, Map.of()).size();
    }

    public synchronized List<Connection> getConnections(UUID userId) {
        var devices = users.get(userId);
        if (devices == null) return List.of();
        return new ArrayList<>(devices.values());
    }

    public synchronized List<Connection> snapshot() {
        return users.values().stream().flatMap(m -> m.values().stream()).toList();
    }
}
