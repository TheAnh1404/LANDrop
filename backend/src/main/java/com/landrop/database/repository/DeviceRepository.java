package com.landrop.database.repository;

import com.landrop.database.DatabaseManager;
import com.landrop.database.model.Device;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class DeviceRepository {

    private final DatabaseManager databaseManager;

    public DeviceRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public DeviceRepository() {
        this(DatabaseManager.getInstance());
    }

    public Device upsert(Device device) throws SQLException {
        String sql = """
            INSERT INTO devices (id, user_id, device_name, peer_id, ip_address, port, last_seen_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                device_name = EXCLUDED.device_name,
                peer_id = COALESCE(EXCLUDED.peer_id, devices.peer_id),
                ip_address = COALESCE(EXCLUDED.ip_address, devices.ip_address),
                port = COALESCE(EXCLUDED.port, devices.port),
                last_seen_at = EXCLUDED.last_seen_at,
                updated_at = EXCLUDED.updated_at
            RETURNING id, user_id, device_name, peer_id, ip_address, port, last_seen_at, created_at, updated_at
        """;

        Instant now = Instant.now();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, device.id());
            ps.setObject(2, device.userId());
            ps.setString(3, device.deviceName());
            ps.setString(4, device.peerId());
            ps.setString(5, device.ipAddress());
            if (device.port() != null) {
                ps.setInt(6, device.port());
            } else {
                ps.setNull(6, Types.INTEGER);
            }
            ps.setTimestamp(7, Timestamp.from(device.lastSeenAt() != null ? device.lastSeenAt() : now));
            ps.setTimestamp(8, Timestamp.from(device.createdAt() != null ? device.createdAt() : now));
            ps.setTimestamp(9, Timestamp.from(now));

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
            }
        }
        throw new SQLException("Failed to upsert device: " + device.id());
    }

    public Optional<Device> findById(String id) throws SQLException {
        String sql = "SELECT * FROM devices WHERE id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<Device> findByUserId(UUID userId) throws SQLException {
        String sql = "SELECT * FROM devices WHERE user_id = ? ORDER BY last_seen_at DESC";
        List<Device> list = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    public Optional<Device> findByPeerId(String peerId) throws SQLException {
        String sql = "SELECT * FROM devices WHERE peer_id = ? ORDER BY last_seen_at DESC LIMIT 1";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, peerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public boolean deleteById(String id) throws SQLException {
        String sql = "DELETE FROM devices WHERE id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    private Device mapRow(ResultSet rs) throws SQLException {
        Integer port = rs.getInt("port");
        if (rs.wasNull()) port = null;

        return new Device(
            rs.getString("id"),
            (UUID) rs.getObject("user_id"),
            rs.getString("device_name"),
            rs.getString("peer_id"),
            rs.getString("ip_address"),
            port,
            rs.getTimestamp("last_seen_at").toInstant(),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        );
    }
}
