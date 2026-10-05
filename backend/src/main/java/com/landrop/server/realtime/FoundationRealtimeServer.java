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
import com.landrop.server.transfers.TransferRecord;
import com.landrop.util.AppLogger;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
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
    private final long heartbeatIntervalMs;
    private final long heartbeatTimeoutMs;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();

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
    private final Map<UUID, Set<String>> channelSubscribers = new ConcurrentHashMap<>();
    private volatile ChannelChatService channelChatService;

    public FoundationRealtimeServer(InetSocketAddress address,
                                    ClientRegistry clientRegistry,
                                    TransferCoordinator transferCoordinator,
                                    long heartbeatIntervalMs,
                                    long heartbeatTimeoutMs) {
        super(address, 4, List.of(new Draft_6455(List.of(), 64 * 1024)));
        this.clientRegistry = Objects.requireNonNull(clientRegistry, "clientRegistry cannot be null");
        this.transferCoordinator = Objects.requireNonNull(transferCoordinator, "transferCoordinator cannot be null");
        this.heartbeatIntervalMs = heartbeatIntervalMs > 0 ? heartbeatIntervalMs : ProtocolConstants.DEFAULT_HEARTBEAT_INTERVAL_MS;
        this.heartbeatTimeoutMs = heartbeatTimeoutMs > 0 ? heartbeatTimeoutMs : ProtocolConstants.DEFAULT_HEARTBEAT_TIMEOUT_MS;
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

    /** Connects persistent workspace chat broadcasts to the authenticated client gateway. */
    public void setChannelChatService(ChannelChatService channelChatService) {
        this.channelChatService = channelChatService;
    }

    public void awaitReady() throws Exception {
        ready.get(10, TimeUnit.SECONDS);
    }

    /** Control-plane hook for the Admin Dashboard; no client auth model is changed here. */
    public boolean disconnectClient(String clientInstanceId, String reasonCode, String reasonMessage) {
        if (clientInstanceId == null || clientInstanceId.isBlank()) return false;
        ClientConnection connection = clientRegistry.findByInstanceId(clientInstanceId).orElse(null);
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
        long sweepInterval = Math.max(1000L, heartbeatTimeoutMs / 3);
        heartbeatMonitor.scheduleAtFixedRate(() -> {
            try {
                clientRegistry.sweepTimeouts(heartbeatTimeoutMs, staleConn -> {
                    ServerEventLogger.log(ServerEvent.HEARTBEAT_TIMEOUT, staleConn.connectionId(),
                            "Inactive for > " + heartbeatTimeoutMs + "ms; terminating connection.");
                    staleConn.close(4000, "Heartbeat timeout");
                    clientRegistry.remove(staleConn.connectionId());
                    notifyDisconnectedPeerTransfers(staleConn.connectionId());
                });
            } catch (Exception e) {
                AppLogger.warn(TAG, "Error during heartbeat sweep: " + e.getMessage());
            }
        }, sweepInterval, sweepInterval, TimeUnit.MILLISECONDS);

        ready.complete(null);
    }

    @Override
    public void onOpen(WebSocket socket, ClientHandshake handshake) {
        String authorization = handshake != null ? handshake.getFieldValue("Authorization") : null;
        if (authorization != null && !authorization.isBlank()) {
            authorizationHeaders.put(socket, authorization.trim());
        }
        String remote = socket.getRemoteSocketAddress() != null ? socket.getRemoteSocketAddress().toString() : "unknown";
        ServerEventLogger.log(ServerEvent.CLIENT_CONNECTED, null, "Remote: " + remote);
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
            connection.updateHeartbeat();

            switch (type) {
                case ProtocolConstants.EVENT_HEARTBEAT -> {
                    if (authRequired() && !validateRuntimeSession(connection)) return;
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
                case ProtocolConstants.EVENT_TRANSFER_REQUEST -> {
                    handleTransferRequest(connection, root, requestId);
                }
                case ProtocolConstants.EVENT_TRANSFER_ACCEPT -> {
                    handleTransferAccept(connection, root, requestId);
                }
                case ProtocolConstants.EVENT_TRANSFER_REJECT -> {
                    handleTransferReject(connection, root, requestId);
                }
                case ProtocolConstants.EVENT_TRANSFER_CANCEL -> {
                    handleTransferCancel(connection, root, requestId);
                }
                case ProtocolConstants.EVENT_WEBRTC_SIGNAL,
                     ProtocolConstants.EVENT_WEBRTC_OFFER,
                     ProtocolConstants.EVENT_WEBRTC_ANSWER,
                     ProtocolConstants.EVENT_WEBRTC_CANDIDATE -> {
                    handleWebRTCSignal(connection, root, type, requestId);
                }
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
            if (!connection.isAuthenticated() || !chat.canAccess(connection.userId(), channelId)) {
                sendEnvelope(connection.socket(), EventEnvelope.error(requestId, "FORBIDDEN",
                        "Channel access is unavailable."));
                return;
            }
            channelSubscribers.computeIfAbsent(channelId, ignored -> ConcurrentHashMap.newKeySet())
                    .add(connection.connectionId());
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

    /**
     * WebRTC signaling relay transferring SDP offers, answers, and ICE candidates
     * directly between authenticated browser sessions without the server ever touching file bytes.
     */
    private void handleWebRTCSignal(ClientConnection connection, JsonNode root, String eventType, String requestId) {
        JsonNode payload = root.path("payload");
        if (payload.isMissingNode() || payload.isNull()) {
            sendEnvelope(connection.socket(), EventEnvelope.error(requestId, ProtocolConstants.ERR_INVALID_PAYLOAD,
                    "Payload is required for WebRTC signaling."));
            return;
        }

        String transferId = payload.hasNonNull("transferId") ? payload.path("transferId").asText(null) : null;
        String toConnId = payload.hasNonNull("toConnectionId") ? payload.path("toConnectionId").asText(null) : null;

        // Resolve destination connection via active TransferRecord if only transferId was supplied
        if ((toConnId == null || toConnId.isBlank()) && transferId != null && !transferId.isBlank()) {
            TransferRecord record = transferCoordinator.getTransfer(transferId).orElse(null);
            if (record != null) {
                if (record.senderConnectionId().equals(connection.connectionId())) {
                    toConnId = record.receiverConnectionId();
                } else if (record.receiverConnectionId().equals(connection.connectionId())) {
                    toConnId = record.senderConnectionId();
                }
            }
        }

        if (toConnId == null || toConnId.isBlank()) {
            sendEnvelope(connection.socket(), EventEnvelope.error(requestId, ProtocolConstants.ERR_INVALID_PAYLOAD,
                    "Target receiver connection or valid transferId is required for WebRTC signaling."));
            return;
        }

        ClientConnection target = clientRegistry.get(toConnId).orElse(null);
        if (target == null || !target.isOpen()) {
            sendEnvelope(connection.socket(), EventEnvelope.error(requestId, ProtocolConstants.ERR_RECEIVER_NOT_FOUND,
                    "Target peer for WebRTC signaling is offline or not found."));
            return;
        }

        // Convert payload into a map and stamp sender metadata
        Map<String, Object> forwardedPayload = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = payload.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            forwardedPayload.put(entry.getKey(), entry.getValue());
        }
        forwardedPayload.put("fromConnectionId", connection.connectionId());
        forwardedPayload.put("fromUserId", connection.userId() != null ? connection.userId().toString() : "");
        forwardedPayload.put("fromDeviceName", connection.deviceName() != null ? connection.deviceName() : "Browser Session");

        // Forward signaling envelope directly to target peer
        sendEnvelope(target.socket(), EventEnvelope.event(eventType, null, forwardedPayload));

        // Acknowledge delivery to sender if client specified a requestId
        if (requestId != null && !requestId.isBlank()) {
            sendEnvelope(connection.socket(), EventEnvelope.event(eventType + "_ack", requestId, Map.of(
                "status", "delivered",
                "toConnectionId", toConnId
            )));
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
        for (String connectionId : Set.copyOf(subscribers)) {
            ClientConnection connection = clientRegistry.get(connectionId).orElse(null);
            if (connection == null || !connection.isOpen() || !connection.isAuthenticated()) {
                removeChannelSubscription(connectionId, channelId);
                continue;
            }
            try {
                if (!chat.canAccess(connection.userId(), channelId)) {
                    removeChannelSubscription(connectionId, channelId);
                    continue;
                }
                sendEnvelope(connection.socket(), EventEnvelope.event(eventType, null, event));
            } catch (Exception ignored) {
                // Treat an unavailable authorization check as no permission.
                removeChannelSubscription(connectionId, channelId);
            }
        }
    }

    private static String eventType(Object event) {
        if (event instanceof Map<?, ?> map) {
            Object type = map.get("type");
            if (type instanceof String value && !value.isBlank()) return value;
        }
        return "message.updated";
    }

    private void removeChannelSubscription(String connectionId, UUID channelId) {
        Set<String> subscribers = channelSubscribers.get(channelId);
        if (subscribers != null) {
            subscribers.remove(connectionId);
            if (subscribers.isEmpty()) channelSubscribers.remove(channelId, subscribers);
        }
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

        socket.setAttachment(connection);
        clientRegistry.register(connection);

        ServerEventLogger.log(ServerEvent.HANDSHAKE_OK, connectionId,
                "Device: " + deviceName + " (v" + clientVersion + ", " + platform + ")");
        ServerEventLogger.log(ServerEvent.CLIENT_HANDSHAKE_OK, connectionId,
                "Device: " + deviceName + " (v" + clientVersion + ", " + platform + ")");

        String authorization = authorizationHeaders.get(socket);
        String token = bearerToken(authorization);
        String deviceId = root.hasNonNull("deviceId") ? root.path("deviceId").asText() : null;

        if (authRequired() && (token != null || (deviceId != null && !deviceId.isBlank()))) {
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

        sendEnvelope(socket, EventEnvelope.welcome(connectionId, ProtocolConstants.SERVER_VERSION,
            ProtocolConstants.PROTOCOL_VERSION, heartbeatIntervalMs));
    }

    private void handleTransferRequest(ClientConnection sender, JsonNode root, String requestId) {
        JsonNode payload = root.path("payload");
        String receiverConnId = payload.hasNonNull("receiverConnectionId") ? payload.path("receiverConnectionId").asText() :
                (payload.hasNonNull("targetConnectionId") ? payload.path("targetConnectionId").asText() : null);
        String fileName = payload.path("fileName").asText(null);
        long fileSize = payload.path("fileSize").asLong(-1);
        String sha256 = payload.hasNonNull("sha256") ? payload.path("sha256").asText() : null;

        try {
            TransferRecord record = transferCoordinator.requestTransfer(sender.connectionId(), receiverConnId, fileName, fileSize, sha256);
            ServerEventLogger.log(ServerEvent.TRANSFER_REQUESTED, sender.connectionId(),
                    "Transfer " + record.transferId() + " -> Receiver: " + receiverConnId + " (" + fileName + ", " + fileSize + " bytes)");

            // Confirm request accepted to sender
            ServerEventLogger.log(ServerEvent.TRANSFER_STATUS, sender.connectionId(),
                    "Transfer " + record.transferId() + " -> PENDING");
            sendEnvelope(sender.socket(), EventEnvelope.event(
                ProtocolConstants.EVENT_TRANSFER_STATUS,
                requestId,
                Map.of(
                    "transferId", record.transferId(),
                    "status", record.status().name(),
                    "fileName", record.fileName(),
                    "fileSize", record.fileSize()
                )
            ));

            // Signal request to target receiver
            clientRegistry.get(receiverConnId).ifPresent(receiver -> {
                sendEnvelope(receiver.socket(), EventEnvelope.event(
                    ProtocolConstants.EVENT_TRANSFER_REQUEST,
                    null,
                    Map.of(
                        "transferId", record.transferId(),
                        "senderConnectionId", sender.connectionId(),
                        "senderDeviceName", sender.deviceName(),
                        "fileName", record.fileName(),
                        "fileSize", record.fileSize(),
                        "sha256", record.sha256() != null ? record.sha256() : ""
                    )
                ));
            });

        } catch (IllegalStateException e) {
            if ("RECEIVER_NOT_FOUND".equals(e.getMessage())) {
                ServerEventLogger.log(ServerEvent.TRANSFER_FAILED, sender.connectionId(), "Receiver connection not found: " + receiverConnId);
                sendEnvelope(sender.socket(), EventEnvelope.error(requestId, ProtocolConstants.ERR_RECEIVER_NOT_FOUND,
                        "Target client receiver not found or offline."));
            } else {
                sendEnvelope(sender.socket(), EventEnvelope.error(requestId, ProtocolConstants.ERR_INVALID_TRANSFER_STATE, e.getMessage()));
            }
        } catch (IllegalArgumentException e) {
            ServerEventLogger.log(ServerEvent.TRANSFER_FAILED, sender.connectionId(), "Invalid transfer request: " + e.getMessage());
            sendEnvelope(sender.socket(), EventEnvelope.error(requestId, ProtocolConstants.ERR_INVALID_PAYLOAD, e.getMessage()));
        }
    }

    private void handleTransferAccept(ClientConnection receiver, JsonNode root, String requestId) {
        JsonNode payload = root.path("payload");
        String transferId = payload.path("transferId").asText(null);

        try {
            TransferRecord record = transferCoordinator.acceptTransfer(transferId, receiver.connectionId());
            ServerEventLogger.log(ServerEvent.TRANSFER_ACCEPTED, receiver.connectionId(), "Transfer " + transferId + " accepted.");
            ServerEventLogger.log(ServerEvent.TRANSFER_STATUS, receiver.connectionId(), "Transfer " + transferId + " -> ACCEPTED");

            // Status ack to receiver
            sendEnvelope(receiver.socket(), EventEnvelope.event(
                ProtocolConstants.EVENT_TRANSFER_STATUS,
                requestId,
                Map.of("transferId", transferId, "status", record.status().name())
            ));

            // Notify sender
            clientRegistry.get(record.senderConnectionId()).ifPresent(sender -> {
                sendEnvelope(sender.socket(), EventEnvelope.event(
                    ProtocolConstants.EVENT_TRANSFER_ACCEPT,
                    null,
                    Map.of("transferId", transferId, "receiverConnectionId", receiver.connectionId())
                ));
            });

        } catch (Exception e) {
            sendEnvelope(receiver.socket(), EventEnvelope.error(requestId, ProtocolConstants.ERR_INVALID_TRANSFER_STATE, e.getMessage()));
        }
    }

    private void handleTransferReject(ClientConnection receiver, JsonNode root, String requestId) {
        JsonNode payload = root.path("payload");
        String transferId = payload.path("transferId").asText(null);
        String reason = payload.hasNonNull("reason") ? payload.path("reason").asText() : "Rejected by receiver";

        try {
            TransferRecord record = transferCoordinator.rejectTransfer(transferId, receiver.connectionId(), reason);
            ServerEventLogger.log(ServerEvent.TRANSFER_STATUS, receiver.connectionId(), "Transfer " + transferId + " -> REJECTED");

            sendEnvelope(receiver.socket(), EventEnvelope.event(
                ProtocolConstants.EVENT_TRANSFER_STATUS,
                requestId,
                Map.of("transferId", transferId, "status", record.status().name())
            ));

            clientRegistry.get(record.senderConnectionId()).ifPresent(sender -> {
                sendEnvelope(sender.socket(), EventEnvelope.event(
                    ProtocolConstants.EVENT_TRANSFER_REJECT,
                    null,
                    Map.of("transferId", transferId, "reason", reason)
                ));
            });

        } catch (Exception e) {
            sendEnvelope(receiver.socket(), EventEnvelope.error(requestId, ProtocolConstants.ERR_INVALID_TRANSFER_STATE, e.getMessage()));
        }
    }

    private void handleTransferCancel(ClientConnection actor, JsonNode root, String requestId) {
        JsonNode payload = root.path("payload");
        String transferId = payload.path("transferId").asText(null);
        String reason = payload.hasNonNull("reason") ? payload.path("reason").asText() : "Cancelled by user";

        try {
            TransferRecord record = transferCoordinator.cancelTransfer(transferId, actor.connectionId(), reason);
            ServerEventLogger.log(ServerEvent.TRANSFER_STATUS, actor.connectionId(), "Transfer " + transferId + " -> CANCELLED");

            sendEnvelope(actor.socket(), EventEnvelope.event(
                ProtocolConstants.EVENT_TRANSFER_STATUS,
                requestId,
                Map.of("transferId", transferId, "status", record.status().name())
            ));

            // Notify counterpart
            String otherConnId = record.senderConnectionId().equals(actor.connectionId())
                    ? record.receiverConnectionId()
                    : record.senderConnectionId();

            clientRegistry.get(otherConnId).ifPresent(other -> {
                sendEnvelope(other.socket(), EventEnvelope.event(
                    ProtocolConstants.EVENT_TRANSFER_CANCEL,
                    null,
                    Map.of("transferId", transferId, "reason", reason)
                ));
            });

        } catch (Exception e) {
            sendEnvelope(actor.socket(), EventEnvelope.error(requestId, ProtocolConstants.ERR_INVALID_TRANSFER_STATE, e.getMessage()));
        }
    }

    @Override
    public void onClose(WebSocket socket, int code, String reason, boolean remote) {
        authorizationHeaders.remove(socket);
        ClientConnection connection = socket.getAttachment();
        if (connection != null) {
            String connId = connection.connectionId();
            removeAllChannelSubscriptions(connId);
            clientRegistry.remove(connId);
            notifyDisconnectedPeerTransfers(connId);
            ServerEventLogger.log(ServerEvent.CLIENT_DISCONNECTED, connId,
                    "Code: " + code + ", Reason: " + (reason != null ? reason : "none") + ", Remote: " + remote);
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
        for (ClientConnection connection : clientRegistry.activeConnections()) {
            sendEnvelope(connection.socket(), EventEnvelope.error(null, ProtocolConstants.ERR_SERVER_SHUTDOWN,
                    "Server is shutting down."));
            connection.close(ProtocolConstants.CLOSE_SERVER_SHUTDOWN, ProtocolConstants.ERR_SERVER_SHUTDOWN);
        }
        heartbeatMonitor.shutdownNow();
        super.stop(timeout);
    }

    private void notifyDisconnectedPeerTransfers(String disconnectedConnId) {
        List<TransferRecord> cancelled = transferCoordinator.onClientDisconnected(disconnectedConnId);
        for (TransferRecord t : cancelled) {
            String peerConnId = t.senderConnectionId().equals(disconnectedConnId)
                    ? t.receiverConnectionId()
                    : t.senderConnectionId();
            clientRegistry.get(peerConnId).ifPresent(peer -> {
                sendEnvelope(peer.socket(), EventEnvelope.event(
                    ProtocolConstants.EVENT_TRANSFER_CANCEL,
                    null,
                    Map.of("transferId", t.transferId(), "reason", "PEER_DISCONNECTED")
                ));
            });
        }
    }

    private void handleAuth(ClientConnection connection, JsonNode root, String requestId) {
        if (authRequired()) {
            sendEnvelope(connection.socket(), EventEnvelope.error(requestId, AuthErrorCode.UNAUTHORIZED,
                "Authenticate with the WebSocket Authorization header during hello."));
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

            // Resolve and prove the device belongs to the authenticated user and installation.
            String resolvedDeviceId = providedDeviceId;
            if (resolvedDeviceId == null || resolvedDeviceId.isBlank()) {
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
                if (resolvedDeviceId == null || resolvedDeviceId.isBlank()) {
                    throw new UnauthorizedException("An enrolled device is required", AuthErrorCode.DEVICE_NOT_FOUND);
                }
                Device device = deviceRepository.findById(resolvedDeviceId)
                    .orElseThrow(() -> new UnauthorizedException("Device not found", AuthErrorCode.DEVICE_NOT_FOUND));
                if (!userId.equals(device.userId()) || !connection.clientInstanceId().equals(device.clientInstanceId())) {
                    throw new UnauthorizedException("Device does not match this user and client installation", AuthErrorCode.DEVICE_CONFLICT);
                }
            }

            String resolvedSessionId = claims.sessionId();
            if (authRequired()) {
                if (resolvedSessionId == null || resolvedSessionId.isBlank()) {
                    throw new UnauthorizedException("Session binding is missing", AuthErrorCode.SESSION_EXPIRED);
                }
                var session = SessionVerifier.requireActive(refreshTokenRepository, claims);
                if (!resolvedDeviceId.equals(session.deviceId())) {
                    throw new UnauthorizedException("Session is not bound to this enrolled device", AuthErrorCode.DEVICE_CONFLICT);
                }
                refreshTokenRepository.updateLastSeen(session.id(), Instant.now());
            }
            if (resolvedSessionId == null || resolvedSessionId.isBlank()) {
                resolvedSessionId = claims.tokenId();
            }

            // Bind in clientRegistry
            clientRegistry.bindIdentity(connection.connectionId(), userId, user.username(), resolvedDeviceId, resolvedSessionId);
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
        if (!connection.isAuthenticated()) {
            return true;
        }
        try {
            UUID sessionId = UUID.fromString(connection.sessionId());
            SessionVerifier.requireActive(refreshTokenRepository, sessionId, connection.userId());
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
        OperationalLogService.getInstance().warn("AUTH_BIND_FAILED", "SYSTEM",
                connection.connectionId(), "Realtime authentication rejected: " + code);
    }

    private void sendEnvelope(WebSocket socket, EventEnvelope envelope) {
        if (socket != null && socket.isOpen()) {
            try {
                socket.send(objectMapper.writeValueAsString(envelope));
            } catch (Exception e) {
                AppLogger.warn(TAG, "Failed to serialize and send event envelope: " + e.getMessage());
            }
        }
    }
}
