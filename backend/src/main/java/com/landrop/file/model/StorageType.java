package com.landrop.file.model;

/**
 * Storage type categorization for file metadata according to CORE-01A.10.
 * Separates control-plane file metadata from actual storage/transport implementations.
 * Supported types:
 * - LAN_ONLY: Files only transferred directly peer-to-peer over LAN.
 * - LOCAL: Files hosted locally on the user's current device.
 * - SERVER: Files stored on the central server backend.
 * - OBJECT_STORAGE: Files stored in object storage (S3 / Cloud Storage / CDN).
 */
public enum StorageType {
    LAN_ONLY,
    LOCAL,
    SERVER,
    OBJECT_STORAGE;

    /**
     * Parses a string to StorageType, defaulting to LAN_ONLY if null, empty, or unrecognized.
     */
    public static StorageType fromString(String val) {
        if (val == null || val.isBlank()) {
            return LAN_ONLY;
        }
        try {
            return StorageType.valueOf(val.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return LAN_ONLY;
        }
    }
}
