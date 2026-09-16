package com.landrop.admin.logging;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

/**
 * Structured audit record for server and administrative events (FOUNDATION-03A.15).
 * Guaranteed to never contain password hashes, tokens, or plaintext secrets.
 */
public record OperationalLogEntry(
    long id,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    Instant timestamp,
    String eventType,
    String level,
    String actor,
    String target,
    String details,
    String ipAddress
) {}
