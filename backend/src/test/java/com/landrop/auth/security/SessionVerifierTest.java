package com.landrop.auth.security;

import com.landrop.auth.exception.UnauthorizedException;
import com.landrop.auth.model.AuthErrorCode;
import com.landrop.auth.model.RefreshToken;
import com.landrop.auth.repository.RefreshTokenRepository;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SessionVerifierTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    @Test
    void acceptsLiveSessionForTheSignedUser() throws SQLException {
        RefreshToken session = session(false, Instant.now().plusSeconds(3600));
        assertSame(session, SessionVerifier.requireActive(new StubSessions(session), claims(userId, sessionId)));
    }

    @Test
    void rejectsRevokedExpiredMissingAndCrossUserSessions() {
        assertCode(AuthErrorCode.SESSION_REVOKED,
                () -> SessionVerifier.requireActive(new StubSessions(session(true, Instant.now().plusSeconds(3600))),
                        claims(userId, sessionId)));
        assertCode(AuthErrorCode.SESSION_EXPIRED,
                () -> SessionVerifier.requireActive(new StubSessions(session(false, Instant.now().minusSeconds(1))),
                        claims(userId, sessionId)));
        assertCode(AuthErrorCode.SESSION_REVOKED,
                () -> SessionVerifier.requireActive(new StubSessions(null), claims(userId, sessionId)));
        assertCode(AuthErrorCode.UNAUTHORIZED,
                () -> SessionVerifier.requireActive(new StubSessions(session(false, Instant.now().plusSeconds(3600))),
                        claims(UUID.randomUUID(), sessionId)));
        assertCode(AuthErrorCode.SESSION_EXPIRED,
                () -> SessionVerifier.requireActive(new StubSessions(session(false, Instant.now().plusSeconds(3600))),
                        claims(userId, null)));
    }

    private RefreshToken session(boolean revoked, Instant expiry) {
        Instant now = Instant.now();
        return new RefreshToken(sessionId, userId, "hash", "device-1", expiry, now,
                revoked ? now : null, revoked, now, now);
    }

    private JwtClaims claims(UUID user, UUID session) {
        return new JwtClaims(user, "user@example.com", "user", "USER", "access",
                Instant.now(), Instant.now().plusSeconds(600), "token-id",
                session == null ? null : session.toString());
    }

    private void assertCode(String expected, org.junit.jupiter.api.function.Executable operation) {
        UnauthorizedException error = assertThrows(UnauthorizedException.class, operation);
        assertEquals(expected, error.errorCode());
    }

    private static final class StubSessions extends RefreshTokenRepository {
        private final RefreshToken session;

        StubSessions(RefreshToken session) {
            super(null);
            this.session = session;
        }

        @Override
        public Optional<RefreshToken> findById(UUID id) {
            return session != null && session.id().equals(id) ? Optional.of(session) : Optional.empty();
        }
    }
}
