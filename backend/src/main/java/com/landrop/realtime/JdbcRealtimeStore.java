package com.landrop.realtime;

import com.landrop.chat.dto.SenderDto;
import com.landrop.database.DatabaseManager;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Uses the Phase 14/17 messages table, preserving its existing user_id foreign key. */
public final class JdbcRealtimeStore implements RealtimeStore {
    @FunctionalInterface public interface Connections { Connection open() throws SQLException; }
    private final Connections connections;
    public JdbcRealtimeStore(DatabaseManager manager) { this(manager::getConnection); }
    public JdbcRealtimeStore(Connections connections) { this.connections = connections; }

    private boolean canAccess(Connection c, UUID user, UUID channel) throws SQLException {
        try (var ps = c.prepareStatement("""
                SELECT 1 FROM channels c
                JOIN workspace_members m ON m.workspace_id = c.workspace_id
                WHERE c.id = ? AND m.user_id = ? AND c.is_private = FALSE
                  AND m.role IN ('OWNER', 'ADMIN', 'MEMBER')
                """)) {
            ps.setObject(1, channel); ps.setObject(2, user);
            try (var rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    @Override public boolean canAccess(UUID user, UUID channel) throws SQLException {
        try (var c = connections.open()) { return canAccess(c, user, channel); }
    }

    private void requireAccess(Connection c, UUID user, UUID channel) throws SQLException {
        if (!canAccess(c, user, channel))
            throw new RealtimeException("FORBIDDEN", "Channel access is unavailable.");
    }

    @Override public void verifyChannelRoomAccess(UUID userId, UUID channelId, UUID workspaceId) throws SQLException {
        try (var c = connections.open()) {
            String sql = """
                SELECT c.workspace_id, m.role FROM channels c
                LEFT JOIN workspace_members m ON m.workspace_id = c.workspace_id AND m.user_id = ?
                WHERE c.id = ?
            """;
            try (var ps = c.prepareStatement(sql)) {
                ps.setObject(1, userId);
                ps.setObject(2, channelId);
                try (var rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new RealtimeException("CHANNEL_NOT_FOUND", "Channel was not found.");
                    }
                    UUID actualWorkspace = rs.getObject("workspace_id", UUID.class);
                    if (workspaceId != null && !workspaceId.equals(actualWorkspace)) {
                        throw new RealtimeException("FORBIDDEN", "Channel does not belong to the specified workspace.");
                    }
                    String role = rs.getString("role");
                    if (role == null || role.equals("GUEST")) {
                        throw new RealtimeException("FORBIDDEN", "Channel access is unavailable.");
                    }
                }
            }
        }
    }

    @Override public Saved save(UUID user, UUID channel, UUID clientId, String content) throws SQLException {
        return save(user, channel, clientId, content, null);
    }

    @Override public Saved save(UUID user, UUID channel, UUID clientId, String content, UUID replyToId) throws SQLException {
        try (var c = connections.open()) {
            c.setAutoCommit(false);
            try {
                requireAccess(c, user, channel);

                // Validate replyToId if supplied
                if (replyToId != null) {
                    try (var ps = c.prepareStatement("SELECT channel_id FROM messages WHERE id = ?")) {
                        ps.setObject(1, replyToId);
                        try (var rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                throw new RealtimeException("INVALID_REQUEST", "Referenced reply message does not exist.");
                            }
                            UUID parentChannel = rs.getObject("channel_id", UUID.class);
                            if (!parentChannel.equals(channel)) {
                                throw new RealtimeException("INVALID_REQUEST", "Referenced reply message belongs to a different channel.");
                            }
                        }
                    }
                }

                Message result = null;
                boolean inserted = false;

                if (clientId != null) {
                    try (var ps = c.prepareStatement("""
                            INSERT INTO messages(channel_id, user_id, client_message_id, parent_message_id, content)
                            VALUES (?, ?, ?, ?, ?) ON CONFLICT (user_id, client_message_id) DO NOTHING RETURNING id
                            """)) {
                        ps.setObject(1, channel);
                        ps.setObject(2, user);
                        ps.setObject(3, clientId);
                        ps.setObject(4, replyToId);
                        ps.setString(5, content);
                        try (var rs = ps.executeQuery()) {
                            if (rs.next()) {
                                inserted = true;
                                UUID insertedId = rs.getObject("id", UUID.class);
                                result = loadCanonical(c, insertedId);
                            }
                        }
                    }

                    if (!inserted) {
                        try (var ps = c.prepareStatement("SELECT id, channel_id, content FROM messages WHERE user_id = ? AND client_message_id = ?")) {
                            ps.setObject(1, user); ps.setObject(2, clientId);
                            try (var rs = ps.executeQuery()) {
                                if (!rs.next()) throw new SQLException("Idempotent message missing");
                                UUID existingId = rs.getObject("id", UUID.class);
                                UUID existingChannel = rs.getObject("channel_id", UUID.class);
                                String existingContent = rs.getString("content");
                                if (!existingChannel.equals(channel) || !existingContent.equals(content))
                                    throw new RealtimeException("REQUEST_CONFLICT", "Use a new clientMessageId for a different message.");
                                result = loadCanonical(c, existingId);
                            }
                        }
                    }
                } else {
                    UUID newId = UUID.randomUUID();
                    try (var ps = c.prepareStatement("""
                            INSERT INTO messages(id, channel_id, user_id, parent_message_id, content)
                            VALUES (?, ?, ?, ?, ?) RETURNING id
                            """)) {
                        ps.setObject(1, newId);
                        ps.setObject(2, channel);
                        ps.setObject(3, user);
                        ps.setObject(4, replyToId);
                        ps.setString(5, content);
                        try (var rs = ps.executeQuery()) {
                            if (rs.next()) {
                                inserted = true;
                                result = loadCanonical(c, newId);
                            }
                        }
                    }
                }

                c.commit();
                return new Saved(result, inserted);
            } catch (SQLException | RuntimeException e) { c.rollback(); throw e; }
        }
    }

    @Override public Message edit(UUID user, UUID messageId, String content) throws SQLException {
        try (var c = connections.open()) {
            c.setAutoCommit(false);
            try {
                UUID channel;
                try (var ps = c.prepareStatement("SELECT channel_id, user_id, deleted_at FROM messages WHERE id = ?")) {
                    ps.setObject(1, messageId);
                    try (var rs = ps.executeQuery()) {
                        if (!rs.next()) throw new RealtimeException("NOT_FOUND", "Message was not found.");
                        UUID sender = rs.getObject("user_id", UUID.class);
                        channel = rs.getObject("channel_id", UUID.class);
                        if (rs.getTimestamp("deleted_at") != null)
                            throw new RealtimeException("INVALID_REQUEST", "Deleted messages cannot be edited.");
                        if (!sender.equals(user))
                            throw new RealtimeException("FORBIDDEN", "Only the original sender may edit this message.");
                    }
                }
                requireAccess(c, user, channel);

                try (var ps = c.prepareStatement("""
                        UPDATE messages SET content = ?, edited_at = NOW(), updated_at = NOW() WHERE id = ?
                        """)) {
                    ps.setString(1, content);
                    ps.setObject(2, messageId);
                    ps.executeUpdate();
                }

                Message updated = loadCanonical(c, messageId);
                c.commit();
                return updated;
            } catch (SQLException | RuntimeException e) { c.rollback(); throw e; }
        }
    }

    @Override public Message delete(UUID user, UUID messageId) throws SQLException {
        try (var c = connections.open()) {
            c.setAutoCommit(false);
            try {
                UUID channel;
                try (var ps = c.prepareStatement("SELECT channel_id, user_id, deleted_at FROM messages WHERE id = ?")) {
                    ps.setObject(1, messageId);
                    try (var rs = ps.executeQuery()) {
                        if (!rs.next()) throw new RealtimeException("NOT_FOUND", "Message was not found.");
                        UUID sender = rs.getObject("user_id", UUID.class);
                        channel = rs.getObject("channel_id", UUID.class);
                        if (!sender.equals(user))
                            throw new RealtimeException("FORBIDDEN", "Only the original sender may delete this message.");
                    }
                }
                requireAccess(c, user, channel);

                try (var ps = c.prepareStatement("""
                        UPDATE messages SET deleted_at = NOW(), updated_at = NOW(), content = '[This message was deleted]' WHERE id = ?
                        """)) {
                    ps.setObject(1, messageId);
                    ps.executeUpdate();
                }

                Message deleted = loadCanonical(c, messageId);
                c.commit();
                return deleted;
            } catch (SQLException | RuntimeException e) { c.rollback(); throw e; }
        }
    }

    @Override public Page history(UUID user, UUID channel, int limit, String before) throws SQLException {
        if (limit < 1 || limit > 100) throw new RealtimeException("INVALID_REQUEST", "Limit must be between 1 and 100.");
        Instant timestamp = null; UUID id = null;
        if (before != null) {
            try {
                String[] cursor = before.split("\\|", -1);
                if (cursor.length != 2) throw new IllegalArgumentException();
                timestamp = Instant.parse(cursor[0]); id = UUID.fromString(cursor[1]);
            } catch (RuntimeException e) { throw new RealtimeException("INVALID_REQUEST", "Invalid history cursor."); }
        }
        try (var c = connections.open()) {
            requireAccess(c, user, channel);
            String sql = """
                    SELECT m.id, m.channel_id, m.user_id, m.client_message_id, m.parent_message_id,
                           m.content, m.created_at, m.updated_at, m.edited_at, m.deleted_at,
                           u.username, u.display_name, u.avatar_url
                    FROM messages m
                    JOIN users u ON u.id = m.user_id
                    WHERE m.channel_id = ?
                    """
                    + (timestamp == null ? "" : " AND (m.created_at, m.id) < (?, ?)")
                    + " ORDER BY m.created_at DESC, m.id DESC LIMIT ?";
            var messages = new ArrayList<Message>();
            try (var ps = c.prepareStatement(sql)) {
                int index = 1;
                ps.setObject(index++, channel);
                if (timestamp != null) { ps.setTimestamp(index++, Timestamp.from(timestamp)); ps.setObject(index++, id); }
                ps.setInt(index, limit + 1);
                try (var rs = ps.executeQuery()) { while (rs.next()) messages.add(row(rs)); }
            }
            String next = null;
            if (messages.size() > limit) {
                messages.removeLast();
                Message last = messages.getLast();
                next = last.createdAt() + "|" + last.id();
            }
            return new Page(List.copyOf(messages), next);
        }
    }

    private Message loadCanonical(Connection c, UUID messageId) throws SQLException {
        String sql = """
                SELECT m.id, m.channel_id, m.user_id, m.client_message_id, m.parent_message_id,
                       m.content, m.created_at, m.updated_at, m.edited_at, m.deleted_at,
                       u.username, u.display_name, u.avatar_url
                FROM messages m
                JOIN users u ON u.id = m.user_id
                WHERE m.id = ?
                """;
        try (var ps = c.prepareStatement(sql)) {
            ps.setObject(1, messageId);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("Canonical message missing: " + messageId);
                return row(rs);
            }
        }
    }

    private static Message row(ResultSet rs) throws SQLException {
        UUID senderId = rs.getObject("user_id", UUID.class);
        String username = null, displayName = null, avatarUrl = null;
        try {
            username = rs.getString("username");
            displayName = rs.getString("display_name");
            avatarUrl = rs.getString("avatar_url");
        } catch (SQLException ignored) {}

        SenderDto sender = new SenderDto(senderId, username, displayName, avatarUrl);

        UUID replyTo = null;
        try { replyTo = rs.getObject("parent_message_id", UUID.class); } catch (SQLException ignored) {}

        UUID clientMsgId = null;
        try { clientMsgId = rs.getObject("client_message_id", UUID.class); } catch (SQLException ignored) {}

        Timestamp editedTs = null;
        Timestamp deletedTs = null;
        try { editedTs = rs.getTimestamp("edited_at"); } catch (SQLException ignored) {}
        try { deletedTs = rs.getTimestamp("deleted_at"); } catch (SQLException ignored) {}

        String content = rs.getString("content");
        if (deletedTs != null) {
            content = "[This message was deleted]";
        }

        return new Message(
            rs.getObject("id", UUID.class),
            rs.getObject("channel_id", UUID.class),
            senderId,
            sender,
            clientMsgId,
            content,
            replyTo,
            rs.getTimestamp("created_at").toInstant().toString(),
            rs.getTimestamp("updated_at").toInstant().toString(),
            editedTs != null ? editedTs.toInstant().toString() : null,
            deletedTs != null ? deletedTs.toInstant().toString() : null
        );
    }
}
