package com.landrop.database.repository;

import com.landrop.database.DatabaseManager;
import com.landrop.database.model.Message;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class MessageRepository {

    private final DatabaseManager databaseManager;

    public MessageRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public MessageRepository() {
        this(DatabaseManager.getInstance());
    }

    public Message create(Message message) throws SQLException {
        String sql = """
            INSERT INTO messages (id, channel_id, user_id, parent_message_id, content, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            RETURNING id, channel_id, user_id, parent_message_id, content, created_at, updated_at
        """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            UUID id = message.id() != null ? message.id() : UUID.randomUUID();
            Instant now = Instant.now();

            ps.setObject(1, id);
            ps.setObject(2, message.channelId());
            ps.setObject(3, message.userId());
            if (message.parentMessageId() != null) {
                ps.setObject(4, message.parentMessageId());
            } else {
                ps.setNull(4, Types.OTHER);
            }
            ps.setString(5, message.content());
            ps.setTimestamp(6, Timestamp.from(message.createdAt() != null ? message.createdAt() : now));
            ps.setTimestamp(7, Timestamp.from(message.updatedAt() != null ? message.updatedAt() : now));

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
            }
        }
        throw new SQLException("Failed to create message record.");
    }

    public Optional<Message> findById(UUID id) throws SQLException {
        String sql = "SELECT * FROM messages WHERE id = ?";
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

    public List<Message> findByChannelId(UUID channelId, int limit, int offset) throws SQLException {
        String sql = """
            SELECT * FROM messages 
            WHERE channel_id = ? 
            ORDER BY created_at ASC 
            LIMIT ? OFFSET ?
        """;
        List<Message> list = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, channelId);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    public List<Message> findReplies(UUID parentMessageId) throws SQLException {
        String sql = "SELECT * FROM messages WHERE parent_message_id = ? ORDER BY created_at ASC";
        List<Message> list = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, parentMessageId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    public boolean deleteById(UUID id) throws SQLException {
        String sql = "DELETE FROM messages WHERE id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    public void deleteAll() throws SQLException {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM messages")) {
            ps.executeUpdate();
        }
    }

    private Message mapRow(ResultSet rs) throws SQLException {
        return new Message(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("channel_id"),
            (UUID) rs.getObject("user_id"),
            (UUID) rs.getObject("parent_message_id"),
            rs.getString("content"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        );
    }
}
