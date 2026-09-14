package com.landrop.server.bootstrap;

import com.landrop.database.DatabaseConfig;
import com.landrop.database.DatabaseManager;
import com.landrop.server.clients.ClientRegistry;
import com.landrop.server.config.ServerConfig;
import com.landrop.server.health.HealthService;
import com.landrop.server.http.FoundationHttpServer;
import com.landrop.server.logging.ServerEvent;
import com.landrop.server.logging.ServerEventLogger;
import com.landrop.server.realtime.FoundationRealtimeServer;
import com.landrop.server.transfers.TransferCoordinator;
import com.landrop.util.AppLogger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Server Bootstrap orchestrator initializing all foundation components,
 * registering graceful shutdown hooks, and ensuring network decoupling.
 */
public class ServerBootstrap {

    private static final String TAG = "ServerBootstrap";

    private final ServerConfig config;
    private final HealthService healthService;
    private final ClientRegistry clientRegistry;
    private final TransferCoordinator transferCoordinator;

    private FoundationHttpServer httpServer;
    private FoundationRealtimeServer realtimeServer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread shutdownHook;

    public ServerBootstrap(ServerConfig config) {
        this.config = config != null ? config : ServerConfig.load();
        this.healthService = new HealthService();
        this.clientRegistry = new ClientRegistry();
        this.transferCoordinator = new TransferCoordinator(this.clientRegistry);
    }

    public ServerBootstrap() {
        this(ServerConfig.load());
    }

    public synchronized void start() throws Exception {
        if (running.get()) {
            return;
        }

        // 1. Check database connectivity gracefully (database decoupling)
        checkDatabaseConnectivity();

        // 2. Start HTTP Server
        httpServer = new FoundationHttpServer(
            config.getHost(),
            config.getHttpPort(),
            config.getWsPort(),
            healthService,
            clientRegistry
        );
        httpServer.start();

        // 3. Start Realtime WebSocket Server
        realtimeServer = new FoundationRealtimeServer(
            new InetSocketAddress(config.getHost(), config.getWsPort()),
            clientRegistry,
            transferCoordinator,
            config.getHeartbeatIntervalMs(),
            config.getHeartbeatTimeoutMs()
        );
        realtimeServer.start();
        realtimeServer.awaitReady();

        // 4. Register Graceful Shutdown Hook
        shutdownHook = new Thread(this::shutdownGracefully, "server-shutdown-hook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        running.set(true);

        // 5. Log startup banner
        printStartupBanner();
    }

    public synchronized void stop() {
        if (!running.get()) {
            return;
        }

        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM already shutting down
            }
        }
        shutdownGracefully();
    }

    private void shutdownGracefully() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        ServerEventLogger.log(ServerEvent.SERVER_STOPPING, "Initiating graceful shutdown sequence...");

        // 1. Stop Realtime Server and disconnect active clients
        if (realtimeServer != null) {
            try {
                realtimeServer.stop(2000);
            } catch (Exception e) {
                AppLogger.warn(TAG, "Error stopping Realtime server: " + e.getMessage());
            }
        }

        // 2. Clean client registry
        if (clientRegistry != null) {
            clientRegistry.clear();
        }

        // 3. Stop HTTP server
        if (httpServer != null) {
            try {
                httpServer.stop(1);
            } catch (Exception e) {
                AppLogger.warn(TAG, "Error stopping HTTP server: " + e.getMessage());
            }
        }

        // 4. Close database pool if present
        try {
            DatabaseManager db = DatabaseManager.getInstance();
            if (db != null) {
                db.close();
            }
        } catch (Exception ignored) {
            // DB was not initialized or already closed
        }

        AppLogger.info(TAG, "LANDrop Server stopped cleanly.");
    }

    private void checkDatabaseConnectivity() {
        String dbUrl = DatabaseConfig.getDatabaseUrl();
        if (dbUrl == null || dbUrl.isBlank()) {
            AppLogger.info(TAG, "No DATABASE_URL configured. Running in pure standalone LAN in-memory mode.");
            return;
        }

        try {
            DatabaseManager db = DatabaseManager.getInstance();
            if (db.testConnection()) {
                AppLogger.info(TAG, "PostgreSQL database connection verified (Neon Cloud).");
            }
        } catch (Exception e) {
            AppLogger.warn(TAG, "Database check encountered an error: " + e.getMessage() + ". Subsystem remains operational in standalone LAN mode.");
        }
    }

    private void printStartupBanner() {
        int actualHttpPort = httpServer != null ? httpServer.getPort() : config.getHttpPort();
        int actualWsPort = realtimeServer != null ? realtimeServer.getPort() : config.getWsPort();

        String banner = String.format("""
            ==================================================
            LANDrop Server Stable Core (FOUNDATION-01A)
            ==================================================
            Status:             RUNNING
            Host Binding:       %s
            HTTP Port:          %d (Health: http://%s:%d/health)
            WebSocket Port:     %d (Gateway: ws://%s:%d)
            Protocol Version:   %d
            Environment:        %s
            Log Level:          %s
            ==================================================
            """,
            config.getHost(),
            actualHttpPort, config.getHost(), actualHttpPort,
            actualWsPort, config.getHost(), actualWsPort,
            config.getProtocolVersion(),
            config.getEnvironment(),
            config.getLogLevel()
        );

        System.out.println(banner);
        ServerEventLogger.log(ServerEvent.SERVER_STARTED,
                "Listening on " + config.getHost() + " (HTTP: " + actualHttpPort + ", WS: " + actualWsPort + ")");
    }

    public boolean isRunning() {
        return running.get();
    }

    public ServerConfig getConfig() {
        return config;
    }

    public HealthService getHealthService() {
        return healthService;
    }

    public ClientRegistry getClientRegistry() {
        return clientRegistry;
    }

    public TransferCoordinator getTransferCoordinator() {
        return transferCoordinator;
    }

    public FoundationHttpServer getHttpServer() {
        return httpServer;
    }

    public FoundationRealtimeServer getRealtimeServer() {
        return realtimeServer;
    }
}
