package com.landrop.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.landrop.auth.exception.ValidationException;

import java.util.regex.Pattern;

/**
 * Registration request payload.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegisterRequest(
    String email,
    String username,
    String password,
    String displayName
) {
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{3,30}$");

    public void validate() {
        if (email == null || email.isBlank()) {
            throw new ValidationException("Email is required");
        }
        if (!EMAIL_PATTERN.matcher(email.trim()).matches()) {
            throw new ValidationException("Invalid email format");
        }
        if (username == null || username.isBlank()) {
            throw new ValidationException("Username is required");
        }
        if (!USERNAME_PATTERN.matcher(username.trim()).matches()) {
            throw new ValidationException("Username must be between 3 and 30 characters and contain only letters, numbers, underscores, or hyphens");
        }
        if (password == null || password.length() < 8) {
            throw new ValidationException("Password must be at least 8 characters long");
        }
    }

    public String resolvedDisplayName() {
        if (displayName != null && !displayName.isBlank()) {
            return displayName.trim();
        }
        return username != null ? username.trim() : "";
    }
}
