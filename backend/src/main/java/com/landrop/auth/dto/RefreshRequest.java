package com.landrop.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.landrop.auth.exception.ValidationException;

/**
 * Token refresh request payload.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RefreshRequest(
    String refreshToken
) {
    public void validate() {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ValidationException("refreshToken is required");
        }
    }
}
