package com.landrop.admin.service;

import com.landrop.admin.dto.*;
import com.landrop.admin.logging.OperationalLogEntry;
import com.landrop.admin.logging.OperationalLogService;
import com.landrop.auth.exception.ValidationException;
import com.landrop.auth.model.RefreshToken;
import com.landrop.auth.repository.RefreshTokenRepository;
import com.landrop.database.model.Device;
import com.landrop.database.model.User;
import com.landrop.database.repository.DeviceRepository;
import com.landrop.database.repository.UserRepository;
import com.landrop.server.clients.ClientConnection;
import com.landrop.server.clients.ClientRegistry;
import com.landrop.server.config.ServerConfig;
import com.landrop.server.config.ServerConfigManager;
import com.landrop.server.network.NetworkInterfaceInspector;
import com.landrop.server.protocol.ProtocolConstants;
import com.landrop.server.transfers.TransferCoordinator;
import com.landrop.util.AppLogger;

import java.lang.management.ManagementFactory;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/**
 * High-level orchestration service for the LANDrop Server Control Center (FOUNDATION-03A).
 * Integrates dashboard metrics, user lifecycle, sessions, clients, config, and operational logging.
 */
public class AdminControlCenterService {

    private static final String TAG = "AdminControlCenterService";

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final DeviceRepository deviceRepository;
    private final ClientRegistry clientRegistry;
    private final TransferCoordinator transferCoordinator;
    private final ServerConfigManager serverConfigManager;
    private final OperationalLogService logService;

    public AdminControlCenterService(
        UserRepository userRepository,
        RefreshTokenRepository refreshTokenRepository,
        ClientRegistry clientRegistry,
        TransferCoordinator transferCoordinator,
        ServerConfigManager serverConfigManager,
        OperationalLogService logService
    ) {
        this(userRepository, refreshTokenRepository, null, clientRegistry, transferCoordinator, serverConfigManager, logService);
    }

    public AdminControlCenterService(
        UserRepository userRepository,
        RefreshTokenRepository refreshTokenRepository,
        DeviceRepository deviceRepository,
        ClientRegistry clientRegistry,
        TransferCoordinator transferCoordinator,
        ServerConfigManager serverConfigManager,
        OperationalLogService logService
    ) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.deviceRepository = deviceRepository;
        this.clientRegistry = clientRegistry;
        this.transferCoordinator = transferCoordinator;
        this.serverConfigManager = serverConfigManager;
        this.logService = logService != null ? logService : OperationalLogService.getInstance();
    }

    public AdminDashboardDto getDashboard() throws SQLException {
        long uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        String formattedUptime = formatUptime(uptimeSeconds);

        int connectedClients = clientRegistry != null ? clientRegistry.size() : 0;
        long registeredUsers = userRepository != null ? userRepository.countAll() : 0;
        long activeUsers = userRepository != null ? userRepository.countByStatus(User.STATUS_ACTIVE) : 0;
        long disabledUsers = userRepository != null ? userRepository.countByStatus(User.STATUS_DISABLED) : 0;
        long activeSessions = refreshTokenRepository != null ? refreshTokenRepository.countActiveSessions() : 0;
        long totalSessions = refreshTokenRepository != null ? refreshTokenRepository.countAllSessions() : 0;
        long systemAdmins = userRepository != null ? userRepository.countSystemAdmins() : 0;
        long activeTransfers = transferCoordinator != null ? transferCoordinator.countActiveTransfers() : 0;
        boolean requiresRestart = serverConfigManager != null && serverConfigManager.isRequiresRestart();

        return new AdminDashboardDto(
            "RUNNING",
            uptimeSeconds,
            formattedUptime,
            connectedClients,
            registeredUsers,
            activeUsers,
            disabledUsers,
            activeSessions,
            totalSessions,
            systemAdmins,
            activeTransfers,
            requiresRestart,
            Instant.now()
        );
    }

    public List<AdminUserDto> listUsers(int offset, int limit, String search, String status) throws SQLException {
        if (userRepository == null) return List.of();

        List<User> users = userRepository.findWithPagination(offset, limit, search, status);
        List<AdminUserDto> dtos = new ArrayList<>(users.size());

        for (User user : users) {
            int activeSessions = 0;
            if (refreshTokenRepository != null) {
                activeSessions = refreshTokenRepository.findActiveByUserId(user.id()).size();
            }
            dtos.add(AdminUserDto.from(user, activeSessions));
        }

        return dtos;
    }

    public Optional<AdminUserDto> getUser(UUID id) throws SQLException {
        if (userRepository == null || id == null) return Optional.empty();

        Optional<User> userOpt = userRepository.findById(id);
        if (userOpt.isEmpty()) return Optional.empty();

        int activeSessions = 0;
        if (refreshTokenRepository != null) {
            activeSessions = refreshTokenRepository.findActiveByUserId(id).size();
        }
        return Optional.of(AdminUserDto.from(userOpt.get(), activeSessions));
    }

    public boolean disableUser(UUID targetUserId, UUID actingAdminId, String actingAdminUsername) throws SQLException {
        if (userRepository == null || targetUserId == null) return false;

        if (actingAdminId != null && actingAdminId.equals(targetUserId)) {
            throw new ValidationException("Cannot disable your own system administrator account");
        }

        Optional<User> userOpt = userRepository.findById(targetUserId);
        if (userOpt.isEmpty()) {
            throw new ValidationException("User not found: " + targetUserId);
        }

        User user = userOpt.get();
        boolean updated = userRepository.updateStatus(targetUserId, User.STATUS_DISABLED);
        if (updated) {
            // Immediately revoke all active sessions to block existing tokens
            int revokedSessions = 0;
            if (refreshTokenRepository != null) {
                revokedSessions = refreshTokenRepository.revokeAllByUserId(targetUserId);
            }

            // Immediately disconnect any active client WebSocket connections for this user
            if (clientRegistry != null) {
                clientRegistry.disconnectUser(targetUserId, "ACCOUNT_DISABLED", "User account has been disabled by administrator");
            }

            logService.warn(
                "USER_DISABLED",
                actingAdminUsername != null ? actingAdminUsername : "ADMIN",
                targetUserId.toString(),
                "User account '" + user.username() + "' was disabled. Revoked " + revokedSessions + " active session(s)."
            );
        }
        return updated;
    }

    public boolean enableUser(UUID targetUserId, String actingAdminUsername) throws SQLException {
        if (userRepository == null || targetUserId == null) return false;

        Optional<User> userOpt = userRepository.findById(targetUserId);
        if (userOpt.isEmpty()) {
            throw new ValidationException("User not found: " + targetUserId);
        }

        User user = userOpt.get();
        boolean updated = userRepository.updateStatus(targetUserId, User.STATUS_ACTIVE);
        if (updated) {
            logService.info(
                "USER_ENABLED",
                actingAdminUsername != null ? actingAdminUsername : "ADMIN",
                targetUserId.toString(),
                "User account '" + user.username() + "' was enabled."
            );
        }
        return updated;
    }

    public int revokeUserSessions(UUID userId, String actingAdminUsername) throws SQLException {
        if (refreshTokenRepository == null || userId == null) return 0;

        int count = refreshTokenRepository.revokeAllByUserId(userId);
        if (clientRegistry != null) {
            clientRegistry.disconnectUser(userId, "SESSION_REVOKED", "All user sessions have been revoked");
        }
        logService.info(
            "USER_SESSIONS_REVOKED",
            actingAdminUsername != null ? actingAdminUsername : "ADMIN",
            userId.toString(),
            "Revoked all (" + count + ") sessions for user."
        );
        return count;
    }

    public List<AdminSessionDto> listSessions(boolean activeOnly, UUID userId, int limit, int offset) throws SQLException {
        if (refreshTokenRepository == null) return List.of();

        List<RefreshToken> tokens = refreshTokenRepository.findAllSessions(activeOnly, userId, limit, offset);
        Map<UUID, String> usernameCache = new HashMap<>();
        Map<String, String> deviceNameCache = new HashMap<>();
        List<AdminSessionDto> dtos = new ArrayList<>(tokens.size());

        for (RefreshToken token : tokens) {
            String username = usernameCache.computeIfAbsent(token.userId(), uid -> {
                if (userRepository == null) return "Unknown";
                try {
                    return userRepository.findById(uid).map(User::username).orElse("Unknown");
                } catch (SQLException e) {
                    return "Unknown";
                }
            });

            String deviceName = null;
            if (token.deviceId() != null && !token.deviceId().isBlank() && deviceRepository != null) {
                deviceName = deviceNameCache.computeIfAbsent(token.deviceId(), devId -> {
                    try {
                        return deviceRepository.findById(devId).map(Device::deviceName).orElse(null);
                    } catch (SQLException e) {
                        return null;
                    }
                });
            }

            boolean isOnline = false;
            if (clientRegistry != null) {
                isOnline = clientRegistry.findBySessionId(token.id().toString()).isPresent();
            }

            dtos.add(AdminSessionDto.from(token, username, deviceName, isOnline));
        }

        return dtos;
    }

    public boolean revokeSession(UUID sessionId, String actingAdminUsername) throws SQLException {
        if (refreshTokenRepository == null || sessionId == null) return false;

        boolean revoked = refreshTokenRepository.revokeById(sessionId);
        if (revoked) {
            if (clientRegistry != null) {
                clientRegistry.disconnectSession(sessionId.toString(), "SESSION_REVOKED", "Session revoked by administrator");
            }
            logService.info(
                "SESSION_REVOKED",
                actingAdminUsername != null ? actingAdminUsername : "ADMIN",
                sessionId.toString(),
                "Session revoked by administrator."
            );
        }
        return revoked;
    }

    public List<AdminClientDto> listClients() {
        if (clientRegistry == null) return List.of();
        return clientRegistry.activeConnections().stream()
                .map(AdminClientDto::from)
                .toList();
    }

    public boolean disconnectClient(String connectionId, String reason, String actingAdminUsername) {
        if (clientRegistry == null || connectionId == null) return false;

        Optional<ClientConnection> connOpt = clientRegistry.get(connectionId);
        if (connOpt.isEmpty()) return false;

        ClientConnection conn = connOpt.get();
        String safeReason = (reason != null && !reason.isBlank()) ? reason : "Disconnected by Administrator";
        conn.close(4001, safeReason);
        clientRegistry.remove(connectionId);

        logService.warn(
            "ADMIN_CLIENT_DISCONNECT",
            actingAdminUsername != null ? actingAdminUsername : "ADMIN",
            connectionId,
            "Client '" + conn.deviceName() + "' (" + conn.remoteAddress() + ") disconnected: " + safeReason
        );
        return true;
    }

    public Map<String, Object> getServerInfo(String bindHost, int httpPort, int wsPort) {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;

        ServerConfig currentConfig = serverConfigManager != null ? serverConfigManager.getCurrentConfig() : null;

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("serverName", "LANDrop Server");
        info.put("version", ProtocolConstants.SERVER_VERSION);
        info.put("protocolVersion", ProtocolConstants.PROTOCOL_VERSION);
        info.put("status", "RUNNING");
        info.put("uptimeSeconds", uptimeSeconds);
        info.put("uptimeFormatted", formatUptime(uptimeSeconds));
        info.put("host", bindHost != null ? bindHost : (currentConfig != null ? currentConfig.getHost() : "0.0.0.0"));
        info.put("httpPort", httpPort);
        info.put("wsPort", wsPort);
        info.put("javaVersion", System.getProperty("java.version"));
        info.put("javaVendor", System.getProperty("java.vendor"));
        info.put("osName", System.getProperty("os.name"));
        info.put("osArch", System.getProperty("os.arch"));
        info.put("availableProcessors", runtime.availableProcessors());
        info.put("usedMemoryBytes", usedMemory);
        info.put("totalMemoryBytes", totalMemory);
        info.put("maxMemoryBytes", runtime.maxMemory());
        info.put("activeClients", clientRegistry != null ? clientRegistry.size() : 0);
        info.put("requiresRestart", serverConfigManager != null && serverConfigManager.isRequiresRestart());
        info.put("serverTime", Instant.now().toString());

        return info;
    }

    public List<NetworkInterfaceInspector.InterfaceDetail> getNetworkInterfaces(String bindHost) {
        return NetworkInterfaceInspector.inspectAll(bindHost);
    }

    public List<NetworkInterfaceInspector.InterfaceDetail> getLanInterfaces(String bindHost) {
        return NetworkInterfaceInspector.inspectLanInterfaces(bindHost);
    }

    public Map<String, Object> getConfig() {
        if (serverConfigManager == null) return Map.of();
        return serverConfigManager.toConfigDto();
    }

    public ServerConfig updateConfig(Map<String, Object> updates, String actingAdminUsername) {
        if (serverConfigManager == null) {
            throw new ValidationException("Server configuration manager is unavailable");
        }
        return serverConfigManager.updateConfig(updates, actingAdminUsername);
    }

    public List<OperationalLogEntry> getLogs(int limit, int offset, String level, String type, String search) {
        return logService.getLogs(limit, offset, level, type, search);
    }

    public OperationalLogService getLogService() {
        return logService;
    }

    public ServerConfigManager getServerConfigManager() {
        return serverConfigManager;
    }

    public UserRepository getUserRepository() {
        return userRepository;
    }

    public RefreshTokenRepository getRefreshTokenRepository() {
        return refreshTokenRepository;
    }

    public ClientRegistry getClientRegistry() {
        return clientRegistry;
    }

    private static String formatUptime(long seconds) {
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0 || days > 0) sb.append(hours).append("h ");
        if (minutes > 0 || hours > 0 || days > 0) sb.append(minutes).append("m ");
        sb.append(secs).append("s");
        return sb.toString().trim();
    }
}
