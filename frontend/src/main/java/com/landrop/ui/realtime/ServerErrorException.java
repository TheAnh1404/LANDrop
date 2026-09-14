package com.landrop.ui.realtime;

/** Typed server error for future auth/device UX without changing the existing login flow. */
public final class ServerErrorException extends IllegalStateException {
    private final String code;

    public ServerErrorException(String code, String message) {
        super(message == null || message.isBlank() ? "Server rejected the request." : message);
        this.code = code == null ? "" : code;
    }

    public String code() { return code; }
}
