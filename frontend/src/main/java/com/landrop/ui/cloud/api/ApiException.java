package com.landrop.ui.cloud.api;

/** HTTP status is zero for local transport or response-contract failures. */
public final class ApiException extends RuntimeException {
    private final int statusCode;
    private final String code;

    public ApiException(int statusCode, String code, String message) {
        super(message);
        this.statusCode = statusCode;
        this.code = code;
    }

    public ApiException(int statusCode, String code, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.code = code;
    }

    public int statusCode() { return statusCode; }
    public String code() { return code; }
}
