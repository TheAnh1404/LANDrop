package com.landrop.auth.exception;

/**
 * Thrown when credentials or token are invalid or missing (HTTP 401 Unauthorized).
 */
public class UnauthorizedException extends AuthException {

    public UnauthorizedException(String message) {
        super(message, 401);
    }
}
