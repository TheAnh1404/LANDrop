package com.landrop.ui.config;

import java.net.URI;
import java.util.Map;

/**
 * Resolves application configuration from command line arguments, system properties,
 * and environment variables, eliminating hardcoded localhost assumptions.
 */
public final class FrontendConfig {

    /** Development-only defaults; deployment uses CLI, system properties, or environment variables. */
    public static final String DEFAULT_API_BASE_URL = "http://localhost:3000/api";
    public static final String DEFAULT_WS_URL = "ws://localhost:3001";
    public static final String DEFAULT_DISCOVERY_HOST = "127.0.0.1";
    public static final String DEFAULT_ADVERTISED_HOST = "127.0.0.1";
    public static final int DEFAULT_DISCOVERY_PORT = 8888;

    private FrontendConfig() {}

    /**
     * Resolves the REST API base URL.
     * Priority:
     * 1. CLI named parameter: {@code --api-url=...}
     * 2. JVM system property: {@code -Dlandrop.api.base-url=...} or {@code -Dlandrop.auth.base-url=...}
     * 3. Environment variables: {@code LANDROP_API_URL}, {@code VITE_API_URL}, {@code API_URL}
     * 4. Default: {@code http://localhost:3000/api}
     */
    public static String resolveApiBaseUrl(Map<String, String> namedArgs) {
        if (namedArgs != null && namedArgs.containsKey("api-url")) {
            String val = stripQuotes(namedArgs.get("api-url"));
            if (!val.isBlank()) return normalizeUrl(val);
        }

        String sysProp = System.getProperty("landrop.api.base-url");
        if (sysProp == null || sysProp.isBlank()) {
            sysProp = System.getProperty("landrop.auth.base-url");
        }
        if (sysProp != null && !sysProp.isBlank()) {
            return normalizeUrl(stripQuotes(sysProp));
        }

        String env = getEnv("LANDROP_API_URL", "VITE_API_URL", "API_URL");
        if (env != null && !env.isBlank()) {
            return normalizeUrl(stripQuotes(env));
        }

        return DEFAULT_API_BASE_URL;
    }

    /**
     * Resolves the WebSocket URL for real-time messaging.
     * Priority:
     * 1. CLI named parameter: {@code --ws-url=...}
     * 2. JVM system property: {@code -Dlandrop.ws.url=...}
     * 3. Environment variables: {@code LANDROP_WS_URL}, {@code VITE_WS_URL}, {@code WS_URL}
     * 4. Derived automatically from the resolved API base URL (e.g. {@code http://host:port/api} -> {@code ws://host:port/ws})
     */
    public static String resolveWsUrl(Map<String, String> namedArgs, String apiBaseUrl) {
        if (namedArgs != null && namedArgs.containsKey("ws-url")) {
            String val = stripQuotes(namedArgs.get("ws-url"));
            if (!val.isBlank()) return normalizeUrl(val);
        }

        String sysProp = System.getProperty("landrop.ws.url");
        if (sysProp != null && !sysProp.isBlank()) {
            return normalizeUrl(stripQuotes(sysProp));
        }

        String env = getEnv("LANDROP_WS_URL", "VITE_WS_URL", "WS_URL");
        if (env != null && !env.isBlank()) {
            return normalizeUrl(stripQuotes(env));
        }

        if (apiBaseUrl != null && !apiBaseUrl.isBlank()) {
            return deriveWsUrl(apiBaseUrl);
        }

        return DEFAULT_WS_URL;
    }

    /**
     * Resolves the session profile partition name.
     * Used to isolate preferences/sessions when running multiple instances on the same machine.
     */
    public static String resolveSessionProfile(Map<String, String> namedArgs) {
        if (namedArgs != null && namedArgs.containsKey("session-profile")) {
            String val = stripQuotes(namedArgs.get("session-profile"));
            if (!val.isBlank()) return val;
        }

        String sysProp = System.getProperty("landrop.session.profile");
        if (sysProp != null && !sysProp.isBlank()) {
            return stripQuotes(sysProp);
        }

        String env = getEnv("LANDROP_SESSION_PROFILE");
        if (env != null && !env.isBlank()) {
            return stripQuotes(env);
        }

        return null;
    }

    /**
     * Derives a WebSocket URL from an HTTP(S) API base URL.
     * Example: {@code http://192.168.1.10:3000/api} -> {@code ws://192.168.1.10:3000/ws}
     */
    public static String deriveWsUrl(String apiBaseUrl) {
        try {
            URI uri = URI.create(apiBaseUrl);
            String scheme = uri.getScheme();
            String wsScheme = "https".equalsIgnoreCase(scheme) ? "wss" : "ws";
            String host = uri.getHost() != null ? uri.getHost() : "localhost";
            // The embedded LANDrop backend serves HTTP on 3000 and realtime on 3001.
            // Deployments with a gateway should provide WS_URL explicitly.
            int port = uri.getPort() == 3000 ? 3001 : uri.getPort();
            String authority = (port > 0) ? host + ":" + port : host;
            return wsScheme + "://" + authority + "/ws";
        } catch (Exception e) {
            return DEFAULT_WS_URL;
        }
    }

    private static String getEnv(String... varNames) {
        for (String varName : varNames) {
            String val = System.getenv(varName);
            if (val != null && !val.isBlank()) {
                return val;
            }
        }
        return null;
    }

    private static String stripQuotes(String s) {
        if (s == null) return "";
        String trimmed = s.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) ||
            (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            if (trimmed.length() >= 2) {
                return trimmed.substring(1, trimmed.length() - 1).trim();
            }
        }
        return trimmed;
    }

    private static String normalizeUrl(String url) {
        if (url == null) return "";
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
