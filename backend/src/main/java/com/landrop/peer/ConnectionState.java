package com.landrop.peer;

/**
 * Represents the lifecycle states of a peer-to-peer connection.
 */
public enum ConnectionState {
    /**
     * Connection is being established with remote peer.
     */
    CONNECTING,

    /**
     * Underlying TCP socket is connected, awaiting protocol handshake (HELLO).
     */
    CONNECTED,

    /**
     * Handshake completed successfully, connection is ready for chat/data exchange.
     */
    READY,

    /**
     * Connection has been terminated or closed gracefully or abnormally.
     */
    DISCONNECTED,

    /**
     * An unrecoverable error occurred during connection or communication.
     */
    ERROR
}
