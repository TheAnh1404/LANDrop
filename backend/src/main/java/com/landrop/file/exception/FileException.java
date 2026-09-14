package com.landrop.file.exception;

import com.landrop.auth.exception.AuthException;

/**
 * Domain exception for File Management and LAN Transfer operations.
 */
public class FileException extends AuthException {

    private final String code;

    public FileException(int statusCode, String code, String message) {
        super(message, statusCode);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public String getCode() {
        return code;
    }

    public int getStatus() {
        return getStatusCode();
    }
}
