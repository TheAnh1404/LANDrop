package com.landrop.auth.repository;

import com.landrop.auth.model.RefreshToken;
import com.landrop.database.DatabaseManager;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access repository for managing persistent, revocable refresh tokens in Neon PostgreSQL (FOUNDATION-04A).
 */
public class RefreshTokenRepository {

    private final DatabaseManager databaseManager;

    public RefreshTokenRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public RefreshToken create(RefreshToken token) throws SQLException {
        try (Connection conn = databaseManager.getConnection()) {
            return create(conn, token);
        }
    }

    public RefreshToken create(Connection conn, RefreshToken token) throws SQLException {
        if (conn == null) return create(token);
        String sql = """
            INSERT INTO refresh_tokens (id, user_id, token_hash, device_id, expires_at, last_seen_at, revoked_at, revoked, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id, user_id, token_hash, device_id, expires_at, last_seen_at, revoked_at, revoked, created_at, updated_at
        """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {

            Instant now = Instant.now();
            UUID id = token.id() != null ? token.id() : UUID.randomUUID();
            ps.setObject(1, id);
            ps.setObject(2, token.userId());
            ps.setString(3, token.tokenHash());
            ps.setString(4, token.deviceId());
            ps.setTimestamp(5, Timestamp.from(token.expiresAt()));
            ps.setTimestamp(6, Timestamp.from(token.lastSeenAt() != null ? token.lastSeenAt() : now));
            ps.setTimestamp(7, token.revokedAt() != null ? Timestamp.from(token.revokedAt()) : null);
            ps.setBoolean(8, token.revoked());
            ps.setTimestamp(9, Timestamp.from(token.createdAt() != null ? token.createdAt() : now));
            ps.setTimestamp(10, Timestamp.from(token.updatedAt() != null ? token.updatedAt() : now));

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
                throw new SQLException("Failed to insert refresh token: no row returned.");
            }
        }
    }

    public Optional<RefreshToken> findByTokenHash(String tokenHash) throws SQLException {
        String sql = """
            SELECT id, user_id, token_hash, device_id, expires_at, last_seen_at, revoked_at, revoked, created_at, updated_at
            FROM refresh_tokens
            WHERE token_hash = ?
        """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, tokenHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<RefreshToken> findById(UUID id) throws SQLException {
        String sql = """
            SELECT id, user_id, token_hash, device_id, expires_at, last_seen_at, revoked_at, revoked, created_at, updated_at
            FROM refresh_tokens
            WHERE id = ?
        """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public boolean revokeById(UUID id) throws SQLException {
        String sql = "UPDATE refresh_tokens SET revoked = TRUE, revoked_at = ?, updated_at = ? WHERE id = ? AND revoked = FALSE";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            Timestamp now = Timestamp.from(Instant.now());
            ps.setTimestamp(1, now);
            ps.setTimestamp(2, now);
            ps.setObject(3, id);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean revokeByTokenHash(String tokenHash) throws SQLException {
        try (Connection conn = databaseManager.getConnection()) {
            return revokeByTokenHash(conn, tokenHash);
        }
    }

    public boolean revokeByTokenHash(Connection conn, String tokenHash) throws SQLException {
        if (conn == null) return revokeByTokenHash(tokenHash);
        String sql = "UPDATE refresh_tokens SET revoked = TRUE, revoked_at = ?, updated_at = ? WHERE token_hash = ? AND revoked = FALSE";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {

            Timestamp now = Timestamp.from(Instant.now());
            ps.setTimestamp(1, now);
            ps.setTimestamp(2, now);
            ps.setString(3, tokenHash);
            return ps.executeUpdate() > 0;
        }
    }

    public int revokeAllByUserId(UUID userId) throws SQLException {
        String sql = "UPDATE refresh_tokens SET revoked = TRUE, revoked_at = ?, updated_at = ? WHERE user_id = ? AND revoked = FALSE";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            Timestamp now = Timestamp.from(Instant.now());
            ps.setTimestamp(1, now);
            ps.setTimestamp(2, now);
            ps.setObject(3, userId);
            return ps.executeUpdate();
        }
    }

    public boolean updateLastSeen(UUID id, Instant lastSeenAt) throws SQLException {
        String sql = "UPDATE refresh_tokens SET last_seen_at = ?, updated_at = ? WHERE id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            Timestamp now = Timestamp.from(Instant.now());
            ps.setTimestamp(1, Timestamp.from(lastSeenAt != null ? lastSeenAt : Instant.now()));
            ps.setTimestamp(2, now);
            ps.setObject(3, id);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean updateDeviceId(UUID id, String deviceId) throws SQLException {
        String sql = "UPDATE refresh_tokens SET device_id = ?, updated_at = ? WHERE id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, deviceId);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.setObject(3, id);
            return ps.executeUpdate() > 0;
        }
    }

    /** Binds a live session once; a session cannot silently move to another device. */
    public boolean bindDevice(UUID sessionId, UUID userId, String deviceId) throws SQLException {
        try (Connection conn = databaseManager.getConnection()) {
            return bindDevice(conn, sessionId, userId, deviceId);
        }
    }

    public boolean bindDevice(Connection conn, UUID sessionId, UUID userId, String deviceId) throws SQLException {
        if (conn == null) return bindDevice(sessionId, userId, deviceId);
        String sql = """
            UPDATE refresh_tokens SET device_id = ?, updated_at = ?
            WHERE id = ? AND user_id = ? AND revoked = FALSE AND expires_at > CURRENT_TIMESTAMP
              AND (device_id IS NULL OR device_id = ?)
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, deviceId);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.setObject(3, sessionId);
            ps.setObject(4, userId);
            ps.setString(5, deviceId);
            return ps.executeUpdate() > 0;
        }
    }

    public List<RefreshToken> findActiveByUserId(UUID userId) throws SQLException {
        String sql = """
            SELECT id, user_id, token_hash, device_id, expires_at, last_seen_at, revoked_at, revoked, created_at, updated_at
            FROM refresh_tokens
            WHERE user_id = ? AND revoked = FALSE AND expires_at > ?
            ORDER BY created_at DESC
        """;

        List<RefreshToken> list = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setObject(1, userId);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    public long countActiveSessions() throws SQLException {
        String sql = "SELECT COUNT(*) FROM refresh_tokens WHERE revoked = FALSE AND expires_at > ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return 0L;
    }

    public long countAllSessions() throws SQLException {
        String sql = "SELECT COUNT(*) FROM refresh_tokens";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        return 0L;
    }

    public List<RefreshToken> findAllSessions(boolean activeOnly, UUID userId, int limit, int offset) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM refresh_tokens WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (activeOnly) {
            sql.append("AND revoked = FALSE AND expires_at > ? ");
            params.add(Timestamp.from(Instant.now()));
        }

        if (userId != null) {
            sql.append("AND user_id = ? ");
            params.add(userId);
        }

        sql.append("ORDER BY created_at DESC LIMIT ? OFFSET ?");
        params.add(Math.max(1, limit));
        params.add(Math.max(0, offset));

        List<RefreshToken> list = new ArrayList<>();
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

    public int deleteExpiredTokens() throws SQLException {
        String sql = "DELETE FROM refresh_tokens WHERE expires_at < ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            return ps.executeUpdate();
        }
    }

    public void deleteAll() throws SQLException {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM refresh_tokens")) {
            ps.executeUpdate();
        }
    }

    private RefreshToken mapRow(ResultSet rs) throws SQLException {
        String deviceId = null;
        try {
            deviceId = rs.getString("device_id");
        } catch (SQLException ignored) {}

        Instant lastSeenAt = null;
        try {
            Timestamp ts = rs.getTimestamp("last_seen_at");
            if (ts != null) lastSeenAt = ts.toInstant();
        } catch (SQLException ignored) {}

        Instant revokedAt = null;
        try {
            Timestamp ts = rs.getTimestamp("revoked_at");
            if (ts != null) revokedAt = ts.toInstant();
        } catch (SQLException ignored) {}

        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        if (lastSeenAt == null) lastSeenAt = createdAt;

        return new RefreshToken(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getString("token_hash"),
            deviceId,
            rs.getTimestamp("expires_at").toInstant(),
            lastSeenAt,
            revokedAt,
            rs.getBoolean("revoked"),
            createdAt,
            rs.getTimestamp("updated_at").toInstant()
        );
    }
}
