package com.landrop.realtime;

import com.landrop.chat.dto.SenderDto;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/** Persistent chat boundary. Sender identity is supplied only by the verified socket principal. */
public interface RealtimeStore {
    record Message(
        UUID id,
        UUID channelId,
        UUID senderId,
        SenderDto sender,
        UUID clientMessageId,
        String content,
        UUID replyToId,
        String createdAt,
        String updatedAt,
        String editedAt,
        String deletedAt
    ) {
        public Message(UUID id, UUID channelId, UUID senderId, UUID clientMessageId,
                       String content, String createdAt, String updatedAt) {
            this(id, channelId, senderId, new SenderDto(senderId, "user", "User", null),
                 clientMessageId, content, null, createdAt, updatedAt, null, null);
        }
    }

    record Saved(Message message, boolean inserted) { }

    record Page(List<Message> messages, String nextCursor) {
        public List<Message> items() {
            return messages;
        }
    }

    boolean canAccess(UUID userId, UUID channelId) throws SQLException;
    Saved save(UUID userId, UUID channelId, UUID clientMessageId, String content) throws SQLException;
    Page history(UUID userId, UUID channelId, int limit, String before) throws SQLException;

    default Saved save(UUID userId, UUID channelId, UUID clientMessageId, String content, UUID replyToId) throws SQLException {
        return save(userId, channelId, clientMessageId, content);
    }

    default Message edit(UUID userId, UUID messageId, String content) throws SQLException {
        throw new UnsupportedOperationException("edit");
    }

    default Message delete(UUID userId, UUID messageId) throws SQLException {
        throw new UnsupportedOperationException("delete");
    }

    default void verifyChannelRoomAccess(UUID userId, UUID channelId, UUID workspaceId) throws SQLException {
        if (!canAccess(userId, channelId)) {
            throw new RealtimeException("FORBIDDEN", "Channel access is unavailable.");
        }
    }
}
