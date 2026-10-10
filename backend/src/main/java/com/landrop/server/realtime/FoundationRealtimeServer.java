package com.landrop.server.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.server.clients.ClientConnection;
import com.landrop.server.clients.ClientRegistry;
import com.landrop.server.clients.ClientStatus;
import com.landrop.chat.service.ChannelBroadcaster;
import com.landrop.chat.service.ChannelChatService;
import com.landrop.server.logging.ServerEvent;
import com.landrop.server.logging.ServerEventLogger;
import com.landrop.server.protocol.EventEnvelope;
import com.landrop.server.protocol.ProtocolConstants;
import com.landrop.server.transfers.TransferCoordinator;
import com.landrop.util.AppLogger;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshakeBuilder;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.server.WebSocketServer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import com.landrop.auth.exception.ForbiddenException;
import com.landrop.auth.exception.UnauthorizedException;
import com.landrop.auth.model.AuthErrorCode;
import com.landrop.auth.security.JwtClaims;
import com.landrop.auth.security.SessionVerifier;
import com.landrop.admin.logging.OperationalLogService;
import com.landrop.database.model.Device;
import com.landrop.database.model.User;

/**
 * Foundation Realtime WebSocket Gateway handling handshakes, heartbeats,
 * echo tests, and control-plane transfer coordination.
 */
public class FoundationRealtimeServer extends WebSocketServer implements ChannelBroadcaster {

    private static final String TAG = "FoundationRealtimeServer";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ClientRegistry clientRegistry;
    private final TransferCoordinator transferCoordinator;
    private final TransferSignalingHandler signalingHandler;
    private final long heartbeatIntervalMs;
    private final long heartbeatTimeoutMs;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private volatile boolean stopping;

    private final ScheduledExecutorService heartbeatMonitor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "foundation-heartbeat-monitor");
        t.setDaemon(true);
        return t;
    });

    private com.landrop.auth.security.JwtService jwtService;
    private com.landrop.database.repository.UserRepository userRepository;
    private com.landrop.database.repository.DeviceRepository deviceRepository;
    private com.landrop.auth.repository.RefreshTokenRepository refreshTokenRepository;
    private final Map<WebSocket, String> authorizationHeaders = new ConcurrentHashMap<>();
    private final Map<WebSocket, Instant> pendingAuthentication = new ConcurrentHashMap<>();
    private final Set<String> allowedOrigins;
    private final long authenticationTimeoutMs;
    private final Map<UUID, Set<String>> channelSubscribers = new ConcurrentHashMap<>();
    private volatile ChannelChatService channelChatService;

    public FoundationRealtimeServer(InetSocketAddress address,
                                    ClientRegistry clientRegistry,
                                    TransferCoordinator transferCoordinator,
                                    long heartbeatIntervalMs,
                                    long heartbeatTimeoutMs) {
        this(address, clientRegistry, transferCoordinator, heartbeatIntervalMs, heartbeatTimeoutMs,
                configuredOrigins(), com.landrop.config.AppConfig.getLong(
                        "landrop.ws.auth.timeout.ms", "WS_AUTH_TIMEOUT_MS", 10_000L));
    }

    public FoundationRealtimeServer(InetSocketAddress address, ClientRegistry clientRegistry,
                                    TransferCoordinator transferCoordinator, long heartbeatIntervalMs,
                                    long heartbeatTimeoutMs, Set<String> allowedOrigins, long authenticationTimeoutMs) {
        super(address, 4, List.of(new Draft_6455(List.of(), 64 * 1024)));
        this.clientRegistry = Objects.requireNonNull(clientRegistry, "clientRegistry cannot be null");
        this.transferCoordinator = Objects.requireNonNull(transferCoordinator, "transferCoordinator cannot be null");
        this.heartbeatIntervalMs = heartbeatIntervalMs > 0 ? heartbeatIntervalMs : ProtocolConstants.DEFAULT_HEARTBEAT_INTERVAL_MS;
        this.heartbeatTimeoutMs = heartbeatTimeoutMs > 0 ? heartbeatTimeoutMs : ProtocolConstants.DEFAULT_HEARTBEAT_TIMEOUT_MS;
        this.allowedOrigins = Set.copyOf(allowedOrigins);
        this.authenticationTimeoutMs = authenticationTimeoutMs > 0 ? authenticationTimeoutMs : 10_000L;
        this.signalingHandler = new TransferSignalingHandler(clientRegistry, transferCoordinator,
                connection -> authRequired() && validateRuntimeSession(connection),
                (connection, envelope) -> sendEnvelope(connection.socket(), envelope));
        setReuseAddr(true);
        setConnectionLostTimeout(0); // We implement our own explicit application-level heartbeat protocol
    }

    public void setAuthDependencies(com.landrop.auth.security.JwtService jwtService,
                                    com.landrop.database.repository.UserRepository userRepository,
                                    com.landrop.database.repository.DeviceRepository deviceRepository,
                                    com.landrop.auth.repository.RefreshTokenRepository refreshTokenRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.deviceRepository = deviceRepository;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    private static Set<String> configuredOrigins() {
        String origins = com.landrop.config.AppConfig.getString("landrop.ws.allowed.origins", "WS_ALLOWED_ORIGINS",
                "http://localhost:3000,http://127.0.0.1:3000");
        Set<String> result = new HashSet<>();
        for (String origin : origins.split(",")) {
            if (!origin.isBlank()) result.add(origin.trim());
        }
        return result;
    }

    @Override
    public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(
            WebSocket socket, Draft draft, ClientHandshake request) throws InvalidDataException {
        if (request.getResourceDescriptor().contains("?")
                || (request.hasFieldValue("Origin") && !isOriginAllowed(request.getFieldValue("Origin")))) {
            ServerEventLogger.log(ServerEvent.WS_AUTH_FAILED, "Realtime upgrade rejected by Origin/query policy: " + request.getFieldValue("Origin"));
            throw new InvalidDataException(1008, "Realtime upgrade rejected");
        }
        return super.onWebsocketHandshakeReceivedAsServer(socket, draft, request);
    }

    private boolean isOriginAllowed(String origin) {
        if (origin == null || origin.isBlank()) {
            return true;
        }
        if (allowedOrigins.contains("*") || allowedOrigins.contains(origin)) {
            return true;
        }
        try {
            URI uri = URI.create(origin);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            if ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host)) {
                return true;
            }
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isSiteLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress()) {
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** Connects persistent workspace chat broadcasts to the authenticated client gateway. */
    public void setChannelChatService(ChannelChatService channelChatService) {
        this.channelChatService = channelChatService;
    }

    public void setSignalingWorkspaceRepository(com.landrop.database.repository.WorkspaceRepository repository) {
        signalingHandler.setWorkspaceRepository(repository);
    }

    public void awaitReady() throws Exception {
        ready.get(10, TimeUnit.SECONDS);
    }

    public boolean cancelTransferFromAdmin(String transferId) {
        return signalingHandler.cancelFromAdmin(transferId);
    }

    /** Control-plane hook for the Admin Dashboard; no client auth model is changed here. */
    public boolean disconnectClient(String clientInstanceId, String reasonCode, String reasonMessage) {
        if (clientInstanceId == null || clientInstanceId.isBlank()) return false;
        ClientConnection connection = clientRegistry.findByInstanceId(clientInstanceId).orElse(null);
        if (connection == null) return false;
        return disconnectConnection(connection.connectionId(), reasonCode, reasonMessage);
    }

    /** Disconnects the selected socket only, even if its client instance subsequently reconnects. */
    public boolean disconnectConnection(String connectionId, String reasonCode, String reasonMessage) {
        if (connectionId == null || connectionId.isBlank()) return false;
        ClientConnection connection = clientRegistry.get(connectionId).orElse(null);
        if (connection == null) return false;
        String code = reasonCode == null || reasonCode.isBlank() ? ProtocolConstants.ERR_ADMIN_DISCONNECT : reasonCode;
        String message = reasonMessage == null || reasonMessage.isBlank() ? "Disconnected by server administrator." : reasonMessage;
        sendEnvelope(connection.socket(), EventEnvelope.error(null, code, message));
        connection.close(ProtocolConstants.CLOSE_ADMIN_DISCONNECT, code + ": " + message);
        return true;
    }

    @Override
    public void onStart() {
        // Schedule periodic heartbeat sweep
        long sweepInterval = Math.max(100L, Math.min(1000L, Math.min(heartbeatTimeoutMs, authenticationTimeoutMs) / 3));
        heartbeatMonitor.scheduleAtFixedRate(() -> {
            try {
                if (stopping) return;
                Instant now = Instant.now();
                pendingAuthentication.forEach((socket, openedAt) -> {
                    if (now.toEpochMilli() - openedAt.toEpochMilli() > authenticationTimeoutMs
                            && pendingAuthentication.remove(socket, openedAt)) {
                        sendEnvelope(socket, EventEnvelope.error(null, AuthErrorCode.AUTH_REQUIRED,
                                "Realtime authentication deadline exceeded."));
                        ClientConnection connection = socket.getAttachment();
                        if (connection != null) cleanupConnection(connection.connectionId());
                        socket.close(1008, "Authentication timeout");
                    }
                });
                if (authRequired()) {
                    for (ClientConnection connection : clientRegistry.activeConnections()) {
                        if (stopping) break;
                        if (connection.isAuthenticated() && !validateRuntimeSession(connection)) {
                            cleanupConnection(connection.connectionId());
                        }
                    }
                }
                signalingHandler.sweep();
                clientRegistry.sweepTimeouts(heartbeatTimeoutMs, staleConn -> {
                    ServerEventLogger.log(ServerEvent.HEARTBEAT_TIMEOUT, staleConn.connectionId(),
                            "Inactive for > " + heartbeatTimeoutMs + "ms; terminating connection.");
                    staleConn.close(4000, "Heartbeat timeout");
                    cleanupConnection(staleConn.connectionId());
                });
            } catch (Exception e) {
                AppLogger.warn(TAG, "Error during heartbeat sweep: " + e.getMessage());
            }
        }, sweepInterval, sweepInterval, TimeUnit.MILLISECONDS);

        ready.complete(null);
    }

    @Override
    public void onOpen(WebSocket socket, ClientHandshake handshake) {
        pendingAuthentication.put(socket, Instant.now());
        String authorization = handshake != null ? handshake.getFieldValue("Authorization") : null;
        if (authorization != null && !authorization.isBlank()) {
            authorizationHeaders.put(socket, authorization.trim());
        }
        String remote = socket.getRemoteSocketAddress() != null ? socket.getRemoteSocketAddress().toString() : "unknown";
        ServerEventLogger.log(ServerEvent.CLIENT_CONNECTED, null, "Remote: " + remote);
        ServerEventLogger.log(ServerEvent.WS_CONNECTED, null, "Remote: " + remote);
    }

    @Override
    public void onMessage(WebSocket socket, String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            if (root == null || !root.isObject()) {
                sendEnvelope(socket, EventEnvelope.error(null, ProtocolConstants.ERR_INVALID_JSON, "Payload must be a JSON object."));
                return;
            }

            String type = root.path("type").asText(null);
            String requestId = root.hasNonNull("requestId") ? root.path("requestId").asText() : null;

            if (type == null || type.isBlank()) {
                sendEnvelope(socket, EventEnvelope.error(requestId, ProtocolConstants.ERR_INVALID_PAYLOAD, "Field 'type' is required."));
                return;
            }

            ClientConnection connection = socket.getAttachment();

            // 1. Handshake Phase
            if (connection == null) {
                if (!ProtocolConstants.EVENT_HELLO.equals(type)) {
                    sendEnvelope(socket, EventEnvelope.error(requestId, ProtocolConstants.ERR_UNAUTHORIZED_HANDSHAKE,
                            "Initial handshake required: first message must be 'hello'."));
                    socket.close(1008, "Initial handshake required");
                    return;
                }
                handleHello(socket, root, requestId);
                return;
            }

            // 2. Authenticated Runtime Phase: update heartbeat
            if (!connection.isOpen() || clientRegistry.get(connection.connectionId()).orElse(null) != connection) return;
            connection.updateHeartbeat();

            if (authRequired()) {
                if (connection.isAuthenticated() && !validateRuntimeSession(connection)) {
                    if ("channel.join".equals(type)) ServerEventLogger.log(
                            ServerEvent.CHANNEL_SUBSCRIBE_DENIED, connection.connectionId(), "Session validation failed.");
                    return;
                }

                boolean isBasicEvent = ProtocolConstants.EVENT_AUTH.equals(type)
                        || ProtocolConstants.EVENT_HEARTBEAT.equals(type)
                        || ProtocolConstants.EVENT_ECHO.equals(type);

                if (!isBasicEvent) {
                    boolean isKnownProtected = type != null && (
                        type.startsWith("channel.")
                        || type.startsWith("transfer.")
                        || type.startsWith("webrtc.")
                    );

                    if (isKnownProtected && !connection.isAuthenticated()) {
                        if ("channel.join".equals(type)) ServerEventLogger.log(
                                ServerEvent.CHANNEL_SUBSCRIBE_DENIED, connection.connectionId(), "Authentication required.");
                        sendEnvelope(socket, EventEnvelope.error(requestId, AuthErrorCode.AUTH_REQUIRED,
                                "Authenticate before using realtime services."));
                        return;
                    }
                }
            }

            switch (type) {
                case ProtocolConstants.EVENT_HEARTBEAT -> {
                    ServerEventLogger.log(ServerEvent.HEARTBEAT, connection.connectionId(), "Heartbeat received");
                    triggerDeviceLastSeenSync(connection);
                    sendEnvelope(socket, EventEnvelope.heartbeatAck(requestId));
                }
                case ProtocolConstants.EVENT_AUTH -> {
                    handleAuth(connection, root, requestId);
                }
                case ProtocolConstants.EVENT_ECHO -> {
                    JsonNode payloadNode = root.path("payload");
                    String msg = payloadNode.hasNonNull("message") ? payloadNode.path("message").asText() : "";
                    sendEnvelope(socket, EventEnvelope.echoAck(requestId, Map.of(
                        "message", msg,
                        "serverTimestamp", Instant.now().toString()
                    )));
                }
                case ProtocolConstants.EVENT_TRANSFER_REQUEST,
                     ProtocolConstants.EVENT_TRANSFER_ACCEPT,
                     ProtocolConstants.EVENT_TRANSFER_REJECT,
                     ProtocolConstants.EVENT_TRANSFER_CANCEL,
                     ProtocolConstants.EVENT_WEBRTC_SIGNAL,
                     ProtocolConstants.EVENT_WEBRTC_OFFER,
                     ProtocolConstants.EVENT_WEBRTC_ANSWER,
                     ProtocolConstants.EVENT_WEBRTC_CANDIDATE,
                     ProtocolConstants.EVENT_WEBRTC_CANDIDATE_ALIAS,
                     ProtocolConstants.EVENT_WEBRTC_CONNECTION_STATUS,
                     "transfer.renew", "webrtc.restart" ->
                        signalingHandler.handle(connection, type, root.path("payload"), requestId);
                case "peer.list", "peers.list" -> handlePeerList(connection, requestId);
                case "channel.join" -> handleChannelJoin(connection, root.path("payload"), requestId);
                case "channel.leave" -> handleChannelLeave(connection, root.path("payload"), requestId);
                default -> {
                    sendEnvelope(socket, EventEnvelope.error(requestId, ProtocolConstants.ERR_UNKNOWN_EVENT,
                            "Unknown event type: " + type));
                }
            }

        } catch (Exception e) {
            // Do not echo parser or internal exception details into a client response.
            sendEnvelope(socket, EventEnvelope.error(null, ProtocolConstants.ERR_INVALID_JSON,
                    "Could not process the realtime message."));
        }
    }

    /**
     * A channel room is only a delivery subscription. Every join and every
     * broadcast verifies current workspace membership, so a stale socket never
     * receives messages after access has been removed.
     */
    private void handleChannelJoin(ClientConnection connection, JsonNode payload, String requestId) {
        UUID channelId = channelId(payload, requestId, connection);
        if (channelId == null) return;
        ChannelChatService chat = channelChatService;
        if (chat == null) {
            sendEnvelope(connection.socket(), EventEnvelope.error(requestId, ProtocolConstants.ERR_INTERNAL_ERROR,
                    "Realtime chat is not available."));
            return;
        }
        try {
            if (!connection.isAuthenticated() || !authRequired() || !validateRuntimeSession(connection)
                    || !chat.canAccess(connection.userId(), channelId)) {
                ServerEventLogger.log(ServerEvent.CHANNEL_SUBSCRIBE_DENIED, connection.connectionId(),
                        "channelId=" + channelId);
                sendEnvelope(connection.socket(), EventEnvelope.error(requestId, "FORBIDDEN",
                        "Channel access is unavailable."));
                return;
            }
            channelSubscribers.compute(channelId, (ignored, subscribers) -> {
                Set<String> room = subscribers != null ? subscribers : ConcurrentHashMap.newKeySet();
                room.add(connection.connectionId());
                return room;
            });
            ServerEventLogger.log(ServerEvent.CHANNEL_SUBSCRIBED, connection.connectionId(), "channelId=" + channelId);
            sendEnvelope(connection.socket(), EventEnvelope.event("channel.joined", requestId,
                    Map.of("channelId", channelId.toString())));
        } catch (Exception error) {
            sendEnvelope(connection.socket(), EventEnvelope.error(requestId, ProtocolConstants.ERR_INTERNAL_ERROR,
                    "Could not join the chat channel."));
        }
    }

    private void handleChannelLeave(ClientConnection connection, JsonNode payload, String requestId) {
        UUID channelId = channelId(payload, requestId, connection);
        if (channelId == null) return;
        removeChannelSubscription(connection.connectionId(), channelId);
        sendEnvelope(connection.socket(), EventEnvelope.event("channel.left", requestId,
                Map.of("channelId", channelId.toString())));
    }

    private UUID channelId(JsonNode payload, String requestId, ClientConnection connection) {
        try {
            String value = payload.path("channelId").asText("");
            if (value.isBlank()) throw new IllegalArgumentException();
            return UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            sendEnvelope(connection.socket(), EventEnvelope.error(requestId, ProtocolConstants.ERR_INVALID_PAYLOAD,
                    "A valid channelId is required."));
            return null;
        }
    }

    @Override
    public void broadcast(UUID channelId, Object event) {
        if (channelId == null || event == null) return;
        ChannelChatService chat = channelChatService;
        if (chat == null) return;
        Set<String> subscribers = channelSubscribers.get(channelId);
        if (subscribers == null || subscribers.isEmpty()) return;

        String eventType = eventType(event);
        int delivered = 0;
        for (String connectionId : Set.copyOf(subscribers)) {
            ClientConnection connection = clientRegistry.get(connectionId).orElse(null);
            if (connection == null || !connection.isOpen() || !connection.isAuthenticated()) {
                removeChannelSubscription(connectionId, channelId);
                continue;
            }
            try {
                if (!authRequired() || !validateRuntimeSession(connection)
                        || !chat.canAccess(connection.userId(), channelId)) {
                    removeChannelSubscription(connectionId, channelId);
                    continue;
                }
                if (sendEnvelope(connection.socket(), EventEnvelope.event(eventType, null, event))) delivered++;
            } catch (Exception ignored) {
                // Treat an unavailable authorization check as no permission.
                removeChannelSubscription(connectionId, channelId);
            }
        }
        ServerEventLogger.log(ServerEvent.MESSAGE_BROADCAST, "channelId=" + channelId
                + ", event=" + eventType + ", recipients=" + delivered);
    }

    private static String eventType(Object event) {
        if (event instanceof Map<?, ?> map) {
            Object type = map.get("type");
            if (type instanceof String value && !value.isBlank()) return value;
        }
        return "message.updated";
    }

    private void removeChannelSubscription(String connectionId, UUID channelId) {
        channelSubscribers.computeIfPresent(channelId, (ignored, subscribers) -> {
            subscribers.remove(connectionId);
            return subscribers.isEmpty() ? null : subscribers;
        });
    }

    private void removeAllChannelSubscriptions(String connectionId) {
        channelSubscribers.forEach((channelId, subscribers) -> removeChannelSubscription(connectionId, channelId));
    }

    private void handleHello(WebSocket socket, JsonNode root, String requestId) {
        String remote = socket.getRemoteSocketAddress() != null ? socket.getRemoteSocketAddress().toString() : "unknown";
        ServerEventLogger.log(ServerEvent.HELLO_RECEIVED, null, "Handshake request from " + remote);

        int protocolVer = root.path("protocolVersion").asInt(-1);
        if (protocolVer != ProtocolConstants.PROTOCOL_VERSION) {
            ServerEventLogger.log(ServerEvent.CLIENT_HANDSHAKE_FAILED, null,
                    "Unsupported protocol version: " + protocolVer + " (expected " + ProtocolConstants.PROTOCOL_VERSION + ")");
            sendEnvelope(socket, EventEnvelope.error(requestId, ProtocolConstants.ERR_PROTOCOL_VERSION_UNSUPPORTED,
                    "Unsupported protocol version: " + protocolVer + ". Supported version: " + ProtocolConstants.PROTOCOL_VERSION));
            socket.close(1002, "Unsupported protocol version");
            return;
        }

        String clientInstanceId = root.hasNonNull("clientInstanceId") ? root.path("clientInstanceId").asText() : UUID.randomUUID().toString();
        String deviceName = root.hasNonNull("deviceName") ? root.path("deviceName").asText() : "Generic Device";
        String clientVersion = root.hasNonNull("clientVersion") ? root.path("clientVersion").asText() : "1.0.0";
        String platform = root.hasNonNull("platform") ? root.path("platform").asText() : "unknown";
        String osVersion = root.hasNonNull("osVersion") ? root.path("osVersion").asText() : "unknown";
        String architecture = root.hasNonNull("architecture") ? root.path("architecture").asText() : "unknown";

        String connectionId = UUID.randomUUID().toString();
        ClientConnection connection = new ClientConnection(
            connectionId,
            clientInstanceId,
            deviceName,
            remote,
            Instant.now(),
            protocolVer,
            clientVersion,
            platform,
            osVersion,
            architecture,
            socket
        );

        synchronized (socket) {
            if (!socket.isOpen() || !pendingAuthentication.containsKey(socket)) return;
            socket.setAttachment(connection);
            clientRegistry.register(connection);
        }

        ServerEventLogger.log(ServerEvent.HANDSHAKE_OK, connectionId,
                "protocolVersion=" + protocolVer);
        ServerEventLogger.log(ServerEvent.CLIENT_HANDSHAKE_OK, connectionId,
                "protocolVersion=" + protocolVer);

        String authorization = authorizationHeaders.get(socket);
        String token = bearerToken(authorization);
        String deviceId = root.hasNonNull("deviceId") ? root.path("deviceId").asText() : null;

        if (authRequired() && authorization != null) {
            if (token == null) {
                logAuthBindFailure(connection, AuthErrorCode.AUTH_REQUIRED);
                rejectHandshake(connection, requestId, AuthErrorCode.AUTH_REQUIRED, "Authentication is required.");
                return;
            }
            if (deviceId == null || deviceId.isBlank()) {
                logAuthBindFailure(connection, AuthErrorCode.DEVICE_NOT_FOUND);
                rejectHandshake(connection, requestId, AuthErrorCode.DEVICE_NOT_FOUND, "An enrolled device is required.");
                return;
            }
            authenticateConnection(connection, token, deviceId, requestId, true);
            return;
        }

        if (!authRequired()) pendingAuthentication.remove(socket);
        sendEnvelope(socket, EventEnvelope.welcome(connectionId, ProtocolConstants.SERVER_VERSION,
            ProtocolConstants.PROTOCOL_VERSION, heartbeatIntervalMs));
    }

    @Override
    public void onClose(WebSocket socket, int code, String reason, boolean remote) {
        synchronized (socket) {
            authorizationHeaders.remove(socket);
            pendingAuthentication.remove(socket);
            ClientConnection connection = socket.getAttachment();
            if (connection != null) {
                String connId = connection.connectionId();
                cleanupConnection(connId);
                ServerEventLogger.log(ServerEvent.CLIENT_DISCONNECTED, connId,
                        "Code: " + code + ", Remote: " + remote);
                ServerEventLogger.log(ServerEvent.WS_DISCONNECTED, connId, "Code: " + code + ", Remote: " + remote);
            }
        }
    }

    @Override
    public void onError(WebSocket socket, Exception ex) {
        if (socket == null) {
            ready.completeExceptionally(ex != null ? ex : new IllegalStateException("Realtime listener failure."));
        } else {
            AppLogger.warn(TAG, "Socket error: " + (ex != null ? ex.getMessage() : "unknown"));
        }
    }

    @Override
    public void stop(int timeout) throws InterruptedException {
        stopping = true;
        for (WebSocket socket : getConnections()) {
            sendEnvelope(socket, EventEnvelope.error(null, ProtocolConstants.ERR_SERVER_SHUTDOWN,
                    "Server is shutting down."));
            ClientConnection connection = socket.getAttachment();
            if (connection != null) connection.close(ProtocolConstants.CLOSE_SERVER_SHUTDOWN, ProtocolConstants.ERR_SERVER_SHUTDOWN);
            else socket.close(ProtocolConstants.CLOSE_SERVER_SHUTDOWN, ProtocolConstants.ERR_SERVER_SHUTDOWN);
        }
        heartbeatMonitor.shutdownNow();
        heartbeatMonitor.awaitTermination(Math.max(1000L, timeout), TimeUnit.MILLISECONDS);
        super.stop(timeout);
        pendingAuthentication.clear();
        authorizationHeaders.clear();
        channelSubscribers.clear();
        clientRegistry.clear();
        transferCoordinator.clear();
    }

    private void cleanupConnection(String connectionId) {
        removeAllChannelSubscriptions(connectionId);
        clientRegistry.remove(connectionId);
        notifyDisconnectedPeerTransfers(connectionId);
    }

    public int subscriptionCount() {
        return channelSubscribers.values().stream().mapToInt(Set::size).sum();
    }

    private void notifyDisconnectedPeerTransfers(String disconnectedConnId) {
        signalingHandler.disconnected(disconnectedConnId);
    }

    private void handlePeerList(ClientConnection connection, String requestId) {
        List<Map<String, Object>> peers = new ArrayList<>();
        for (ClientConnection other : clientRegistry.activeConnections()) {
            if (!other.connectionId().equals(connection.connectionId()) && other.isOpen()) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("connectionId", other.connectionId());
                p.put("userId", other.userId() != null ? other.userId().toString() : null);
                p.put("deviceId", other.deviceId());
                p.put("username", other.username());
                p.put("displayName", other.username() != null ? other.username() : (other.deviceName() != null ? other.deviceName() : "Thiết bị LAN"));
                p.put("deviceName", other.deviceName() != null ? other.deviceName() : "Thiết bị LAN");
                p.put("platform", other.platform() != null ? other.platform() : "browser");
                peers.add(p);
            }
        }
        sendEnvelope(connection.socket(), EventEnvelope.event("peer.list", requestId, Map.of("peers", peers)));
    }

    private void handleAuth(ClientConnection connection, JsonNode root, String requestId) {
        if (connection.isAuthenticated()) {
            sendEnvelope(connection.socket(), EventEnvelope.error(requestId, AuthErrorCode.UNAUTHORIZED,
                "Connection is already authenticated; reconnect to change credentials."));
            return;
        }
        JsonNode payload = root.path("payload");
        String token = null;
        if (payload.hasNonNull("token")) {
            token = payload.path("token").asText();
        } else if (payload.hasNonNull("authToken")) {
            token = payload.path("authToken").asText();
        } else if (payload.hasNonNull("accessToken")) {
            token = payload.path("accessToken").asText();
        } else if (root.hasNonNull("token")) {
            token = root.path("token").asText();
        }

        if (token == null || token.isBlank()) {
            logAuthBindFailure(connection, AuthErrorCode.UNAUTHORIZED);
            sendEnvelope(connection.socket(), EventEnvelope.error(requestId, AuthErrorCode.UNAUTHORIZED, "Missing authentication token in payload."));
            return;
        }

        String providedDeviceId = payload.hasNonNull("deviceId") ? payload.path("deviceId").asText() : null;
        authenticateConnection(connection, token, providedDeviceId, requestId, false);
    }

    private boolean authenticateConnection(ClientConnection connection, String token, String providedDeviceId,
                                           String requestId, boolean authenticatedWelcome) {
        if (jwtService == null || userRepository == null) {
            sendEnvelope(connection.socket(), EventEnvelope.error(requestId, ProtocolConstants.ERR_INTERNAL_ERROR, "Authentication service is not configured on server."));
            return false;
        }

        try {
            JwtClaims claims = jwtService.verifyToken(token, false);
            UUID userId = claims.userId();

            Optional<User> userOpt = userRepository.findById(userId);
            if (userOpt.isEmpty()) {
                logAuthBindFailure(connection, AuthErrorCode.UNAUTHORIZED);
                sendEnvelope(connection.socket(), EventEnvelope.error(requestId, AuthErrorCode.UNAUTHORIZED, "User not found."));
                return false;
            }

            User user = userOpt.get();
            if (user.isDisabled()) {
                logAuthBindFailure(connection, AuthErrorCode.ACCOUNT_DISABLED);
                sendEnvelope(connection.socket(), EventEnvelope.error(requestId, AuthErrorCode.ACCOUNT_DISABLED, "User account is disabled."));
                connection.close(ProtocolConstants.CLOSE_ACCOUNT_DISABLED, "ACCOUNT_DISABLED: User account is disabled");
                return false;
            }

            // The authenticated realtime channel is a Client channel. System
            // administrators use the Server Control Center and never bind a
            // client installation/device here.
            if (user.isSystemAdmin()) {
                throw new ForbiddenException("System administrators must use the Server Control Center", AuthErrorCode.ADMIN_LOGIN_REQUIRED);
            }

            // Browser event auth derives its optional device from the signed session.
            String resolvedDeviceId = providedDeviceId;
            com.landrop.auth.model.RefreshToken verifiedSession = null;
            if (authRequired()) {
                verifiedSession = SessionVerifier.requireActive(refreshTokenRepository, claims);
                if (!authenticatedWelcome) {
                    if (providedDeviceId != null && !providedDeviceId.isBlank()
                            && !providedDeviceId.equals(verifiedSession.deviceId())) {
                        throw new UnauthorizedException("Device does not match this session", AuthErrorCode.DEVICE_CONFLICT);
                    }
                    resolvedDeviceId = verifiedSession.deviceId();
                }
            }
            if (!authRequired() && (resolvedDeviceId == null || resolvedDeviceId.isBlank())) {
                if (deviceRepository != null && connection.clientInstanceId() != null) {
                    var devOpt = deviceRepository.findByClientInstanceId(connection.clientInstanceId());
                    if (devOpt.isPresent()) {
                        var dev = devOpt.get();
                        if (dev.userId().equals(userId)) {
                            resolvedDeviceId = dev.id();
                        }
                    }
                }
            }

            if (authRequired()) {
                if (authenticatedWelcome && (resolvedDeviceId == null || resolvedDeviceId.isBlank())) {
                    throw new UnauthorizedException("An enrolled device is required", AuthErrorCode.DEVICE_NOT_FOUND);
                }
                if (resolvedDeviceId != null && !resolvedDeviceId.isBlank()) {
                    Device device = deviceRepository.findById(resolvedDeviceId)
                            .orElseThrow(() -> new UnauthorizedException("Device not found", AuthErrorCode.DEVICE_NOT_FOUND));
                    if (!userId.equals(device.userId()) || (authenticatedWelcome
                            && !connection.clientInstanceId().equals(device.clientInstanceId()))) {
                        throw new UnauthorizedException("Device does not match this user and client installation", AuthErrorCode.DEVICE_CONFLICT);
                    }
                }
            }

            String resolvedSessionId = claims.sessionId();
            if (authRequired()) {
                if (resolvedSessionId == null || resolvedSessionId.isBlank()) {
                    throw new UnauthorizedException("Session binding is missing", AuthErrorCode.SESSION_EXPIRED);
                }
                var session = verifiedSession;
                if (!Objects.equals(resolvedDeviceId, session.deviceId())) {
                    throw new UnauthorizedException("Session is not bound to this enrolled device", AuthErrorCode.DEVICE_CONFLICT);
                }
                refreshTokenRepository.updateLastSeen(session.id(), Instant.now());
            }
            if (resolvedSessionId == null || resolvedSessionId.isBlank()) {
                resolvedSessionId = claims.tokenId();
            }

            // Bind in clientRegistry
            connection.setAccessTokenExpiresAt(claims.expiresAt());
            clientRegistry.bindIdentity(connection.connectionId(), userId, user.username(), resolvedDeviceId, resolvedSessionId);
            pendingAuthentication.remove(connection.socket());
            authorizationHeaders.remove(connection.socket());
            ServerEventLogger.log(ServerEvent.WS_AUTHENTICATED, connection.connectionId(), "userId=" + userId);
            OperationalLogService.getInstance().info("AUTH_BIND_SUCCESS", userId.toString(),
                    connection.connectionId(), "Authenticated realtime connection bound to session and device.");

            // Trigger lastSeen update on device if deviceId is resolved
            triggerDeviceLastSeenSync(connection);

            if (authenticatedWelcome) {
                sendEnvelope(connection.socket(), EventEnvelope.authenticatedWelcome(connection.connectionId(),
                    ProtocolConstants.SERVER_VERSION, ProtocolConstants.PROTOCOL_VERSION, heartbeatIntervalMs,
                    userId.toString(), user.username(), resolvedDeviceId, resolvedSessionId));
                return true;
            }

            // Legacy unauthenticated gateway compatibility.
            Map<String, Object> ackPayload = new LinkedHashMap<>();
            ackPayload.put("authenticated", true);
            ackPayload.put("userId", userId.toString());
            ackPayload.put("username", user.username());
            ackPayload.put("email", user.email());
            ackPayload.put("deviceId", resolvedDeviceId);
            ackPayload.put("sessionId", resolvedSessionId);
            sendEnvelope(connection.socket(), EventEnvelope.authAck(requestId, ackPayload));

            ServerEventLogger.log(ServerEvent.HANDSHAKE_OK, connection.connectionId(),
                    "Authenticated client connection for user: " + user.username() + " (Device: " + resolvedDeviceId + ")");
            return true;

        } catch (UnauthorizedException e) {
            String code = e.errorCode() != null ? e.errorCode() : AuthErrorCode.UNAUTHORIZED;
            logAuthBindFailure(connection, code);
            if (authenticatedWelcome) {
                rejectHandshake(connection, requestId, code, e.getMessage());
            } else {
                sendEnvelope(connection.socket(), EventEnvelope.error(requestId, code, e.getMessage()));
            }
            return false;
        } catch (ForbiddenException e) {
            String code = e.errorCode() != null ? e.errorCode() : AuthErrorCode.ACCOUNT_DISABLED;
            logAuthBindFailure(connection, code);
            sendEnvelope(connection.socket(), EventEnvelope.error(requestId, code, e.getMessage()));
            connection.close(ProtocolConstants.CLOSE_ACCOUNT_DISABLED, code + ": " + e.getMessage());
            return false;
        } catch (Exception e) {
            AppLogger.error(TAG, "Error authenticating connection: " + e.getMessage(), e);
            logAuthBindFailure(connection, ProtocolConstants.ERR_INTERNAL_ERROR);
            if (authenticatedWelcome) {
                rejectHandshake(connection, requestId, ProtocolConstants.ERR_INTERNAL_ERROR, "Authentication failed.");
            } else {
                sendEnvelope(connection.socket(), EventEnvelope.error(requestId, ProtocolConstants.ERR_INTERNAL_ERROR, "Authentication failed."));
            }
            return false;
        }
    }

    private boolean authRequired() {
        return jwtService != null && userRepository != null && deviceRepository != null && refreshTokenRepository != null;
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String token = authorization.substring(7).trim();
        return token.isBlank() ? null : token;
    }

    private void rejectHandshake(ClientConnection connection, String requestId, String code, String message) {
        sendEnvelope(connection.socket(), EventEnvelope.error(requestId, code, message));
        clientRegistry.remove(connection.connectionId());
        connection.close(1008, code);
    }

    private void triggerDeviceLastSeenSync(ClientConnection connection) {
        if (connection == null || !connection.isAuthenticated()) return;
        String devId = connection.deviceId();
        if (devId != null && !devId.isBlank() && deviceRepository != null) {
            if (connection.shouldUpdateDeviceLastSeen(15_000L)) {
                connection.markDeviceLastSeenUpdated();
                CompletableFuture.runAsync(() -> {
                    try {
                        deviceRepository.updateLastSeen(devId, Instant.now());
                    } catch (Exception e) {
                        AppLogger.warn(TAG, "Failed to update device last_seen_at for " + devId + ": " + e.getMessage());
                    }
                });
            }
        }
        String sessionId = connection.sessionId();
        if (sessionId != null && refreshTokenRepository != null && connection.shouldUpdateSessionLastSeen(15_000L)) {
            connection.markSessionLastSeenUpdated();
            CompletableFuture.runAsync(() -> {
                try {
                    refreshTokenRepository.updateLastSeen(UUID.fromString(sessionId), Instant.now());
                } catch (Exception e) {
                    AppLogger.warn(TAG, "Failed to update session last_seen_at: " + e.getMessage());
                }
            });
        }
    }

    private boolean validateRuntimeSession(ClientConnection connection) {
        if (stopping || !connection.isOpen() || !connection.isAuthenticated()) {
            return false;
        }
        try {
            Instant expiresAt = connection.accessTokenExpiresAt();
            if (expiresAt == null || !Instant.now().isBefore(expiresAt)) {
                sendEnvelope(connection.socket(), EventEnvelope.error(null, AuthErrorCode.UNAUTHORIZED,
                        "Access token expired; refresh and reconnect."));
                connection.close(4002, AuthErrorCode.UNAUTHORIZED);
                cleanupConnection(connection.connectionId());
                return false;
            }
            UUID sessionId = UUID.fromString(connection.sessionId());
            var session = SessionVerifier.requireActive(refreshTokenRepository, sessionId, connection.userId());
            if (!Objects.equals(session.deviceId(), connection.deviceId())) {
                throw new UnauthorizedException("Session device binding changed", AuthErrorCode.DEVICE_CONFLICT);
            }
            User user = userRepository.findById(connection.userId())
                    .orElseThrow(() -> new UnauthorizedException("User no longer exists"));
            if (user.isDisabled()) {
                clientRegistry.disconnectSession(connection.sessionId(), AuthErrorCode.ACCOUNT_DISABLED,
                        "User account is disabled");
                return false;
            }
            return true;
        } catch (UnauthorizedException invalid) {
            clientRegistry.disconnectSession(connection.sessionId(), invalid.errorCode(), invalid.getMessage());
            return false;
        } catch (Exception unavailable) {
            clientRegistry.disconnectSession(connection.sessionId(), AuthErrorCode.UNAUTHORIZED,
                    "Authentication state is unavailable");
            return false;
        }
    }

    private void logAuthBindFailure(ClientConnection connection, String code) {
        ServerEventLogger.log(ServerEvent.WS_AUTH_FAILED, connection.connectionId(), "code=" + code);
        OperationalLogService.getInstance().warn("AUTH_BIND_FAILED", "SYSTEM",
                connection.connectionId(), "Realtime authentication rejected: " + code);
    }

    private boolean sendEnvelope(WebSocket socket, EventEnvelope envelope) {
        if (socket != null && socket.isOpen()) {
            try {
                socket.send(objectMapper.writeValueAsString(envelope));
                return true;
            } catch (Exception e) {
                AppLogger.warn(TAG, "Failed to serialize or send realtime envelope.");
            }
        }
        return false;
    }
}
