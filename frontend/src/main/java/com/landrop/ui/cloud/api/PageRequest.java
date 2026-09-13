package com.landrop.ui.cloud.api;

import java.util.Map;

public record PageRequest(int limit, String cursor) {
    public PageRequest {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        if (cursor != null && cursor.isBlank()) {
            throw new IllegalArgumentException("cursor must be nonblank or null");
        }
    }

    public static PageRequest first() { return new PageRequest(50, null); }

    Map<String, String> query() {
        return cursor == null ? Map.of("limit", Integer.toString(limit))
                : Map.of("limit", Integer.toString(limit), "cursor", cursor);
    }
}
