package com.landrop.ui.auth.dto;

import java.util.Objects;

/** JSON shapes for the Phase 15A authentication API. */
public final class AuthDto {
    private AuthDto() { }

    public record LoginRequest(String email, String password) { }

    public record RegisterRequest(String username, String displayName, String email, String password) { }

    public record RefreshRequest(String refreshToken) { }

    public record AuthUserResponse(String id, String username, String displayName, String email,
                                   String avatarUrl, String createdAt, String updatedAt) { }

    public record AuthResponse(String accessToken, String refreshToken, String tokenType,
                               long expiresIn, AuthUserResponse user) {
        public AuthResponse {
            if (expiresIn < 1) throw new IllegalArgumentException("expiresIn must be positive");
            Objects.requireNonNull(user, "user");
        }
    }

    public record RefreshResponse(String accessToken, String refreshToken, String tokenType,
                                  long expiresIn) {
        public RefreshResponse {
            if (expiresIn < 1) throw new IllegalArgumentException("expiresIn must be positive");
        }
    }
}
