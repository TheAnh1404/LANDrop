package com.landrop.database.repository;

import com.landrop.database.DatabaseManager;
import com.landrop.database.model.User;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class UserRepository {

    private final DatabaseManager databaseManager;

    public UserRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public UserRepository() {
        this(DatabaseManager.getInstance());
    }

    public User create(User user) throws SQLException {
        String sql = """
            INSERT INTO users (id, email, username, display_name, avatar_url, password_hash, status, system_role, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id, email, username, display_name, avatar_url, password_hash, status, system_role, created_at, updated_at
        """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            UUID id = user.id() != null ? user.id() : UUID.randomUUID();
            Instant now = Instant.now();

            ps.setObject(1, id);
            ps.setString(2, user.email());
            ps.setString(3, user.username());
            ps.setString(4, user.displayName());
            ps.setString(5, user.avatarUrl());
            ps.setString(6, user.passwordHash());
            ps.setString(7, user.status() != null ? user.status() : User.STATUS_ACTIVE);
            ps.setString(8, user.systemRole() != null ? user.systemRole() : User.ROLE_USER);
            ps.setTimestamp(9, Timestamp.from(user.createdAt() != null ? user.createdAt() : now));
            ps.setTimestamp(10, Timestamp.from(user.updatedAt() != null ? user.updatedAt() : now));

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
            }
        }
        throw new SQLException("Failed to create user record.");
    }

    public Optional<User> findById(UUID id) throws SQLException {
        String sql = "SELECT * FROM users WHERE id = ?";
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

    public Optional<User> findByEmail(String email) throws SQLException {
        String sql = "SELECT * FROM users WHERE email = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<User> findByUsername(String username) throws SQLException {
        String sql = "SELECT * FROM users WHERE username = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<User> findAll() throws SQLException {
        String sql = "SELECT * FROM users ORDER BY created_at ASC";
        List<User> list = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        }
        return list;
    }

    public List<User> findWithPagination(int offset, int limit, String search, String statusFilter) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM users WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (statusFilter != null && !statusFilter.isBlank() && !"ALL".equalsIgnoreCase(statusFilter)) {
            sql.append("AND status = ? ");
            params.add(statusFilter.toUpperCase());
        }

        if (search != null && !search.isBlank()) {
            sql.append("AND (LOWER(username) LIKE ? OR LOWER(email) LIKE ? OR LOWER(display_name) LIKE ?) ");
            String term = "%" + search.toLowerCase().trim() + "%";
            params.add(term);
            params.add(term);
            params.add(term);
        }

        sql.append("ORDER BY created_at DESC LIMIT ? OFFSET ?");
        params.add(Math.max(1, limit));
        params.add(Math.max(0, offset));

        List<User> list = new ArrayList<>();
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

    public boolean updateStatus(UUID id, String status) throws SQLException {
        String sql = "UPDATE users SET status = ?, updated_at = ? WHERE id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status != null ? status : User.STATUS_ACTIVE);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.setObject(3, id);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean updateSystemRole(UUID id, String systemRole) throws SQLException {
        String sql = "UPDATE users SET system_role = ?, updated_at = ? WHERE id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, systemRole != null ? systemRole : User.ROLE_USER);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.setObject(3, id);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean updateDisplayName(UUID id, String displayName) throws SQLException {
        String sql = "UPDATE users SET display_name = ?, updated_at = ? WHERE id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, displayName);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.setObject(3, id);
            return ps.executeUpdate() > 0;
        }
    }

    public long countAll() throws SQLException {
        String sql = "SELECT COUNT(*) FROM users";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        return 0L;
    }

    public long countSystemAdmins() throws SQLException {
        String sql = "SELECT COUNT(*) FROM users WHERE system_role = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, User.ROLE_SYSTEM_ADMIN);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return 0L;
    }

    /** Returns one active system administrator for administrative recovery tooling. */
    public Optional<User> findFirstSystemAdmin() throws SQLException {
        String sql = "SELECT id, email, username, display_name, avatar_url, password_hash, status, system_role, created_at, updated_at "
                + "FROM users WHERE system_role = ? ORDER BY created_at ASC LIMIT 1";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, User.ROLE_SYSTEM_ADMIN);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        }
        return Optional.empty();
    }

    public long countByStatus(String status) throws SQLException {
        String sql = "SELECT COUNT(*) FROM users WHERE status = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return 0L;
    }

    public boolean deleteById(UUID id) throws SQLException {
        String sql = "DELETE FROM users WHERE id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean updatePasswordHash(UUID id, String passwordHash) throws SQLException {
        String sql = "UPDATE users SET password_hash = ?, updated_at = ? WHERE id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, passwordHash);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.setObject(3, id);
            return ps.executeUpdate() > 0;
        }
    }

    public void deleteAll() throws SQLException {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM users")) {
            ps.executeUpdate();
        }
    }

    private User mapRow(ResultSet rs) throws SQLException {
        String status = User.STATUS_ACTIVE;
        try {
            String s = rs.getString("status");
            if (s != null && !s.isBlank()) status = s;
        } catch (SQLException ignored) {}

        String systemRole = User.ROLE_USER;
        try {
            String r = rs.getString("system_role");
            if (r != null && !r.isBlank()) systemRole = r;
        } catch (SQLException ignored) {}

        return new User(
            (UUID) rs.getObject("id"),
            rs.getString("email"),
            rs.getString("username"),
            rs.getString("display_name"),
            rs.getString("avatar_url"),
            rs.getString("password_hash"),
            status,
            systemRole,
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        );
    }
}
