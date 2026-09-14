package com.landrop.chat.service;

import com.landrop.auth.exception.AuthException;

/**
 * Structured safe exception for chat operations across REST and WebSocket boundaries.
 */
public class ChatException extends AuthException {
    private final String code;

    public ChatException(int status, String code, String message) {
        super(message, status);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public int getStatus() {
        return getStatusCode();
    }
}
