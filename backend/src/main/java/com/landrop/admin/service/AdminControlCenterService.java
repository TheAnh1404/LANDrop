package com.landrop.admin.service;

import com.landrop.admin.dto.*;
import com.landrop.admin.logging.OperationalLogEntry;
import com.landrop.admin.logging.OperationalLogService;
import com.landrop.auth.exception.ValidationException;
import com.landrop.auth.model.RefreshToken;
import com.landrop.auth.repository.RefreshTokenRepository;
import com.landrop.database.model.Device;
import com.landrop.database.model.User;
import com.landrop.database.model.Workspace;
import com.landrop.database.model.WorkspaceMember;
import com.landrop.database.model.WorkspaceRole;
import com.landrop.database.repository.DeviceRepository;
import com.landrop.database.repository.UserRepository;
import com.landrop.database.repository.WorkspaceRepository;
import com.landrop.device.dto.DeviceDto;
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
    private final WorkspaceRepository workspaceRepository;
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
        this(userRepository, refreshTokenRepository, null, null, clientRegistry, transferCoordinator, serverConfigManager, logService);
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
        this(userRepository, refreshTokenRepository, deviceRepository, null, clientRegistry, transferCoordinator, serverConfigManager, logService);
    }

    public AdminControlCenterService(
        UserRepository userRepository,
        RefreshTokenRepository refreshTokenRepository,
        DeviceRepository deviceRepository,
        WorkspaceRepository workspaceRepository,
        ClientRegistry clientRegistry,
        TransferCoordinator transferCoordinator,
        ServerConfigManager serverConfigManager,
        OperationalLogService logService
    ) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.deviceRepository = deviceRepository;
        this.workspaceRepository = workspaceRepository;
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
        long registeredDevices = deviceRepository != null ? deviceRepository.countAll() : 0;
        long workspaceCount = workspaceRepository != null ? workspaceRepository.countAll() : 0;
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
            registeredDevices,
            workspaceCount,
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

    public Optional<AdminUserIdentityDto> getUserIdentity(UUID id) throws SQLException {
        Optional<AdminUserDto> user = getUser(id);
        if (user.isEmpty()) return Optional.empty();

        List<DeviceDto> devices = deviceRepository == null ? List.of()
                : deviceRepository.findByUserId(id).stream()
                    .map(device -> DeviceDto.from(device,
                            clientRegistry != null && clientRegistry.isDeviceOnline(device.id())))
                    .toList();
        List<AdminSessionDto> sessions = listSessions(false, id, 500, 0);
        List<AdminClientDto> connections = clientRegistry == null ? List.of()
                : clientRegistry.findByUserId(id).stream().map(AdminClientDto::from).toList();
        return Optional.of(new AdminUserIdentityDto(user.get(), devices, sessions, connections));
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
        if (user.isSystemAdmin() && User.STATUS_ACTIVE.equals(user.status())
                && userRepository.countActiveSystemAdmins() <= 1) {
            throw new ValidationException("At least one active system administrator must remain");
        }
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
                "ACCOUNT_DISABLED",
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

    /** Updates the safe, operator-controlled fields of a user record. */
    public AdminUserDto updateUser(UUID targetUserId, UUID actingAdminId, Map<String, Object> updates, String actingAdminUsername) throws SQLException {
        if (userRepository == null || targetUserId == null) throw new ValidationException("User management is unavailable");
        User target = userRepository.findById(targetUserId).orElseThrow(() -> new ValidationException("User not found"));
        Map<String, Object> safeUpdates = updates != null ? updates : Map.of();
        String displayName = null;
        String role = null;
        String status = null;

        if (safeUpdates.containsKey("displayName")) {
            displayName = String.valueOf(safeUpdates.get("displayName")).trim();
            if (displayName.isBlank() || displayName.length() > 255) throw new ValidationException("Display name must be 1-255 characters");
        }
        if (safeUpdates.containsKey("systemRole")) {
            role = String.valueOf(safeUpdates.get("systemRole")).trim().toUpperCase(Locale.ROOT);
            if (!Set.of(User.ROLE_USER, User.ROLE_SYSTEM_ADMIN).contains(role)) throw new ValidationException("Invalid system role");
            if (targetUserId.equals(actingAdminId) && !User.ROLE_SYSTEM_ADMIN.equals(role)) {
                throw new ValidationException("Cannot remove your own system administrator role");
            }
            if (target.isSystemAdmin() && !User.ROLE_SYSTEM_ADMIN.equals(role) && userRepository.countSystemAdmins() <= 1) {
                throw new ValidationException("At least one system administrator must remain");
            }
            if (target.isSystemAdmin() && User.STATUS_ACTIVE.equals(target.status()) && !User.ROLE_SYSTEM_ADMIN.equals(role)
                    && userRepository.countActiveSystemAdmins() <= 1) {
                throw new ValidationException("At least one active system administrator must remain");
            }
        }
        if (safeUpdates.containsKey("status")) {
            status = String.valueOf(safeUpdates.get("status")).trim().toUpperCase(Locale.ROOT);
            if (!Set.of(User.STATUS_ACTIVE, User.STATUS_DISABLED).contains(status)) throw new ValidationException("Invalid user status");
            if (User.STATUS_DISABLED.equals(status)) {
                if (targetUserId.equals(actingAdminId)) throw new ValidationException("Cannot disable your own system administrator account");
                if (target.isSystemAdmin() && User.STATUS_ACTIVE.equals(target.status()) && userRepository.countActiveSystemAdmins() <= 1) {
                    throw new ValidationException("At least one active system administrator must remain");
                }
            }
        }
        // Validate the whole editor before saving anything, including the final-admin checks.
        if (displayName != null) userRepository.updateDisplayName(targetUserId, displayName);
        if (role != null) userRepository.updateSystemRole(targetUserId, role);
        if (status != null) {
            if (User.STATUS_DISABLED.equals(status)) disableUser(targetUserId, actingAdminId, actingAdminUsername);
            else enableUser(targetUserId, actingAdminUsername);
        }
        logService.info("USER_UPDATED", actor(actingAdminUsername), targetUserId.toString(), "Updated user record for '" + target.username() + "'.");
        return getUser(targetUserId).orElseThrow(() -> new ValidationException("User no longer exists"));
    }

    public boolean deleteUser(UUID targetUserId, UUID actingAdminId, String actingAdminUsername) throws SQLException {
        if (userRepository == null || targetUserId == null) return false;
        if (targetUserId.equals(actingAdminId)) throw new ValidationException("Cannot delete your own system administrator account");
        User target = userRepository.findById(targetUserId).orElseThrow(() -> new ValidationException("User not found"));
        if (target.isSystemAdmin() && userRepository.countSystemAdmins() <= 1) {
            throw new ValidationException("At least one system administrator must remain");
        }
        if (target.isSystemAdmin() && User.STATUS_ACTIVE.equals(target.status()) && userRepository.countActiveSystemAdmins() <= 1) {
            throw new ValidationException("At least one active system administrator must remain");
        }
        revokeUserSessions(targetUserId, actingAdminUsername);
        boolean deleted;
        try {
            deleted = userRepository.deleteById(targetUserId);
        } catch (SQLException failure) {
            if ("23503".equals(failure.getSQLState())) {
                throw new ValidationException("User still owns groups or content. Delete or transfer those records before deleting the user.");
            }
            throw failure;
        }
        if (deleted) logService.warn("USER_DELETED", actor(actingAdminUsername), targetUserId.toString(), "Deleted user record '" + target.username() + "'.");
        return deleted;
    }

    // =========================================================================
    // Workspace (group) management
    // =========================================================================

    public List<AdminWorkspaceDto> listWorkspaces(String search) throws SQLException {
        if (workspaceRepository == null) return List.of();
        String query = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<AdminWorkspaceDto> result = new ArrayList<>();
        for (Workspace workspace : workspaceRepository.findAll()) {
            if (!query.isBlank() && !workspace.name().toLowerCase(Locale.ROOT).contains(query)
                    && !workspace.slug().toLowerCase(Locale.ROOT).contains(query)) continue;
            String owner = userRepository == null ? null : userRepository.findById(workspace.ownerId()).map(User::username).orElse("Deleted user");
            result.add(AdminWorkspaceDto.from(workspace, owner, workspaceRepository.getMembers(workspace.id()).size()));
        }
        return result;
    }

    public AdminWorkspaceDto createWorkspace(Map<String, Object> request, String actingAdminUsername) throws SQLException {
        if (workspaceRepository == null) throw new ValidationException("Workspace administration requires a database connection");
        String name = requiredText(request, "name", 120);
        UUID ownerId = requiredUuid(request, "ownerId");
        String description = optionalText(request, "description", 2_000);
        String slug = optionalText(request, "slug", 100);
        if (slug == null || slug.isBlank()) slug = slugify(name);
        if (!slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) throw new ValidationException("Group slug may contain lowercase letters, numbers, and hyphens only");
        final String finalSlug = slug;
        Workspace created = workspaceRepository.transaction(repo -> {
            if (!repo.userExists(ownerId)) throw new ValidationException("Owner user does not exist");
            if (repo.findBySlug(finalSlug).isPresent()) throw new ValidationException("A group with this slug already exists");
            Workspace workspace = repo.create(new Workspace(name, finalSlug, description, ownerId));
            repo.addMember(new WorkspaceMember(workspace.id(), ownerId, WorkspaceRole.OWNER));
            return workspace;
        });
        logService.info("WORKSPACE_CREATED", actor(actingAdminUsername), created.id().toString(), "Created group '" + created.name() + "'.");
        return workspaceDto(created);
    }

    public AdminWorkspaceDto updateWorkspace(UUID workspaceId, Map<String, Object> request, String actingAdminUsername) throws SQLException {
        if (workspaceRepository == null) throw new ValidationException("Workspace administration requires a database connection");
        Workspace current = workspaceRepository.findById(workspaceId).orElseThrow(() -> new ValidationException("Group not found"));
        String name = request != null && request.containsKey("name") ? requiredText(request, "name", 120) : current.name();
        String description = request != null && request.containsKey("description") ? optionalText(request, "description", 2_000) : current.description();
        Workspace updated = workspaceRepository.updateBasic(workspaceId, name, description);
        logService.info("WORKSPACE_UPDATED", actor(actingAdminUsername), workspaceId.toString(), "Updated group '" + updated.name() + "'.");
        return workspaceDto(updated);
    }

    public boolean deleteWorkspace(UUID workspaceId, String actingAdminUsername) throws SQLException {
        if (workspaceRepository == null) return false;
        Workspace workspace = workspaceRepository.findById(workspaceId).orElseThrow(() -> new ValidationException("Group not found"));
        boolean deleted = workspaceRepository.deleteById(workspaceId);
        if (deleted) logService.warn("WORKSPACE_DELETED", actor(actingAdminUsername), workspaceId.toString(), "Deleted group '" + workspace.name() + "' and its related data.");
        return deleted;
    }

    public List<AdminWorkspaceMemberDto> listWorkspaceMembers(UUID workspaceId) throws SQLException {
        if (workspaceRepository == null) return List.of();
        if (workspaceRepository.findById(workspaceId).isEmpty()) throw new ValidationException("Group not found");
        return workspaceRepository.memberPage(workspaceId, 500, 0).stream().map(AdminWorkspaceMemberDto::from).toList();
    }

    public AdminWorkspaceMemberDto upsertWorkspaceMember(UUID workspaceId, Map<String, Object> request, String actingAdminUsername) throws SQLException {
        if (workspaceRepository == null) throw new ValidationException("Workspace administration requires a database connection");
        UUID userId = requiredUuid(request, "userId");
        WorkspaceRole role = WorkspaceRole.fromString(optionalText(request, "role", 20));
        if (role == WorkspaceRole.GUEST) throw new ValidationException("Guest is not a manageable group role");
        WorkspaceRepository.MemberWithUser member = workspaceRepository.transaction(repo -> {
            Workspace workspace = repo.findById(workspaceId).orElseThrow(() -> new ValidationException("Group not found"));
            if (!repo.userExists(userId)) throw new ValidationException("User not found");
            if (workspace.ownerId().equals(userId) && role != WorkspaceRole.OWNER) {
                throw new ValidationException("The group owner must retain the OWNER role");
            }
            if (!workspace.ownerId().equals(userId) && role == WorkspaceRole.OWNER) {
                throw new ValidationException("Transfer ownership is not supported from this form");
            }
            Optional<WorkspaceMember> existing = repo.findMember(workspaceId, userId);
            if (existing.isPresent()) repo.updateRole(workspaceId, userId, role);
            else repo.addMember(new WorkspaceMember(workspaceId, userId, role));
            return repo.memberWithUser(workspaceId, userId);
        });
        logService.info("WORKSPACE_MEMBER_SAVED", actor(actingAdminUsername), workspaceId + "/" + userId, "Saved group member role.");
        return AdminWorkspaceMemberDto.from(member);
    }

    public boolean removeWorkspaceMember(UUID workspaceId, UUID userId, String actingAdminUsername) throws SQLException {
        if (workspaceRepository == null) return false;
        Workspace workspace = workspaceRepository.findById(workspaceId).orElseThrow(() -> new ValidationException("Group not found"));
        if (workspace.ownerId().equals(userId)) throw new ValidationException("The group owner cannot be removed; transfer ownership first");
        boolean removed = workspaceRepository.removeMember(workspaceId, userId);
        if (removed) logService.warn("WORKSPACE_MEMBER_REMOVED", actor(actingAdminUsername), workspaceId + "/" + userId, "Removed user from group.");
        return removed;
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

    private AdminWorkspaceDto workspaceDto(Workspace workspace) throws SQLException {
        String owner = userRepository == null ? null : userRepository.findById(workspace.ownerId()).map(User::username).orElse("Deleted user");
        long members = workspaceRepository == null ? 0 : workspaceRepository.getMembers(workspace.id()).size();
        return AdminWorkspaceDto.from(workspace, owner, members);
    }

    private static String actor(String username) { return username != null && !username.isBlank() ? username : "ADMIN"; }
    private static String requiredText(Map<String, Object> input, String key, int maxLength) {
        String value = optionalText(input, key, maxLength);
        if (value == null || value.isBlank()) throw new ValidationException(key + " is required");
        return value;
    }
    private static String optionalText(Map<String, Object> input, String key, int maxLength) {
        if (input == null || !input.containsKey(key) || input.get(key) == null) return null;
        String value = String.valueOf(input.get(key)).trim();
        if (value.length() > maxLength) throw new ValidationException(key + " exceeds " + maxLength + " characters");
        return value;
    }
    private static UUID requiredUuid(Map<String, Object> input, String key) {
        try { return UUID.fromString(requiredText(input, key, 64)); }
        catch (IllegalArgumentException e) { throw new ValidationException(key + " must be a UUID"); }
    }
    private static String slugify(String name) {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (slug.isBlank()) throw new ValidationException("Name must contain letters or numbers");
        return slug;
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
