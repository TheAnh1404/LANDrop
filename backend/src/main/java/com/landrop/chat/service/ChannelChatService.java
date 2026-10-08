package com.landrop.chat.service;

import com.landrop.chat.dto.ChannelDto;
import com.landrop.chat.dto.ChatMessageDto;
import com.landrop.chat.dto.MessagesPageDto;
import com.landrop.chat.dto.SenderDto;
import com.landrop.database.DatabaseManager;
import com.landrop.database.model.Channel;
import com.landrop.database.model.Message;
import com.landrop.database.model.WorkspaceRole;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service coordinating persistent workspace chat operations, authorization,
 * cursor pagination, message mutations, and realtime broadcast dispatch.
 */
public class ChannelChatService {

    @FunctionalInterface
    public interface Connections {
        Connection open() throws SQLException;
    }

    public record SavedMessage(ChatMessageDto message, boolean inserted) {}

    private final Connections connections;
    private volatile ChannelBroadcaster broadcaster;

    public void setBroadcaster(ChannelBroadcaster broadcaster) {
        this.broadcaster = broadcaster != null ? broadcaster : (c, e) -> {};
    }

    public ChannelChatService(Connections connections, ChannelBroadcaster broadcaster) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.broadcaster = broadcaster != null ? broadcaster : (c, e) -> {};
    }

    public ChannelChatService(DatabaseManager databaseManager, ChannelBroadcaster broadcaster) {
        this(Objects.requireNonNull(databaseManager, "databaseManager")::getConnection, broadcaster);
    }

    public ChannelChatService(DatabaseManager databaseManager) {
        this(databaseManager, (c, e) -> {});
    }

    public ChannelChatService() {
        this(DatabaseManager.getInstance(), (c, e) -> {});
    }

    // =========================================================================
    // Authorization & Membership Helpers
    // =========================================================================

    /**
     * Verifies that the user is an active member of the workspace (OWNER, ADMIN, or MEMBER).
     */
    public WorkspaceRole requireWorkspaceMember(Connection conn, UUID workspaceId, UUID userId) throws SQLException {
        String sql = """
            SELECT m.role FROM workspace_members m
            JOIN workspaces w ON w.id = m.workspace_id
            WHERE m.workspace_id = ? AND m.user_id = ?
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, workspaceId);
            ps.setObject(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new ChatException(404, "WORKSPACE_NOT_FOUND", "Workspace was not found.");
                }
                String roleStr = rs.getString("role");
                try {
                    WorkspaceRole role = WorkspaceRole.valueOf(roleStr);
                    if (role == WorkspaceRole.GUEST) {
                        throw new ChatException(403, "FORBIDDEN", "Guest role cannot access workspace channels.");
                    }
                    return role;
                } catch (IllegalArgumentException e) {
                    throw new ChatException(403, "FORBIDDEN", "Unsupported membership role.");
                }
            }
        }
    }

    /**
     * Verifies that the channel exists and the user is an active member of its workspace.
     */
    public Channel requireChannelAccess(Connection conn, UUID channelId, UUID userId) throws SQLException {
        String sql = """
            SELECT c.id, c.workspace_id, c.name, c.topic, c.is_private, c.created_at, c.updated_at,
                   m.role
            FROM channels c
            LEFT JOIN workspace_members m ON m.workspace_id = c.workspace_id AND m.user_id = ?
            WHERE c.id = ?
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setObject(2, channelId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new ChatException(404, "CHANNEL_NOT_FOUND", "Channel was not found.");
                }
                String roleStr = rs.getString("role");
                if (roleStr == null) {
                    throw new ChatException(404, "CHANNEL_NOT_FOUND", "Channel access is unavailable.");
                }
                try {
                    WorkspaceRole role = WorkspaceRole.valueOf(roleStr);
                    if (role == WorkspaceRole.GUEST) {
                        throw new ChatException(403, "FORBIDDEN", "Channel access is unavailable.");
                    }
                } catch (IllegalArgumentException e) {
                    throw new ChatException(403, "FORBIDDEN", "Channel access is unavailable.");
                }

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
    }

    public boolean canAccess(UUID userId, UUID channelId) throws SQLException {
        try (Connection conn = connections.open()) {
            try {
                requireChannelAccess(conn, channelId, userId);
                return true;
            } catch (ChatException e) {
                return false;
            }
        }
    }

    // =========================================================================
    // 17A.3 Channels List
    // =========================================================================

    public List<ChannelDto> listChannels(UUID userId, UUID workspaceId) throws SQLException {
        try (Connection conn = connections.open()) {
            requireWorkspaceMember(conn, workspaceId, userId);

            String sql = """
                SELECT id, workspace_id, name, topic, is_private, created_at, updated_at
                FROM channels
                WHERE workspace_id = ?
                ORDER BY created_at ASC, id ASC
            """;
            List<ChannelDto> channels = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, workspaceId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        channels.add(mapChannelDto(rs));
                    }
                }
            }
            return Collections.unmodifiableList(channels);
        }
    }

    // =========================================================================
    // 17A.4 Create Channel
    // =========================================================================

    public ChannelDto createChannel(UUID userId, UUID workspaceId, String name, String description) throws SQLException {
        if (name == null || name.trim().isBlank()) {
            throw new ChatException(400, "INVALID_REQUEST", "Channel name cannot be empty.");
        }
        String trimmedName = name.trim();
        if (trimmedName.length() > 100) {
            throw new ChatException(400, "INVALID_REQUEST", "Channel name cannot exceed 100 characters.");
        }
        String topic = (description != null && !description.isBlank()) ? description.trim() : null;
        if (topic != null && topic.length() > 2000) {
            throw new ChatException(400, "INVALID_REQUEST", "Channel description cannot exceed 2000 characters.");
        }

        try (Connection conn = connections.open()) {
            conn.setAutoCommit(false);
            try {
                WorkspaceRole role = requireWorkspaceMember(conn, workspaceId, userId);
                if (role != WorkspaceRole.OWNER && role != WorkspaceRole.ADMIN) {
                    throw new ChatException(403, "FORBIDDEN", "This action requires a workspace administrator.");
                }

                // Check for duplicate channel name in workspace
                String checkSql = "SELECT 1 FROM channels WHERE workspace_id = ? AND LOWER(name) = LOWER(?)";
                try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                    ps.setObject(1, workspaceId);
                    ps.setString(2, trimmedName);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            throw new ChatException(409, "CHANNEL_EXISTS", "Channel with this name already exists in this workspace.");
                        }
                    }
                }

                UUID channelId = UUID.randomUUID();
                Instant now = Instant.now();
                String insertSql = """
                    INSERT INTO channels (id, workspace_id, name, topic, is_private, created_at, updated_at)
                    VALUES (?, ?, ?, ?, FALSE, ?, ?)
                    RETURNING id, workspace_id, name, topic, is_private, created_at, updated_at
                """;
                ChannelDto created;
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setObject(1, channelId);
                    ps.setObject(2, workspaceId);
                    ps.setString(3, trimmedName);
                    ps.setString(4, topic);
                    ps.setTimestamp(5, Timestamp.from(now));
                    ps.setTimestamp(6, Timestamp.from(now));
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new SQLException("Failed to insert channel.");
                        }
                        created = mapChannelDto(rs);
                    }
                }
                conn.commit();
                return created;
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    public ChannelDto getChannel(UUID userId, UUID channelId) throws SQLException {
        try (Connection conn = connections.open()) {
            Channel channel = requireChannelAccess(conn, channelId, userId);
            return new ChannelDto(
                channel.id(),
                channel.workspaceId(),
                channel.name(),
                channel.topic(),
                channel.topic(),
                channel.isPrivate(),
                channel.createdAt().toString(),
                channel.updatedAt().toString()
            );
        }
    }

    // =========================================================================
    // 17A.5 Message History with Cursor Pagination
    // =========================================================================

    public MessagesPageDto getHistory(UUID userId, UUID channelId, int limit, String cursor, String before, String order) throws SQLException {
        if (limit < 1 || limit > 100) {
            throw new ChatException(400, "INVALID_REQUEST", "Limit must be between 1 and 100.");
        }

        Instant timestamp = null;
        UUID cursorId = null;
        boolean isReverse = false; // true if paging backwards (before)

        String activeCursor = (before != null && !before.isBlank()) ? before : cursor;
        if (before != null && !before.isBlank()) {
            isReverse = true;
        }

        if (activeCursor != null && !activeCursor.isBlank()) {
            try {
                String decoded = activeCursor;
                if (!activeCursor.contains("|")) {
                    try {
                        decoded = new String(Base64.getUrlDecoder().decode(activeCursor), StandardCharsets.UTF_8);
                    } catch (IllegalArgumentException ignored) {}
                }
                String[] parts = decoded.split("\\|", 2);
                if (parts.length != 2) throw new IllegalArgumentException("Bad cursor format");
                timestamp = Instant.parse(parts[0]);
                cursorId = UUID.fromString(parts[1]);
            } catch (RuntimeException e) {
                throw new ChatException(400, "INVALID_REQUEST", "Invalid history cursor.");
            }
        }

        try (Connection conn = connections.open()) {
            requireChannelAccess(conn, channelId, userId);

            // Predictable chronological order:
            // When isReverse is true (or order=desc): ORDER BY created_at DESC, id DESC
            // Otherwise default forward chronological: ORDER BY created_at ASC, id ASC
            boolean descending = isReverse || "desc".equalsIgnoreCase(order);

            String comparison = descending ? "AND (m.created_at, m.id) < (?, ?)" : "AND (m.created_at, m.id) > (?, ?)";
            String direction = descending ? "DESC" : "ASC";

            String sql = """
                SELECT m.id, m.channel_id, m.user_id, m.parent_message_id, m.client_message_id,
                       m.content, m.created_at, m.updated_at, m.edited_at, m.deleted_at,
                       u.id AS sender_id, u.username, u.display_name, u.avatar_url
                FROM messages m
                JOIN users u ON u.id = m.user_id
                WHERE m.channel_id = ?
            """ + (timestamp != null ? " " + comparison : "")
               + " ORDER BY m.created_at " + direction + ", m.id " + direction
               + " LIMIT ?";

            List<ChatMessageDto> messages = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int idx = 1;
                ps.setObject(idx++, channelId);
                if (timestamp != null) {
                    ps.setTimestamp(idx++, Timestamp.from(timestamp));
                    ps.setObject(idx++, cursorId);
                }
                ps.setInt(idx, limit + 1);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        messages.add(mapChatMessageDto(rs));
                    }
                }
            }

            String nextCursor = null;
            if (messages.size() > limit) {
                messages.removeLast();
                ChatMessageDto last = messages.getLast();
                nextCursor = last.createdAt() + "|" + last.id();
            }

            return new MessagesPageDto(Collections.unmodifiableList(messages), nextCursor);
        }
    }

    // =========================================================================
    // 17A.6 & 17A.9 Send Message & Reply
    // =========================================================================

    public SavedMessage createMessage(UUID userId, UUID channelId, UUID clientMessageId, String content, UUID replyToId) throws SQLException {
        if (content == null || content.isBlank()) {
            throw new ChatException(400, "INVALID_REQUEST", "Message content cannot be empty.");
        }
        if (content.length() > 4000 || content.indexOf('\0') >= 0) {
            throw new ChatException(400, "INVALID_REQUEST", "Message content exceeds limit or contains invalid characters.");
        }

        try (Connection conn = connections.open()) {
            conn.setAutoCommit(false);
            try {
                requireChannelAccess(conn, channelId, userId);

                // Validate replyToId if supplied
                if (replyToId != null) {
                    String replyCheckSql = "SELECT channel_id FROM messages WHERE id = ?";
                    try (PreparedStatement ps = conn.prepareStatement(replyCheckSql)) {
                        ps.setObject(1, replyToId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                throw new ChatException(400, "INVALID_REQUEST", "Referenced reply message does not exist.");
                            }
                            UUID parentChannel = (UUID) rs.getObject("channel_id");
                            if (!parentChannel.equals(channelId)) {
                                throw new ChatException(400, "INVALID_REQUEST", "Referenced reply message belongs to a different channel.");
                            }
                        }
                    }
                }

                ChatMessageDto canonical = null;
                boolean inserted = false;

                // Idempotent insertion using clientMessageId if provided
                if (clientMessageId != null) {
                    String insertSql = """
                        INSERT INTO messages (id, channel_id, user_id, parent_message_id, client_message_id, content, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())
                        ON CONFLICT (user_id, client_message_id) DO NOTHING
                        RETURNING id
                    """;
                    UUID newId = UUID.randomUUID();
                    try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                        ps.setObject(1, newId);
                        ps.setObject(2, channelId);
                        ps.setObject(3, userId);
                        ps.setObject(4, replyToId);
                        ps.setObject(5, clientMessageId);
                        ps.setString(6, content);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                inserted = true;
                            }
                        }
                    }

                    if (inserted) {
                        canonical = loadCanonicalMessage(conn, newId);
                    } else {
                        // Conflict / duplicate clientMessageId: load existing message
                        String existingSql = "SELECT id, channel_id, content, parent_message_id FROM messages WHERE user_id = ? AND client_message_id = ?";
                        try (PreparedStatement ps = conn.prepareStatement(existingSql)) {
                            ps.setObject(1, userId);
                            ps.setObject(2, clientMessageId);
                            try (ResultSet rs = ps.executeQuery()) {
                                if (!rs.next()) {
                                    throw new SQLException("Idempotent message conflict resolution failed.");
                                }
                                UUID existingId = (UUID) rs.getObject("id");
                                UUID existingChannel = (UUID) rs.getObject("channel_id");
                                String existingContent = rs.getString("content");
                                if (!existingChannel.equals(channelId) || !existingContent.equals(content)
                                        || !Objects.equals(rs.getObject("parent_message_id"), replyToId)) {
                                    throw new ChatException(409, "REQUEST_CONFLICT", "Use a new clientMessageId for a different message.");
                                }
                                canonical = loadCanonicalMessage(conn, existingId);
                            }
                        }
                    }
                } else {
                    // No clientMessageId: direct insert
                    UUID newId = UUID.randomUUID();
                    String insertSql = """
                        INSERT INTO messages (id, channel_id, user_id, parent_message_id, content, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, NOW(), NOW())
                        RETURNING id
                    """;
                    try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                        ps.setObject(1, newId);
                        ps.setObject(2, channelId);
                        ps.setObject(3, userId);
                        ps.setObject(4, replyToId);
                        ps.setString(5, content);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                inserted = true;
                            }
                        }
                    }
                    canonical = loadCanonicalMessage(conn, newId);
                }

                conn.commit();

                // Broadcast canonical message to channel room only if newly inserted
                if (inserted) {
                    dispatchCommitted(channelId, java.util.Map.of(
                        "type", "message.created",
                        "channelId", channelId,
                        "message", canonical
                    ));
                }

                return new SavedMessage(canonical, inserted);
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    // =========================================================================
    // 17A.10 Edit Message
    // =========================================================================

    public ChatMessageDto editMessage(UUID userId, UUID messageId, String newContent) throws SQLException {
        if (newContent == null || newContent.isBlank()) {
            throw new ChatException(400, "INVALID_REQUEST", "Message content cannot be empty.");
        }
        if (newContent.length() > 4000 || newContent.indexOf('\0') >= 0) {
            throw new ChatException(400, "INVALID_REQUEST", "Message content exceeds limit or contains invalid characters.");
        }

        try (Connection conn = connections.open()) {
            conn.setAutoCommit(false);
            try {
                // Find message and check channel access & ownership
                String findSql = "SELECT id, channel_id, user_id, deleted_at FROM messages WHERE id = ?";
                UUID channelId;
                try (PreparedStatement ps = conn.prepareStatement(findSql)) {
                    ps.setObject(1, messageId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new ChatException(404, "MESSAGE_NOT_FOUND", "Message was not found.");
                        }
                        UUID senderId = (UUID) rs.getObject("user_id");
                        channelId = (UUID) rs.getObject("channel_id");
                        if (rs.getTimestamp("deleted_at") != null) {
                            throw new ChatException(400, "INVALID_REQUEST", "Deleted messages cannot be edited.");
                        }
                        if (!senderId.equals(userId)) {
                            throw new ChatException(403, "FORBIDDEN", "Only the original sender may edit this message.");
                        }
                    }
                }

                // Verify channel access
                requireChannelAccess(conn, channelId, userId);

                Instant now = Instant.now();
                String updateSql = """
                    UPDATE messages
                    SET content = ?, edited_at = ?, updated_at = ?
                    WHERE id = ?
                """;
                try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                    ps.setString(1, newContent);
                    ps.setTimestamp(2, Timestamp.from(now));
                    ps.setTimestamp(3, Timestamp.from(now));
                    ps.setObject(4, messageId);
                    ps.executeUpdate();
                }

                ChatMessageDto canonical = loadCanonicalMessage(conn, messageId);
                conn.commit();

                // Broadcast canonical update to channel room
                dispatchCommitted(channelId, java.util.Map.of(
                    "type", "message.updated",
                    "channelId", channelId,
                    "messageId", messageId,
                    "message", canonical
                ));

                return canonical;
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    // =========================================================================
    // 17A.11 Delete Message (Soft Delete)
    // =========================================================================

    public ChatMessageDto deleteMessage(UUID userId, UUID messageId) throws SQLException {
        try (Connection conn = connections.open()) {
            conn.setAutoCommit(false);
            try {
                String findSql = "SELECT id, channel_id, user_id, deleted_at FROM messages WHERE id = ?";
                UUID channelId;
                try (PreparedStatement ps = conn.prepareStatement(findSql)) {
                    ps.setObject(1, messageId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new ChatException(404, "MESSAGE_NOT_FOUND", "Message was not found.");
                        }
                        UUID senderId = (UUID) rs.getObject("user_id");
                        channelId = (UUID) rs.getObject("channel_id");
                        if (!senderId.equals(userId)) {
                            throw new ChatException(403, "FORBIDDEN", "Only the original sender may delete this message.");
                        }
                    }
                }

                requireChannelAccess(conn, channelId, userId);

                Instant now = Instant.now();
                String deleteSql = """
                    UPDATE messages
                    SET deleted_at = ?, updated_at = ?, content = '[This message was deleted]'
                    WHERE id = ?
                """;
                try (PreparedStatement ps = conn.prepareStatement(deleteSql)) {
                    ps.setTimestamp(1, Timestamp.from(now));
                    ps.setTimestamp(2, Timestamp.from(now));
                    ps.setObject(3, messageId);
                    ps.executeUpdate();
                }

                ChatMessageDto canonical = loadCanonicalMessage(conn, messageId);
                conn.commit();

                // Broadcast canonical deletion event to channel room
                dispatchCommitted(channelId, java.util.Map.of(
                    "type", "message.deleted",
                    "channelId", channelId,
                    "messageId", messageId,
                    "deletedAt", canonical.deletedAt(),
                    "message", canonical
                ));

                return canonical;
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    // =========================================================================
    // Canonical Message Loader & Row Mappers
    // =========================================================================

    private void dispatchCommitted(UUID channelId, Object event) {
        try {
            broadcaster.broadcast(channelId, event);
        } catch (RuntimeException unavailable) {
            // Persistence succeeded. Clients recover missed delivery using REST history.
            com.landrop.util.AppLogger.warn("ChannelChatService",
                    "Realtime dispatch failed after commit for channel " + channelId);
        }
    }

    public ChatMessageDto loadCanonicalMessage(Connection conn, UUID messageId) throws SQLException {
        String sql = """
            SELECT m.id, m.channel_id, m.user_id, m.parent_message_id, m.client_message_id,
                   m.content, m.created_at, m.updated_at, m.edited_at, m.deleted_at,
                   u.id AS sender_id, u.username, u.display_name, u.avatar_url
            FROM messages m
            JOIN users u ON u.id = m.user_id
            WHERE m.id = ?
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Message not found after write: " + messageId);
                }
                return mapChatMessageDto(rs);
            }
        }
    }

    public static ChatMessageDto mapChatMessageDto(ResultSet rs) throws SQLException {
        UUID id = (UUID) rs.getObject("id");
        UUID channelId = (UUID) rs.getObject("channel_id");
        UUID senderId = (UUID) rs.getObject("user_id");
        UUID replyToId = (UUID) rs.getObject("parent_message_id");
        UUID clientMessageId = null;
        try {
            clientMessageId = (UUID) rs.getObject("client_message_id");
        } catch (SQLException ignored) {}

        String content = rs.getString("content");
        Timestamp createdAtTs = rs.getTimestamp("created_at");
        Timestamp updatedAtTs = rs.getTimestamp("updated_at");
        Timestamp editedAtTs = null;
        Timestamp deletedAtTs = null;
        try {
            editedAtTs = rs.getTimestamp("edited_at");
        } catch (SQLException ignored) {}
        try {
            deletedAtTs = rs.getTimestamp("deleted_at");
        } catch (SQLException ignored) {}

        String createdAt = createdAtTs != null ? createdAtTs.toInstant().toString() : null;
        String updatedAt = updatedAtTs != null ? updatedAtTs.toInstant().toString() : null;
        String editedAt = editedAtTs != null ? editedAtTs.toInstant().toString() : null;
        String deletedAt = deletedAtTs != null ? deletedAtTs.toInstant().toString() : null;

        // If message is deleted, conceal content
        if (deletedAt != null) {
            content = "[This message was deleted]";
        }

        SenderDto sender = new SenderDto(
            senderId,
            rs.getString("username"),
            rs.getString("display_name"),
            rs.getString("avatar_url")
        );

        return new ChatMessageDto(
            id,
            channelId,
            senderId,
            sender,
            content,
            replyToId,
            clientMessageId,
            createdAt,
            updatedAt,
            editedAt,
            deletedAt
        );
    }

    private static ChannelDto mapChannelDto(ResultSet rs) throws SQLException {
        UUID id = (UUID) rs.getObject("id");
        UUID workspaceId = (UUID) rs.getObject("workspace_id");
        String name = rs.getString("name");
        String topic = rs.getString("topic");
        boolean isPrivate = rs.getBoolean("is_private");
        String createdAt = rs.getTimestamp("created_at").toInstant().toString();
        String updatedAt = rs.getTimestamp("updated_at").toInstant().toString();
        return new ChannelDto(id, workspaceId, name, topic, topic, isPrivate, createdAt, updatedAt);
    }
}
