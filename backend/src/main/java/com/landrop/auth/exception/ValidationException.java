package com.landrop.auth.exception;

/**
 * Thrown when request payload fails validation (HTTP 400 Bad Request).
 */
public class ValidationException extends AuthException {

    public ValidationException(String message) {
        super(message, 400);
    }
}
