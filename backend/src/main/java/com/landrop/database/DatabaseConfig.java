package com.landrop.database;

import com.landrop.config.AppConfig;
import com.landrop.util.AppLogger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Configuration provider for PostgreSQL database connections (Neon Cloud).
 * <p>
 * Evaluates in priority order:
 * 1. Java System Property (e.g. {@code -Dlandrop.database.url=...})
 * 2. OS Environment Variable (e.g. {@code DATABASE_URL=...})
 * 3. Local {@code .env} file in current working directory or parent directories.
 */
public final class DatabaseConfig {

    private static final String TAG = "DatabaseConfig";

    private DatabaseConfig() {}

    public static String getDatabaseUrl() {
        // Explicitly opt out of persistence for local regression runs even when
        // a developer has a cloud DATABASE_URL in the repository .env file.
        if ("false".equalsIgnoreCase(System.getProperty("landrop.database.enabled"))) {
            return null;
        }
        String url = System.getProperty("landrop.database.url");
        if (url == null || url.isBlank()) {
            url = System.getenv("DATABASE_URL");
        }
        if (url == null || url.isBlank()) {
            url = readDotEnv("DATABASE_URL");
        }
        return url != null ? url.trim() : null;
    }

    public static int getMaximumPoolSize() {
        return AppConfig.getInt("landrop.database.pool.max", "DATABASE_POOL_MAX", 10);
    }

    public static int getMinimumIdle() {
        return AppConfig.getInt("landrop.database.pool.min.idle", "DATABASE_POOL_MIN_IDLE", 2);
    }

    public static long getConnectionTimeoutMs() {
        return AppConfig.getLong("landrop.database.pool.timeout.ms", "DATABASE_TIMEOUT_MS", 30_000L);
    }

    public static long getIdleTimeoutMs() {
        return AppConfig.getLong("landrop.database.pool.idle.timeout.ms", "DATABASE_IDLE_TIMEOUT_MS", 600_000L);
    }

    public static long getMaxLifetimeMs() {
        return AppConfig.getLong("landrop.database.pool.max.lifetime.ms", "DATABASE_MAX_LIFETIME_MS", 1_800_000L);
    }

    private static String readDotEnv(String key) {
        File[] candidateFiles = new File[] {
            new File(".env"),
            new File("../.env"),
            new File("../../.env")
        };

        for (File envFile : candidateFiles) {
            if (envFile.exists() && envFile.isFile()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(envFile, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.startsWith("#") || line.isEmpty()) {
                            continue;
                        }
                        int eqIdx = line.indexOf('=');
                        if (eqIdx > 0) {
                            String k = line.substring(0, eqIdx).trim();
                            if (k.equals(key)) {
                                String v = line.substring(eqIdx + 1).trim();
                                if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
                                    v = v.substring(1, v.length() - 1);
                                }
                                return v;
                            }
                        }
                    }
                } catch (IOException e) {
                    AppLogger.warn(TAG, "Failed to parse .env file at " + envFile.getAbsolutePath(), e);
                }
            }
        }
        return null;
    }
}
