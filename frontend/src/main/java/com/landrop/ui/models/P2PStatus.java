package com.landrop.ui.models;

/**
 * Lifecycle and connection status of direct peer-to-peer TCP sessions.
 */
public enum P2PStatus {
    DISCONNECTED("Disconnected", "#94a3b8"),
    CONNECTING("Connecting...", "#f59e0b"),
    CONNECTED("Connected", "#10b981"),
    ERROR("Connection Failed", "#ef4444");

    private final String label;
    private final String colorHex;

    P2PStatus(String label, String colorHex) {
        this.label = label;
        this.colorHex = colorHex;
    }

    public String getLabel() {
        return label;
    }

    public String getColorHex() {
        return colorHex;
    }
}
