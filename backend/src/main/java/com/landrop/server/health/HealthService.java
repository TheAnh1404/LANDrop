package com.landrop.server.health;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.landrop.server.protocol.ProtocolConstants;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health and uptime tracking service.
 */
public class HealthService {

    private final Instant startTime;
    private volatile boolean persistenceAvailable;

    public void setPersistenceAvailable(boolean available) {
        this.persistenceAvailable = available;
    }

    public HealthService() {
        this.startTime = Instant.now();
    }

    public HealthService(Instant startTime) {
        this.startTime = startTime != null ? startTime : Instant.now();
    }

    public Instant getStartTime() {
        return startTime;
    }

    public long getUptimeSeconds() {
        return Duration.between(startTime, Instant.now()).toSeconds();
    }

    public long getUptimeMillis() {
        return Duration.between(startTime, Instant.now()).toMillis();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HealthReport(
        String status,
        String serverVersion,
        int protocolVersion,
        long uptime,
        boolean persistenceAvailable
    ) {}

    public HealthReport getHealthReport() {
        return new HealthReport(
            "ok",
            ProtocolConstants.SERVER_VERSION,
            ProtocolConstants.PROTOCOL_VERSION,
            getUptimeSeconds(),
            persistenceAvailable
        );
    }

    public Map<String, Object> getStatusReport(int activeClients, String host, int httpPort, int wsPort) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", "ok");
        map.put("serverStatus", "RUNNING");
        map.put("persistenceAvailable", persistenceAvailable);
        map.put("serverVersion", ProtocolConstants.SERVER_VERSION);
        map.put("protocolVersion", ProtocolConstants.PROTOCOL_VERSION);
        map.put("host", host);
        map.put("httpPort", httpPort);
        map.put("wsPort", wsPort);
        map.put("lanAddresses", com.landrop.server.bootstrap.ServerBootstrap.resolveLanIpv4Addresses());
        map.put("connectedClients", activeClients);
        map.put("uptimeSeconds", getUptimeSeconds());
        return map;
    }
}
