package com.landrop.server.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.server.clients.ClientConnection;
import com.landrop.server.clients.ClientRegistry;
import com.landrop.server.clients.ClientStatus;
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

/**
 * Foundation Realtime WebSocket Gateway handling handshakes, heartbeats,
 * echo tests, and control-plane transfer coordination.
 */
public class FoundationRealtimeServer extends WebSocketServer {

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

    public void awaitReady() throws Exception {
        ready.get(10, TimeUnit.SECONDS);
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
                    ServerEventLogger.log(ServerEvent.HEARTBEAT, connection.connectionId(), "Heartbeat received");
                    sendEnvelope(socket, EventEnvelope.heartbeatAck(requestId));
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
                default -> {
                    sendEnvelope(socket, EventEnvelope.error(requestId, ProtocolConstants.ERR_UNKNOWN_EVENT,
                            "Unknown event type: " + type));
                }
            }

        } catch (Exception e) {
            // Guarantee server does not crash on malformed JSON or illegal payloads
            sendEnvelope(socket, EventEnvelope.error(null, ProtocolConstants.ERR_INVALID_JSON, "Failed to process message: " + e.getMessage()));
        }
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
            socket
        );

        socket.setAttachment(connection);
        clientRegistry.register(connection);

        ServerEventLogger.log(ServerEvent.HANDSHAKE_OK, connectionId,
                "Device: " + deviceName + " (v" + clientVersion + ", " + platform + ")");
        ServerEventLogger.log(ServerEvent.CLIENT_HANDSHAKE_OK, connectionId,
                "Device: " + deviceName + " (v" + clientVersion + ", " + platform + ")");

        sendEnvelope(socket, EventEnvelope.welcome(
            connectionId,
            ProtocolConstants.SERVER_VERSION,
            ProtocolConstants.PROTOCOL_VERSION,
            heartbeatIntervalMs
        ));
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
        ClientConnection connection = socket.getAttachment();
        if (connection != null) {
            String connId = connection.connectionId();
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
