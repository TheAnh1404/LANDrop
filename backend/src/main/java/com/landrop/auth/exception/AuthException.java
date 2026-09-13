package com.landrop.auth.exception;

/**
 * Base exception for authentication and authorization errors.
 */
public class AuthException extends RuntimeException {

    private final int statusCode;

    public AuthException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public AuthException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
