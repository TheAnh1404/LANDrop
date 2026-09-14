package com.landrop.realtime;

import com.landrop.database.DatabaseManager;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Uses the Phase 14 messages table, preserving its existing user_id foreign key. */
public final class JdbcRealtimeStore implements RealtimeStore {
    @FunctionalInterface public interface Connections { Connection open() throws SQLException; }
    private final Connections connections;
    public JdbcRealtimeStore(DatabaseManager manager) { this(manager::getConnection); }
    public JdbcRealtimeStore(Connections connections) { this.connections = connections; }

    private boolean canAccess(Connection c, UUID user, UUID channel) throws SQLException {
        // Read the existing membership only. No membership/Workspace CRUD is introduced.
        // Private channels have no per-channel ACL in Phase 14; deny them until one exists.
        try (var ps = c.prepareStatement("""
                SELECT 1 FROM channels c JOIN workspace_members m ON m.workspace_id = c.workspace_id
                WHERE c.id = ? AND m.user_id = ? AND c.is_private = FALSE
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
    @Override public Saved save(UUID user, UUID channel, UUID clientId, String content) throws SQLException {
        try (var c = connections.open()) {
            c.setAutoCommit(false);
            try {
                requireAccess(c, user, channel);
                Message result = null;
                try (var ps = c.prepareStatement("""
                        INSERT INTO messages(channel_id, user_id, client_message_id, content)
                        VALUES (?, ?, ?, ?) ON CONFLICT (user_id, client_message_id) DO NOTHING RETURNING *
                        """)) {
                    ps.setObject(1, channel); ps.setObject(2, user); ps.setObject(3, clientId); ps.setString(4, content);
                    try (var rs = ps.executeQuery()) { if (rs.next()) result = row(rs); }
                }
                boolean inserted = result != null;
                if (!inserted) {
                    try (var ps = c.prepareStatement("SELECT * FROM messages WHERE user_id = ? AND client_message_id = ?")) {
                        ps.setObject(1, user); ps.setObject(2, clientId);
                        try (var rs = ps.executeQuery()) {
                            if (!rs.next()) throw new SQLException("Idempotent message missing");
                            result = row(rs);
                        }
                    }
                    if (!result.channelId().equals(channel) || !result.content().equals(content))
                        throw new RealtimeException("REQUEST_CONFLICT", "Use a new clientMessageId for a different message.");
                }
                c.commit();
                return new Saved(result, inserted);
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
            String sql = "SELECT * FROM messages WHERE channel_id = ?"
                    + (timestamp == null ? "" : " AND (created_at, id) < (?, ?)")
                    + " ORDER BY created_at DESC, id DESC LIMIT ?";
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
    private static Message row(ResultSet rs) throws SQLException {
        return new Message(rs.getObject("id", UUID.class), rs.getObject("channel_id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getObject("client_message_id", UUID.class),
                rs.getString("content"), rs.getTimestamp("created_at").toInstant().toString(),
                rs.getTimestamp("updated_at").toInstant().toString());
    }
}
