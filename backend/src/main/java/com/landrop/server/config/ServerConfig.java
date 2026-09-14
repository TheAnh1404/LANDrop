package com.landrop.server.config;

import com.landrop.config.AppConfig;
import com.landrop.server.protocol.ProtocolConstants;

/**
 * Server configuration loaded from Environment Variables, System Properties, or defaults.
 */
public final class ServerConfig {

    private final String host;
    private final int httpPort;
    private final int wsPort;
    private final int protocolVersion;
    private final long heartbeatIntervalMs;
    private final long heartbeatTimeoutMs;
    private final String environment;
    private final String logLevel;

    public ServerConfig(String host,
                        int httpPort,
                        int wsPort,
                        int protocolVersion,
                        long heartbeatIntervalMs,
                        long heartbeatTimeoutMs,
                        String environment,
                        String logLevel) {
        this.host = (host != null && !host.isBlank()) ? host.trim() : ProtocolConstants.DEFAULT_HOST;
        this.httpPort = httpPort;
        this.wsPort = wsPort;
        this.protocolVersion = protocolVersion;
        this.heartbeatIntervalMs = heartbeatIntervalMs > 0 ? heartbeatIntervalMs : ProtocolConstants.DEFAULT_HEARTBEAT_INTERVAL_MS;
        this.heartbeatTimeoutMs = heartbeatTimeoutMs > 0 ? heartbeatTimeoutMs : ProtocolConstants.DEFAULT_HEARTBEAT_TIMEOUT_MS;
        this.environment = (environment != null && !environment.isBlank()) ? environment.trim() : "development";
        this.logLevel = (logLevel != null && !logLevel.isBlank()) ? logLevel.trim() : "INFO";
    }

    public static ServerConfig load() {
        String host = AppConfig.getString("landrop.server.host", "HOST", ProtocolConstants.DEFAULT_HOST);
        int httpPort = AppConfig.getInt("landrop.server.port", "PORT", ProtocolConstants.DEFAULT_HTTP_PORT);
        // Fall back to HTTP_PORT if PORT is not set
        if (httpPort == ProtocolConstants.DEFAULT_HTTP_PORT) {
            httpPort = AppConfig.getInt("landrop.server.http.port", "HTTP_PORT", ProtocolConstants.DEFAULT_HTTP_PORT);
        }

        int wsPort = AppConfig.getInt("landrop.server.ws.port", "REALTIME_PORT", ProtocolConstants.DEFAULT_WS_PORT);
        if (wsPort == ProtocolConstants.DEFAULT_WS_PORT) {
            wsPort = AppConfig.getInt("landrop.server.ws.port", "WS_PORT", ProtocolConstants.DEFAULT_WS_PORT);
        }

        int protocolVer = AppConfig.getInt("landrop.protocol.version", "PROTOCOL_VERSION", ProtocolConstants.PROTOCOL_VERSION);
        long heartbeatInterval = AppConfig.getLong("landrop.heartbeat.interval.ms", "HEARTBEAT_INTERVAL_MS", ProtocolConstants.DEFAULT_HEARTBEAT_INTERVAL_MS);
        long heartbeatTimeout = AppConfig.getLong("landrop.heartbeat.timeout.ms", "HEARTBEAT_TIMEOUT_MS", ProtocolConstants.DEFAULT_HEARTBEAT_TIMEOUT_MS);
        String env = AppConfig.getString("landrop.environment", "NODE_ENV", "development");
        String logLevel = AppConfig.getString("landrop.log.level", "LOG_LEVEL", "INFO");

        return new ServerConfig(host, httpPort, wsPort, protocolVer, heartbeatInterval, heartbeatTimeout, env, logLevel);
    }

    public String getHost() {
        return host;
    }

    public int getHttpPort() {
        return httpPort;
    }

    public int getWsPort() {
        return wsPort;
    }

    public int getProtocolVersion() {
        return protocolVersion;
    }

    public long getHeartbeatIntervalMs() {
        return heartbeatIntervalMs;
    }

    public long getHeartbeatTimeoutMs() {
        return heartbeatTimeoutMs;
    }

    public String getEnvironment() {
        return environment;
    }

    public String getLogLevel() {
        return logLevel;
    }
}
