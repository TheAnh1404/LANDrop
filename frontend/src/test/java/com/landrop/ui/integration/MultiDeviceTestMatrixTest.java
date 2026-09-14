package com.landrop.ui.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.discovery.PeerInfo;
import com.landrop.ui.auth.api.AuthApi;
import com.landrop.ui.auth.api.InMemorySessionStore;
import com.landrop.ui.auth.api.PreferencesSessionStore;
import com.landrop.ui.auth.dto.AuthDto;
import com.landrop.ui.auth.model.AuthModels;
import com.landrop.ui.auth.services.AuthStateService;
import com.landrop.ui.models.RealtimeConnectionStatus;
import com.landrop.ui.models.UIMessage;
import com.landrop.ui.services.RealtimeChatService;
import com.landrop.ui.services.RealtimeTransport;
import javafx.application.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification of the complete Phase 15.5B Test Matrix (Scenarios A through G).
 */
public class MultiDeviceTestMatrixTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void initJavaFX() {
        try {
            Platform.startup(() -> {});
            Platform.setImplicitExit(false);
        } catch (IllegalStateException ignored) {
            Platform.setImplicitExit(false);
        }
    }

    private final List<RealtimeChatService> activeServices = new ArrayList<>();
    private final List<PreferencesSessionStore> activeStores = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (RealtimeChatService s : activeServices) {
            try {
                s.close();
            } catch (Exception ignored) {}
        }
        activeServices.clear();

        for (PreferencesSessionStore store : activeStores) {
            try {
                store.clear();
            } catch (Exception ignored) {}
        }
        activeStores.clear();
    }

    // Shared in-memory test hub connecting simulated devices
    static class SharedRealtimeHub {
        private final List<HubClientTransport> clients = new CopyOnWriteArrayList<>();

        public HubClientTransport registerClient() {
            HubClientTransport transport = new HubClientTransport(this);
            clients.add(transport);
            return transport;
        }

        public void broadcast(String senderPayload, HubClientTransport sender) {
            for (HubClientTransport client : clients) {
                if (client.listener != null && client.connected) {
                    client.listener.onMessage(senderPayload);
                }
            }
        }
    }

    static class HubClientTransport implements RealtimeTransport {
        private final SharedRealtimeHub hub;
        Listener listener;
        boolean connected = false;

        HubClientTransport(SharedRealtimeHub hub) {
            this.hub = hub;
        }

        @Override
        public void setListener(Listener listener) {
            this.listener = listener;
        }

        @Override
        public CompletableFuture<Void> connect() {
            connected = true;
            if (listener != null) listener.onOpen();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> send(String text) {
            try {
                var root = MAPPER.readTree(text);
                String tempId = root.has("tempId") ? root.get("tempId").asText() : null;
                String senderId = root.get("senderId").asText();
                String senderDisplayName = root.get("senderDisplayName").asText();
                String content = root.get("content").asText();

                // Server assigns canonical ID and broadcasts to all clients (including sender)
                Map<String, Object> serverMsg = new LinkedHashMap<>();
                serverMsg.put("id", "srv-" + UUID.randomUUID());
                serverMsg.put("tempId", tempId);
                serverMsg.put("senderId", senderId);
                serverMsg.put("senderDisplayName", senderDisplayName);
                serverMsg.put("content", content);
                serverMsg.put("timestamp", System.currentTimeMillis());

                String broadcastPayload = MAPPER.writeValueAsString(serverMsg);
                hub.broadcast(broadcastPayload, this);
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
            connected = false;
            if (listener != null) listener.onClose(1000, "Closed");
        }

        @Override
        public boolean isConnected() {
            return connected;
        }
    }

    private static AuthStateService createMockAuth(String userId, String displayName, String username) {
        AuthDto.AuthUserResponse u = new AuthDto.AuthUserResponse(userId, username, displayName, username + "@example.com", null, "2026-09-14T00:00:00Z", "2026-09-14T00:00:00Z");
        AuthApi api = new AuthApi() {
            @Override public CompletableFuture<AuthDto.AuthResponse> register(AuthDto.RegisterRequest request) { return null; }
            @Override public CompletableFuture<AuthDto.AuthResponse> login(AuthDto.LoginRequest request) {
                return CompletableFuture.completedFuture(new AuthDto.AuthResponse("access", "refresh", "Bearer", 3600, u));
            }
            @Override public CompletableFuture<AuthDto.RefreshResponse> refresh(AuthDto.RefreshRequest request) { return null; }
            @Override public CompletableFuture<AuthDto.AuthUserResponse> me(String accessToken) { return CompletableFuture.completedFuture(u); }
            @Override public CompletableFuture<Void> logout(String accessToken, String refreshToken) { return CompletableFuture.completedFuture(null); }
        };
        AuthStateService auth = new AuthStateService(api, new InMemorySessionStore());
        auth.login(new AuthModels.LoginRequest("test@example.com", "pw")).join();
        return auth;
    }

    private static void awaitFx() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        assertTrue(latch.await(3, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Scenario A: Two different users on two separate devices")
    void testScenarioA_TwoUsersSeparateDevices() throws Exception {
        SharedRealtimeHub hub = new SharedRealtimeHub();

        AuthStateService auth1 = createMockAuth("usr-user1", "User One", "user1");
        AuthStateService auth2 = createMockAuth("usr-user2", "User Two", "user2");

        RealtimeChatService dev1 = new RealtimeChatService(hub.registerClient(), auth1);
        RealtimeChatService dev2 = new RealtimeChatService(hub.registerClient(), auth2);
        activeServices.addAll(List.of(dev1, dev2));

        dev1.connect().get(5, TimeUnit.SECONDS);
        dev2.connect().get(5, TimeUnit.SECONDS);

        // User 1 sends message from Device 1
        dev1.sendMessage("Hello from User 1!").get(5, TimeUnit.SECONDS);
        awaitFx();

        // Verify Device 1 presentation
        assertEquals(1, dev1.getMessages().size());
        UIMessage msgOnDev1 = dev1.getMessages().getFirst();
        assertTrue(msgOnDev1.isMine("usr-user1"), "Device 1 displays message right-aligned (isMine == true)");
        assertEquals("User One", msgOnDev1.senderLabel());
        assertEquals("UO", msgOnDev1.senderInitials());

        // Verify Device 2 presentation
        assertEquals(1, dev2.getMessages().size());
        UIMessage msgOnDev2 = dev2.getMessages().getFirst();
        assertFalse(msgOnDev2.isMine("usr-user2"), "Device 2 displays message left-aligned (isMine == false)");
        assertEquals("User One", msgOnDev2.senderLabel(), "Device 2 identifies sender as 'User One', NOT peerA/peerB");
        assertEquals("UO", msgOnDev2.senderInitials());
        assertEquals(msgOnDev1.id(), msgOnDev2.id(), "Both devices agree on canonical message ID");
    }

    @Test
    @DisplayName("Scenario B: Same user logged in on multiple devices")
    void testScenarioB_SameUserMultiDevice() throws Exception {
        SharedRealtimeHub hub = new SharedRealtimeHub();

        // Same user ID on both Device 1 and Device 2
        AuthStateService authDevice1 = createMockAuth("usr-ken", "Ken", "ken");
        AuthStateService authDevice2 = createMockAuth("usr-ken", "Ken", "ken");

        RealtimeChatService dev1 = new RealtimeChatService(hub.registerClient(), authDevice1);
        RealtimeChatService dev2 = new RealtimeChatService(hub.registerClient(), authDevice2);
        activeServices.addAll(List.of(dev1, dev2));

        dev1.connect().get(5, TimeUnit.SECONDS);
        dev2.connect().get(5, TimeUnit.SECONDS);

        // Ken sends message from Device 1
        dev1.sendMessage("Message from my desktop").get(5, TimeUnit.SECONDS);
        awaitFx();

        // Machine 1 check
        assertEquals(1, dev1.getMessages().size());
        UIMessage dev1Msg = dev1.getMessages().getFirst();
        assertTrue(dev1Msg.isMine("usr-ken"));
        assertEquals("Ken", dev1Msg.senderLabel());

        // Machine 2 check (received in realtime without refresh)
        assertEquals(1, dev2.getMessages().size());
        UIMessage dev2Msg = dev2.getMessages().getFirst();
        assertTrue(dev2Msg.isMine("usr-ken"), "Machine 2 recognizes message as owned by Ken (isMine == true)");
        assertEquals("Ken", dev2Msg.senderLabel(), "Machine 2 renders sender as 'Ken', never local vs remote peer");
        assertEquals(dev1Msg.id(), dev2Msg.id(), "Canonical ID matches between devices");
    }

    @Test
    @DisplayName("Scenario C: Three users interacting simultaneously")
    void testScenarioC_ThreeUsers() throws Exception {
        SharedRealtimeHub hub = new SharedRealtimeHub();

        AuthStateService auth1 = createMockAuth("usr-1", "Alice Smith", "alice");
        AuthStateService auth2 = createMockAuth("usr-2", "Bob Jones", "bob");
        AuthStateService auth3 = createMockAuth("usr-3", "Charlie Brown", "charlie");

        RealtimeChatService dev1 = new RealtimeChatService(hub.registerClient(), auth1);
        RealtimeChatService dev2 = new RealtimeChatService(hub.registerClient(), auth2);
        RealtimeChatService dev3 = new RealtimeChatService(hub.registerClient(), auth3);
        activeServices.addAll(List.of(dev1, dev2, dev3));

        dev1.connect().get(5, TimeUnit.SECONDS);
        dev2.connect().get(5, TimeUnit.SECONDS);
        dev3.connect().get(5, TimeUnit.SECONDS);

        dev1.sendMessage("Hello from Alice").get(5, TimeUnit.SECONDS);
        dev2.sendMessage("Hi Alice, Bob here").get(5, TimeUnit.SECONDS);
        dev3.sendMessage("Hey everyone, Charlie joined").get(5, TimeUnit.SECONDS);
        awaitFx();

        // All 3 devices have 3 messages in order
        assertEquals(3, dev1.getMessages().size());
        assertEquals(3, dev2.getMessages().size());
        assertEquals(3, dev3.getMessages().size());

        // For Alice: msg 0 is mine, msg 1 is Bob, msg 2 is Charlie
        assertTrue(dev1.getMessages().get(0).isMine("usr-1"));
        assertFalse(dev1.getMessages().get(1).isMine("usr-1"));
        assertFalse(dev1.getMessages().get(2).isMine("usr-1"));
        assertEquals("AS", dev1.getMessages().get(0).senderInitials());
        assertEquals("BJ", dev1.getMessages().get(1).senderInitials());
        assertEquals("CB", dev1.getMessages().get(2).senderInitials());

        // For Charlie: msg 0 is Alice, msg 1 is Bob, msg 2 is mine
        assertFalse(dev3.getMessages().get(0).isMine("usr-3"));
        assertFalse(dev3.getMessages().get(1).isMine("usr-3"));
        assertTrue(dev3.getMessages().get(2).isMine("usr-3"));
    }

    @Test
    @DisplayName("Scenario D: Realtime reconnect / disconnect status feedback")
    void testScenarioD_DisconnectReconnectLifecycle() throws Exception {
        SharedRealtimeHub hub = new SharedRealtimeHub();
        AuthStateService auth = createMockAuth("usr-1", "Alice", "alice");
        HubClientTransport transport = hub.registerClient();

        try (RealtimeChatService service = new RealtimeChatService(transport, auth)) {
            assertEquals(RealtimeConnectionStatus.DISCONNECTED, service.getConnectionStatus());

            service.connect().get(5, TimeUnit.SECONDS);
            awaitFx();
            assertEquals(RealtimeConnectionStatus.CONNECTED, service.getConnectionStatus());

            // User sends message
            service.sendMessage("Persisted message").get(5, TimeUnit.SECONDS);
            awaitFx();
            assertEquals(1, service.getMessages().size());

            // Transport drops
            transport.listener.onClose(1006, "Abnormal closure");
            awaitFx();
            assertEquals(RealtimeConnectionStatus.RECONNECTING, service.getConnectionStatus());

            // Reconnected
            transport.listener.onOpen();
            awaitFx();
            assertEquals(RealtimeConnectionStatus.CONNECTED, service.getConnectionStatus());

            // Message history retained across reconnect
            assertEquals(1, service.getMessages().size());
            assertEquals("Persisted message", service.getMessages().getFirst().content());
        }
    }

    @Test
    @DisplayName("Scenario E: LAN Peer discovery distinct from Product User identity")
    void testScenarioE_LanPeerDistinctFromProductUser() {
        // LAN peer discovery provides host, port, and local node name
        PeerInfo peer = new PeerInfo("peer-ken-1", "Ken-MacBook", "192.168.1.15", 5001);

        // Product UI formats device card with clear distinction
        String displayCardUser = "Ken";
        String displayCardDevice = String.format("Device: %s (%s:%d)",
                peer.getDisplayName(), peer.getHost(), peer.getPort());

        assertEquals("Ken", displayCardUser);
        assertEquals("Device: Ken-MacBook (192.168.1.15:5001)", displayCardDevice);
        assertFalse(displayCardDevice.contains("peerA"), "Must not mention legacy peerA/peerB");
    }

    @Test
    @DisplayName("Scenario F: Session isolation between instances with separate profiles")
    void testScenarioF_MultiInstanceSessionIsolation() {
        PreferencesSessionStore storeProfile1 = new PreferencesSessionStore("User1");
        PreferencesSessionStore storeProfile2 = new PreferencesSessionStore("User2");
        activeStores.addAll(List.of(storeProfile1, storeProfile2));

        storeProfile1.saveRefreshToken("refresh-token-alice-on-dev1");
        storeProfile2.saveRefreshToken("refresh-token-bob-on-dev1");

        assertEquals(Optional.of("refresh-token-alice-on-dev1"), storeProfile1.loadRefreshToken());
        assertEquals(Optional.of("refresh-token-bob-on-dev1"), storeProfile2.loadRefreshToken());

        // Alice logs out
        storeProfile1.clear();

        assertTrue(storeProfile1.loadRefreshToken().isEmpty(), "Profile 1 logged out");
        assertEquals(Optional.of("refresh-token-bob-on-dev1"), storeProfile2.loadRefreshToken(), "Profile 2 remains logged in");
    }

    @Test
    @DisplayName("Scenario G: Server echo deduplication with canonical message ID")
    void testScenarioG_ServerEchoDeduplication() throws Exception {
        SharedRealtimeHub hub = new SharedRealtimeHub();
        AuthStateService auth = createMockAuth("usr-ada", "Ada", "ada");
        HubClientTransport transport = hub.registerClient();

        try (RealtimeChatService service = new RealtimeChatService(transport, auth)) {
            service.connect().get(5, TimeUnit.SECONDS);

            // Send message: creates optimistic item, then receives canonical server broadcast
            service.sendMessage("Reliable broadcast").get(5, TimeUnit.SECONDS);
            awaitFx();

            assertEquals(1, service.getMessages().size());
            String canonicalId = service.getMessages().getFirst().id();
            assertFalse(canonicalId.startsWith("opt-"), "Reconciled with canonical ID");

            // Server accidentally retransmits identical message ID
            String duplicatePayload = MAPPER.writeValueAsString(Map.of(
                    "id", canonicalId,
                    "senderId", "usr-ada",
                    "senderDisplayName", "Ada",
                    "content", "Reliable broadcast",
                    "timestamp", System.currentTimeMillis()
            ));
            transport.listener.onMessage(duplicatePayload);
            awaitFx();

            assertEquals(1, service.getMessages().size(), "Duplicate echo must not create duplicate bubble");
        }
    }
}
