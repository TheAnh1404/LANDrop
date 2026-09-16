package com.landrop.auth.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Standard error response envelope.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
    String code,
    String error,
    String message,
    int status,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    Instant timestamp
) {
    public ErrorResponse(String code, String error, String message, int status) {
        this(code, error, message, status, Instant.now());
    }

    public ErrorResponse(String error, String message, int status) {
        this(null, error, message, status, Instant.now());
    }
}
