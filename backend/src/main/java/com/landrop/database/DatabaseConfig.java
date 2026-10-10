package com.landrop.database;

import com.landrop.config.AppConfig;

/**
 * Configuration provider for PostgreSQL database connections (Neon Cloud).
 * <p>
 * Evaluates in priority order:
 * 1. Java System Property (e.g. {@code -Dlandrop.database.url=...})
 * 2. OS Environment Variable (e.g. {@code DATABASE_URL=...})
 * 3. Local {@code .env.local}, then {@code .env}, in the working directory or parent directories.
 */
public final class DatabaseConfig {

    private DatabaseConfig() {}

    public static String getDatabaseUrl() {
        // Explicitly opt out of persistence for local regression runs even when
        // a developer has a cloud DATABASE_URL in the repository .env file.
        if ("false".equalsIgnoreCase(System.getProperty("landrop.database.enabled"))) {
            return null;
        }
        String url = com.landrop.config.EnvironmentFiles.resolve("landrop.database.url", "DATABASE_URL");
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
}
