package com.landrop.ui.realtime;

import com.landrop.ui.bootstrap.ClientInstanceService;
import com.landrop.ui.connection.ConnectionState;
import com.landrop.ui.protocol.RealtimeEnvelope;
import com.landrop.ui.services.RealtimeTransport;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class ClientRealtimeServiceTest {
    @Test
    void completesHandshakeThenCorrelatesEchoResponse() throws Exception {
        FakeTransport transport = new FakeTransport();
        ClientRealtimeService client = new ClientRealtimeService(URI.create("ws://server/api/realtime"),
                () -> CompletableFuture.completedFuture(null), new ClientInstanceService(), () -> "token", () -> "Laptop",
                (uri, token) -> transport);
        try {
            client.start();
            RealtimeEnvelope hello = RealtimeEnvelope.parse(transport.sent.getFirst());
            assertEquals("hello", hello.type());
            assertEquals(1, hello.payload().path("protocolVersion").asInt());
            assertFalse(hello.payload().path("clientInstanceId").asText().isBlank());
            assertEquals("Laptop", hello.payload().path("deviceName").asText());
            assertFalse(hello.payload().path("platform").asText().isBlank());
            assertFalse(hello.payload().path("clientVersion").asText().isBlank());
            assertFalse(hello.payload().path("osVersion").asText().isBlank());
            assertFalse(hello.payload().path("architecture").asText().isBlank());

            transport.receive(new RealtimeEnvelope("welcome", hello.requestId(), "2026-01-01T00:00:00Z",
                    RealtimeEnvelope.newPayload().put("protocolVersion", 1).put("heartbeatIntervalMs", 60_000)
                            .put("serverName", "Test Server").put("serverVersion", "test-1")).toJson());
            assertEquals(ConnectionState.CONNECTED, client.state());
            assertEquals("Test Server", client.serverNameProperty().get());
            assertEquals("test-1", client.serverVersionProperty().get());

            var payload = RealtimeEnvelope.newPayload().put("message", "hello server");
            CompletableFuture<RealtimeEnvelope> response = client.request("echo", payload);
            RealtimeEnvelope echo = RealtimeEnvelope.parse(transport.sent.getLast());
            transport.receive(new RealtimeEnvelope("echo_ack", echo.requestId(), "2026-01-01T00:00:01Z", payload).toJson());
            assertEquals("echo_ack", response.get().type());
        } finally { client.close(); }
    }

    @Test
    void invalidServerEventDoesNotBreakAConnectedClient() {
        FakeTransport transport = new FakeTransport();
        ClientRealtimeService client = new ClientRealtimeService(URI.create("ws://server/api/realtime"),
                () -> CompletableFuture.completedFuture(null), new ClientInstanceService(), () -> "token", () -> "Laptop",
                (uri, token) -> transport);
        try {
            client.start();
            RealtimeEnvelope hello = RealtimeEnvelope.parse(transport.sent.getFirst());
            transport.receive(new RealtimeEnvelope("welcome", hello.requestId(), "2026-01-01T00:00:00Z",
                    RealtimeEnvelope.newPayload().put("protocolVersion", 1)).toJson());
            transport.receive("not json");
            assertEquals(ConnectionState.CONNECTED, client.state());
        } finally { client.close(); }
    }

    @Test
    void requestTimeoutRemovesPendingCorrelation() throws Exception {
        FakeTransport transport = new FakeTransport();
        ClientRealtimeService client = new ClientRealtimeService(URI.create("ws://server/api/realtime"),
                () -> CompletableFuture.completedFuture(null), new ClientInstanceService("timeout-" + System.nanoTime()), () -> "token", () -> "Laptop",
                (uri, token) -> transport, Duration.ofMillis(50));
        try {
            client.start(); RealtimeEnvelope hello = RealtimeEnvelope.parse(transport.sent.getFirst());
            transport.receive(new RealtimeEnvelope("welcome", null, "2026-01-01T00:00:00Z", RealtimeEnvelope.newPayload().put("protocolVersion", 1)).toJson());
            assertThrows(Exception.class, () -> client.request("echo", RealtimeEnvelope.newPayload()).get(1, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, client.pendingRequestCount());
        } finally { client.close(); }
    }

    @Test
    void clientInstanceIdPersistsWithinTheSameProfile() {
        String profile = "instance-" + System.nanoTime();
        assertEquals(new ClientInstanceService(profile).clientInstanceId(), new ClientInstanceService(profile).clientInstanceId());
    }

    @Test
    void adminDisconnectCleansUpWithoutSchedulingReconnect() {
        FakeTransport transport = new FakeTransport();
        ClientRealtimeService client = new ClientRealtimeService(URI.create("ws://server/api/realtime"),
                () -> CompletableFuture.completedFuture(null), new ClientInstanceService("admin-disconnect-" + System.nanoTime()), () -> "token", () -> "Laptop",
                (uri, token) -> transport);
        try {
            client.start();
            RealtimeEnvelope hello = RealtimeEnvelope.parse(transport.sent.getFirst());
            transport.receive(new RealtimeEnvelope("welcome", hello.requestId(), "2026-01-01T00:00:00Z",
                    RealtimeEnvelope.newPayload().put("protocolVersion", 1).put("heartbeatIntervalMs", 60_000)).toJson());
            transport.receive(new RealtimeEnvelope("error", null, "2026-01-01T00:00:01Z",
                    RealtimeEnvelope.newPayload().put("code", "ADMIN_DISCONNECT").put("message", "Removed by admin")).toJson());
            assertEquals(ConnectionState.DISCONNECTED, client.state());
            assertTrue(client.disconnectReasonProperty().get().startsWith("ADMIN_DISCONNECT"));
            assertEquals(0, client.reconnectAttemptsProperty().get());
        } finally { client.close(); }
    }

    @Test void authenticatedBindingRejectsLegacyOrMismatchedWelcome() {
        for (String wrongDevice : new String[]{"", "another-device"}) {
            FakeTransport transport = new FakeTransport();
            ClientRealtimeService client = new ClientRealtimeService(URI.create("ws://server/api/realtime"),
                    () -> CompletableFuture.completedFuture(null), new ClientInstanceService(), () -> "token", () -> "Laptop", (uri, token) -> transport);
            try {
                client.requireAuthenticatedBinding("user-1", "device-1", () -> CompletableFuture.completedFuture("token"));
                client.start();
                var hello = RealtimeEnvelope.parse(transport.sent.getFirst());
                assertEquals("device-1", hello.payload().path("deviceId").asText());
                assertFalse(hello.payload().has("userId")); assertFalse(hello.payload().has("accessToken"));
                transport.receive(RealtimeEnvelope.create("welcome", RealtimeEnvelope.newPayload().put("protocolVersion", 1)
                        .put("authenticated", true).put("userId", "user-1").put("deviceId", wrongDevice)
                        .put("sessionId", "session-1").put("connectionId", "connection-1")).toJson());
                assertEquals(ConnectionState.DISCONNECTED, client.state()); assertFalse(transport.connected);
                assertTrue(client.disconnectReasonProperty().get().startsWith("AUTH_BINDING_REQUIRED"));
                assertEquals(0, client.reconnectAttemptsProperty().get());
            } finally { client.dispose(); }
        }
    }
    @Test void authenticatedWelcomeAndTerminalSessionErrorsStopSocketAndRequests() {
        for (String code : new String[]{"SESSION_EXPIRED", "SESSION_REVOKED", "ACCOUNT_DISABLED"}) {
            FakeTransport transport = new FakeTransport();
            ClientRealtimeService client = new ClientRealtimeService(URI.create("ws://server/api/realtime"),
                    () -> CompletableFuture.completedFuture(null), new ClientInstanceService(), () -> "token", () -> "Laptop", (uri, token) -> transport);
            try {
                client.requireAuthenticatedBinding("user-1", "device-1", () -> CompletableFuture.completedFuture("token"));
                client.start(); client.start(); assertEquals(1, transport.sent.size());
                transport.receive(RealtimeEnvelope.create("welcome", RealtimeEnvelope.newPayload().put("protocolVersion", 1)
                        .put("authenticated", true).put("userId", "user-1").put("deviceId", "device-1")
                        .put("sessionId", "session-1").put("connectionId", "connection-1")).toJson());
                assertTrue(client.isConnected());
                var request = client.request("echo", RealtimeEnvelope.newPayload());
                transport.receive(RealtimeEnvelope.create("error", RealtimeEnvelope.newPayload().put("code", code)).toJson());
                assertTrue(request.isCompletedExceptionally()); assertEquals(0, client.pendingRequestCount());
                assertFalse(transport.connected); assertEquals(ConnectionState.DISCONNECTED, client.state());
                assertEquals(0, client.reconnectAttemptsProperty().get());
            } finally { client.dispose(); }
        }
    }
    @Test void supersededHealthCheckCannotOpenDuplicateSocket() {
        CompletableFuture<Void> first = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicInteger probes = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger sockets = new java.util.concurrent.atomic.AtomicInteger();
        ClientRealtimeService client = new ClientRealtimeService(URI.create("ws://server/api/realtime"),
                () -> probes.getAndIncrement() == 0 ? first : CompletableFuture.completedFuture(null),
                new ClientInstanceService(), () -> "token", () -> "Laptop", (uri, token) -> { sockets.incrementAndGet(); return new FakeTransport(); });
        try { client.start(); client.reconnectNow(); first.complete(null); assertEquals(1, sockets.get()); }
        finally { client.dispose(); }
    }

    @Test void networkReconnectRebindsSameDeviceAndKeepsOneHeartbeatTimer() throws Exception {
        var created = new java.util.concurrent.LinkedBlockingQueue<FakeTransport>();
        var refreshes = new java.util.concurrent.atomic.AtomicInteger();
        ClientRealtimeService client = new ClientRealtimeService(URI.create("ws://server/api/realtime"),
                () -> CompletableFuture.completedFuture(null), new ClientInstanceService(), () -> "token", () -> "Laptop",
                (uri, token) -> { var transport = new FakeTransport(); created.add(transport); return transport; });
        try {
            client.requireAuthenticatedBinding("user-1", "device-1", () -> { refreshes.incrementAndGet(); return CompletableFuture.completedFuture("token"); });
            client.start(); var first = created.poll(2, java.util.concurrent.TimeUnit.SECONDS); assertNotNull(first);
            first.helloSent.get(2, java.util.concurrent.TimeUnit.SECONDS);
            String welcome = RealtimeEnvelope.create("welcome", RealtimeEnvelope.newPayload().put("protocolVersion", 1)
                    .put("heartbeatIntervalMs", 1000).put("authenticated", true).put("userId", "user-1")
                    .put("deviceId", "device-1").put("sessionId", "session-1").put("connectionId", "connection-1")).toJson();
            first.receive(welcome);
            first.listener.onClose(1006, "Network interrupted");
            var second = created.poll(3, java.util.concurrent.TimeUnit.SECONDS); assertNotNull(second);
            second.helloSent.get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(first.connected); assertEquals(2, refreshes.get());
            assertEquals("device-1", RealtimeEnvelope.parse(second.sent.getFirst()).payload().path("deviceId").asText());
            second.receive(welcome); second.receive(welcome);
            assertTrue(client.isConnected());
            assertNotNull(second.heartbeats.poll(2, java.util.concurrent.TimeUnit.SECONDS));
            assertNull(second.heartbeats.poll(250, java.util.concurrent.TimeUnit.MILLISECONDS));
            assertTrue(first.heartbeats.isEmpty()); assertTrue(created.isEmpty());
        } finally { client.dispose(); }
    }

    private static final class FakeTransport implements RealtimeTransport {
        private Listener listener; private boolean connected; private final List<String> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final CompletableFuture<Void> helloSent = new CompletableFuture<>();
        private final java.util.concurrent.LinkedBlockingQueue<String> heartbeats = new java.util.concurrent.LinkedBlockingQueue<>();
        @Override public void setListener(Listener listener) { this.listener = listener; }
        @Override public CompletableFuture<Void> connect() { connected = true; listener.onOpen(); return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Void> send(String payload) {
            sent.add(payload);
            String type = RealtimeEnvelope.parse(payload).type();
            if ("hello".equals(type)) helloSent.complete(null);
            if ("heartbeat".equals(type)) heartbeats.add(payload);
            return CompletableFuture.completedFuture(null);
        }
        @Override public void close() { connected = false; }
        @Override public boolean isConnected() { return connected; }
        void receive(String payload) { listener.onMessage(payload); }
    }
}
