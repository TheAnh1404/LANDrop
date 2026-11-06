package com.landrop.auth.model;

/**
 * Standardized error code identifiers for FOUNDATION-04A.
 * Enables client applications to handle logic deterministically without inspecting text messages.
 */
public final class AuthErrorCode {

    private AuthErrorCode() {}

    public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
    public static final String ACCOUNT_DISABLED = "ACCOUNT_DISABLED";
    public static final String SESSION_EXPIRED = "SESSION_EXPIRED";
    public static final String SESSION_REVOKED = "SESSION_REVOKED";
    public static final String DEVICE_ALREADY_OWNED = "DEVICE_ALREADY_OWNED";
    public static final String DEVICE_CONFLICT = "DEVICE_CONFLICT";
    public static final String DEVICE_NOT_FOUND = "DEVICE_NOT_FOUND";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String AUTH_REQUIRED = "AUTH_REQUIRED";
    public static final String PROTOCOL_VERSION_UNSUPPORTED = "PROTOCOL_VERSION_UNSUPPORTED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String TOO_MANY_REQUESTS = "TOO_MANY_REQUESTS";
    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    /** A system administrator must authenticate through the Server Control Center. */
    public static final String ADMIN_LOGIN_REQUIRED = "ADMIN_LOGIN_REQUIRED";
    /** The Server Control Center requires a SYSTEM_ADMIN account. */
    public static final String ADMIN_REQUIRED = "ADMIN_REQUIRED";
}
