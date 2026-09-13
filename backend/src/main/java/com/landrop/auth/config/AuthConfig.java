package com.landrop.auth.config;

import com.landrop.config.AppConfig;
import com.landrop.util.AppLogger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

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
        return AppConfig.getInt("landrop.auth.port", "AUTH_PORT", DEFAULT_AUTH_PORT);
    }

    public static String getJwtAccessSecret() {
        String secret = getResolvedValue("landrop.jwt.access.secret", "JWT_ACCESS_SECRET");
        return (secret != null && !secret.isBlank()) ? secret.trim() : DEFAULT_ACCESS_SECRET;
    }

    public static String getJwtRefreshSecret() {
        String secret = getResolvedValue("landrop.jwt.refresh.secret", "JWT_REFRESH_SECRET");
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
        String val = System.getProperty(propKey);
        if (val == null || val.isBlank()) {
            val = System.getenv(envKey);
        }
        if (val == null || val.isBlank()) {
            val = readDotEnv(envKey);
        }
        return val;
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
