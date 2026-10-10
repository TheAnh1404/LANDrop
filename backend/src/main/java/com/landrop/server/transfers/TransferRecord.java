package com.landrop.server.transfers;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

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
    String errorMessage,
    UUID workspaceId,
    UUID senderUserId,
    String senderSessionId,
    UUID receiverUserId,
    String receiverSessionId,
    Instant expiresAt,
    boolean offerRelayed,
    boolean answerRelayed,
    boolean senderConnected,
    boolean receiverConnected
) {
    public TransferRecord withExpiry(Instant deadline, Instant now) {
        return new TransferRecord(transferId, senderConnectionId, receiverConnectionId, fileName, fileSize,
                sha256, status, createdAt, now, errorMessage, workspaceId, senderUserId, senderSessionId,
                receiverUserId, receiverSessionId, deadline, offerRelayed, answerRelayed, senderConnected, receiverConnected);
    }
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
            error, workspaceId, senderUserId, senderSessionId, receiverUserId,
            receiverSessionId, expiresAt, offerRelayed, answerRelayed, senderConnected, receiverConnected
        );
    }

    public TransferRecord withSignaling(TransferStatus next, boolean offer, boolean answer,
                                        boolean senderReady, boolean receiverReady) {
        return new TransferRecord(transferId, senderConnectionId, receiverConnectionId, fileName, fileSize,
                sha256, next, createdAt, Instant.now(), null, workspaceId, senderUserId, senderSessionId,
                receiverUserId, receiverSessionId, expiresAt, offer, answer, senderReady, receiverReady);
    }
}
