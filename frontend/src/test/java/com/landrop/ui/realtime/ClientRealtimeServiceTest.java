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

            transport.receive(new RealtimeEnvelope("welcome", hello.requestId(), "2026-01-01T00:00:00Z",
                    RealtimeEnvelope.newPayload().put("protocolVersion", 1).put("heartbeatIntervalMs", 60_000)).toJson());
            assertEquals(ConnectionState.CONNECTED, client.state());

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

    private static final class FakeTransport implements RealtimeTransport {
        private Listener listener; private boolean connected; private final List<String> sent = new ArrayList<>();
        @Override public void setListener(Listener listener) { this.listener = listener; }
        @Override public CompletableFuture<Void> connect() { connected = true; listener.onOpen(); return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Void> send(String payload) { sent.add(payload); return CompletableFuture.completedFuture(null); }
        @Override public void close() { connected = false; }
        @Override public boolean isConnected() { return connected; }
        void receive(String payload) { listener.onMessage(payload); }
    }
}
