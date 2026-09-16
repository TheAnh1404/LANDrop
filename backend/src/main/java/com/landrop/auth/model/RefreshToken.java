package com.landrop.auth.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable domain model representing a persistent, revocable refresh token session (FOUNDATION-04A).
 * Stores a cryptographically secure hash of the issued refresh token (never plaintext),
 * with optional device binding, last seen timestamp, and explicit revocation timestamp.
 */
public record RefreshToken(
    UUID id,
    UUID userId,
    String tokenHash,
    String deviceId,
    Instant expiresAt,
    Instant lastSeenAt,
    Instant revokedAt,
    boolean revoked,
    Instant createdAt,
    Instant updatedAt
) {
    public RefreshToken(UUID userId, String tokenHash, Instant expiresAt) {
        this(UUID.randomUUID(), userId, tokenHash, null, expiresAt, Instant.now(), null, false, Instant.now(), Instant.now());
    }

    public RefreshToken(UUID userId, String tokenHash, String deviceId, Instant expiresAt) {
        this(UUID.randomUUID(), userId, tokenHash, deviceId, expiresAt, Instant.now(), null, false, Instant.now(), Instant.now());
    }

    public RefreshToken(UUID id, UUID userId, String tokenHash, Instant expiresAt, boolean revoked, Instant createdAt, Instant updatedAt) {
        this(id, userId, tokenHash, null, expiresAt, createdAt, revoked ? updatedAt : null, revoked, createdAt, updatedAt);
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    public boolean isValid() {
        return !revoked && (revokedAt == null) && !isExpired();
    }

    public RefreshToken withRevoked(boolean newRevoked) {
        Instant revAt = newRevoked ? (revokedAt != null ? revokedAt : Instant.now()) : null;
        return new RefreshToken(id, userId, tokenHash, deviceId, expiresAt, lastSeenAt, revAt, newRevoked, createdAt, Instant.now());
    }

    public RefreshToken withDeviceId(String newDeviceId) {
        return new RefreshToken(id, userId, tokenHash, newDeviceId, expiresAt, lastSeenAt, revokedAt, revoked, createdAt, Instant.now());
    }

    public RefreshToken withLastSeenAt(Instant newLastSeen) {
        return new RefreshToken(id, userId, tokenHash, deviceId, expiresAt, newLastSeen != null ? newLastSeen : Instant.now(), revokedAt, revoked, createdAt, Instant.now());
    }
}
