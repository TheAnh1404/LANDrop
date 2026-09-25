package com.landrop.auth.security;

import com.landrop.auth.exception.UnauthorizedException;
import com.landrop.auth.model.AuthErrorCode;
import com.landrop.auth.model.RefreshToken;
import com.landrop.auth.repository.RefreshTokenRepository;

import java.sql.SQLException;
import java.util.UUID;

/** Resolves a signed access token to its current persistent session. */
public final class SessionVerifier {

    private SessionVerifier() {}

    public static RefreshToken requireActive(RefreshTokenRepository sessions, JwtClaims claims) throws SQLException {
        if (sessions == null || claims == null || claims.sessionId() == null || claims.sessionId().isBlank()) {
            throw new UnauthorizedException("A valid session is required", AuthErrorCode.SESSION_EXPIRED);
        }

        UUID sessionId;
        try {
            sessionId = UUID.fromString(claims.sessionId());
        } catch (IllegalArgumentException invalid) {
            throw new UnauthorizedException("Invalid session identity", AuthErrorCode.UNAUTHORIZED);
        }

        return requireActive(sessions, sessionId, claims.userId());
    }

    public static RefreshToken requireActive(RefreshTokenRepository sessions, UUID sessionId, UUID userId) throws SQLException {
        if (sessions == null || sessionId == null || userId == null) {
            throw new UnauthorizedException("A valid session is required", AuthErrorCode.SESSION_EXPIRED);
        }

        RefreshToken session = sessions.findById(sessionId)
                .orElseThrow(() -> new UnauthorizedException("Session no longer exists", AuthErrorCode.SESSION_REVOKED));
        if (!session.userId().equals(userId)) {
            throw new UnauthorizedException("Session does not belong to this user", AuthErrorCode.UNAUTHORIZED);
        }
        if (session.revoked() || session.revokedAt() != null) {
            throw new UnauthorizedException("Session has been revoked", AuthErrorCode.SESSION_REVOKED);
        }
        if (session.isExpired()) {
            throw new UnauthorizedException("Session has expired", AuthErrorCode.SESSION_EXPIRED);
        }
        return session;
    }
}
