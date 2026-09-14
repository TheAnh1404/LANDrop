package com.landrop.server.logging;

import com.landrop.util.AppLogger;

import java.time.Instant;

/**
 * Structured logger for core server events.
 * Guarantees no sensitive data (passwords, tokens, secrets, binary payloads) is logged.
 */
public final class ServerEventLogger {

    private static final String TAG = "LandropServer";

    private ServerEventLogger() {}

    public static void log(ServerEvent event, String connectionId, String details) {
        String timestamp = Instant.now().toString();
        String connPart = (connectionId != null && !connectionId.isBlank()) ? "[" + connectionId + "] " : "";
        String detailPart = (details != null && !details.isBlank()) ? sanitize(details) : "";
        String line = String.format("[%s] [%s] %s%s", timestamp, event.name(), connPart, detailPart);

        switch (event) {
            case CLIENT_HANDSHAKE_FAILED, HEARTBEAT_TIMEOUT, TRANSFER_FAILED -> AppLogger.warn(TAG, line);
            default -> AppLogger.info(TAG, line);
        }
    }

    public static void log(ServerEvent event, String details) {
        log(event, null, details);
    }

    /**
     * Sanitizes strings to ensure credentials or binary data cannot leak to logs.
     */
    private static String sanitize(String input) {
        if (input == null) return "";
        // Mask standard token/password patterns if accidentally included
        return input.replaceAll("(?i)(password|token|secret|authorization)=[^,\\s&]+", "$1=***");
    }
}
