package com.landrop.server.protocol;

/**
 * Protocol constants defining the LANDrop Server Stable Core specifications (FOUNDATION-01A).
 */
public final class ProtocolConstants {

    private ProtocolConstants() {}

    /**
     * Canonical protocol version supported by this server.
     */
    public static final int PROTOCOL_VERSION = 1;

    /**
     * Server version identifier.
     */
    public static final String SERVER_VERSION = "1.0.0-foundation";

    /**
     * Default network host (0.0.0.0 listens on all LAN interfaces).
     */
    public static final String DEFAULT_HOST = "0.0.0.0";

    /**
     * Default HTTP server port for REST and health check.
     */
    public static final int DEFAULT_HTTP_PORT = 3000;

    /**
     * Default WebSocket gateway port for realtime communication.
     */
    public static final int DEFAULT_WS_PORT = 3001;

    /**
     * Recommended heartbeat interval sent to client in milliseconds (15s).
     */
    public static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 15_000L;

    /**
     * Inactive connection timeout in milliseconds before disconnecting zombie clients (45s).
     */
    public static final long DEFAULT_HEARTBEAT_TIMEOUT_MS = 45_000L;

    // Standard Event Types (Client -> Server)
    public static final String EVENT_HELLO = "hello";
    public static final String EVENT_HEARTBEAT = "heartbeat";
    public static final String EVENT_ECHO = "echo";
    public static final String EVENT_TRANSFER_REQUEST = "transfer.request";
    public static final String EVENT_TRANSFER_ACCEPT = "transfer.accept";
    public static final String EVENT_TRANSFER_REJECT = "transfer.reject";
    public static final String EVENT_TRANSFER_CANCEL = "transfer.cancel";
    public static final String EVENT_TRANSFER_STATUS = "transfer.status";

    // Standard Event Types (Server -> Client)
    public static final String EVENT_WELCOME = "welcome";
    public static final String EVENT_HEARTBEAT_ACK = "heartbeat_ack";
    public static final String EVENT_ECHO_ACK = "echo_ack";
    public static final String EVENT_ERROR = "error";

    // Error Codes
    public static final String ERR_PROTOCOL_VERSION_UNSUPPORTED = "PROTOCOL_VERSION_UNSUPPORTED";
    public static final String ERR_UNAUTHORIZED_HANDSHAKE = "UNAUTHORIZED_HANDSHAKE";
    public static final String ERR_INVALID_JSON = "INVALID_JSON";
    public static final String ERR_INVALID_PAYLOAD = "INVALID_PAYLOAD";
    public static final String ERR_UNKNOWN_EVENT = "UNKNOWN_EVENT";
    public static final String ERR_RECEIVER_NOT_FOUND = "RECEIVER_NOT_FOUND";
    public static final String ERR_TRANSFER_NOT_FOUND = "TRANSFER_NOT_FOUND";
    public static final String ERR_INVALID_TRANSFER_STATE = "INVALID_TRANSFER_STATE";
    public static final String ERR_INTERNAL_ERROR = "INTERNAL_ERROR";
}
