package com.landrop.server.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.landrop.chat.service.ChannelChatService;
import com.landrop.database.model.User;
import com.landrop.database.model.WorkspaceRole;
import com.landrop.server.FoundationWsClient;
import com.landrop.server.SignalingTestSupport;
import com.landrop.server.clients.ClientRegistry;
import com.landrop.server.transfers.*;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real gateway/socket tests. Authentication, session verifier and WorkspaceAccess are not bypassed. */
class WebRTCSignalingRelayTest {
    private ClientRegistry registry;
    private TransferCoordinator coordinator;
    private FoundationRealtimeServer gateway;
    private SignalingTestSupport auth;
    private final List<FoundationWsClient> clients = new ArrayList<>();
    private final Map<FoundationWsClient, String> connectionIds = new IdentityHashMap<>();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
    private record Peer(FoundationWsClient socket, String connectionId, SignalingTestSupport.Identity identity) { }

    @BeforeEach void start() throws Exception {
        registry = new ClientRegistry();
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        coordinator = new TransferCoordinator(registry, clock, Duration.ofMinutes(2), Duration.ofSeconds(1));
        gateway = new FoundationRealtimeServer(new InetSocketAddress("127.0.0.1", 0), registry, coordinator,
                5000, 120000, Set.of("http://localhost:3000"), 10000);
        auth = new SignalingTestSupport(gateway);
        gateway.start();
        gateway.awaitReady();
    }

    @AfterEach void stop() throws Exception {
        for (var client : clients) client.close();
        gateway.stop(1000);
        assertEquals(0, registry.size());
        assertEquals(0, coordinator.size());
    }

    private Peer peer() throws Exception { return peer(null); }
    private Peer peer(User user) throws Exception {
        FoundationWsClient client = hello();
        String id = connectionIds.get(client);
        var identity = user == null ? auth.authenticate(client) : auth.authenticate(client, user);
        return new Peer(client, id, identity);
    }
    private FoundationWsClient hello() throws Exception {
        var client = FoundationWsClient.connect(URI.create("ws://127.0.0.1:" + gateway.getPort()), Map.of("Origin", "http://localhost:3000"));
        clients.add(client);
        client.sendJson(Map.of("type", "hello", "protocolVersion", 1, "clientInstanceId", UUID.randomUUID().toString()));
        connectionIds.put(client, event(client, "welcome").path("connectionId").asText());
        return client;
    }
    private static JsonNode event(FoundationWsClient client, String type) throws Exception {
        JsonNode result = client.receive(3000);
        assertNotNull(result, "Expected " + type);
        assertEquals(type, result.path("type").asText(), result.toString());
        return result;
    }
    private static JsonNode event(Peer peer, String type) throws Exception { return event(peer.socket(), type); }
    private static void command(Peer peer, String event, Map<String, Object> payload) throws Exception {
        peer.socket().sendJson(Map.of("type", event, "requestId", "r-" + event, "payload", payload));
    }
    private static void error(Peer peer, String code, String type, Map<String, Object> payload) throws Exception {
        command(peer, type, payload);
        JsonNode response = event(peer, "error");
        assertEquals(code, response.path("error").path("code").asText());
        assertEquals("r-" + type, response.path("requestId").asText());
    }
    private Map<String, Object> requestPayload(String target) {
        return new HashMap<>(Map.of("workspaceId", auth.workspace.id().toString(), "receiverConnectionId", target,
                "fileName", "sample.txt", "fileSize", 42));
    }
    private String request(Peer sender, Peer receiver) throws Exception {
        Map<String, Object> payload = requestPayload(receiver.connectionId());
        payload.put("senderUserId", receiver.identity().user().id().toString());
        command(sender, "transfer.request", payload);
        JsonNode status = event(sender, "transfer.status");
        assertEquals("REQUESTED", status.path("payload").path("status").asText());
        String id = status.path("payload").path("transferId").asText();
        JsonNode inbound = event(receiver, "transfer.request");
        assertEquals(id, inbound.path("payload").path("transferId").asText());
        assertEquals("r-transfer.request", inbound.path("requestId").asText());
        TransferRecord record = coordinator.getTransfer(id).orElseThrow();
        assertEquals(sender.identity().user().id(), record.senderUserId());
        assertEquals(sender.identity().sessionId().toString(), record.senderSessionId());
        assertEquals(receiver.identity().sessionId().toString(), record.receiverSessionId());
        assertEquals(auth.workspace.id(), record.workspaceId());
        assertEquals(now.get().plusSeconds(120), record.expiresAt());
        return id;
    }
    private void accept(Peer sender, Peer receiver, String id) throws Exception {
        command(receiver, "transfer.accept", Map.of("transferId", id));
        assertEquals("ACCEPTED", event(receiver, "transfer.status").path("payload").path("status").asText());
        assertEquals(id, event(sender, "transfer.accept").path("payload").path("transferId").asText());
    }
    private void relay(Peer sender, Peer receiver, String id, String type, String field, Object value) throws Exception {
        command(sender, type, Map.of("transferId", id, field, value, "fromUserId", "forged", "fromConnectionId", "forged"));
        assertEquals(id, event(sender, type + "_ack").path("payload").path("transferId").asText());
        JsonNode forwarded = event(receiver, type);
        assertEquals(id, forwarded.path("payload").path("transferId").asText());
        assertEquals(sender.connectionId(), forwarded.path("payload").path("fromConnectionId").asText());
        assertEquals(sender.identity().user().id().toString(), forwarded.path("payload").path("fromUserId").asText());
        assertEquals("r-" + type, forwarded.path("requestId").asText());
        assertEquals(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(value), forwarded.path("payload").path(field));
    }
    private void offer(Peer a, Peer b, String id) throws Exception {
        relay(a, b, id, "webrtc.offer", "sdp", Map.of("type", "offer", "sdp", "v=0\r\no=opaque-offer\r\n"));
        assertEquals("CONNECTING", event(a, "transfer.status").path("payload").path("status").asText());
        assertEquals("CONNECTING", event(b, "transfer.status").path("payload").path("status").asText());
    }
    private void answer(Peer b, Peer a, String id) throws Exception {
        relay(b, a, id, "webrtc.answer", "sdp", Map.of("type", "answer", "sdp", "v=0\r\no=opaque-answer\r\n"));
    }
    private static Map<String, Object> candidate() { return Map.of("candidate", "candidate:1 1 UDP opaque", "sdpMid", "0", "sdpMLineIndex", 0); }

    @Test void validRequestAcceptOpaqueSdpTrickleIceAndBothConnected() throws Exception {
        Peer a = peer(), b = peer();
        String id = request(a, b);
        accept(a, b, id);
        relay(a, b, id, "webrtc.candidate", "candidate", candidate()); // Before offer: client buffers.
        offer(a, b, id);
        answer(b, a, id);
        relay(b, a, id, "webrtc.ice_candidate", "candidate", candidate());
        Map<String, Object> end = new HashMap<>();
        end.put("transferId", id); end.put("candidate", null);
        command(a, "webrtc.candidate", end);
        event(a, "webrtc.candidate_ack");
        assertTrue(event(b, "webrtc.candidate").path("payload").path("candidate").isNull());
        relay(a, b, id, "webrtc.connection-status", "status", "connected");
        assertEquals(TransferStatus.CONNECTING, coordinator.getTransfer(id).orElseThrow().status());
        relay(b, a, id, "webrtc.connection-status", "status", "connected");
        assertEquals("P2P_CONNECTED", event(a, "transfer.status").path("payload").path("status").asText());
        assertEquals("P2P_CONNECTED", event(b, "transfer.status").path("payload").path("status").asText());
        relay(a, b, id, "webrtc.connection-status", "status", "disconnected");
        assertEquals("CONNECTING", event(a, "transfer.status").path("payload").path("status").asText());
        assertEquals("CONNECTING", event(b, "transfer.status").path("payload").path("status").asText());
        assertFalse(coordinator.getTransfer(id).orElseThrow().senderConnected());
        assertFalse(coordinator.getTransfer(id).orElseThrow().receiverConnected());
    }

    @Test void outsiderSameUserDifferentSessionAndWrongTransferCannotInject() throws Exception {
        Peer a = peer(), b = peer(), outsider = peer(), otherTab = peer(a.identity().user());
        String id = request(a, b);
        accept(a, b, id);
        for (Peer attacker : List.of(outsider, otherTab)) {
            for (String type : List.of("webrtc.offer", "webrtc.answer", "webrtc.candidate", "webrtc.connection-status",
                    "transfer.accept", "transfer.reject", "transfer.cancel")) {
                error(attacker, "FORBIDDEN", type, Map.of("transferId", id, "sdp", Map.of("type", "offer", "sdp", "v=0"), "candidate", candidate()));
            }
        }
        error(a, "TRANSFER_NOT_FOUND", "webrtc.offer", Map.of("transferId", UUID.randomUUID().toString()));
        error(a, "INVALID_PAYLOAD", "webrtc.offer", Map.of("toConnectionId", b.connectionId()));
        error(a, "FORBIDDEN", "webrtc.candidate", Map.of("transferId", id, "toConnectionId", outsider.connectionId(), "candidate", candidate()));
        assertNull(b.socket().receive(100));
        assertNull(outsider.socket().receive(100));
    }

    @Test void consentAndOfferAnswerRolesAreEnforced() throws Exception {
        Peer a = peer(), b = peer();
        String id = request(a, b);
        error(a, "FORBIDDEN", "transfer.accept", Map.of("transferId", id));
        error(a, "INVALID_TRANSFER_STATE", "webrtc.offer", Map.of("transferId", id, "sdp", Map.of("type", "offer", "sdp", "v=0")));
        error(b, "INVALID_TRANSFER_STATE", "webrtc.candidate", Map.of("transferId", id, "candidate", candidate()));
        accept(a, b, id);
        error(b, "INVALID_TRANSFER_STATE", "transfer.accept", Map.of("transferId", id));
        error(b, "INVALID_TRANSFER_STATE", "webrtc.offer", Map.of("transferId", id, "sdp", Map.of("type", "offer", "sdp", "v=0")));
        error(b, "INVALID_TRANSFER_STATE", "webrtc.answer", Map.of("transferId", id, "sdp", Map.of("type", "answer", "sdp", "v=0")));
        offer(a, b, id);
        error(a, "INVALID_TRANSFER_STATE", "webrtc.answer", Map.of("transferId", id, "sdp", Map.of("type", "answer", "sdp", "v=0")));
        error(a, "INVALID_TRANSFER_STATE", "webrtc.connection-status", Map.of("transferId", id, "status", "connected"));
        answer(b, a, id);
        error(b, "INVALID_TRANSFER_STATE", "webrtc.answer", Map.of("transferId", id, "sdp", Map.of("type", "answer", "sdp", "v=0")));
    }

    @Test void invalidWorkspaceMemberGuestAndOfflineReceiverAreRejected() throws Exception {
        Peer a = peer(), b = peer();
        Map<String, Object> payload = requestPayload(b.connectionId());
        payload.put("workspaceId", UUID.randomUUID().toString());
        error(a, "FORBIDDEN", "transfer.request", payload);
        auth.members.remove(b.identity().user().id());
        error(a, "FORBIDDEN", "transfer.request", requestPayload(b.connectionId()));
        auth.members.put(b.identity().user().id(), WorkspaceRole.GUEST);
        error(a, "FORBIDDEN", "transfer.request", requestPayload(b.connectionId()));
        auth.members.put(b.identity().user().id(), WorkspaceRole.MEMBER);
        auth.members.remove(a.identity().user().id());
        error(a, "FORBIDDEN", "transfer.request", requestPayload(b.connectionId()));
        auth.members.put(a.identity().user().id(), WorkspaceRole.ADMIN);
        error(a, "RECEIVER_NOT_FOUND", "transfer.request", requestPayload(UUID.randomUUID().toString()));
        FoundationWsClient anonymous = hello();
        String anonymousId = registry.allConnections().stream().filter(c -> !c.isAuthenticated()).findFirst().orElseThrow().connectionId();
        error(a, "RECEIVER_NOT_FOUND", "transfer.request", requestPayload(anonymousId));
        anonymous.sendJson(Map.of("type", "transfer.request", "payload", requestPayload(b.connectionId())));
        assertEquals("AUTH_REQUIRED", event(anonymous, "error").path("error").path("code").asText());
        assertEquals(0, coordinator.size());
    }

    @Test void malformedMetadataAndSignalingDoNotChangeStateOrRelay() throws Exception {
        Peer a = peer(), b = peer();
        for (Object value : List.of(-1, "42", 1.5)) {
            Map<String, Object> payload = requestPayload(b.connectionId()); payload.put("fileSize", value);
            error(a, "INVALID_PAYLOAD", "transfer.request", payload);
        }
        String id = request(a, b);
        accept(a, b, id);
        for (Object value : List.of("sdp", Map.of("type", "answer", "sdp", "v=0"), Map.of("type", "offer", "sdp", ""))) {
            error(a, "INVALID_PAYLOAD", "webrtc.offer", Map.of("transferId", id, "sdp", value));
        }
        error(a, "INVALID_PAYLOAD", "webrtc.candidate", Map.of("transferId", id, "candidate", Map.of("candidate", "x", "sdpMLineIndex", -1)));
        error(a, "INVALID_PAYLOAD", "webrtc.connection-status", Map.of("transferId", id, "status", "unknown"));
        error(a, "INVALID_PAYLOAD", "webrtc.signal", Map.of("transferId", id, "toConnectionId", b.connectionId()));
        assertEquals(TransferStatus.ACCEPTED, coordinator.getTransfer(id).orElseThrow().status());
        assertNull(b.socket().receive(100));
    }

    @Test void rejectCancelAndFailureTerminateAndTombstonesArePurged() throws Exception {
        Peer a = peer(), b = peer();
        String rejected = request(a, b);
        command(b, "transfer.reject", Map.of("transferId", rejected, "reason", "Declined"));
        assertEquals("REJECTED", event(b, "transfer.status").path("payload").path("status").asText());
        event(a, "transfer.reject");
        error(a, "INVALID_TRANSFER_STATE", "webrtc.candidate", Map.of("transferId", rejected, "candidate", candidate()));
        String cancelled = request(a, b);
        accept(a, b, cancelled);
        command(a, "transfer.cancel", Map.of("transferId", cancelled));
        assertEquals("CANCELLED", event(a, "transfer.status").path("payload").path("status").asText());
        event(b, "transfer.cancel");
        String failed = request(a, b);
        accept(a, b, failed);
        relay(b, a, failed, "webrtc.connection-status", "status", "failed");
        assertEquals("FAILED", event(a, "transfer.status").path("payload").path("status").asText());
        event(b, "transfer.status");
        assertEquals(0, coordinator.countActiveTransfers());
        now.updateAndGet(t -> t.plusSeconds(2));
        coordinator.sweepExpired();
        assertEquals(0, coordinator.size());
    }

    @Test void timeoutCleansRequestedAcceptedConnectingAndConnectedSessions() throws Exception {
        Peer a = peer(), b = peer();
        List<String> ids = new ArrayList<>();
        ids.add(request(a, b));
        String accepted = request(a, b); accept(a, b, accepted); ids.add(accepted);
        String connecting = request(a, b); accept(a, b, connecting); offer(a, b, connecting); ids.add(connecting);
        String connected = request(a, b); accept(a, b, connected); offer(a, b, connected); answer(b, a, connected);
        relay(a, b, connected, "webrtc.connection-status", "status", "connected");
        relay(b, a, connected, "webrtc.connection-status", "status", "connected");
        event(a, "transfer.status"); event(b, "transfer.status"); ids.add(connected);
        now.updateAndGet(t -> t.plusSeconds(120));
        for (int i = 0; i < ids.size(); i++) {
            assertEquals("EXPIRED", event(a, "transfer.status").path("payload").path("status").asText());
            assertEquals("EXPIRED", event(b, "transfer.status").path("payload").path("status").asText());
        }
        error(a, "TRANSFER_EXPIRED", "webrtc.candidate", Map.of("transferId", accepted, "candidate", candidate()));
        assertEquals(0, coordinator.countActiveTransfers());
        now.updateAndGet(t -> t.plusSeconds(2));
        coordinator.sweepExpired();
        assertEquals(0, coordinator.size());
    }

    @Test void eitherPeerDisconnectCancelsAndReconnectCannotResumeOldSession() throws Exception {
        for (boolean senderDisconnect : List.of(true, false)) {
            Peer a = peer(), b = peer();
            String id = request(a, b);
            accept(a, b, id);
            Peer gone = senderDisconnect ? a : b;
            Peer survivor = senderDisconnect ? b : a;
            gone.socket().close();
            assertEquals("PEER_DISCONNECTED", event(survivor, "transfer.cancel").path("payload").path("reason").asText());
            Peer replacement = peer(gone.identity().user());
            error(replacement, "FORBIDDEN", "webrtc.candidate", Map.of("transferId", id, "candidate", candidate()));
            assertEquals(TransferStatus.CANCELLED, coordinator.getTransfer(id).orElseThrow().status());
        }
    }

    @Test void sessionRevocationAndMembershipRemovalStopSignaling() throws Exception {
        Peer a = peer(), b = peer();
        String id = request(a, b);
        accept(a, b, id);
        registry.disconnectSession(b.identity().sessionId().toString(), "SESSION_REVOKED", "Logout");
        event(b, "error");
        event(a, "transfer.cancel");
        assertEquals(0, coordinator.countActiveTransfers());
        Peer replacement = peer();
        String second = request(a, replacement);
        accept(a, replacement, second);
        auth.members.remove(replacement.identity().user().id());
        // Periodic authorization cleanup may win the race, but remaining peer must see FAILED.
        assertEquals("FAILED", event(a, "transfer.status").path("payload").path("status").asText());
        error(a, "INVALID_TRANSFER_STATE", "webrtc.candidate", Map.of("transferId", second, "candidate", candidate()));
        assertNull(replacement.socket().receive(100));
    }

    @Test void revokedReceiverSessionIsValidatedBeforeRequestDelivery() throws Exception {
        Peer a = peer(), b = peer();
        auth.sessions.remove(b.identity().sessionId());
        error(a, "RECEIVER_NOT_FOUND", "transfer.request", requestPayload(b.connectionId()));
        assertEquals(0, coordinator.size());
        assertEquals("SESSION_REVOKED", event(b, "error").path("error").path("code").asText());
    }

    @Test void candidateRoutingStaysWithinTransferAndChatStillBroadcasts() throws Exception {
        Peer a = peer(), b = peer(), c = peer();
        UUID channel = UUID.randomUUID();
        gateway.setChannelChatService(new ChannelChatService(() -> { throw new SQLException("No chat persistence in this test"); }, null) {
            @Override public boolean canAccess(UUID user, UUID room) { return room.equals(channel) && auth.members.containsKey(user); }
        });
        command(a, "channel.join", Map.of("channelId", channel.toString())); event(a, "channel.joined");
        command(b, "channel.join", Map.of("channelId", channel.toString())); event(b, "channel.joined");
        String ab = request(a, b), ac = request(a, c);
        accept(a, b, ab); accept(a, c, ac);
        relay(a, b, ab, "webrtc.candidate", "candidate", candidate());
        assertNull(c.socket().receive(100));
        error(b, "FORBIDDEN", "webrtc.candidate", Map.of("transferId", ac, "candidate", candidate()));
        gateway.broadcast(channel, Map.of("type", "message.created", "channelId", channel, "message", Map.of("id", "message")));
        event(a, "message.created"); event(b, "message.created");
        assertNull(c.socket().receive(100));
    }

    @Test void serverRestartInvalidatesAllTemporaryTransferIds() throws Exception {
        Peer a = peer(), b = peer();
        String id = request(a, b);
        gateway.stop(1000);
        assertEquals(0, coordinator.size());
        assertTrue(new TransferCoordinator(registry).getTransfer(id).isEmpty());
    }

    @Test void signalingFailsClosedWithoutAuthDependencies() throws Exception {
        gateway.setAuthDependencies(null, null, null, null);
        FoundationWsClient client = hello();
        client.sendJson(Map.of("type", "transfer.request", "requestId", "no-auth", "payload", requestPayload(UUID.randomUUID().toString())));
        assertEquals("AUTH_REQUIRED", event(client, "error").path("error").path("code").asText());
    }

    @Test void activeSessionLimitAndTerminalCleanupBoundMemory() throws Exception {
        Peer a = peer(), b = peer();
        for (int i = 0; i < 16; i++) request(a, b);
        error(a, "TRANSFER_LIMIT_REACHED", "transfer.request", requestPayload(b.connectionId()));
        assertEquals(16, coordinator.countActiveTransfers());
        a.socket().close();
        for (int i = 0; i < 16; i++) event(b, "transfer.cancel");
        assertEquals(0, coordinator.countActiveTransfers());
        now.updateAndGet(t -> t.plusSeconds(2));
        coordinator.sweepExpired();
        assertEquals(0, coordinator.size());
    }

    @Test void missingPersistentSenderSessionCancelsExistingTransfer() throws Exception {
        Peer a = peer(), b = peer();
        String id = request(a, b);
        accept(a, b, id);
        auth.sessions.remove(a.identity().sessionId());
        assertEquals("SESSION_REVOKED", event(a, "error").path("error").path("code").asText());
        event(b, "transfer.cancel");
        assertEquals(TransferStatus.CANCELLED, coordinator.getTransfer(id).orElseThrow().status());
    }

    @Test void desktopCancellationNotifiesBothPeersAndRemovesActiveSession() throws Exception {
        Peer a = peer(), b = peer();
        String id = request(a, b);
        accept(a, b, id);
        assertTrue(gateway.cancelTransferFromAdmin(id));
        for (Peer participant : List.of(a, b)) {
            var payload = event(participant, "transfer.cancel").path("payload");
            assertEquals(id, payload.path("transferId").asText());
            assertEquals("CANCELLED", payload.path("status").asText());
            assertEquals("ADMIN_CANCELLED", payload.path("reason").asText());
        }
        assertEquals(0, coordinator.countActiveTransfers());
        assertFalse(gateway.cancelTransferFromAdmin(id));
        assertFalse(gateway.cancelTransferFromAdmin(UUID.randomUUID().toString()));
    }

    @Test void desktopDisconnectClosesSelectedSocketAndCancelsItsTransfers() throws Exception {
        Peer a = peer(), b = peer();
        String id = request(a, b); accept(a, b, id);
        assertTrue(gateway.disconnectConnection(a.connectionId(), "ADMIN_DISCONNECT", "Desktop disconnect"));
        assertEquals("ADMIN_DISCONNECT", event(a, "error").path("error").path("code").asText());
        assertEquals("PEER_DISCONNECTED", event(b, "transfer.cancel").path("payload").path("reason").asText());
        assertEquals(0, coordinator.countActiveTransfers());
        Peer replacement = peer(a.identity().user());
        assertFalse(gateway.disconnectConnection(a.connectionId(), "ADMIN_DISCONNECT", "Stale selection"));
        assertTrue(registry.get(replacement.connectionId()).isPresent());
        assertTrue(registry.get(b.connectionId()).isPresent());
    }

    @Test void versionTwoRequestsAreIdempotentAndConflictingRetriesAreRejected() throws Exception {
        Peer a = peer(), b = peer();
        Map<String, Object> payload = requestPayload(b.connectionId()); payload.put("protocolVersion", 2);
        command(a, "transfer.request", payload);
        String id = event(a, "transfer.status").path("payload").path("transferId").asText();
        event(b, "transfer.request");
        command(a, "transfer.request", payload);
        assertEquals(id, event(a, "transfer.status").path("payload").path("transferId").asText());
        assertEquals(1, coordinator.size()); assertNull(b.socket().receive(100));
        payload.put("fileName", "changed.txt"); error(a, "INVALID_PAYLOAD", "transfer.request", payload);
    }

    @Test void repeatedCancelReturnsTheSameTerminalStateWithoutDuplicateNotifications() throws Exception {
        Peer a = peer(), b = peer(); String id = request(a, b); accept(a, b, id);
        command(a, "transfer.cancel", Map.of("transferId", id)); event(a, "transfer.status"); event(b, "transfer.cancel");
        command(a, "transfer.cancel", Map.of("transferId", id));
        assertEquals("CANCELLED", event(a, "transfer.status").path("payload").path("status").asText());
        assertNull(b.socket().receive(100));
    }

    @Test void v2LateIceAndStatusAfterCancelAreIgnoredWithoutForwardingOrRevivingSession() throws Exception {
        Peer a = peer(), b = peer(), outsider = peer(); String id = request(a, b); accept(a, b, id);
        command(a, "transfer.cancel", Map.of("transferId", id)); event(a, "transfer.status"); event(b, "transfer.cancel");
        for (String type : List.of("webrtc.candidate", "webrtc.ice_candidate", "webrtc.connection-status")) {
            Map<String, Object> payload = new HashMap<>(Map.of("transferId", id, "protocolVersion", 2, "status", "connected"));
            payload.put("candidate", null);
            command(b, type, payload);
            JsonNode ack = event(b, type + "_ack");
            assertEquals("ignored", ack.path("payload").path("status").asText());
            assertEquals("SESSION_CANCELLED", ack.path("payload").path("reason").asText());
            assertEquals(TransferStatus.CANCELLED, coordinator.getTransfer(id).orElseThrow().status());
            assertNull(a.socket().receive(100));
            error(outsider, "FORBIDDEN", type, payload);
        }
        auth.members.remove(b.identity().user().id());
        error(b, "FORBIDDEN", "webrtc.candidate", Map.of("transferId", id, "protocolVersion", 2, "candidate", candidate()));
    }

    @Test void connectedLeaseRenewsBeyondInitialDeadlineAndRechecksMembership() throws Exception {
        Peer a = peer(), b = peer(); String id = request(a, b); accept(a, b, id); offer(a, b, id); answer(b, a, id);
        relay(a, b, id, "webrtc.connection-status", "status", "connected");
        relay(b, a, id, "webrtc.connection-status", "status", "connected"); event(a, "transfer.status"); event(b, "transfer.status");
        now.updateAndGet(time -> time.plusSeconds(110));
        command(a, "transfer.renew", Map.of("transferId", id));
        assertEquals(now.get().plusSeconds(120).toString(), event(a, "transfer.renew_ack").path("payload").path("expiresAt").asText());
        now.updateAndGet(time -> time.plusSeconds(20)); coordinator.sweepExpired();
        assertEquals(TransferStatus.P2P_CONNECTED, coordinator.getTransfer(id).orElseThrow().status());
        auth.members.remove(b.identity().user().id());
        command(a, "transfer.renew", Map.of("transferId", id));
        assertEquals("FAILED", event(a, "transfer.status").path("payload").path("status").asText());
        assertEquals("FORBIDDEN", event(a, "error").path("error").path("code").asText());
    }

    @Test void iceRestartRequiresSenderAndRejectsStaleGenerationBeforeRelaying() throws Exception {
        Peer a = peer(), b = peer(); String id = request(a, b); accept(a, b, id); offer(a, b, id); answer(b, a, id);
        error(b, "FORBIDDEN", "webrtc.restart", Map.of("transferId", id, "generation", 1));
        command(a, "webrtc.restart", Map.of("transferId", id, "generation", 1));
        assertEquals(1, event(a, "webrtc.restart_ack").path("payload").path("generation").asInt());
        assertEquals(1, event(b, "webrtc.restart").path("payload").path("generation").asInt());
        error(a, "STALE_NEGOTIATION", "webrtc.candidate", Map.of("transferId", id, "candidate", candidate(), "generation", 0));
        command(a, "webrtc.offer", Map.of("transferId", id, "generation", 1, "sdp", Map.of("type", "offer", "sdp", "v=0")));
        event(a, "webrtc.offer_ack");
        assertEquals(1, event(b, "webrtc.offer").path("payload").path("generation").asInt());
        event(a, "transfer.status"); event(b, "transfer.status");
    }

    @Test void acceptedTransferCannotBeRejectedAndReceiverMayCancel() throws Exception {
        Peer a = peer(), b = peer();
        String id = request(a, b);
        accept(a, b, id);
        error(b, "INVALID_TRANSFER_STATE", "transfer.reject", Map.of("transferId", id));
        command(b, "transfer.cancel", Map.of("transferId", id));
        assertEquals("CANCELLED", event(b, "transfer.status").path("payload").path("status").asText());
        event(a, "transfer.cancel");
    }
}
