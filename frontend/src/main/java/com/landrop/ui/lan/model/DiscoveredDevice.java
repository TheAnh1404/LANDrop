package com.landrop.ui.lan.model;

import java.util.Objects;

/** LAN endpoint only. It is not a product user and its peer ID is diagnostic-only. */
public record DiscoveredDevice(String deviceId, String deviceName, String peerId, String host, int port) {
    public DiscoveredDevice {
        require(deviceId, "deviceId"); require(deviceName, "deviceName"); require(peerId, "peerId"); require(host, "host");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port must be valid");
    }
    private static void require(String value, String field) { if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required"); }
    /** Never use for authorization or product identity. */
    public String diagnosticEndpoint() { return host + ":" + port; }
}
