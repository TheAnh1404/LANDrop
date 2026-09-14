package com.landrop.database.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistent representation of a user-authorized file transfer request.
 */
public record TransferRecord(
    UUID id,
    UUID fileId,
    UUID senderUserId,
    UUID receiverUserId,
    String senderDeviceId,
    String receiverDeviceId,
    String status,
    Instant createdAt,
    Instant startedAt,
    Instant completedAt,
    Instant failedAt,
    String errorMessage
) {
    public TransferRecord(UUID fileId, UUID senderUserId, UUID receiverUserId, String senderDeviceId, String receiverDeviceId) {
        this(UUID.randomUUID(), fileId, senderUserId, receiverUserId, senderDeviceId, receiverDeviceId, "PENDING",
             Instant.now(), null, null, null, null);
    }
}
