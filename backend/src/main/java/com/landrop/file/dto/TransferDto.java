package com.landrop.file.dto;

import java.util.UUID;

/**
 * Transfer request and lifecycle progress DTO.
 */
public record TransferDto(
    UUID id,
    UUID fileId,
    FileDto file,
    UUID senderUserId,
    UUID receiverUserId,
    String senderDeviceId,
    String receiverDeviceId,
    String status,
    String createdAt,
    String startedAt,
    String completedAt,
    String failedAt,
    String errorMessage
) {}
