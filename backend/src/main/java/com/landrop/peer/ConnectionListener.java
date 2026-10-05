package com.landrop.peer;

import com.landrop.protocol.Packet;

/**
 * Listener interface for monitoring connection lifecycle events and packet I/O.
 */
public interface ConnectionListener {

    /**
     * Invoked when the connection transitions between states.
     *
     * @param oldState the previous connection state
     * @param newState the new connection state
     */
    default void onStateChanged(ConnectionState oldState, ConnectionState newState) {}

    /**
     * Invoked when a packet is successfully received and decoded.
     *
     * @param packet the received packet
     */
    default void onPacketReceived(Packet packet) {}

    /**
     * Invoked when a packet is successfully encoded and written to the wire.
     *
     * @param packet the sent packet
     */
    default void onPacketSent(Packet packet) {}

    /**
     * Invoked when the connection has been disconnected.
     */
    default void onDisconnected() {}

    /**
     * Invoked when an I/O or protocol error occurs.
     *
     * @param error the exception encountered
     */
    default void onError(Exception error) {}

    /**
     * Invoked when peer-to-peer end-to-end encryption has been successfully negotiated.
     *
     * @param fingerprint the 6-digit verification security fingerprint
     */
    default void onEncryptionEstablished(String fingerprint) {}
}
