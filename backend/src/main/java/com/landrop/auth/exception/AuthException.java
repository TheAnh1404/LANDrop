package com.landrop.auth.exception;

/**
 * Base exception for authentication and authorization errors.
 */
public class AuthException extends RuntimeException {

    private final int statusCode;
    private final String errorCode;

    public AuthException(String message, int statusCode) {
        this(message, statusCode, (String) null);
    }

    public AuthException(String message, int statusCode, String errorCode) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    public AuthException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.errorCode = null;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
