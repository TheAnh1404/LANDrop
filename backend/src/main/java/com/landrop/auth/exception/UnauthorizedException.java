package com.landrop.auth.exception;

import com.landrop.auth.model.AuthErrorCode;

/**
 * Thrown when credentials or token are invalid or missing (HTTP 401 Unauthorized).
 */
public class UnauthorizedException extends AuthException {

    public UnauthorizedException(String message) {
        this(message, AuthErrorCode.UNAUTHORIZED);
    }

    public UnauthorizedException(String message, String errorCode) {
        super(message, 401, errorCode != null ? errorCode : AuthErrorCode.UNAUTHORIZED);
    }
}
