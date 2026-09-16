package com.landrop.device.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.landrop.auth.exception.ValidationException;

/**
 * Request payload for device enrollment (FOUNDATION-04A.6).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeviceEnrollRequest(
    String clientInstanceId,
    String deviceName,
    String name,
    String platform,
    String clientVersion
) {
    public void validate() {
        if (clientInstanceId == null || clientInstanceId.isBlank()) {
            throw new ValidationException("clientInstanceId is required");
        }
        String resolved = resolvedName();
        if (resolved == null || resolved.isBlank()) {
            throw new ValidationException("deviceName (or name) is required");
        }
    }

    public String resolvedName() {
        if (deviceName != null && !deviceName.isBlank()) return deviceName.trim();
        if (name != null && !name.isBlank()) return name.trim();
        return null;
    }

    public String resolvedPlatform() {
        return (platform != null && !platform.isBlank()) ? platform.trim() : "unknown";
    }

    public String resolvedClientVersion() {
        return (clientVersion != null && !clientVersion.isBlank()) ? clientVersion.trim() : "1.0.0";
    }
}
