package com.landrop.chat.dto;

import java.util.List;
import java.util.Map;

/**
 * Paginated message history response.
 * Exposes items and nextCursor, with data and page envelopes for client compatibility.
 */
public record MessagesPageDto(
    List<ChatMessageDto> items,
    String nextCursor
) {
    public List<ChatMessageDto> data() {
        return items;
    }

    public Map<String, String> page() {
        return nextCursor != null ? Map.of("nextCursor", nextCursor) : Map.of();
    }
}
