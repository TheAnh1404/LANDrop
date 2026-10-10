package com.landrop.server.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.landrop.database.repository.WorkspaceRepository;
import com.landrop.server.clients.ClientConnection;
import com.landrop.server.clients.ClientRegistry;
import com.landrop.server.protocol.EventEnvelope;
import com.landrop.server.transfers.*;
import com.landrop.workspace.WorkspaceAccess;

import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import static com.landrop.server.protocol.ProtocolConstants.*;

/** Authorized transfer and WebRTC events on the existing gateway/envelope. No SDP/ICE storage or logging. */
public final class TransferSignalingHandler {
    private final ClientRegistry registry;
    private final TransferCoordinator coordinator;
    private final Predicate<ClientConnection> validSession;
    private final BiPredicate<ClientConnection, EventEnvelope> send;
    private volatile WorkspaceRepository workspaceRepository;
    private final Map<String, String> requestIds = new HashMap<>();

    public TransferSignalingHandler(ClientRegistry registry, TransferCoordinator coordinator,
                                     Predicate<ClientConnection> validSession,
                                     BiPredicate<ClientConnection, EventEnvelope> send) {
        this.registry = registry;
        this.coordinator = coordinator;
        this.validSession = validSession;
        this.send = send;
    }

    public void setWorkspaceRepository(WorkspaceRepository repository) { workspaceRepository = repository; }

    public void handle(ClientConnection actor, String type, JsonNode payload, String requestId) {
        // Serialize checks, state transitions and enqueue order across WS workers/expiry/disconnect.
        synchronized (coordinator) {
            try {
                if (!live(actor)) throw error("AUTH_REQUIRED", "An active authenticated session is required.");
                if (!payload.isObject()) throw error(ERR_INVALID_PAYLOAD, "An object payload is required.");
                if (EVENT_TRANSFER_REQUEST.equals(type)) {
                    request(actor, payload, requestId);
                    return;
                }
                String id = text(payload, "transferId");
                TransferRecord record = coordinator.requireParticipant(id, actor, EVENT_TRANSFER_CANCEL.equals(type));
                if (payload.has("toConnectionId") && !peerId(record, actor).equals(text(payload, "toConnectionId"))) {
                    throw error("FORBIDDEN", "Destination must be the bound peer.");
                }
                if (EVENT_TRANSFER_CANCEL.equals(type)) {
                    if (!member(record.workspaceId(), actor.userId())) throw error("FORBIDDEN", "Workspace access is no longer available.");
                    TransferRecord cancelled = coordinator.cancelTransfer(record, reason(payload, "Cancelled by peer"));
                    status(actor, cancelled, requestId);
                    ClientConnection target = registry.get(peerId(record, actor)).orElse(null);
                    if (record.status() != TransferStatus.CANCELLED && canNotify(cancelled, target)) {
                        send.test(target, EventEnvelope.event(type, requestId, metadata(cancelled)));
                    }
                    return;
                }
                ClientConnection peer = authorizePeers(record, actor);
                if ("transfer.renew".equals(type)) {
                    TransferRecord renewed = coordinator.renew(record);
                    send.test(actor, EventEnvelope.event("transfer.renew_ack", requestId, metadata(renewed)));
                    return;
                }
                if ("webrtc.restart".equals(type)) {
                    int generation = generation(payload);
                    TransferRecord restarted = coordinator.restart(record, actor, generation);
                    Map<String, Object> info = new LinkedHashMap<>(metadata(restarted));
                    info.put("generation", generation);
                    if (!send.test(peer, EventEnvelope.event(type, requestId, info))) { deliveryFailed(restarted); return; }
                    send.test(actor, EventEnvelope.event("webrtc.restart_ack", requestId, info));
                    return;
                }
                TransferRecord updated;
                switch (type) {
                    case EVENT_TRANSFER_ACCEPT -> updated = coordinator.acceptTransfer(record, actor);
                    case EVENT_TRANSFER_REJECT -> updated = coordinator.rejectTransfer(record, actor, reason(payload, "Rejected by receiver"));
                    case EVENT_TRANSFER_CANCEL -> updated = coordinator.cancelTransfer(record, reason(payload, "Cancelled by peer"));
                    default -> {
                        relay(actor, peer, record, type, payload, requestId);
                        return;
                    }
                }
                status(actor, updated, requestId);
                if (!send.test(peer, EventEnvelope.event(type, requestId, metadata(updated)))) {
                    deliveryFailed(updated);
                }
            } catch (SignalingException denied) {
                send.test(actor, EventEnvelope.error(requestId, denied.code(), denied.getMessage()));
            } catch (Exception unavailable) {
                send.test(actor, EventEnvelope.error(requestId, ERR_INTERNAL_ERROR, "Signaling authorization is unavailable."));
            }
        }
    }

    private void request(ClientConnection sender, JsonNode payload, String requestId) {
        UUID workspace;
        try { workspace = UUID.fromString(text(payload, "workspaceId")); }
        catch (IllegalArgumentException invalid) { throw error(ERR_INVALID_PAYLOAD, "A valid workspaceId is required."); }
        String target = payload.has("receiverConnectionId") ? text(payload, "receiverConnectionId") : text(payload, "targetConnectionId");
        ClientConnection receiver = registry.get(target).orElse(null);
        if (!live(receiver)) throw error(ERR_RECEIVER_NOT_FOUND, "Receiver is offline or has no active session.");
        if (!member(workspace, sender.userId()) || !member(workspace, receiver.userId())) {
            throw error("FORBIDDEN", "Both peers must be current workspace members.");
        }
        JsonNode size = payload.path("fileSize");
        if (!size.isIntegralNumber() || !size.canConvertToLong()) throw error(ERR_INVALID_PAYLOAD, "fileSize must be an integer.");
        String key = sender.connectionId() + ":" + requestId;
        boolean deduplicate = payload.path("protocolVersion").asInt() == 2 && requestId != null && !requestId.isBlank() && requestId.length() <= 128;
        if (deduplicate && requestIds.containsKey(key)) {
            TransferRecord previous = coordinator.getTransfer(requestIds.get(key)).orElse(null);
            if (previous != null) {
                if (!previous.receiverConnectionId().equals(target) || !previous.workspaceId().equals(workspace)
                        || previous.fileSize() != size.longValue() || !previous.fileName().equals(text(payload, "fileName"))) {
                    throw error(ERR_INVALID_PAYLOAD, "requestId conflicts with another transfer request.");
                }
                status(sender, previous, requestId);
                return;
            }
        }
        TransferRecord record = coordinator.requestTransfer(sender, receiver, workspace, text(payload, "fileName"), size.longValue());
        if (deduplicate) requestIds.put(key, record.transferId());
        if (!send.test(receiver, EventEnvelope.event(EVENT_TRANSFER_REQUEST, requestId, metadata(record)))) {
            deliveryFailed(record);
            throw error(ERR_RECEIVER_NOT_FOUND, "Receiver disconnected before delivery.");
        }
        status(sender, record, requestId);
    }

    private void relay(ClientConnection actor, ClientConnection peer, TransferRecord record,
                       String event, JsonNode payload, String requestId) {
        String kind;
        Map<String, Object> forwarded = new LinkedHashMap<>();
        String connectionStatus = null;
        int generation = generation(payload);
        if (generation != coordinator.generation(record.transferId())) throw error("STALE_NEGOTIATION", "Discard signaling from an older negotiation.");
        if (payload.has("generation")) forwarded.put("generation", generation);
        switch (event) {
            case EVENT_WEBRTC_OFFER, EVENT_WEBRTC_ANSWER -> {
                kind = EVENT_WEBRTC_OFFER.equals(event) ? "offer" : "answer";
                JsonNode sdp = payload.path("sdp");
                if (!sdp.isObject() || !kind.equals(sdp.path("type").asText()) || !sdp.path("sdp").isTextual()
                        || sdp.path("sdp").textValue().isBlank() || sdp.path("sdp").textValue().length() > 48 * 1024) {
                    throw error(ERR_INVALID_PAYLOAD, "A matching RTCSessionDescriptionInit is required.");
                }
                forwarded.put("sdp", sdp); // Preserve opaque description exactly.
            }
            case EVENT_WEBRTC_CANDIDATE, EVENT_WEBRTC_CANDIDATE_ALIAS -> {
                kind = "candidate";
                JsonNode candidate = payload.get("candidate");
                if (candidate == null || (!candidate.isNull() && (!candidate.isObject()
                        || !candidate.path("candidate").isTextual() || candidate.path("candidate").textValue().length() > 8192
                        || (candidate.hasNonNull("sdpMid") && !candidate.path("sdpMid").isTextual())
                        || (candidate.hasNonNull("sdpMLineIndex") && (!candidate.path("sdpMLineIndex").isIntegralNumber()
                            || !candidate.path("sdpMLineIndex").canConvertToInt() || candidate.path("sdpMLineIndex").intValue() < 0))))) {
                    throw error(ERR_INVALID_PAYLOAD, "A valid RTCIceCandidateInit or null is required.");
                }
                forwarded.put("candidate", candidate);
            }
            case EVENT_WEBRTC_CONNECTION_STATUS -> {
                kind = "connection-status";
                connectionStatus = text(payload, "status");
                if (!Set.of("connecting", "connected", "disconnected", "failed", "closed").contains(connectionStatus)) {
                    throw error(ERR_INVALID_PAYLOAD, "Unsupported peer connection status.");
                }
                forwarded.put("status", connectionStatus);
            }
            default -> throw error(ERR_INVALID_PAYLOAD, "Use the typed offer, answer or candidate events.");
        }
        TransferRecord updated = coordinator.signal(record, actor, kind, connectionStatus);
        forwarded.put("transferId", record.transferId());
        forwarded.put("workspaceId", record.workspaceId().toString());
        forwarded.put("fromConnectionId", actor.connectionId());
        forwarded.put("fromUserId", actor.userId().toString());
        if (!send.test(peer, EventEnvelope.event(event, requestId, forwarded))) {
            deliveryFailed(updated);
            throw error(ERR_RECEIVER_NOT_FOUND, "Peer disconnected before signaling delivery.");
        }
        if (requestId != null && !requestId.isBlank()) {
            send.test(actor, EventEnvelope.event(event + "_ack", requestId, Map.of("transferId", record.transferId(),
                    "status", "delivered", "toConnectionId", peer.connectionId())));
        }
        if (updated.status() != record.status()) notifyStatus(updated);
    }

    private ClientConnection authorizePeers(TransferRecord record, ClientConnection actor) {
        ClientConnection peer = registry.get(peerId(record, actor)).orElse(null);
        if (!bound(record, peer) || !live(peer)) {
            deliveryFailed(record);
            throw error(ERR_RECEIVER_NOT_FOUND, "Bound peer is offline or its session ended.");
        }
        if (!member(record.workspaceId(), actor.userId()) || !member(record.workspaceId(), peer.userId())) {
            TransferRecord failed = coordinator.fail(record, "WORKSPACE_ACCESS_REVOKED");
            notifyStatus(failed);
            throw error("FORBIDDEN", "Workspace access is no longer available.");
        }
        return peer;
    }

    public void disconnected(String connectionId) {
        synchronized (coordinator) {
            for (TransferRecord record : coordinator.onClientDisconnected(connectionId)) {
                String peer = record.senderConnectionId().equals(connectionId) ? record.receiverConnectionId() : record.senderConnectionId();
                ClientConnection target = registry.get(peer).orElse(null);
                if (canNotify(record, target)) send.test(target, EventEnvelope.event(EVENT_TRANSFER_CANCEL, null, metadata(record)));
            }
        }
    }

    /** Trusted local/admin control hook; the same participant authorization applies to notifications. */
    public boolean cancelFromAdmin(String transferId) {
        synchronized (coordinator) {
            TransferRecord record = coordinator.getTransfer(transferId).orElse(null);
            if (record == null || record.status().isTerminal()) return false;
            TransferRecord cancelled = coordinator.cancelTransfer(record, "ADMIN_CANCELLED");
            for (String id : List.of(record.senderConnectionId(), record.receiverConnectionId())) {
                ClientConnection target = registry.get(id).orElse(null);
                if (canNotify(cancelled, target)) send.test(target, EventEnvelope.event(EVENT_TRANSFER_CANCEL, null, metadata(cancelled)));
            }
            return true;
        }
    }

    public void sweep() {
        synchronized (coordinator) {
            requestIds.entrySet().removeIf(entry -> coordinator.getTransfer(entry.getValue()).isEmpty());
            for (TransferRecord record : coordinator.sweepExpired()) notifyStatus(record);
            for (TransferRecord record : coordinator.activeTransfers()) {
                ClientConnection sender = registry.get(record.senderConnectionId()).orElse(null);
                ClientConnection receiver = registry.get(record.receiverConnectionId()).orElse(null);
                if (!bound(record, sender) || !live(sender)) disconnected(record.senderConnectionId());
                else if (!bound(record, receiver) || !live(receiver)) disconnected(record.receiverConnectionId());
                else if (!member(record.workspaceId(), sender.userId()) || !member(record.workspaceId(), receiver.userId())) {
                    notifyStatus(coordinator.fail(record, "WORKSPACE_ACCESS_REVOKED"));
                }
            }
        }
    }

    private void deliveryFailed(TransferRecord record) { notifyStatus(coordinator.fail(record, "PEER_UNAVAILABLE")); }
    private void notifyStatus(TransferRecord record) {
        for (String id : List.of(record.senderConnectionId(), record.receiverConnectionId())) {
            ClientConnection c = registry.get(id).orElse(null);
            if (canNotify(record, c)) status(c, record, null);
        }
    }
    private boolean canNotify(TransferRecord record, ClientConnection c) {
        return bound(record, c) && live(c) && member(record.workspaceId(), c.userId());
    }
    private boolean live(ClientConnection c) {
        return c != null && c.isAuthenticated() && c.isOpen() && registry.get(c.connectionId()).orElse(null) == c && validSession.test(c);
    }
    private boolean member(UUID workspaceId, UUID userId) {
        WorkspaceRepository repository = workspaceRepository;
        if (repository == null) return true; // Standalone LAN mode allows direct peer transfers
        try { WorkspaceAccess.requireMember(repository, workspaceId, userId); return true; }
        catch (Exception unavailable) { return false; } // Fail closed, including database unavailability.
    }
    private static boolean bound(TransferRecord r, ClientConnection c) {
        if (c == null) return false;
        return (r.senderConnectionId().equals(c.connectionId()) && Objects.equals(r.senderUserId(), c.userId())
                && Objects.equals(r.senderSessionId(), c.sessionId()))
                || (r.receiverConnectionId().equals(c.connectionId()) && Objects.equals(r.receiverUserId(), c.userId())
                && Objects.equals(r.receiverSessionId(), c.sessionId()));
    }
    private void status(ClientConnection c, TransferRecord r, String requestId) {
        send.test(c, EventEnvelope.event(EVENT_TRANSFER_STATUS, requestId, metadata(r)));
    }
    private Map<String, Object> metadata(TransferRecord r) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transferId", r.transferId());
        result.put("workspaceId", r.workspaceId().toString());
        result.put("senderUserId", r.senderUserId().toString());
        result.put("senderConnectionId", r.senderConnectionId());
        result.put("receiverUserId", r.receiverUserId().toString());
        result.put("receiverConnectionId", r.receiverConnectionId());
        registry.get(r.senderConnectionId()).ifPresent(peer -> { result.put("senderDeviceId", peer.deviceId()); result.put("senderDeviceName", peer.deviceName()); });
        registry.get(r.receiverConnectionId()).ifPresent(peer -> { result.put("receiverDeviceId", peer.deviceId()); result.put("receiverDeviceName", peer.deviceName()); });
        result.put("fileName", r.fileName());
        result.put("fileSize", r.fileSize());
        result.put("status", r.status().name());
        result.put("createdAt", r.createdAt().toString());
        result.put("expiresAt", r.expiresAt().toString());
        if (r.errorMessage() != null) result.put("reason", r.errorMessage());
        return result;
    }
    private static String peerId(TransferRecord r, ClientConnection actor) {
        return r.senderConnectionId().equals(actor.connectionId()) ? r.receiverConnectionId() : r.senderConnectionId();
    }
    private static String text(JsonNode payload, String field) {
        JsonNode value = payload.path(field);
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 255) {
            throw error(ERR_INVALID_PAYLOAD, "A valid " + field + " is required.");
        }
        return value.textValue();
    }
    private static int generation(JsonNode payload) {
        JsonNode value = payload.get("generation");
        if (value == null) return 0;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) throw error(ERR_INVALID_PAYLOAD, "Invalid negotiation generation.");
        return value.intValue();
    }
    private static String reason(JsonNode payload, String fallback) {
        return payload.has("reason") ? text(payload, "reason") : fallback;
    }
    private static SignalingException error(String code, String message) { return new SignalingException(code, message); }
}
