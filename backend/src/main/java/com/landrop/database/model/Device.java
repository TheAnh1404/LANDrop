package com.landrop.database.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a registered user device mapped to persistent metadata (FOUNDATION-04A).
 * Decouples runtime client connections from persistent device identities.
 */
public record Device(
    String id,
    UUID userId,
    String clientInstanceId,
    String deviceName,
    String platform,
    String clientVersion,
    String status,
    String peerId,
    String ipAddress,
    Integer port,
    Instant lastSeenAt,
    Instant createdAt,
    Instant updatedAt
) {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    public Device(String id, UUID userId, String clientInstanceId, String deviceName, String platform, String clientVersion) {
        this(
            id,
            userId,
            clientInstanceId,
            deviceName,
            platform != null ? platform : "unknown",
            clientVersion != null ? clientVersion : "1.0.0",
            STATUS_ACTIVE,
            null,
            null,
            null,
            Instant.now(),
            Instant.now(),
            Instant.now()
        );
    }

    public Device(String id, UUID userId, String deviceName, String peerId, String ipAddress, Integer port) {
        this(id, userId, id, deviceName, "unknown", "1.0.0", STATUS_ACTIVE, peerId, ipAddress, port, Instant.now(), Instant.now(), Instant.now());
    }

    public String deviceId() {
        return id;
    }

    public String name() {
        return deviceName;
    }

    public Device withDeviceName(String newName) {
        return new Device(id, userId, clientInstanceId, newName, platform, clientVersion, status, peerId, ipAddress, port, lastSeenAt, createdAt, Instant.now());
    }

    public Device withLastSeenAt(Instant seenAt) {
        return new Device(id, userId, clientInstanceId, deviceName, platform, clientVersion, status, peerId, ipAddress, port, seenAt != null ? seenAt : Instant.now(), createdAt, Instant.now());
    }

    public Device withStatus(String newStatus) {
        return new Device(id, userId, clientInstanceId, deviceName, platform, clientVersion, newStatus, peerId, ipAddress, port, lastSeenAt, createdAt, Instant.now());
    }
}
