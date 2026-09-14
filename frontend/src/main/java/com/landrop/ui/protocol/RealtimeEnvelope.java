package com.landrop.ui.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Shared Server-control envelope. Binary data must never be carried here. */
public record RealtimeEnvelope(String type, String requestId, String timestamp, JsonNode payload) {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public RealtimeEnvelope {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("Event type is required.");
        if (requestId == null || requestId.isBlank()) throw new IllegalArgumentException("requestId is required.");
        if (timestamp == null || timestamp.isBlank()) throw new IllegalArgumentException("timestamp is required.");
        payload = payload == null ? JSON.createObjectNode() : payload;
    }
    public static RealtimeEnvelope create(String type, JsonNode payload) { return new RealtimeEnvelope(type, UUID.randomUUID().toString(), Instant.now().toString(), payload); }
    public static RealtimeEnvelope parse(String source) {
        try {
            JsonNode root = JSON.readTree(source);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("Realtime event must be an object.");
            JsonNode p = root.path("payload");
            return new RealtimeEnvelope(text(root, "type"), text(root, "requestId"), text(root, "timestamp"), p.isMissingNode() ? JSON.createObjectNode() : p);
        } catch (Exception error) { throw new IllegalArgumentException("Invalid realtime event.", error); }
    }
    public String toJson() {
        try { ObjectNode root = JSON.createObjectNode(); root.put("type", type); root.put("requestId", requestId); root.put("timestamp", timestamp); root.set("payload", payload); return JSON.writeValueAsString(root); }
        catch (Exception error) { throw new IllegalStateException("Cannot encode realtime event.", error); }
    }
    public static ObjectNode newPayload() { return JSON.createObjectNode(); }
    private static String text(JsonNode root, String field) { JsonNode value = root.get(field); if (value == null || !value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException(field + " is required."); return value.asText(); }
}
