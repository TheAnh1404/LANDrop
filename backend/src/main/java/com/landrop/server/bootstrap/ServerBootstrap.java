package com.landrop.server.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.admin.logging.OperationalLogService;
import com.landrop.admin.security.AdminAuthMiddleware;
import com.landrop.admin.security.AdminBootstrapService;
import com.landrop.admin.server.AdminHttpServer;
import com.landrop.admin.service.AdminControlCenterService;
import com.landrop.auth.repository.RefreshTokenRepository;
import com.landrop.auth.security.JwtService;
import com.landrop.auth.server.AuthHttpHandler;
import com.landrop.auth.server.AuthMiddleware;
import com.landrop.auth.service.AuthService;
import com.landrop.database.DatabaseConfig;
import com.landrop.database.DatabaseManager;
import com.landrop.database.repository.DeviceRepository;
import com.landrop.database.repository.UserRepository;
import com.landrop.database.repository.WorkspaceRepository;
import com.landrop.device.server.DeviceHttpHandler;
import com.landrop.device.service.DeviceService;
import com.landrop.database.service.WorkspaceService;
import com.landrop.workspace.http.WorkspaceHttpServer;
import com.landrop.chat.server.ChatHttpServer;
import com.landrop.chat.service.ChannelChatService;
import com.landrop.file.server.FileHttpServer;
import com.landrop.file.service.FileManagementService;
import com.landrop.file.service.TransferCoordinationService;
import com.landrop.server.clients.ClientRegistry;
import com.landrop.server.config.ServerConfig;
import com.landrop.server.config.ServerConfigManager;
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
 * registering graceful shutdown hooks, Server Control Center, and ensuring network decoupling.
 */
public class ServerBootstrap {

    private static final String TAG = "ServerBootstrap";

    private final ServerConfig config;
    private final HealthService healthService;
    private final ClientRegistry clientRegistry;
    private final TransferCoordinator transferCoordinator;
    private final ServerConfigManager serverConfigManager;
    private final OperationalLogService logService;

    private UserRepository userRepository;
    private RefreshTokenRepository refreshTokenRepository;
    private DeviceRepository deviceRepository;
    private WorkspaceRepository workspaceRepository;
    private JwtService jwtService;
    private AuthService authService;
    private DeviceService deviceService;
    private AuthMiddleware authMiddleware;
    private AuthHttpHandler authHttpHandler;
    private DeviceHttpHandler deviceHttpHandler;
    private WorkspaceHttpServer workspaceHttpServer;
    private ChatHttpServer chatHttpServer;
    private ChannelChatService channelChatService;
    private FileHttpServer fileHttpServer;
    private AdminBootstrapService adminBootstrapService;
    private AdminAuthMiddleware adminAuthMiddleware;
    private AdminControlCenterService adminControlCenterService;
    private AdminHttpServer adminHttpServer;

    private FoundationHttpServer httpServer;
    private FoundationRealtimeServer realtimeServer;
    private com.landrop.discovery.DiscoveryServer embeddedDiscoveryServer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread shutdownHook;

    public ServerBootstrap(ServerConfig config) {
        this.config = config != null ? config : ServerConfig.load();
        this.healthService = new HealthService();
        this.clientRegistry = new ClientRegistry();
        this.transferCoordinator = new TransferCoordinator(this.clientRegistry);
        this.serverConfigManager = new ServerConfigManager(this.config);
        this.logService = OperationalLogService.getInstance();
    }

    public ServerBootstrap() {
        this(ServerConfig.load());
    }

    public synchronized void start() throws Exception {
        if (running.get()) {
            return;
        }

        if ("production".equalsIgnoreCase(config.getEnvironment())) {
            String adminPassword = System.getenv("ADMIN_PASSWORD");
            if (adminPassword == null || adminPassword.isBlank()) {
                adminPassword = System.getProperty("landrop.admin.password");
            }
            requireProductionAdminPassword(adminPassword);
        }

        // 1. Check database connectivity gracefully (database decoupling)
        boolean dbConnected = checkDatabaseConnectivity();
        if (dbConnected) {
            try {
                DatabaseManager db = DatabaseManager.getInstance();
                if (this.userRepository == null) {
                    this.userRepository = new UserRepository(db);
                }
                if (this.refreshTokenRepository == null) {
                    this.refreshTokenRepository = new RefreshTokenRepository(db);
                }
                if (this.deviceRepository == null) {
                    this.deviceRepository = new DeviceRepository(db);
                }
                if (this.workspaceRepository == null) {
                    this.workspaceRepository = new WorkspaceRepository(db);
                }
                if (this.jwtService == null) {
                    this.jwtService = new JwtService();
                }
                if (this.authService == null) {
                    this.authService = new AuthService(this.userRepository, this.refreshTokenRepository, this.jwtService);
                }
                if (this.deviceService == null) {
                    this.deviceService = new DeviceService(this.deviceRepository, this.clientRegistry, this.refreshTokenRepository);
                }
                if (this.authMiddleware == null) {
                    this.authMiddleware = new AuthMiddleware(this.jwtService, this.userRepository, this.refreshTokenRepository);
                }
                if (this.authHttpHandler == null) {
                    this.authHttpHandler = new AuthHttpHandler(this.authService, this.authMiddleware, this.clientRegistry, com.landrop.auth.server.AuthHttpServer.createObjectMapper());
                }
                if (this.deviceHttpHandler == null) {
                    this.deviceHttpHandler = new DeviceHttpHandler(this.deviceService, this.authMiddleware, com.landrop.auth.server.AuthHttpServer.createObjectMapper());
                }
                // Mount the existing workspace/chat/file APIs on the same HTTP
                // server as auth and device enrollment. Without this wiring,
                // the client receives a plain 404 from /api/workspaces after a
                // successful login.
                if (this.workspaceHttpServer == null) {
                    var mapper = com.landrop.auth.server.AuthHttpServer.createObjectMapper();
                    this.channelChatService = new ChannelChatService();
                    var fileService = new FileManagementService();
                    var transferService = new TransferCoordinationService();
                    var fileRoutes = new FileHttpServer(this.authMiddleware, fileService, transferService, mapper);
                    this.workspaceHttpServer = new WorkspaceHttpServer(
                        this.authMiddleware, new WorkspaceService(), this.channelChatService, fileRoutes, mapper);
                    this.chatHttpServer = new ChatHttpServer(this.authMiddleware, this.channelChatService, mapper);
                    this.fileHttpServer = fileRoutes;
                }
                this.adminBootstrapService = new AdminBootstrapService(this.userRepository);
                this.adminBootstrapService.ensureSystemAdminExists();
                this.adminAuthMiddleware = new AdminAuthMiddleware(this.jwtService, this.userRepository, this.refreshTokenRepository);
            } catch (Exception e) {
                AppLogger.warn(TAG, "Failed initializing database admin subsystems: " + e.getMessage());
            }
        }

        if (this.jwtService == null) {
            this.jwtService = new JwtService();
        }
        if (this.adminAuthMiddleware == null) {
            this.adminAuthMiddleware = new AdminAuthMiddleware(this.jwtService, this.userRepository, this.refreshTokenRepository);
        }

        this.adminControlCenterService = new AdminControlCenterService(
            this.userRepository,
            this.refreshTokenRepository,
            this.deviceRepository,
            this.workspaceRepository,
            this.clientRegistry,
            this.transferCoordinator,
            this.serverConfigManager,
            this.logService
        );

        this.adminHttpServer = new AdminHttpServer(
            this.adminAuthMiddleware,
            this.adminControlCenterService,
            new ObjectMapper(),
            config.getHost(),
            config.getHttpPort(),
            config.getWsPort(),
            this.authService
        );

        // 2. Start HTTP Server with Control Center, Auth, and Device endpoints mounted
        httpServer = new FoundationHttpServer(
            config.getHost(),
            config.getHttpPort(),
            config.getWsPort(),
            healthService,
            clientRegistry,
            adminHttpServer,
            authHttpHandler,
            deviceHttpHandler,
            workspaceHttpServer,
            chatHttpServer,
            fileHttpServer
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
        realtimeServer.setAuthDependencies(this.jwtService, this.userRepository, this.deviceRepository, this.refreshTokenRepository);
        realtimeServer.setChannelChatService(this.channelChatService);
        realtimeServer.setSignalingWorkspaceRepository(this.workspaceRepository);
        if (this.channelChatService != null) this.channelChatService.setBroadcaster(realtimeServer);
        realtimeServer.start();
        realtimeServer.awaitReady();

        if (adminHttpServer != null) {
            adminHttpServer.setActualPorts(httpServer.getPort(), realtimeServer.getPort());
        }

        // 4. Start Embedded LAN Discovery Server on port 8888 (skip in test environment)
        if (!"test".equalsIgnoreCase(config.getEnvironment())) {
            try {
                int discPort = 8888;
                this.embeddedDiscoveryServer = new com.landrop.discovery.DiscoveryServer(discPort);
                this.embeddedDiscoveryServer.startAsync();
                AppLogger.info(TAG, "Embedded LAN Discovery Server started on port " + discPort);
            } catch (Exception e) {
                AppLogger.warn(TAG, "Embedded Discovery Server could not be started on 8888: " + e.getMessage());
            }
        }

        // 5. Register Graceful Shutdown Hook
        shutdownHook = new Thread(this::shutdownGracefully, "server-shutdown-hook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        running.set(true);

        // 6. Log startup banner
        printStartupBanner();
    }

    static void requireProductionAdminPassword(String password) {
        if (password == null || password.length() < 12
                || password.startsWith("replace-with-")
                || com.landrop.admin.security.AdminBootstrapService.DEFAULT_ADMIN_PASSWORD.equals(password)) {
            throw new IllegalStateException("Production requires ADMIN_PASSWORD with at least 12 characters");
        }
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

        // 4. Stop Embedded Discovery Server
        if (embeddedDiscoveryServer != null) {
            try {
                embeddedDiscoveryServer.stop();
                AppLogger.info(TAG, "Embedded Discovery Server stopped.");
            } catch (Exception ignored) {
            }
        }

        // 5. Close database pool if present
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

    private boolean checkDatabaseConnectivity() {
        String dbUrl = DatabaseConfig.getDatabaseUrl();
        if (dbUrl == null || dbUrl.isBlank()) {
            AppLogger.info(TAG, "No DATABASE_URL configured. Running in standalone LAN in-memory mode.");
            return false;
        }

        try {
            DatabaseManager db = DatabaseManager.getInstance();
            if (db.testConnection()) {
                AppLogger.info(TAG, "PostgreSQL database connection verified (Neon Cloud).");
                return true;
            }
        } catch (Exception e) {
            AppLogger.warn(TAG, "Database check encountered an error: " + e.getMessage() + ". Subsystem remains operational in standalone LAN mode.");
        }
        return false;
    }

    public static java.util.List<String> resolveLanIpv4Addresses() {
        java.util.List<String> list = new java.util.ArrayList<>();
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp() || iface.isVirtual()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress() && a.isSiteLocalAddress()) {
                        list.add(a.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    private void printStartupBanner() {
        int actualHttpPort = httpServer != null ? httpServer.getPort() : config.getHttpPort();
        int actualWsPort = realtimeServer != null ? realtimeServer.getPort() : config.getWsPort();

        java.util.List<String> lanIps = resolveLanIpv4Addresses();
        String primaryLanIp = lanIps.isEmpty() ? (config.getHost().equals("0.0.0.0") ? "127.0.0.1" : config.getHost()) : lanIps.get(0);
        String lanHealthUrl = "http://" + primaryLanIp + ":" + actualHttpPort + "/health";
        String lanAdminUrl = "http://" + primaryLanIp + ":" + actualHttpPort + "/admin";
        String lanWsUrl = "ws://" + primaryLanIp + ":" + actualWsPort;

        String banner = String.format("""
            ==================================================
            LANDrop Server (FOUNDATION-03A Control Center)
            ==================================================
            Status:             RUNNING
            Host:               %s
            Port (HTTP):        %d
            Port (WebSocket):   %d
            LAN IP(s):          %s
            LAN URL (Health):   %s
            WebSocket URL:      %s
            Control Center URL: %s
            Protocol Version:   %d
            Environment:        %s
            Log Level:          %s
            ==================================================
            """,
            config.getHost(),
            actualHttpPort,
            actualWsPort,
            lanIps.isEmpty() ? primaryLanIp : String.join(", ", lanIps),
            lanHealthUrl,
            lanWsUrl,
            lanAdminUrl,
            config.getProtocolVersion(),
            config.getEnvironment(),
            config.getLogLevel()
        );

        System.out.println(banner);
        ServerEventLogger.log(ServerEvent.SERVER_STARTED,
                "Host: " + config.getHost() + " | LAN URL: " + lanHealthUrl + " | WebSocket URL: " + lanWsUrl + " | Control Center: " + lanAdminUrl);
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

    public ServerConfigManager getServerConfigManager() {
        return serverConfigManager;
    }

    public OperationalLogService getOperationalLogService() {
        return logService;
    }

    public AdminControlCenterService getAdminControlCenterService() {
        return adminControlCenterService;
    }

    public AdminHttpServer getAdminHttpServer() {
        return adminHttpServer;
    }

    public AdminAuthMiddleware getAdminAuthMiddleware() {
        return adminAuthMiddleware;
    }

    public UserRepository getUserRepository() {
        return userRepository;
    }

    public RefreshTokenRepository getRefreshTokenRepository() {
        return refreshTokenRepository;
    }

    public DeviceRepository getDeviceRepository() {
        return deviceRepository;
    }

    public DeviceService getDeviceService() {
        return deviceService;
    }

    public AuthService getAuthService() {
        return authService;
    }

    public JwtService getJwtService() {
        return jwtService;
    }

    public AuthMiddleware getAuthMiddleware() {
        return authMiddleware;
    }

    public AuthHttpHandler getAuthHttpHandler() {
        return authHttpHandler;
    }

    public DeviceHttpHandler getDeviceHttpHandler() {
        return deviceHttpHandler;
    }

    public void setAuthSubsystems(AuthService authService, DeviceService deviceService, AuthMiddleware authMiddleware,
                                  AuthHttpHandler authHttpHandler, DeviceHttpHandler deviceHttpHandler) {
        this.authService = authService;
        this.deviceService = deviceService;
        this.authMiddleware = authMiddleware;
        this.authHttpHandler = authHttpHandler;
        this.deviceHttpHandler = deviceHttpHandler;
    }

    public void setRepositories(UserRepository userRepo, RefreshTokenRepository tokenRepo) {
        setRepositories(userRepo, tokenRepo, null);
    }

    public void setRepositories(UserRepository userRepo, RefreshTokenRepository tokenRepo, DeviceRepository deviceRepo) {
        this.userRepository = userRepo;
        this.refreshTokenRepository = tokenRepo;
        this.deviceRepository = deviceRepo;
    }
}
