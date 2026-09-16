package com.landrop.auth.exception;

import com.landrop.auth.model.AuthErrorCode;

/**
 * Thrown when a requested resource is not found (HTTP 404 Not Found).
 */
public class NotFoundException extends AuthException {

    public NotFoundException(String message) {
        this(message, "NOT_FOUND");
    }

    public NotFoundException(String message, String errorCode) {
        super(message, 404, errorCode != null ? errorCode : "NOT_FOUND");
    }
}
