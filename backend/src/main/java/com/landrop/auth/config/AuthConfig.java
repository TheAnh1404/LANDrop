package com.landrop.auth.config;

import com.landrop.util.AppLogger;

/**
 * Configuration provider for Phase 15A Authentication & User Sessions.
 * Resolves properties from System Properties, OS Environment Variables, and local .env files.
 */
public final class AuthConfig {

    private static final String TAG = "AuthConfig";

    // Defaults
    public static final int DEFAULT_AUTH_PORT = 3000;
    public static final long DEFAULT_ACCESS_TOKEN_EXPIRY_SEC = 900L;       // 15 minutes
    public static final long DEFAULT_REFRESH_TOKEN_EXPIRY_SEC = 604_800L;   // 7 days

    private static final String DEFAULT_ACCESS_SECRET =
        "landrop_phase15a_jwt_access_secret_key_minimum_256_bits_for_hmac_sha256_security_production_grade";
    private static final String DEFAULT_REFRESH_SECRET =
        "landrop_phase15a_jwt_refresh_secret_key_minimum_256_bits_for_hmac_sha256_security_production_grade";

    private AuthConfig() {}

    public static int getAuthPort() {
        return port("landrop.auth.port", "AUTH_PORT", DEFAULT_AUTH_PORT);
    }

    public static int getRealtimePort() { return port("landrop.realtime.port", "REALTIME_PORT", 3001); }

    public static String getBindHost() {
        String host = getResolvedValue("landrop.bind.host", "BIND_HOST");
        return host == null || host.isBlank() ? "0.0.0.0" : host.trim();
    }

    private static int port(String property, String environment, int fallback) {
        String value = getResolvedValue(property, environment);
        int port = value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        if (port < 1 || port > 65535) throw new IllegalArgumentException(environment + " must be between 1 and 65535");
        return port;
    }

    public static boolean isProduction() {
        String env = getResolvedValue("landrop.environment", "NODE_ENV");
        if (env == null || env.isBlank()) {
            env = getResolvedValue("landrop.env", "ENVIRONMENT");
        }
        if (env == null || env.isBlank()) {
            env = getResolvedValue("app.env", "APP_ENV");
        }
        return env != null && (env.equalsIgnoreCase("production") || env.equalsIgnoreCase("prod"));
    }

    public static void validateProductionSecret(String name, String secret, String defaultSecret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("Production requires " + name + " to be explicitly configured.");
        }
        String trimmed = secret.trim();
        if (trimmed.equals(defaultSecret)) {
            throw new IllegalStateException("Production cannot use the hardcoded default " + name + ".");
        }
        if (trimmed.startsWith("replace-with-") || trimmed.contains("example") || trimmed.contains("your-secret")) {
            throw new IllegalStateException("Production cannot use placeholder " + name + ".");
        }
        if (trimmed.length() < 32) {
            throw new IllegalStateException("Production requires " + name + " to have at least 32 characters (256 bits).");
        }
    }

    public static void validateProductionSecrets() {
        String accessSecret = getResolvedValue("landrop.jwt.access.secret", "JWT_ACCESS_SECRET");
        validateProductionSecret("JWT_ACCESS_SECRET", accessSecret, DEFAULT_ACCESS_SECRET);

        String refreshSecret = getResolvedValue("landrop.jwt.refresh.secret", "JWT_REFRESH_SECRET");
        validateProductionSecret("JWT_REFRESH_SECRET", refreshSecret, DEFAULT_REFRESH_SECRET);

        if (accessSecret.trim().equals(refreshSecret.trim())) {
            throw new IllegalStateException("Production requires distinct JWT_ACCESS_SECRET and JWT_REFRESH_SECRET.");
        }
    }

    public static String getJwtAccessSecret() {
        String secret = getResolvedValue("landrop.jwt.access.secret", "JWT_ACCESS_SECRET");
        if (isProduction()) {
            validateProductionSecret("JWT_ACCESS_SECRET", secret, DEFAULT_ACCESS_SECRET);
        }
        return (secret != null && !secret.isBlank()) ? secret.trim() : DEFAULT_ACCESS_SECRET;
    }

    public static String getJwtRefreshSecret() {
        String secret = getResolvedValue("landrop.jwt.refresh.secret", "JWT_REFRESH_SECRET");
        if (isProduction()) {
            validateProductionSecret("JWT_REFRESH_SECRET", secret, DEFAULT_REFRESH_SECRET);
        }
        return (secret != null && !secret.isBlank()) ? secret.trim() : DEFAULT_REFRESH_SECRET;
    }

    public static long getAccessTokenExpirySeconds() {
        String val = getResolvedValue("landrop.jwt.access.expires.in", "JWT_ACCESS_EXPIRES_IN");
        if (val != null && !val.isBlank()) {
            try {
                return parseDurationToSeconds(val.trim());
            } catch (Exception e) {
                AppLogger.warn(TAG, "Invalid JWT_ACCESS_EXPIRES_IN format (" + val + "), using default");
            }
        }
        return DEFAULT_ACCESS_TOKEN_EXPIRY_SEC;
    }

    public static long getRefreshTokenExpirySeconds() {
        String val = getResolvedValue("landrop.jwt.refresh.expires.in", "JWT_REFRESH_EXPIRES_IN");
        if (val != null && !val.isBlank()) {
            try {
                return parseDurationToSeconds(val.trim());
            } catch (Exception e) {
                AppLogger.warn(TAG, "Invalid JWT_REFRESH_EXPIRES_IN format (" + val + "), using default");
            }
        }
        return DEFAULT_REFRESH_TOKEN_EXPIRY_SEC;
    }

    public static long parseDurationToSeconds(String val) {
        if (val == null || val.isBlank()) {
            return DEFAULT_ACCESS_TOKEN_EXPIRY_SEC;
        }
        val = val.trim().toLowerCase();
        if (val.endsWith("s")) {
            return Long.parseLong(val.substring(0, val.length() - 1));
        } else if (val.endsWith("m")) {
            return Long.parseLong(val.substring(0, val.length() - 1)) * 60;
        } else if (val.endsWith("h")) {
            return Long.parseLong(val.substring(0, val.length() - 1)) * 3600;
        } else if (val.endsWith("d")) {
            return Long.parseLong(val.substring(0, val.length() - 1)) * 86400;
        }
        return Long.parseLong(val);
    }

    private static String getResolvedValue(String propKey, String envKey) {
        return com.landrop.config.EnvironmentFiles.resolve(propKey, envKey);
    }
}
