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
            INSERT INTO messages (id, channel_id, user_id, parent_message_id, client_message_id, content, created_at, updated_at, edited_at, deleted_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id, channel_id, user_id, parent_message_id, client_message_id, content, created_at, updated_at, edited_at, deleted_at
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
            if (message.clientMessageId() != null) {
                ps.setObject(5, message.clientMessageId());
            } else {
                ps.setNull(5, Types.OTHER);
            }
            ps.setString(6, message.content());
            ps.setTimestamp(7, Timestamp.from(message.createdAt() != null ? message.createdAt() : now));
            ps.setTimestamp(8, Timestamp.from(message.updatedAt() != null ? message.updatedAt() : now));
            if (message.editedAt() != null) {
                ps.setTimestamp(9, Timestamp.from(message.editedAt()));
            } else {
                ps.setNull(9, Types.TIMESTAMP);
            }
            if (message.deletedAt() != null) {
                ps.setTimestamp(10, Timestamp.from(message.deletedAt()));
            } else {
                ps.setNull(10, Types.TIMESTAMP);
            }

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

    public Optional<Message> findByUserAndClientId(UUID userId, UUID clientMessageId) throws SQLException {
        String sql = "SELECT * FROM messages WHERE user_id = ? AND client_message_id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setObject(2, clientMessageId);
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
            ORDER BY created_at ASC, id ASC
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
        String sql = "SELECT * FROM messages WHERE parent_message_id = ? ORDER BY created_at ASC, id ASC";
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

    public Optional<Message> updateContent(UUID id, String newContent, Instant editedAt) throws SQLException {
        String sql = """
            UPDATE messages
            SET content = ?, edited_at = ?, updated_at = ?
            WHERE id = ?
            RETURNING *
        """;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            Instant now = editedAt != null ? editedAt : Instant.now();
            ps.setString(1, newContent);
            ps.setTimestamp(2, Timestamp.from(now));
            ps.setTimestamp(3, Timestamp.from(now));
            ps.setObject(4, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<Message> softDelete(UUID id, Instant deletedAt, String placeholderContent) throws SQLException {
        String sql = """
            UPDATE messages
            SET deleted_at = ?, updated_at = ?, content = ?
            WHERE id = ?
            RETURNING *
        """;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            Instant now = deletedAt != null ? deletedAt : Instant.now();
            ps.setTimestamp(1, Timestamp.from(now));
            ps.setTimestamp(2, Timestamp.from(now));
            ps.setString(3, placeholderContent != null ? placeholderContent : "[This message was deleted]");
            ps.setObject(4, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
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
        Timestamp editedAtTs = null;
        Timestamp deletedAtTs = null;
        UUID clientMsgId = null;
        try {
            editedAtTs = rs.getTimestamp("edited_at");
        } catch (SQLException ignored) {}
        try {
            deletedAtTs = rs.getTimestamp("deleted_at");
        } catch (SQLException ignored) {}
        try {
            clientMsgId = (UUID) rs.getObject("client_message_id");
        } catch (SQLException ignored) {}

        return new Message(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("channel_id"),
            (UUID) rs.getObject("user_id"),
            (UUID) rs.getObject("parent_message_id"),
            clientMsgId,
            rs.getString("content"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            editedAtTs != null ? editedAtTs.toInstant() : null,
            deletedAtTs != null ? deletedAtTs.toInstant() : null
        );
    }
}
