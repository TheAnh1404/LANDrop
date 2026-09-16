package com.landrop.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.landrop.auth.exception.ValidationException;

/**
 * Login request payload supporting login via either email, username, or identifier (FOUNDATION-04A.2).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LoginRequest(
    String identifier,
    String emailOrUsername,
    String email,
    String username,
    String password,
    String deviceId
) {
    public LoginRequest(String identifier, String password) {
        this(identifier, null, null, null, password, null);
    }

    public LoginRequest(String identifier, String emailOrUsername, String email, String password) {
        this(identifier, emailOrUsername, email, null, password, null);
    }

    public LoginRequest(String identifier, String emailOrUsername, String email, String username, String password) {
        this(identifier, emailOrUsername, email, username, password, null);
    }

    public void validate() {
        String id = resolvedIdentifier();
        if (id == null || id.isBlank()) {
            throw new ValidationException("Email or username is required");
        }
        if (password == null || password.isBlank()) {
            throw new ValidationException("Password is required");
        }
    }

    public String resolvedIdentifier() {
        if (identifier != null && !identifier.isBlank()) {
            return identifier.trim();
        }
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
