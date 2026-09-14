package com.landrop.ui.connection;

/** Authoritative lifecycle for the single Client-to-Server control connection. */
public enum ConnectionState {
    IDLE, CONNECTING, HANDSHAKING, CONNECTED, RECONNECTING, DISCONNECTED, ERROR
}
