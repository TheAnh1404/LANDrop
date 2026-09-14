package com.landrop.server.logging;

/**
 * Standard audit event categories for LANDrop Server (FOUNDATION-01A Section 13).
 */
public enum ServerEvent {
    SERVER_STARTED,
    CLIENT_CONNECTED,
    CLIENT_HANDSHAKE_OK,
    CLIENT_HANDSHAKE_FAILED,
    CLIENT_DISCONNECTED,
    HEARTBEAT_TIMEOUT,
    TRANSFER_REQUESTED,
    TRANSFER_ACCEPTED,
    TRANSFER_FAILED,
    SERVER_STOPPING
}
