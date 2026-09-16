package com.landrop.device.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.landrop.database.model.Device;

import java.time.Instant;
import java.util.UUID;

/**
 * Public DTO representing an enrolled User Device (FOUNDATION-04A.5, 04A.13).
 * Never exposes sensitive peer credentials, socket internals, or network secrets.
 */
public record DeviceDto(
    String id,
    UUID userId,
    String clientInstanceId,
    String name,
    String deviceName,
    String platform,
    String clientVersion,
    String status,
    boolean isOnline,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    Instant lastSeenAt,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    Instant createdAt,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    Instant updatedAt
) {
    public static DeviceDto from(Device d, boolean isOnline) {
        if (d == null) return null;
        return new DeviceDto(
            d.id(),
            d.userId(),
            d.clientInstanceId(),
            d.deviceName(),
            d.deviceName(),
            d.platform(),
            d.clientVersion(),
            d.status(),
            isOnline,
            d.lastSeenAt(),
            d.createdAt(),
            d.updatedAt()
        );
    }
}
