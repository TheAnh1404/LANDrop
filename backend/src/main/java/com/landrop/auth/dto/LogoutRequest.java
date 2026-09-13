package com.landrop.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Logout request payload containing the refresh token to revoke.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LogoutRequest(
    String refreshToken
) {}
