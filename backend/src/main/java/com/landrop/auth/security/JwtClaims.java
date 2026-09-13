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
    String tokenType,
    Instant issuedAt,
    Instant expiresAt,
    String tokenId
) {
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}
