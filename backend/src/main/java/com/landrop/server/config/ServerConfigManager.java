package com.landrop.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.landrop.admin.logging.OperationalLogService;
import com.landrop.auth.exception.ValidationException;
import com.landrop.server.protocol.ProtocolConstants;
import com.landrop.util.AppLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages Server Runtime Configuration, Current vs Pending states,
 * validation rules, and persistence to data/server-config.json (FOUNDATION-03A.5 - 03A.7).
 */
public class ServerConfigManager {

    private static final String TAG = "ServerConfigManager";
    public static final String DEFAULT_CONFIG_PATH = "data/server-config.json";

    private final ObjectMapper objectMapper;
    private final Path configPath;
    private final ServerConfig currentConfig;
    private ServerConfig pendingConfig;
    private boolean requiresRestart = false;

    public ServerConfigManager(ServerConfig currentConfig, Path configPath) {
        this.currentConfig = currentConfig != null ? currentConfig : ServerConfig.load();
        this.configPath = configPath != null ? configPath : Paths.get(DEFAULT_CONFIG_PATH);
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        loadPendingConfigFromFile();
    }

    public ServerConfigManager(ServerConfig currentConfig) {
        this(currentConfig, Paths.get(DEFAULT_CONFIG_PATH));
    }

    public ServerConfig getCurrentConfig() {
        return currentConfig;
    }

    public ServerConfig getPendingConfig() {
        return pendingConfig != null ? pendingConfig : currentConfig;
    }

    public boolean isRequiresRestart() {
        return requiresRestart;
    }

    public synchronized ServerConfig updateConfig(Map<String, Object> updates, String actor) {
        if (updates == null || updates.isEmpty()) {
            throw new ValidationException("Update configuration payload cannot be empty");
        }

        // 1. Start from current or already pending config
        ServerConfig base = pendingConfig != null ? pendingConfig : currentConfig;

        String host = base.getHost();
        if (updates.containsKey("host") && updates.get("host") != null) {
            host = updates.get("host").toString().trim();
        }

        int httpPort = base.getHttpPort();
        if (updates.containsKey("httpPort") && updates.get("httpPort") != null) {
            httpPort = parsePort(updates.get("httpPort"), "httpPort");
        }

        int wsPort = base.getWsPort();
        if (updates.containsKey("wsPort") && updates.get("wsPort") != null) {
            wsPort = parsePort(updates.get("wsPort"), "wsPort");
        }

        if (httpPort == wsPort) {
            throw new ValidationException("HTTP port and WebSocket port cannot be the same (" + httpPort + ")");
        }

        long heartbeatIntervalMs = base.getHeartbeatIntervalMs();
        if (updates.containsKey("heartbeatIntervalMs") && updates.get("heartbeatIntervalMs") != null) {
            heartbeatIntervalMs = parseLong(updates.get("heartbeatIntervalMs"), "heartbeatIntervalMs");
            if (heartbeatIntervalMs < 100) {
                throw new ValidationException("Heartbeat interval must be at least 100ms");
            }
        }

        long heartbeatTimeoutMs = base.getHeartbeatTimeoutMs();
        if (updates.containsKey("heartbeatTimeoutMs") && updates.get("heartbeatTimeoutMs") != null) {
            heartbeatTimeoutMs = parseLong(updates.get("heartbeatTimeoutMs"), "heartbeatTimeoutMs");
            if (heartbeatTimeoutMs <= heartbeatIntervalMs) {
                throw new ValidationException("Heartbeat timeout (" + heartbeatTimeoutMs + "ms) must be greater than heartbeat interval (" + heartbeatIntervalMs + "ms)");
            }
        }

        String environment = base.getEnvironment();
        if (updates.containsKey("environment") && updates.get("environment") != null) {
            environment = updates.get("environment").toString().trim();
        }

        String logLevel = base.getLogLevel();
        if (updates.containsKey("logLevel") && updates.get("logLevel") != null) {
            logLevel = updates.get("logLevel").toString().trim().toUpperCase();
        }

        // Protocol version is strictly read-only
        int protocolVersion = ProtocolConstants.PROTOCOL_VERSION;

        ServerConfig newConfig = new ServerConfig(
            host,
            httpPort,
            wsPort,
            protocolVersion,
            heartbeatIntervalMs,
            heartbeatTimeoutMs,
            environment,
            logLevel
        );

        // Check if restart is required (host or ports changed compared to active runtime config)
        boolean portOrHostChanged = !host.equalsIgnoreCase(currentConfig.getHost())
                || httpPort != currentConfig.getHttpPort()
                || wsPort != currentConfig.getWsPort();

        if (portOrHostChanged) {
            this.requiresRestart = true;
            this.pendingConfig = newConfig;
        } else {
            this.pendingConfig = newConfig;
        }

        // Persist to disk
        saveToFile(newConfig);

        OperationalLogService.getInstance().info(
            "CONFIG_CHANGED",
            actor != null ? actor : "ADMIN",
            "server-config",
            "Server configuration updated (requiresRestart: " + requiresRestart + ")"
        );

        return newConfig;
    }

    public Map<String, Object> toConfigDto() {
        Map<String, Object> currentMap = configToMap(currentConfig);
        Map<String, Object> pendingMap = configToMap(getPendingConfig());

        Map<String, Object> dto = new HashMap<>();
        dto.put("current", currentMap);
        dto.put("pending", pendingMap);
        dto.put("requiresRestart", requiresRestart);
        return dto;
    }

    private Map<String, Object> configToMap(ServerConfig c) {
        Map<String, Object> m = new HashMap<>();
        m.put("host", c.getHost());
        m.put("httpPort", c.getHttpPort());
        m.put("wsPort", c.getWsPort());
        m.put("protocolVersion", c.getProtocolVersion());
        m.put("heartbeatIntervalMs", c.getHeartbeatIntervalMs());
        m.put("heartbeatTimeoutMs", c.getHeartbeatTimeoutMs());
        m.put("environment", c.getEnvironment());
        m.put("logLevel", c.getLogLevel());
        return m;
    }

    private void saveToFile(ServerConfig config) {
        try {
            if (configPath.getParent() != null) {
                Files.createDirectories(configPath.getParent());
            }
            Map<String, Object> data = configToMap(config);
            objectMapper.writeValue(configPath.toFile(), data);
            AppLogger.info(TAG, "Saved server configuration to " + configPath.toAbsolutePath());
        } catch (IOException e) {
            AppLogger.error(TAG, "Failed to persist server configuration to " + configPath, e);
        }
    }

    private void loadPendingConfigFromFile() {
        if (!Files.exists(configPath)) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(configPath.toFile(), Map.class);
            if (map != null && !map.isEmpty()) {
                String host = (String) map.getOrDefault("host", currentConfig.getHost());
                int httpPort = ((Number) map.getOrDefault("httpPort", currentConfig.getHttpPort())).intValue();
                int wsPort = ((Number) map.getOrDefault("wsPort", currentConfig.getWsPort())).intValue();
                long hbInt = ((Number) map.getOrDefault("heartbeatIntervalMs", currentConfig.getHeartbeatIntervalMs())).longValue();
                long hbTimeout = ((Number) map.getOrDefault("heartbeatTimeoutMs", currentConfig.getHeartbeatTimeoutMs())).longValue();
                String env = (String) map.getOrDefault("environment", currentConfig.getEnvironment());
                String log = (String) map.getOrDefault("logLevel", currentConfig.getLogLevel());

                ServerConfig loaded = new ServerConfig(host, httpPort, wsPort, ProtocolConstants.PROTOCOL_VERSION, hbInt, hbTimeout, env, log);
                if (!host.equalsIgnoreCase(currentConfig.getHost())
                        || httpPort != currentConfig.getHttpPort()
                        || wsPort != currentConfig.getWsPort()) {
                    this.requiresRestart = true;
                    this.pendingConfig = loaded;
                }
            }
        } catch (Exception e) {
            AppLogger.warn(TAG, "Could not load saved config from " + configPath + ": " + e.getMessage());
        }
    }

    private int parsePort(Object val, String field) {
        try {
            int port = (val instanceof Number n) ? n.intValue() : Integer.parseInt(val.toString().trim());
            if (port < 1 || port > 65535) {
                throw new ValidationException(field + " must be between 1 and 65535");
            }
            return port;
        } catch (NumberFormatException e) {
            throw new ValidationException(field + " must be a valid port number (1-65535)");
        }
    }

    private long parseLong(Object val, String field) {
        try {
            return (val instanceof Number n) ? n.longValue() : Long.parseLong(val.toString().trim());
        } catch (NumberFormatException e) {
            throw new ValidationException(field + " must be a valid number");
        }
    }
}
