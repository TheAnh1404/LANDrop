package com.landrop.auth.security;

import java.time.Instant;
import java.util.UUID;

/**
 * Parsed and verified claims from a JSON Web Token (JWT).
 */
public record JwtClaims(
    UUID userId,
    String email,
    String username,
    String systemRole,
    String tokenType,
    Instant issuedAt,
    Instant expiresAt,
    String tokenId,
    String sessionId
) {
    public JwtClaims(UUID userId, String email, String username, String tokenType, Instant issuedAt, Instant expiresAt, String tokenId) {
        this(userId, email, username, "USER", tokenType, issuedAt, expiresAt, tokenId, null);
    }

    public JwtClaims(UUID userId, String email, String username, String systemRole, String tokenType,
                     Instant issuedAt, Instant expiresAt, String tokenId) {
        this(userId, email, username, systemRole, tokenType, issuedAt, expiresAt, tokenId, null);
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    public boolean isSystemAdmin() {
        return "SYSTEM_ADMIN".equalsIgnoreCase(systemRole);
    }
}
