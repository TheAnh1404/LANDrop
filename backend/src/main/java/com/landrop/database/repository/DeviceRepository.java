package com.landrop.database.repository;

import com.landrop.database.DatabaseManager;
import com.landrop.database.model.Device;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access repository for managing persistent user devices (FOUNDATION-04A).
 */
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
            INSERT INTO devices (id, user_id, client_instance_id, device_name, platform, client_version, status, peer_id, ip_address, port, last_seen_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                user_id = EXCLUDED.user_id,
                client_instance_id = COALESCE(EXCLUDED.client_instance_id, devices.client_instance_id),
                device_name = EXCLUDED.device_name,
                platform = COALESCE(EXCLUDED.platform, devices.platform),
                client_version = COALESCE(EXCLUDED.client_version, devices.client_version),
                status = COALESCE(EXCLUDED.status, devices.status),
                peer_id = COALESCE(EXCLUDED.peer_id, devices.peer_id),
                ip_address = COALESCE(EXCLUDED.ip_address, devices.ip_address),
                port = COALESCE(EXCLUDED.port, devices.port),
                last_seen_at = EXCLUDED.last_seen_at,
                updated_at = EXCLUDED.updated_at
            RETURNING id, user_id, client_instance_id, device_name, platform, client_version, status, peer_id, ip_address, port, last_seen_at, created_at, updated_at
        """;

        Instant now = Instant.now();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, device.id());
            ps.setObject(2, device.userId());
            ps.setString(3, device.clientInstanceId() != null ? device.clientInstanceId() : device.id());
            ps.setString(4, device.deviceName());
            ps.setString(5, device.platform() != null ? device.platform() : "unknown");
            ps.setString(6, device.clientVersion() != null ? device.clientVersion() : "1.0.0");
            ps.setString(7, device.status() != null ? device.status() : Device.STATUS_ACTIVE);
            ps.setString(8, device.peerId());
            ps.setString(9, device.ipAddress());
            if (device.port() != null) {
                ps.setInt(10, device.port());
            } else {
                ps.setNull(10, Types.INTEGER);
            }
            ps.setTimestamp(11, Timestamp.from(device.lastSeenAt() != null ? device.lastSeenAt() : now));
            ps.setTimestamp(12, Timestamp.from(device.createdAt() != null ? device.createdAt() : now));
            ps.setTimestamp(13, Timestamp.from(now));

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

    public Optional<Device> findByClientInstanceId(String clientInstanceId) throws SQLException {
        if (clientInstanceId == null || clientInstanceId.isBlank()) return Optional.empty();
        String sql = "SELECT * FROM devices WHERE client_instance_id = ? OR id = ? LIMIT 1";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, clientInstanceId);
            ps.setString(2, clientInstanceId);
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

    public boolean updateName(String id, String newName) throws SQLException {
        String sql = "UPDATE devices SET device_name = ?, updated_at = ? WHERE id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newName);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.setString(3, id);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean updateLastSeen(String id, Instant lastSeenAt) throws SQLException {
        String sql = "UPDATE devices SET last_seen_at = ?, updated_at = ? WHERE id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            Timestamp now = Timestamp.from(Instant.now());
            ps.setTimestamp(1, Timestamp.from(lastSeenAt != null ? lastSeenAt : Instant.now()));
            ps.setTimestamp(2, now);
            ps.setString(3, id);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean deleteById(String id) throws SQLException {
        String sql = "DELETE FROM devices WHERE id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    public long countAll() throws SQLException {
        String sql = "SELECT COUNT(*) FROM devices";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        return 0L;
    }

    public long countByUserId(UUID userId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM devices WHERE user_id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return 0L;
    }

    public List<Device> findAll(int limit, int offset, String search) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM devices WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (search != null && !search.isBlank()) {
            sql.append("AND (device_name ILIKE ? OR client_instance_id ILIKE ? OR id ILIKE ?) ");
            String term = "%" + search.trim() + "%";
            params.add(term);
            params.add(term);
            params.add(term);
        }

        sql.append("ORDER BY last_seen_at DESC LIMIT ? OFFSET ?");
        params.add(Math.max(1, limit));
        params.add(Math.max(0, offset));

        List<Device> list = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    private Device mapRow(ResultSet rs) throws SQLException {
        Integer port = rs.getInt("port");
        if (rs.wasNull()) port = null;

        String clientInstanceId = null;
        try {
            clientInstanceId = rs.getString("client_instance_id");
        } catch (SQLException ignored) {}
        if (clientInstanceId == null) {
            clientInstanceId = rs.getString("id");
        }

        String platform = "unknown";
        try {
            String p = rs.getString("platform");
            if (p != null) platform = p;
        } catch (SQLException ignored) {}

        String clientVersion = "1.0.0";
        try {
            String v = rs.getString("client_version");
            if (v != null) clientVersion = v;
        } catch (SQLException ignored) {}

        String status = Device.STATUS_ACTIVE;
        try {
            String s = rs.getString("status");
            if (s != null) status = s;
        } catch (SQLException ignored) {}

        return new Device(
            rs.getString("id"),
            (UUID) rs.getObject("user_id"),
            clientInstanceId,
            rs.getString("device_name"),
            platform,
            clientVersion,
            status,
            rs.getString("peer_id"),
            rs.getString("ip_address"),
            port,
            rs.getTimestamp("last_seen_at").toInstant(),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        );
    }
}
