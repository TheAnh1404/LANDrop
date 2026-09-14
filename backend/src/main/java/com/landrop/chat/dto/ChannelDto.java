package com.landrop.chat.dto;

import java.util.UUID;

/**
 * Channel DTO representation.
 */
public record ChannelDto(
    UUID id,
    UUID workspaceId,
    String name,
    String topic,
    String description,
    boolean isPrivate,
    String createdAt,
    String updatedAt
) {
    public ChannelDto(UUID id, UUID workspaceId, String name, String topic, boolean isPrivate, String createdAt, String updatedAt) {
        this(id, workspaceId, name, topic, topic, isPrivate, createdAt, updatedAt);
    }
}
