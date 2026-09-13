package com.landrop.auth.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.landrop.database.model.User;

import java.time.Instant;
import java.util.UUID;

/**
 * Safe public representation of a User.
 * CRITICAL SECURITY INVARIANT: NEVER contains or exposes passwordHash or credentials.
 */
public record UserDto(
    UUID id,
    String email,
    String username,
    String displayName,
    String avatarUrl,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    Instant createdAt,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    Instant updatedAt
) {
    public static UserDto from(User user) {
        if (user == null) {
            return null;
        }
        return new UserDto(
            user.id(),
            user.email(),
            user.username(),
            user.displayName(),
            user.avatarUrl(),
            user.createdAt(),
            user.updatedAt()
        );
    }
}
