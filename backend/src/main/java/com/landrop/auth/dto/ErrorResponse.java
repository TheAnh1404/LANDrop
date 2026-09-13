package com.landrop.auth.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

/**
 * Standard error response envelope.
 */
public record ErrorResponse(
    String error,
    String message,
    int status,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    Instant timestamp
) {
    public ErrorResponse(String error, String message, int status) {
        this(error, message, status, Instant.now());
    }
}
