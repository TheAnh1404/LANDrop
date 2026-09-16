package com.landrop.auth.exception;

import com.landrop.auth.model.AuthErrorCode;

/**
 * Thrown when an entity conflicts with an existing record (e.g. duplicate email/username or device ownership).
 */
public class ConflictException extends AuthException {

    public ConflictException(String message) {
        this(message, "CONFLICT");
    }

    public ConflictException(String message, String errorCode) {
        super(message, 409, errorCode != null ? errorCode : "CONFLICT");
    }
}
