package com.landrop.server.clients;

/**
 * Lifecycle status of a runtime client connection in the Client Registry.
 */
public enum ClientStatus {
    CONNECTED,    // Socket open, awaiting handshake
    ACTIVE,       // Handshake validated, active heartbeats
    STALE,        // Missed expected heartbeats, flagged for cleanup
    DISCONNECTED  // Socket closed or terminated
}
