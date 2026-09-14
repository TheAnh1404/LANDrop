package com.landrop.realtime;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/** Persistent chat boundary. Sender identity is supplied only by the verified socket principal. */
public interface RealtimeStore {
    record Message(UUID id, UUID channelId, UUID senderId, UUID clientMessageId,
                   String content, String createdAt, String updatedAt) { }
    record Saved(Message message, boolean inserted) { }
    record Page(List<Message> messages, String nextCursor) { }
    boolean canAccess(UUID userId, UUID channelId) throws SQLException;
    Saved save(UUID userId, UUID channelId, UUID clientMessageId, String content) throws SQLException;
    Page history(UUID userId, UUID channelId, int limit, String before) throws SQLException;
}
