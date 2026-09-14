package com.landrop.ui.realtime;

import com.landrop.ui.bootstrap.ClientInstanceService;
import com.landrop.ui.connection.ConnectionState;
import com.landrop.ui.connection.ServerHealthClient;
import com.landrop.ui.protocol.RealtimeEnvelope;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in gate against a separately running FOUNDATION server; no server implementation classes are linked here. */
class FoundationLanGateTest {
    @Test
    void validatesLiveHealthHandshakeHeartbeatEchoAndTransferControl() throws Exception {
        String apiUrl = System.getProperty("landrop.gate.api");
        String wsUrl = System.getProperty("landrop.gate.ws");
        Assumptions.assumeTrue(apiUrl != null && wsUrl != null, "Set landrop.gate.api and landrop.gate.ws for the live gate.");
        ServerHealthClient health = new ServerHealthClient(URI.create(apiUrl));
        health.check().get(5, TimeUnit.SECONDS);
        assertEquals("ok", health.health().status()); assertEquals(1, health.health().protocolVersion());

        ClientRealtimeService sender = client(URI.create(apiUrl), URI.create(wsUrl), "gate-sender");
        ClientRealtimeService receiver = client(URI.create(apiUrl), URI.create(wsUrl), "gate-receiver");
        try {
            sender.start(); receiver.start();
            await(() -> sender.state() == ConnectionState.CONNECTED && receiver.state() == ConnectionState.CONNECTED, 8_000);
            assertFalse(sender.connectionIdProperty().get().isBlank()); assertEquals(1, sender.serverProtocolVersionProperty().get());
            for (int index = 1; index <= 3; index++) {
                assertEquals("echo_ack", sender.request("echo", RealtimeEnvelope.newPayload().put("message", "echo " + index))
                        .get(4, TimeUnit.SECONDS).type());
            }
            await(() -> !sender.lastHeartbeatProperty().get().isBlank(), 20_000);
            AtomicReference<RealtimeEnvelope> incoming = new AtomicReference<>();
            AutoCloseable listener = receiver.subscribe("transfer.request", incoming::set);
            try {
                TransferControlService controls = new TransferControlService(sender, ignored -> { });
                RealtimeEnvelope status = controls.requestToConnection(receiver.connectionIdProperty().get(), "gate.txt", 42, null)
                        .get(4, TimeUnit.SECONDS);
                assertEquals("transfer.status", status.type()); await(() -> incoming.get() != null, 4_000);
                assertEquals("transfer.status", new TransferControlService(receiver, ignored -> { })
                        .accept(status.payload().path("transferId").asText()).get(4, TimeUnit.SECONDS).type());
                controls.close();
            } finally { listener.close(); }
        } finally { sender.close(); receiver.close(); }
    }
    @Test
    void observesExternalServerShutdownAndRecovery() throws Exception {
        String apiUrl = System.getProperty("landrop.gate.api"); String wsUrl = System.getProperty("landrop.gate.ws");
        Assumptions.assumeTrue(apiUrl != null && wsUrl != null && Boolean.getBoolean("landrop.gate.recovery"), "Enable only when an external restart is orchestrated.");
        ClientRealtimeService client = client(URI.create(apiUrl), URI.create(wsUrl), "gate-recovery");
        AtomicBoolean reconnecting = new AtomicBoolean();
        client.stateProperty().addListener((obs, old, next) -> { if (next == ConnectionState.RECONNECTING || next == ConnectionState.DISCONNECTED) reconnecting.set(true); });
        try {
            client.start(); await(() -> client.state() == ConnectionState.CONNECTED, 8_000);
            String initialConnection = client.connectionIdProperty().get();
            reconnecting.set(false);
            await(reconnecting::get, 10_000);
            await(() -> client.state() == ConnectionState.CONNECTED && !initialConnection.equals(client.connectionIdProperty().get()), 12_000);
        } finally { client.close(); }
    }
    private static ClientRealtimeService client(URI api, URI ws, String label) {
        return new ClientRealtimeService(ws, new ServerHealthClient(api), new ClientInstanceService(label + "-" + UUID.randomUUID()), () -> "gate-token", () -> label);
    }
    private static void await(java.util.function.BooleanSupplier condition, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(25);
        assertTrue(condition.getAsBoolean(), "Timed out after " + timeoutMillis + "ms");
    }
}
