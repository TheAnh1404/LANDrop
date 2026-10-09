package com.landrop.util;

import com.landrop.config.AppConfig;

import java.io.PrintStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Lightweight, zero-dependency structured logger for LANDrop.
 * <p>
 * Supports DEBUG, INFO, WARN, and ERROR levels with configurable threshold
 * via {@code -Dlandrop.log.level=DEBUG} or environment variable {@code LANDROP_LOG_LEVEL}.
 * <p>
 * Ensures sensitive data masking and avoids excessive logging in tight loops.
 */
public final class AppLogger {

    public enum Level {
        DEBUG(0),
        INFO(1),
        WARN(2),
        ERROR(3),
        OFF(4);

        private final int severity;

        Level(int severity) {
            this.severity = severity;
        }

        public boolean isLoggable(Level current) {
            return this.severity >= current.severity;
        }

        public static Level fromString(String name) {
            if (name == null || name.isBlank()) {
                return INFO;
            }
            try {
                return Level.valueOf(name.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return INFO;
            }
        }
    }

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter
            .ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    private static volatile Level currentLevel = Level.fromString(AppConfig.getLogLevel());
    private static volatile boolean includeTimestamps = AppConfig.getBoolean("landrop.log.timestamps", "LANDROP_LOG_TIMESTAMPS", false);
    private static final java.util.List<java.util.function.Consumer<String>> LOG_LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    private AppLogger() {}

    public static void addLogListener(java.util.function.Consumer<String> listener) {
        if (listener != null) {
            LOG_LISTENERS.add(listener);
        }
    }

    public static void removeLogListener(java.util.function.Consumer<String> listener) {
        if (listener != null) {
            LOG_LISTENERS.remove(listener);
        }
    }

    public static Level getLevel() {
        return currentLevel;
    }

    public static void setLevel(Level level) {
        if (level != null) {
            currentLevel = level;
        }
    }

    public static boolean isDebugEnabled() {
        return Level.DEBUG.isLoggable(currentLevel);
    }

    public static boolean isInfoEnabled() {
        return Level.INFO.isLoggable(currentLevel);
    }

    public static void debug(String tag, String message) {
        if (Level.DEBUG.isLoggable(currentLevel)) {
            log(System.out, Level.DEBUG, tag, message);
        }
    }

    public static void info(String tag, String message) {
        if (Level.INFO.isLoggable(currentLevel)) {
            log(System.out, Level.INFO, tag, message);
        }
    }

    public static void warn(String tag, String message) {
        if (Level.WARN.isLoggable(currentLevel)) {
            log(System.err, Level.WARN, tag, message);
        }
    }

    public static void warn(String tag, String message, Throwable t) {
        if (Level.WARN.isLoggable(currentLevel)) {
            String msg = (t != null) ? message + " (" + t.getMessage() + ")" : message;
            log(System.err, Level.WARN, tag, msg);
        }
    }

    public static void error(String tag, String message) {
        if (Level.ERROR.isLoggable(currentLevel)) {
            log(System.err, Level.ERROR, tag, message);
        }
    }

    public static void error(String tag, String message, Throwable t) {
        if (Level.ERROR.isLoggable(currentLevel)) {
            String msg = (t != null) ? message + ": " + t.getMessage() : message;
            log(System.err, Level.ERROR, tag, msg);
        }
    }

    private static void log(PrintStream out, Level level, String tag, String message) {
        StringBuilder sb = new StringBuilder();
        if (includeTimestamps) {
            sb.append(TIME_FORMATTER.format(Instant.now())).append(" ");
        }

        // For backward compatibility and clean formatting:
        // When level is INFO and tag already has brackets or tag is standard, keep format clean
        if (level == Level.DEBUG) {
            sb.append("[DEBUG] ");
        } else if (level == Level.WARN) {
            sb.append("[WARN] ");
        } else if (level == Level.ERROR) {
            sb.append("[ERROR] ");
        }

        if (tag != null && !tag.isBlank()) {
            if (!tag.startsWith("[")) {
                sb.append("[").append(tag).append("] ");
            } else {
                sb.append(tag).append(" ");
            }
        }

        sb.append(sanitizeMessage(message));
        String logLine = sb.toString();
        out.println(logLine);
        if (!LOG_LISTENERS.isEmpty()) {
            for (java.util.function.Consumer<String> listener : LOG_LISTENERS) {
                try {
                    listener.accept(logLine);
                } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Sanitizes sensitive data from log statements (e.g. passwords, secrets).
     */
    private static String sanitizeMessage(String message) {
        if (message == null) return "";
        // Mask any password or secret patterns if present
        return message.replaceAll("(?i)(password|secret|token)=[^&\\s]+", "$1=***");
    }
}
