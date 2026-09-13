package com.landrop.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.landrop.auth.exception.ValidationException;

/**
 * Login request payload supporting login via either email or username.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LoginRequest(
    String emailOrUsername,
    String email,
    String username,
    String password
) {
    public void validate() {
        String identifier = resolvedIdentifier();
        if (identifier == null || identifier.isBlank()) {
            throw new ValidationException("Email or username is required");
        }
        if (password == null || password.isBlank()) {
            throw new ValidationException("Password is required");
        }
    }

    public String resolvedIdentifier() {
        if (emailOrUsername != null && !emailOrUsername.isBlank()) {
            return emailOrUsername.trim();
        }
        if (email != null && !email.isBlank()) {
            return email.trim();
        }
        if (username != null && !username.isBlank()) {
            return username.trim();
        }
        return null;
    }
}
