package com.landrop.auth.dto;

/**
 * Authentication response containing access and refresh tokens along with public user details.
 */
public record AuthResponse(
    String accessToken,
    String refreshToken,
    String tokenType,
    long expiresIn,
    UserDto user
) {
    public AuthResponse(String accessToken, String refreshToken, long expiresIn, UserDto user) {
        this(accessToken, refreshToken, "Bearer", expiresIn, user);
    }
}
