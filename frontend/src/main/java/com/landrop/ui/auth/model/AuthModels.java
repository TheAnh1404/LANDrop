package com.landrop.ui.auth.model;

import com.landrop.ui.cloud.model.Domain;

import java.time.Instant;
import java.util.Objects;

/** Authentication-only request and session models. Persistent profile data reuses Domain.User. */
public final class AuthModels {
    private AuthModels() { }

    public record LoginRequest(String email, String password) { }

    public record RegisterRequest(String username, String displayName, String email, String password) { }

    /** Auth API username is kept alongside the existing persistent Domain.User profile. */
    public record AuthUser(String username, Domain.User profile) {
        public AuthUser {
            if (username == null || username.isBlank()) {
                throw new IllegalArgumentException("username must be a nonblank string");
            }
            Objects.requireNonNull(profile, "profile");
        }

        public String id() { return profile.id(); }
        public String displayName() { return profile.displayName(); }
        public String email() { return profile.email(); }
        public String avatarUrl() { return profile.avatarUrl(); }
    }

    public record AuthSession(AuthUser user, String accessToken, String refreshToken,
                              String tokenType, Instant expiresAt) {
        public AuthSession {
            Objects.requireNonNull(user, "user");
            if (accessToken == null || accessToken.isBlank()) {
                throw new IllegalArgumentException("accessToken must be nonblank");
            }
            if (refreshToken != null && refreshToken.isBlank()) {
                throw new IllegalArgumentException("refreshToken must be nonblank or null");
            }
            if (tokenType == null || tokenType.isBlank()) {
                throw new IllegalArgumentException("tokenType must be nonblank");
            }
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    public record RefreshTokens(String accessToken, String refreshToken,
                                String tokenType, Instant expiresAt) { }
}
