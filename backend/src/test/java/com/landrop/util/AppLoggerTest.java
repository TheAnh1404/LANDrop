package com.landrop.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppLoggerTest {

    @Test
    @DisplayName("Verify AppLogger log levels and filtering")
    void testLogLevelsAndFiltering() {
        AppLogger.Level orig = AppLogger.getLevel();
        try {
            AppLogger.setLevel(AppLogger.Level.DEBUG);
            assertEquals(AppLogger.Level.DEBUG, AppLogger.getLevel());
            assertTrue(AppLogger.isDebugEnabled());
            assertTrue(AppLogger.isInfoEnabled());

            AppLogger.debug("TEST", "Debug message");
            AppLogger.info("TEST", "Info message");
            AppLogger.warn("TEST", "Warning message");
            AppLogger.error("TEST", "Error message", new RuntimeException("test error"));

            AppLogger.setLevel(AppLogger.Level.WARN);
            assertFalse(AppLogger.isDebugEnabled());
            assertFalse(AppLogger.isInfoEnabled());
        } finally {
            AppLogger.setLevel(orig);
        }
    }

    @Test
    @DisplayName("Verify Level.fromString parsing fallback")
    void testLevelParsing() {
        assertEquals(AppLogger.Level.DEBUG, AppLogger.Level.fromString("debug"));
        assertEquals(AppLogger.Level.INFO, AppLogger.Level.fromString("INFO"));
        assertEquals(AppLogger.Level.WARN, AppLogger.Level.fromString("warn"));
        assertEquals(AppLogger.Level.ERROR, AppLogger.Level.fromString("ERROR"));
        assertEquals(AppLogger.Level.INFO, AppLogger.Level.fromString("invalid_level"));
        assertEquals(AppLogger.Level.INFO, AppLogger.Level.fromString(null));
    }
}
