package com.landrop.ui.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.ui.auth.api.AuthApi;
import com.landrop.ui.auth.api.InMemorySessionStore;
import com.landrop.ui.auth.dto.AuthDto;
import com.landrop.ui.auth.model.AuthModels;
import com.landrop.ui.auth.services.AuthStateService;
import com.landrop.ui.models.RealtimeConnectionStatus;
import com.landrop.ui.models.UIMessage;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class RealtimeChatServiceTest {

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

    private static AuthStateService createMockAuthState(String id, String displayName, String username) {
        AuthDto.AuthUserResponse u = new AuthDto.AuthUserResponse(id, username, displayName, username + "@example.com", null, "2026-09-14T00:00:00Z", "2026-09-14T00:00:00Z");
        AuthApi api = new AuthApi() {
            @Override public CompletableFuture<AuthDto.AuthResponse> register(AuthDto.RegisterRequest request) { return null; }
            @Override public CompletableFuture<AuthDto.AuthResponse> login(AuthDto.LoginRequest request) {
                return CompletableFuture.completedFuture(new AuthDto.AuthResponse("access-token", "refresh-token", "Bearer", 3600, u));
            }
            @Override public CompletableFuture<AuthDto.RefreshResponse> refresh(AuthDto.RefreshRequest request) { return null; }
            @Override public CompletableFuture<AuthDto.AuthUserResponse> me(String accessToken) { return CompletableFuture.completedFuture(u); }
            @Override public CompletableFuture<Void> logout(String accessToken, String refreshToken) { return CompletableFuture.completedFuture(null); }
        };
        AuthStateService state = new AuthStateService(api, new InMemorySessionStore());
        state.login(new AuthModels.LoginRequest("ada@example.com", "pw")).join();
        return state;
    }

    static class MockTransport implements RealtimeTransport {
        Listener listener;
        boolean connected = false;
        final List<String> sentMessages = new CopyOnWriteArrayList<>();

        @Override public void setListener(Listener listener) { this.listener = listener; }
        @Override public CompletableFuture<Void> connect() {
            connected = true;
            if (listener != null) listener.onOpen();
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> send(String text) {
            sentMessages.add(text);
            return CompletableFuture.completedFuture(null);
        }
        @Override public void close() {
            connected = false;
            if (listener != null) listener.onClose(1000, "Closed");
        }
        @Override public boolean isConnected() { return connected; }
    }

    private static void awaitFx() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        assertTrue(latch.await(3, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("15.5B.6: Connection lifecycle status transitions")
    void testConnectionLifecycle() throws Exception {
        MockTransport transport = new MockTransport();
        AuthStateService auth = createMockAuthState("u-1", "Ada", "ada");

        try (RealtimeChatService service = new RealtimeChatService(transport, auth)) {
            assertEquals(RealtimeConnectionStatus.DISCONNECTED, service.getConnectionStatus());

            service.connect().get(5, TimeUnit.SECONDS);
            awaitFx();
            assertEquals(RealtimeConnectionStatus.CONNECTED, service.getConnectionStatus());

            // Simulate transport error
            transport.listener.onError(new RuntimeException("Network drop"));
            awaitFx();
            assertEquals(RealtimeConnectionStatus.RECONNECTING, service.getConnectionStatus());

            // Transport reopens
            transport.listener.onOpen();
            awaitFx();
            assertEquals(RealtimeConnectionStatus.CONNECTED, service.getConnectionStatus());
        }
    }

    @Test
    @DisplayName("15.5B.4: Optimistic message creation and reconciliation with canonical server message")
    void testOptimisticSendAndReconciliation() throws Exception {
        MockTransport transport = new MockTransport();
        AuthStateService auth = createMockAuthState("u-ada", "Ada Lovelace", "ada");

        try (RealtimeChatService service = new RealtimeChatService(transport, auth)) {
            service.connect().get(5, TimeUnit.SECONDS);

            // 1. Send optimistic message
            UIMessage sent = service.sendMessage("Hello everyone!").get(5, TimeUnit.SECONDS);
            awaitFx();
            assertNotNull(sent);
            assertTrue(sent.id().startsWith("opt-"), "Temporary optimistic ID expected");
            assertEquals("u-ada", sent.senderId());
            assertEquals("Ada Lovelace", sent.senderDisplayName());
            assertTrue(sent.isMine("u-ada"));
            assertEquals(1, service.getMessages().size());

            // Verify transport frame
            assertEquals(1, transport.sentMessages.size());
            String frame = transport.sentMessages.getFirst();
            assertTrue(frame.contains("\"action\":\"send_message\""));
            assertTrue(frame.contains(sent.id()));

            // 2. Server echoes canonical message with assigned server ID
            String serverPayload = MAPPER.writeValueAsString(Map.of(
                    "id", "srv-msg-9999",
                    "tempId", sent.id(),
                    "senderId", "u-ada",
                    "senderDisplayName", "Ada Lovelace",
                    "senderUsername", "ada",
                    "content", "Hello everyone!",
                    "timestamp", System.currentTimeMillis()
            ));

            transport.listener.onMessage(serverPayload);

            // Wait for JavaFX thread dispatch if needed
            awaitFx();

            // Verify optimistic message was replaced in-place, NOT duplicated
            assertEquals(1, service.getMessages().size(), "Optimistic message must be reconciled without duplication");
            UIMessage reconciled = service.getMessages().getFirst();
            assertEquals("srv-msg-9999", reconciled.id());
            assertEquals("Hello everyone!", reconciled.content());
            assertTrue(reconciled.isMine("u-ada"));
        }
    }

    @Test
    @DisplayName("15.5B.4: Canonical message deduplication suppresses repeated messages")
    void testMessageDeduplication() throws Exception {
        MockTransport transport = new MockTransport();
        AuthStateService auth = createMockAuthState("u-ada", "Ada", "ada");

        try (RealtimeChatService service = new RealtimeChatService(transport, auth)) {
            service.connect().get(5, TimeUnit.SECONDS);

            String serverPayload = MAPPER.writeValueAsString(Map.of(
                    "id", "canonical-uuid-12345",
                    "senderId", "u-ken",
                    "senderDisplayName", "Ken",
                    "senderUsername", "ken",
                    "content", "First canonical delivery",
                    "timestamp", System.currentTimeMillis()
            ));

            // Deliver twice
            transport.listener.onMessage(serverPayload);
            transport.listener.onMessage(serverPayload);

            awaitFx();

            assertEquals(1, service.getMessages().size(), "Duplicate message with same canonical ID must be ignored");
            assertEquals("canonical-uuid-12345", service.getMessages().getFirst().id());
        }
    }

    @Test
    @DisplayName("15.5B.5: Multi-device same user: device receives message sent from another device")
    void testMultiDeviceSameUserMessage() throws Exception {
        MockTransport transport = new MockTransport();
        AuthStateService auth = createMockAuthState("u-ada", "Ada", "ada");

        try (RealtimeChatService service = new RealtimeChatService(transport, auth)) {
            service.connect().get(5, TimeUnit.SECONDS);

            // Message originates from Ada on Device 2 (server broadcast to Device 1)
            String remoteDevicePayload = MAPPER.writeValueAsString(Map.of(
                    "id", "srv-msg-from-device-2",
                    "senderId", "u-ada", // Same user ID!
                    "senderDisplayName", "Ada",
                    "senderUsername", "ada",
                    "content", "Sent from my laptop",
                    "timestamp", System.currentTimeMillis()
            ));

            transport.listener.onMessage(remoteDevicePayload);

            awaitFx();

            assertEquals(1, service.getMessages().size());
            UIMessage msg = service.getMessages().getFirst();
            assertEquals("srv-msg-from-device-2", msg.id());
            assertEquals("Sent from my laptop", msg.content());
            assertTrue(msg.isMine("u-ada"), "Even when arriving from another device, message from same userId is owned by user");
        }
    }
}
