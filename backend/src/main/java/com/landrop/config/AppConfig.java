package com.landrop.config;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Centralized, production-safe configuration management for LANDrop.
 * <p>
 * Provides sensible defaults for all operational parameters and allows overrides
 * via Java System Properties (e.g. {@code -Dlandrop.discovery.port=8888})
 * or Environment Variables (e.g. {@code DISCOVERY_PORT=8888}).
 * <p>
 * Zero external framework dependencies. Backward compatible with all existing defaults.
 */
public final class AppConfig {

    private AppConfig() {}

    // ==========================================
    // DISCOVERY SERVER CONFIGURATION
    // ==========================================

    public static final int DEFAULT_DISCOVERY_PORT = 8888;
    public static final String DEFAULT_DISCOVERY_HOST = "localhost";
    public static final int DEFAULT_HEARTBEAT_INTERVAL_SEC = 5;
    public static final long DEFAULT_DISCOVERY_TIMEOUT_MS = 15_000L;
    public static final long DEFAULT_DISCOVERY_CLEANUP_SEC = 3L;

    public static int getDiscoveryPort() {
        return getInt("landrop.discovery.port", "DISCOVERY_PORT", DEFAULT_DISCOVERY_PORT);
    }

    public static String getDiscoveryHost() {
        return getString("landrop.discovery.host", "DISCOVERY_HOST", DEFAULT_DISCOVERY_HOST);
    }

    public static int getHeartbeatIntervalSec() {
        return getInt("landrop.discovery.heartbeat.interval.sec", "DISCOVERY_HEARTBEAT_SEC", DEFAULT_HEARTBEAT_INTERVAL_SEC);
    }

    public static long getDiscoveryTimeoutMs() {
        return getLong("landrop.discovery.timeout.ms", "DISCOVERY_TIMEOUT_MS", DEFAULT_DISCOVERY_TIMEOUT_MS);
    }

    public static long getDiscoveryCleanupSec() {
        return getLong("landrop.discovery.cleanup.sec", "DISCOVERY_CLEANUP_SEC", DEFAULT_DISCOVERY_CLEANUP_SEC);
    }

    // ==========================================
    // PEER NETWORKING CONFIGURATION
    // ==========================================

    public static final int DEFAULT_PEER_PORT = 5001;
    public static final String DEFAULT_ADVERTISED_HOST = "127.0.0.1";
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 5000;
    public static final int DEFAULT_SOCKET_TIMEOUT_MS = 10000;

    public static int getPeerPort() {
        return getInt("landrop.peer.port", "PEER_PORT", DEFAULT_PEER_PORT);
    }

    public static String getAdvertisedHost() {
        return getString("landrop.peer.advertised.host", "ADVERTISED_HOST", DEFAULT_ADVERTISED_HOST);
    }

    public static int getConnectTimeoutMs() {
        return getInt("landrop.peer.connect.timeout.ms", "CONNECT_TIMEOUT_MS", DEFAULT_CONNECT_TIMEOUT_MS);
    }

    public static int getSocketTimeoutMs() {
        return getInt("landrop.peer.socket.timeout.ms", "SOCKET_TIMEOUT_MS", DEFAULT_SOCKET_TIMEOUT_MS);
    }

    // ==========================================
    // FILE TRANSFER CONFIGURATION
    // ==========================================

    public static final int DEFAULT_CHUNK_SIZE = 1024 * 1024; // 1 MiB
    public static final int DEFAULT_MAX_CHUNK_RETRIES = 3;
    public static final long DEFAULT_CHUNK_TIMEOUT_MS = 5000L;
    public static final int DEFAULT_MAX_RECONNECT_ATTEMPTS = 5;
    public static final String DEFAULT_DOWNLOAD_FOLDER = "LANDrop";

    public static int getChunkSize() {
        return getInt("landrop.transfer.chunk.size", "CHUNK_SIZE", DEFAULT_CHUNK_SIZE);
    }

    public static int getMaxChunkRetries() {
        return getInt("landrop.transfer.max.chunk.retries", "MAX_CHUNK_RETRIES", DEFAULT_MAX_CHUNK_RETRIES);
    }

    public static long getChunkTimeoutMs() {
        return getLong("landrop.transfer.chunk.timeout.ms", "CHUNK_TIMEOUT_MS", DEFAULT_CHUNK_TIMEOUT_MS);
    }

    public static int getMaxReconnectAttempts() {
        return getInt("landrop.transfer.max.reconnect.attempts", "MAX_RECONNECT_ATTEMPTS", DEFAULT_MAX_RECONNECT_ATTEMPTS);
    }

    public static Path getDownloadDirectory() {
        String customDir = getString("landrop.transfer.download.dir", "DOWNLOAD_DIR", null);
        if (customDir != null && !customDir.isBlank()) {
            return Paths.get(customDir).toAbsolutePath().normalize();
        }
        String userHome = System.getProperty("user.home", ".");
        return Paths.get(userHome, "Downloads", DEFAULT_DOWNLOAD_FOLDER).toAbsolutePath().normalize();
    }

    // ==========================================
    // LOGGING & OBSERVABILITY CONFIGURATION
    // ==========================================

    public static final String DEFAULT_LOG_LEVEL = "INFO";

    public static String getLogLevel() {
        return getString("landrop.log.level", "LANDROP_LOG_LEVEL", DEFAULT_LOG_LEVEL).toUpperCase();
    }

    // ==========================================
    // INTERNAL UTILITY PARSERS
    // ==========================================

    public static int getInt(String propKey, String envKey, int defaultValue) {
        String val = System.getProperty(propKey);
        if (val == null && envKey != null) {
            val = System.getenv(envKey);
        }
        if (val != null && !val.isBlank()) {
            try {
                return Integer.parseInt(val.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    public static long getLong(String propKey, String envKey, long defaultValue) {
        String val = System.getProperty(propKey);
        if (val == null && envKey != null) {
            val = System.getenv(envKey);
        }
        if (val != null && !val.isBlank()) {
            try {
                return Long.parseLong(val.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    public static String getString(String propKey, String envKey, String defaultValue) {
        String val = System.getProperty(propKey);
        if (val == null && envKey != null) {
            val = System.getenv(envKey);
        }
        if (val != null && !val.isBlank()) {
            return val.trim();
        }
        return defaultValue;
    }

    public static boolean getBoolean(String propKey, String envKey, boolean defaultValue) {
        String val = System.getProperty(propKey);
        if (val == null && envKey != null) {
            val = System.getenv(envKey);
        }
        if (val != null && !val.isBlank()) {
            return Boolean.parseBoolean(val.trim());
        }
        return defaultValue;
    }
}
