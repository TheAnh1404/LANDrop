package com.landrop.ui.realtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landrop.ui.protocol.RealtimeEnvelope;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Server-coordinated transfer control only. Direct file bytes remain in LanTransferService. */
public final class TransferControlService implements AutoCloseable {
    private final ClientRealtimeService realtime;
    private final AutoCloseable requestEvents;
    private final AutoCloseable statusEvents;
    public TransferControlService(ClientRealtimeService realtime, Consumer<RealtimeEnvelope> incoming) {
        this.realtime = Objects.requireNonNull(realtime); Objects.requireNonNull(incoming);
        this.requestEvents = realtime.subscribe("transfer.request", event -> { System.out.println("[LANDrop Client] TRANSFER_REQUEST_RECEIVED"); incoming.accept(event); });
        this.statusEvents = realtime.subscribe("transfer.status", incoming);
    }
    public CompletableFuture<RealtimeEnvelope> request(String fileId, String receiverUserId, String receiverDeviceId) {
        ObjectNode payload = RealtimeEnvelope.newPayload(); payload.put("fileId", fileId); payload.put("receiverUserId", receiverUserId);
        if (receiverDeviceId != null && !receiverDeviceId.isBlank()) payload.put("receiverDeviceId", receiverDeviceId);
        return realtime.request("transfer.request", payload);
    }
    public CompletableFuture<RealtimeEnvelope> accept(String transferId) { return command("transfer.accept", transferId, null); }
    public CompletableFuture<RealtimeEnvelope> reject(String transferId, String reason) { return command("transfer.reject", transferId, reason); }
    public CompletableFuture<RealtimeEnvelope> cancel(String transferId, String reason) { return command("transfer.cancel", transferId, reason); }
    private CompletableFuture<RealtimeEnvelope> command(String type, String transferId, String reason) {
        ObjectNode payload = RealtimeEnvelope.newPayload(); payload.put("transferId", transferId); if (reason != null && !reason.isBlank()) payload.put("reason", reason); return realtime.request(type, payload);
    }
    @Override public void close() { try { requestEvents.close(); } catch (Exception ignored) { } try { statusEvents.close(); } catch (Exception ignored) { } }
}
