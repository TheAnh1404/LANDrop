package com.landrop.admin.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.landrop.server.clients.ClientConnection;

import java.time.Instant;

/**
 * Public administrative representation of a connected WebSocket client (FOUNDATION-03A.12).
 */
public record AdminClientDto(
    String connectionId,
    String clientInstanceId,
    String deviceName,
    String remoteAddress,
    int protocolVersion,
    String clientVersion,
    String platform,
    String osVersion,
    String architecture,
    String status,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    Instant connectedAt,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    Instant lastHeartbeatAt,
    java.util.UUID userId,
    String username,
    String deviceId,
    String sessionId,
    boolean authenticated
) {
    public static AdminClientDto from(ClientConnection c) {
        if (c == null) return null;
        return new AdminClientDto(
            c.connectionId(),
            c.clientInstanceId(),
            c.deviceName(),
            c.remoteAddress(),
            c.protocolVersion(),
            c.clientVersion(),
            c.platform(),
            c.osVersion(),
            c.architecture(),
            c.status() != null ? c.status().name() : "UNKNOWN",
            c.connectedAt(),
            c.lastHeartbeatAt(),
            c.userId(),
            c.username(),
            c.deviceId(),
            c.sessionId(),
            c.isAuthenticated()
        );
    }
}
