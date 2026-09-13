package com.landrop.auth.exception;

/**
 * Thrown when attempting to register a user with an already existing email or username (HTTP 409 Conflict).
 */
public class ConflictException extends AuthException {

    public ConflictException(String message) {
        super(message, 409);
    }
}
