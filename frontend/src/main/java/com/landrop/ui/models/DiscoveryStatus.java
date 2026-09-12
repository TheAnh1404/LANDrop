package com.landrop.ui.models;

/**
 * Lifecycle and connection status of the Discovery Server connection.
 */
public enum DiscoveryStatus {
    CONNECTING("Connecting...", "#f59e0b"),
    CONNECTED("Discovery Connected", "#10b981"),
    DISCONNECTED("Discovery Offline", "#94a3b8"),
    ERROR("Discovery Error", "#ef4444");

    private final String label;
    private final String colorHex;

    DiscoveryStatus(String label, String colorHex) {
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
