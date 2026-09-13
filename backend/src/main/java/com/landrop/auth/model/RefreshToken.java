package com.landrop.auth.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable domain model representing a persistent, revocable refresh token session.
 * Stores a cryptographically secure hash of the issued refresh token (never plaintext).
 */
public record RefreshToken(
    UUID id,
    UUID userId,
    String tokenHash,
    Instant expiresAt,
    boolean revoked,
    Instant createdAt,
    Instant updatedAt
) {
    public RefreshToken(UUID userId, String tokenHash, Instant expiresAt) {
        this(UUID.randomUUID(), userId, tokenHash, expiresAt, false, Instant.now(), Instant.now());
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    public boolean isValid() {
        return !revoked && !isExpired();
    }

    public RefreshToken withRevoked(boolean newRevoked) {
        return new RefreshToken(id, userId, tokenHash, expiresAt, newRevoked, createdAt, Instant.now());
    }
}
