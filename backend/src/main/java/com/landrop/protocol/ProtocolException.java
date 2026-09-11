package com.landrop.protocol;

import java.io.IOException;

/**
 * Exception thrown when a protocol-level validation or decoding error occurs,
 * such as an unsupported protocol version, unknown message type, or invalid payload length.
 */
public class ProtocolException extends IOException {

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
