package com.landrop.auth.exception;

import com.landrop.auth.model.AuthErrorCode;

/**
 * Thrown when request payload fails validation (HTTP 400 Bad Request).
 */
public class ValidationException extends AuthException {

    public ValidationException(String message) {
        this(message, AuthErrorCode.VALIDATION_ERROR);
    }

    public ValidationException(String message, String errorCode) {
        super(message, 400, errorCode != null ? errorCode : AuthErrorCode.VALIDATION_ERROR);
    }
}
