package com.landrop.admin.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.landrop.database.model.User;

import java.time.Instant;
import java.util.UUID;

/**
 * Public administrative projection of User entity (FOUNDATION-03A.9).
 * CRITICAL SECURITY INVARIANT: NEVER exposes password hashes, secrets, or raw credentials.
 */
public record AdminUserDto(
    UUID id,
    String email,
    String username,
    String displayName,
    String avatarUrl,
    String status,
    String systemRole,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    Instant createdAt,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    Instant updatedAt,
    int activeSessionsCount
) {
    public static AdminUserDto from(User user, int activeSessions) {
        if (user == null) return null;
        return new AdminUserDto(
            user.id(),
            user.email(),
            user.username(),
            user.displayName(),
            user.avatarUrl(),
            user.status() != null ? user.status() : User.STATUS_ACTIVE,
            user.systemRole() != null ? user.systemRole() : User.ROLE_USER,
            user.createdAt(),
            user.updatedAt(),
            activeSessions
        );
    }
}
