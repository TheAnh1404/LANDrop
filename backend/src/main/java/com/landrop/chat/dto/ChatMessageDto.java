package com.landrop.chat.dto;

import java.util.UUID;

/**
 * Canonical team chat message representation.
 * Supports sender projection, replies, edit timestamp, and soft delete.
 */
public record ChatMessageDto(
    UUID id,
    UUID channelId,
    UUID senderId,
    SenderDto sender,
    String content,
    UUID replyToId,
    UUID clientMessageId,
    String createdAt,
    String updatedAt,
    String editedAt,
    String deletedAt
) {}
