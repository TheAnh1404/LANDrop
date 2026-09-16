package com.landrop.admin.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.landrop.auth.model.RefreshToken;

import java.time.Instant;
import java.util.UUID;

/**
 * Public administrative projection of a user session / refresh token (FOUNDATION-03A.11).
 * CRITICAL SECURITY INVARIANT: NEVER exposes token hashes or raw secret strings.
 */
public record AdminSessionDto(
    UUID id,
    UUID userId,
    String username,
    String deviceId,
    String deviceName,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    Instant createdAt,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    Instant expiresAt,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    Instant lastSeenAt,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    Instant revokedAt,
    boolean revoked,
    boolean isExpired,
    boolean isActive,
    boolean isOnline
) {
    public static AdminSessionDto from(RefreshToken token, String username) {
        return from(token, username, null, false);
    }

    public static AdminSessionDto from(RefreshToken token, String username, String deviceName, boolean isOnline) {
        if (token == null) return null;
        boolean expired = token.isExpired();
        boolean active = !token.revoked() && !expired;
        return new AdminSessionDto(
            token.id(),
            token.userId(),
            username,
            token.deviceId(),
            deviceName,
            token.createdAt(),
            token.expiresAt(),
            token.lastSeenAt(),
            token.revokedAt(),
            token.revoked(),
            expired,
            active,
            isOnline
        );
    }
}
