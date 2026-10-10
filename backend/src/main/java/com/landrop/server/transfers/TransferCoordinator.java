package com.landrop.server.transfers;

import com.landrop.server.clients.ClientConnection;
import com.landrop.server.clients.ClientRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Existing control-plane coordinator. Stores signaling metadata, never SDP/ICE or file bytes. */
public class TransferCoordinator {
    private final ClientRegistry registry;
    private final Map<String, TransferRecord> transfers = new HashMap<>();
    private final Map<String, Integer> generations = new HashMap<>();
    private final Clock clock;
    private final Duration lifetime;
    private final Duration retention;
    private static final int MAX_SESSIONS = 4096;
    private static final int MAX_PER_CONNECTION = 16;

    public TransferCoordinator(ClientRegistry registry) {
        this(registry, Clock.systemUTC(), Duration.ofMillis(com.landrop.config.AppConfig.getLong(
                "landrop.webrtc.session.ttl.ms", "WEBRTC_SESSION_TTL_MS", 120_000L)), Duration.ofSeconds(60));
    }

    public TransferCoordinator(ClientRegistry registry, Clock clock, Duration lifetime, Duration retention) {
        this.registry = Objects.requireNonNull(registry);
        this.clock = Objects.requireNonNull(clock);
        if (lifetime.isNegative() || lifetime.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("Invalid signaling lifetime/retention");
        }
        this.lifetime = lifetime;
        this.retention = retention;
    }

    public synchronized TransferRecord requestTransfer(ClientConnection sender, ClientConnection receiver,
                                                        UUID workspaceId, String fileName, long fileSize) {
        requireOnline(sender);
        requireOnline(receiver);
        if (sender.connectionId().equals(receiver.connectionId())) {
            throw error("INVALID_PAYLOAD", "Sender and receiver must use different connections.");
        }
        String name = sanitizeFileName(fileName);
        if (fileSize < 0 || fileSize > 5L * 1024 * 1024 * 1024 * 1024) throw error("INVALID_PAYLOAD", "Invalid file size.");
        if (transfers.size() >= MAX_SESSIONS || activeFor(sender) >= MAX_PER_CONNECTION || activeFor(receiver) >= MAX_PER_CONNECTION) {
            throw error("TRANSFER_LIMIT_REACHED", "Too many signaling sessions; retry later.");
        }
        Instant now = clock.instant();
        TransferRecord record = new TransferRecord(UUID.randomUUID().toString(), sender.connectionId(), receiver.connectionId(),
                name, fileSize, null, TransferStatus.REQUESTED, now, now, null, Objects.requireNonNull(workspaceId),
                sender.userId(), sender.sessionId(), receiver.userId(), receiver.sessionId(), now.plus(lifetime),
                false, false, false, false);
        return store(record);
    }

    public synchronized TransferRecord requireParticipant(String id, ClientConnection actor) {
        return requireParticipant(id, actor, false);
    }

    public synchronized TransferRecord requireParticipant(String id, ClientConnection actor, boolean allowCancelled) {
        TransferRecord record = transfers.get(id);
        if (record == null) throw error("TRANSFER_NOT_FOUND", "Transfer does not exist.");
        boolean sender = record.senderConnectionId().equals(actor.connectionId())
                && Objects.equals(record.senderUserId(), actor.userId()) && Objects.equals(record.senderSessionId(), actor.sessionId());
        boolean receiver = record.receiverConnectionId().equals(actor.connectionId())
                && Objects.equals(record.receiverUserId(), actor.userId()) && Objects.equals(record.receiverSessionId(), actor.sessionId());
        if (!sender && !receiver) throw error("FORBIDDEN", "Only the bound transfer participants may signal.");
        if (allowCancelled && record.status() == TransferStatus.CANCELLED) return record;
        if (record.status() == TransferStatus.EXPIRED || !clock.instant().isBefore(record.expiresAt())) {
            throw error("TRANSFER_EXPIRED", "Signaling session expired; create a new transfer.");
        }
        requireState(!record.status().isTerminal());
        return record;
    }

    public synchronized TransferRecord acceptTransfer(TransferRecord record, ClientConnection actor) {
        requireReceiver(record, actor);
        requireState(record.status() == TransferStatus.REQUESTED);
        return store(record.withStatus(TransferStatus.ACCEPTED, null));
    }

    public synchronized TransferRecord rejectTransfer(TransferRecord record, ClientConnection actor, String reason) {
        requireReceiver(record, actor);
        requireState(record.status() == TransferStatus.REQUESTED);
        return terminate(record, TransferStatus.REJECTED, reason);
    }

    public synchronized TransferRecord cancelTransfer(TransferRecord record, String reason) {
        if (record.status() == TransferStatus.CANCELLED) return record;
        return terminate(record, TransferStatus.CANCELLED, reason);
    }

    /** Authorization of both peers is rechecked by the gateway before extending a connected lease. */
    public synchronized TransferRecord renew(TransferRecord record) {
        requireState(record.status() == TransferStatus.P2P_CONNECTED);
        return store(record.withExpiry(clock.instant().plus(lifetime), clock.instant()));
    }

    public synchronized int generation(String id) { return generations.getOrDefault(id, 0); }

    public synchronized TransferRecord restart(TransferRecord record, ClientConnection actor, int nextGeneration) {
        if (!record.senderConnectionId().equals(actor.connectionId())) throw error("FORBIDDEN", "Only the offerer may restart ICE.");
        requireState(record.answerRelayed()
                && (record.status() == TransferStatus.P2P_CONNECTED || record.status() == TransferStatus.CONNECTING)
                && nextGeneration == generation(record.transferId()) + 1);
        generations.put(record.transferId(), nextGeneration);
        return store(record.withSignaling(TransferStatus.ACCEPTED, false, false, false, false)
                .withExpiry(clock.instant().plus(lifetime), clock.instant()));
    }

    /** Gateway validates payload and both sessions/memberships before calling this method. */
    public synchronized TransferRecord signal(TransferRecord record, ClientConnection actor, String kind, String status) {
        requireState(record.status() == TransferStatus.ACCEPTED || record.status() == TransferStatus.CONNECTING
                || record.status() == TransferStatus.P2P_CONNECTED);
        boolean sender = record.senderConnectionId().equals(actor.connectionId());
        return switch (kind) {
            case "offer" -> {
                requireState(sender && !record.offerRelayed() && record.status() == TransferStatus.ACCEPTED);
                yield store(record.withSignaling(TransferStatus.CONNECTING, true, false, false, false));
            }
            case "answer" -> {
                requireState(!sender && record.offerRelayed() && !record.answerRelayed());
                yield store(record.withSignaling(TransferStatus.CONNECTING, true, true, false, false));
            }
            case "candidate" -> record; // Trickle ICE may precede remote description.
            case "connection-status" -> {
                if ("failed".equals(status) || "closed".equals(status)) {
                    yield terminate(record, TransferStatus.FAILED, "P2P_" + status.toUpperCase(Locale.ROOT));
                }
                requireState(record.offerRelayed());
                if ("disconnected".equals(status)) {
                    yield store(record.withSignaling(TransferStatus.CONNECTING, record.offerRelayed(),
                            record.answerRelayed(), false, false));
                }
                if (!"connected".equals(status)) yield record;
                requireState(record.answerRelayed());
                boolean senderReady = sender || record.senderConnected();
                boolean receiverReady = !sender || record.receiverConnected();
                yield store(record.withSignaling(senderReady && receiverReady ? TransferStatus.P2P_CONNECTED
                        : TransferStatus.CONNECTING, true, true, senderReady, receiverReady));
            }
            default -> throw error("INVALID_PAYLOAD", "Unsupported signaling kind.");
        };
    }

    public synchronized TransferRecord fail(TransferRecord record, String reason) {
        return record.status().isTerminal() ? record : terminate(record, TransferStatus.FAILED, reason);
    }

    public synchronized List<TransferRecord> onClientDisconnected(String connectionId) {
        List<TransferRecord> affected = new ArrayList<>();
        for (TransferRecord record : List.copyOf(transfers.values())) {
            if (!record.status().isTerminal() && involves(record, connectionId)) {
                affected.add(terminate(record, TransferStatus.CANCELLED, "PEER_DISCONNECTED"));
            }
        }
        return affected;
    }

    /** Expire unrenewed leases, including connected peers; retain terminal tombstones briefly. */
    public synchronized List<TransferRecord> sweepExpired() {
        Instant now = clock.instant();
        List<TransferRecord> expired = new ArrayList<>();
        for (TransferRecord record : List.copyOf(transfers.values())) {
            if (!record.status().isTerminal() && !now.isBefore(record.expiresAt())) {
                expired.add(terminate(record, TransferStatus.EXPIRED, "SIGNALING_TIMEOUT"));
            } else if (record.status().isTerminal() && !now.isBefore(record.updatedAt().plus(retention))) {
                transfers.remove(record.transferId());
                generations.remove(record.transferId());
            }
        }
        return expired;
    }

    public synchronized Optional<TransferRecord> getTransfer(String id) { return Optional.ofNullable(transfers.get(id)); }
    public synchronized List<TransferRecord> activeTransfers() {
        return transfers.values().stream().filter(t -> !t.status().isTerminal()).toList();
    }
    public synchronized long countActiveTransfers() { return activeTransfers().size(); }
    public synchronized int size() { return transfers.size(); }
    public synchronized void clear() { transfers.clear(); generations.clear(); }

    private TransferRecord terminate(TransferRecord record, TransferStatus status, String reason) {
        return store(new TransferRecord(record.transferId(), record.senderConnectionId(), record.receiverConnectionId(),
                record.fileName(), record.fileSize(), null, status, record.createdAt(), clock.instant(), reason,
                record.workspaceId(), record.senderUserId(), record.senderSessionId(), record.receiverUserId(),
                record.receiverSessionId(), record.expiresAt(), false, false, false, false));
    }
    private TransferRecord store(TransferRecord record) { transfers.put(record.transferId(), record); return record; }
    private long activeFor(ClientConnection c) {
        return transfers.values().stream().filter(t -> !t.status().isTerminal() && involves(t, c.connectionId())).count();
    }
    private static boolean involves(TransferRecord t, String id) {
        return t.senderConnectionId().equals(id) || t.receiverConnectionId().equals(id);
    }
    private void requireOnline(ClientConnection c) {
        if (c == null || !c.isOpen() || !c.isAuthenticated() || c.sessionId() == null
                || registry.get(c.connectionId()).orElse(null) != c) {
            throw error("RECEIVER_NOT_FOUND", "Peer is offline or unauthenticated.");
        }
    }
    private static void requireReceiver(TransferRecord record, ClientConnection actor) {
        if (!record.receiverConnectionId().equals(actor.connectionId())) throw error("FORBIDDEN", "Only the receiver may accept or reject.");
    }
    private static void requireState(boolean allowed) {
        if (!allowed) throw error("INVALID_TRANSFER_STATE", "Event is not allowed in the current transfer state.");
    }
    private static SignalingException error(String code, String message) { return new SignalingException(code, message); }
    private static String sanitizeFileName(String value) {
        if (value == null || value.isBlank() || value.length() > 255 || value.chars().anyMatch(Character::isISOControl)) {
            throw error("INVALID_PAYLOAD", "Invalid file name.");
        }
        String name = value.trim().replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) throw error("INVALID_PAYLOAD", "Invalid file name.");
        return name;
    }
}
