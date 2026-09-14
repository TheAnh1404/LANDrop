package com.landrop.file.dto;

import java.util.UUID;

/**
 * Public-safe projection of User Device and Peer mapping.
 */
public record DeviceDto(
    String deviceId,
    UUID userId,
    String deviceName,
    String peerId,
    String ipAddress,
    Integer port,
    String lastSeenAt,
    boolean isOnline
) {
    public String id() {
        return deviceId;
    }
}
