package com.landrop.database.repository;

import com.landrop.database.DatabaseManager;
import com.landrop.database.model.Channel;

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

public class ChannelRepository {

    private final DatabaseManager databaseManager;

    public ChannelRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public ChannelRepository() {
        this(DatabaseManager.getInstance());
    }

    public Channel create(Channel channel) throws SQLException {
        String sql = """
            INSERT INTO channels (id, workspace_id, name, topic, is_private, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            RETURNING id, workspace_id, name, topic, is_private, created_at, updated_at
        """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            UUID id = channel.id() != null ? channel.id() : UUID.randomUUID();
            Instant now = Instant.now();

            ps.setObject(1, id);
            ps.setObject(2, channel.workspaceId());
            ps.setString(3, channel.name());
            ps.setString(4, channel.topic());
            ps.setBoolean(5, channel.isPrivate());
            ps.setTimestamp(6, Timestamp.from(channel.createdAt() != null ? channel.createdAt() : now));
            ps.setTimestamp(7, Timestamp.from(channel.updatedAt() != null ? channel.updatedAt() : now));

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
            }
        }
        throw new SQLException("Failed to create channel record.");
    }

    public Optional<Channel> findById(UUID id) throws SQLException {
        String sql = "SELECT * FROM channels WHERE id = ?";
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

    public Optional<Channel> findByName(UUID workspaceId, String name) throws SQLException {
        String sql = "SELECT * FROM channels WHERE workspace_id = ? AND name = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, workspaceId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<Channel> findByWorkspaceId(UUID workspaceId) throws SQLException {
        String sql = "SELECT * FROM channels WHERE workspace_id = ? ORDER BY created_at ASC";
        List<Channel> list = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, workspaceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    public boolean deleteById(UUID id) throws SQLException {
        String sql = "DELETE FROM channels WHERE id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    public void deleteAll() throws SQLException {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM channels")) {
            ps.executeUpdate();
        }
    }

    private Channel mapRow(ResultSet rs) throws SQLException {
        return new Channel(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("workspace_id"),
            rs.getString("name"),
            rs.getString("topic"),
            rs.getBoolean("is_private"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        );
    }
}
