package com.landrop.device.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.landrop.auth.exception.ValidationException;

/**
 * Request payload for renaming a device (FOUNDATION-04A.14).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeviceRenameRequest(
    String name,
    String deviceName
) {
    public void validate() {
        String resolved = resolvedName();
        if (resolved == null || resolved.isBlank()) {
            throw new ValidationException("New device name cannot be empty");
        }
    }

    public String resolvedName() {
        if (name != null && !name.isBlank()) return name.trim();
        if (deviceName != null && !deviceName.isBlank()) return deviceName.trim();
        return null;
    }
}
