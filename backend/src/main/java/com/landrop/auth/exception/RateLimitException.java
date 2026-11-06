package com.landrop.auth.exception;

import com.landrop.auth.model.AuthErrorCode;

/**
 * Thrown when an IP address or user account exceeds the allowed rate limit for authentication requests.
 */
public class RateLimitException extends AuthException {

    private final long retryAfterSeconds;

    public RateLimitException(String message, long retryAfterSeconds) {
        super(message, 429, AuthErrorCode.TOO_MANY_REQUESTS);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
