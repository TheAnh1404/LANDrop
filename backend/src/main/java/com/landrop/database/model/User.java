package com.landrop.database.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a registered user in the persistent cloud database.
 * Supports system admin roles and active/disabled status (FOUNDATION-03A).
 */
public record User(
    UUID id,
    String email,
    String username,
    String displayName,
    String avatarUrl,
    String passwordHash,
    String status,
    String systemRole,
    Instant createdAt,
    Instant updatedAt
) {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    public static final String ROLE_USER = "USER";
    public static final String ROLE_SYSTEM_ADMIN = "SYSTEM_ADMIN";

    public User(String email, String username, String displayName, String avatarUrl) {
        this(UUID.randomUUID(), email, username, displayName, avatarUrl, null, STATUS_ACTIVE, ROLE_USER, Instant.now(), Instant.now());
    }

    public User(UUID id, String email, String username, String displayName, String avatarUrl, Instant createdAt, Instant updatedAt) {
        this(id, email, username, displayName, avatarUrl, null, STATUS_ACTIVE, ROLE_USER, createdAt, updatedAt);
    }

    public User(String email, String username, String displayName, String avatarUrl, String passwordHash) {
        this(UUID.randomUUID(), email, username, displayName, avatarUrl, passwordHash, STATUS_ACTIVE, ROLE_USER, Instant.now(), Instant.now());
    }

    public User(UUID id, String email, String username, String displayName, String avatarUrl, String passwordHash, Instant createdAt, Instant updatedAt) {
        this(id, email, username, displayName, avatarUrl, passwordHash, STATUS_ACTIVE, ROLE_USER, createdAt, updatedAt);
    }

    public User withId(UUID newId) {
        return new User(newId, email, username, displayName, avatarUrl, passwordHash, status, systemRole, createdAt, updatedAt);
    }

    public User withPasswordHash(String hash) {
        return new User(id, email, username, displayName, avatarUrl, hash, status, systemRole, createdAt, updatedAt);
    }

    public User withStatus(String newStatus) {
        return new User(id, email, username, displayName, avatarUrl, passwordHash, newStatus, systemRole, createdAt, updatedAt);
    }

    public User withSystemRole(String newRole) {
        return new User(id, email, username, displayName, avatarUrl, passwordHash, status, newRole, createdAt, updatedAt);
    }

    public boolean isSystemAdmin() {
        return ROLE_SYSTEM_ADMIN.equalsIgnoreCase(systemRole);
    }

    public boolean isActive() {
        return STATUS_ACTIVE.equalsIgnoreCase(status);
    }

    public boolean isDisabled() {
        return STATUS_DISABLED.equalsIgnoreCase(status);
    }
}
