package com.landrop.database.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a registered user in the persistent cloud database.
 */
public record User(
    UUID id,
    String email,
    String username,
    String displayName,
    String avatarUrl,
    Instant createdAt,
    Instant updatedAt
) {
    public User(String email, String username, String displayName, String avatarUrl) {
        this(UUID.randomUUID(), email, username, displayName, avatarUrl, Instant.now(), Instant.now());
    }

    public User withId(UUID newId) {
        return new User(newId, email, username, displayName, avatarUrl, createdAt, updatedAt);
    }
}
