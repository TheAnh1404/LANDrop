package com.landrop.auth.exception;

import com.landrop.auth.model.AuthErrorCode;

/**
 * Thrown when an authenticated user lacks required permissions (HTTP 403 Forbidden).
 */
public class ForbiddenException extends AuthException {

    public ForbiddenException(String message) {
        this(message, AuthErrorCode.FORBIDDEN);
    }

    public ForbiddenException(String message, String errorCode) {
        super(message, 403, errorCode != null ? errorCode : AuthErrorCode.FORBIDDEN);
    }
}
