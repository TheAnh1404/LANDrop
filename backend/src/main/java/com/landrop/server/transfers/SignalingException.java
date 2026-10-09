package com.landrop.server.transfers;

/** A safe, correlated control-plane error; never contains SDP or ICE content. */
public class SignalingException extends RuntimeException {
    private final String code;

    public SignalingException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
