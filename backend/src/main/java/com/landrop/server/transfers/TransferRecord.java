package com.landrop.server.transfers;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Runtime transfer state record maintained in-memory on the Control Plane.
 * Binary data does NOT travel through this record or the WebSocket control channel.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransferRecord(
    String transferId,
    String senderConnectionId,
    String receiverConnectionId,
    String fileName,
    long fileSize,
    String sha256,
    TransferStatus status,
    Instant createdAt,
    Instant updatedAt,
    String errorMessage
) {
    public TransferRecord withStatus(TransferStatus newStatus, String error) {
        return new TransferRecord(
            transferId,
            senderConnectionId,
            receiverConnectionId,
            fileName,
            fileSize,
            sha256,
            newStatus,
            createdAt,
            Instant.now(),
            error
        );
    }
}
