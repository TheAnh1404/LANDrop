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
 * Data access repository for managing persistent, revocable refresh tokens in Neon PostgreSQL.
 */
public class RefreshTokenRepository {

    private final DatabaseManager databaseManager;

    public RefreshTokenRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public RefreshToken create(RefreshToken token) throws SQLException {
        String sql = """
            INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at, revoked, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            RETURNING id, user_id, token_hash, expires_at, revoked, created_at, updated_at
        """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            Instant now = Instant.now();
            UUID id = token.id() != null ? token.id() : UUID.randomUUID();
            ps.setObject(1, id);
            ps.setObject(2, token.userId());
            ps.setString(3, token.tokenHash());
            ps.setTimestamp(4, Timestamp.from(token.expiresAt()));
            ps.setBoolean(5, token.revoked());
            ps.setTimestamp(6, Timestamp.from(token.createdAt() != null ? token.createdAt() : now));
            ps.setTimestamp(7, Timestamp.from(token.updatedAt() != null ? token.updatedAt() : now));

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
            SELECT id, user_id, token_hash, expires_at, revoked, created_at, updated_at
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
            SELECT id, user_id, token_hash, expires_at, revoked, created_at, updated_at
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

    public boolean revokeByTokenHash(String tokenHash) throws SQLException {
        String sql = "UPDATE refresh_tokens SET revoked = TRUE, updated_at = ? WHERE token_hash = ? AND revoked = FALSE";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setString(2, tokenHash);
            return ps.executeUpdate() > 0;
        }
    }

    public int revokeAllByUserId(UUID userId) throws SQLException {
        String sql = "UPDATE refresh_tokens SET revoked = TRUE, updated_at = ? WHERE user_id = ? AND revoked = FALSE";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setObject(2, userId);
            return ps.executeUpdate();
        }
    }

    public List<RefreshToken> findActiveByUserId(UUID userId) throws SQLException {
        String sql = """
            SELECT id, user_id, token_hash, expires_at, revoked, created_at, updated_at
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
        return new RefreshToken(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getString("token_hash"),
            rs.getTimestamp("expires_at").toInstant(),
            rs.getBoolean("revoked"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        );
    }
}
