package com.landrop.server.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * Standardized realtime event envelope conforming to FOUNDATION-01A Section 10.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventEnvelope(
    String type,
    String requestId,
    String timestamp,
    Object payload,
    ErrorDetail error,
    // Top-level fields for flat handshake / welcome compatibility
    String connectionId,
    String serverVersion,
    Integer protocolVersion,
    Long heartbeatIntervalMs,
    String code,
    String message
) {

    public record ErrorDetail(String code, String message) {}

    public static EventEnvelope event(String type, String requestId, Object payload) {
        return new EventEnvelope(
            type,
            requestId,
            Instant.now().toString(),
            payload,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    public static EventEnvelope welcome(String connectionId, String serverVersion, int protocolVersion, long heartbeatIntervalMs) {
        return new EventEnvelope(
            ProtocolConstants.EVENT_WELCOME,
            null,
            Instant.now().toString(),
            Map.of(
                "connectionId", connectionId,
                "serverVersion", serverVersion,
                "protocolVersion", protocolVersion,
                "heartbeatIntervalMs", heartbeatIntervalMs
            ),
            null,
            connectionId,
            serverVersion,
            protocolVersion,
            heartbeatIntervalMs,
            null,
            null
        );
    }

    public static EventEnvelope error(String requestId, String code, String message) {
        return new EventEnvelope(
            ProtocolConstants.EVENT_ERROR,
            requestId,
            Instant.now().toString(),
            null,
            new ErrorDetail(code, message),
            null,
            null,
            null,
            null,
            code,
            message
        );
    }

    public static EventEnvelope echoAck(String requestId, Object payload) {
        return new EventEnvelope(
            ProtocolConstants.EVENT_ECHO_ACK,
            requestId,
            Instant.now().toString(),
            payload,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    public static EventEnvelope heartbeatAck(String requestId) {
        return new EventEnvelope(
            ProtocolConstants.EVENT_HEARTBEAT_ACK,
            requestId,
            Instant.now().toString(),
            Map.of("serverTimestamp", Instant.now().toString()),
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }
}
