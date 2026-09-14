package com.landrop.database.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a registered user device mapped to an optional LAN peer endpoint.
 */
public record Device(
    String id,
    UUID userId,
    String deviceName,
    String peerId,
    String ipAddress,
    Integer port,
    Instant lastSeenAt,
    Instant createdAt,
    Instant updatedAt
) {
    public Device(String id, UUID userId, String deviceName, String peerId, String ipAddress, Integer port) {
        this(id, userId, deviceName, peerId, ipAddress, port, Instant.now(), Instant.now(), Instant.now());
    }

    public String deviceId() {
        return id;
    }
}
