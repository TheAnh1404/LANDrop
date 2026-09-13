package com.landrop.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppConfigTest {

    @Test
    @DisplayName("Verify default configuration values adhere to specification")
    void testDefaultConfigValues() {
        assertEquals(AppConfig.DEFAULT_DISCOVERY_PORT, AppConfig.getDiscoveryPort());
        assertEquals("localhost", AppConfig.getDiscoveryHost());
        assertEquals(5, AppConfig.getHeartbeatIntervalSec());
        assertEquals(15_000L, AppConfig.getDiscoveryTimeoutMs());
        assertEquals(3L, AppConfig.getDiscoveryCleanupSec());

        assertEquals(AppConfig.DEFAULT_PEER_PORT, AppConfig.getPeerPort());
        assertEquals("127.0.0.1", AppConfig.getAdvertisedHost());
        assertEquals(5000, AppConfig.getConnectTimeoutMs());
        assertEquals(10000, AppConfig.getSocketTimeoutMs());

        assertEquals(1024 * 1024, AppConfig.getChunkSize());
        assertEquals(3, AppConfig.getMaxChunkRetries());
        assertEquals(5000L, AppConfig.getChunkTimeoutMs());
        assertEquals(5, AppConfig.getMaxReconnectAttempts());

        Path downloadDir = AppConfig.getDownloadDirectory();
        assertNotNull(downloadDir);
        assertTrue(downloadDir.toString().contains("LANDrop"));

        assertEquals("INFO", AppConfig.getLogLevel());
    }

    @Test
    @DisplayName("Verify System property overrides for operational configuration")
    void testSystemPropertyOverrides() {
        String origPort = System.getProperty("landrop.discovery.port");
        String origLogLevel = System.getProperty("landrop.log.level");
        try {
            System.setProperty("landrop.discovery.port", "9999");
            System.setProperty("landrop.log.level", "DEBUG");

            assertEquals(9999, AppConfig.getDiscoveryPort());
            assertEquals("DEBUG", AppConfig.getLogLevel());
        } finally {
            if (origPort != null) System.setProperty("landrop.discovery.port", origPort);
            else System.clearProperty("landrop.discovery.port");

            if (origLogLevel != null) System.setProperty("landrop.log.level", origLogLevel);
            else System.clearProperty("landrop.log.level");
        }
    }

    @Test
    @DisplayName("Verify utility parsers handle invalid numbers gracefully")
    void testUtilityParsers() {
        assertEquals(42, AppConfig.getInt("non.existent.int", null, 42));
        assertEquals(100L, AppConfig.getLong("non.existent.long", null, 100L));
        assertEquals("fallback", AppConfig.getString("non.existent.str", null, "fallback"));
        assertFalse(AppConfig.getBoolean("non.existent.bool", null, false));
    }
}
