package com.landrop.ui.models;

/**
 * Connection states for the real-time messaging WebSocket transport.
 * Kept strictly independent from authenticated user status.
 */
public enum RealtimeConnectionStatus {
    CONNECTED("Connected", "#10b981"),
    CONNECTING("Connecting...", "#f59e0b"),
    DISCONNECTED("Disconnected", "#94a3b8"),
    RECONNECTING("Reconnecting...", "#f59e0b");

    private final String label;
    private final String colorHex;

    RealtimeConnectionStatus(String label, String colorHex) {
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
