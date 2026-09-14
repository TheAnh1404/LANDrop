package com.landrop.realtime;

/** Stable public protocol error. Internal exceptions must never be sent to a client. */
public final class RealtimeException extends RuntimeException {
    private final String code;
    public RealtimeException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
