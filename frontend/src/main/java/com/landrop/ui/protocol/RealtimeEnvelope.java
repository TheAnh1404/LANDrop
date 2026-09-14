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
        if (timestamp == null || timestamp.isBlank()) throw new IllegalArgumentException("timestamp is required.");
        payload = payload == null ? JSON.createObjectNode() : payload;
    }
    public static RealtimeEnvelope create(String type, JsonNode payload) { return new RealtimeEnvelope(type, UUID.randomUUID().toString(), Instant.now().toString(), payload); }
    public static RealtimeEnvelope parse(String source) {
        try {
            JsonNode root = JSON.readTree(source);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("Realtime event must be an object.");
            JsonNode p = root.path("payload");
            ObjectNode normalized = p.isMissingNode() || p.isNull() || !p.isObject()
                    ? JSON.createObjectNode() : (ObjectNode) p;
            // FOUNDATION-01A errors carry code/message at the top level for compatibility.
            if (normalized.isEmpty() && root.hasNonNull("message")) {
                normalized.put("message", root.path("message").asText());
                if (root.hasNonNull("code")) normalized.put("code", root.path("code").asText());
            }
            if (root.path("error").isObject()) {
                JsonNode error = root.path("error");
                if (!normalized.has("code") && error.hasNonNull("code")) normalized.put("code", error.path("code").asText());
                if (!normalized.has("message") && error.hasNonNull("message")) normalized.put("message", error.path("message").asText());
            }
            return new RealtimeEnvelope(text(root, "type"), optionalText(root, "requestId"), text(root, "timestamp"), normalized);
        } catch (Exception error) { throw new IllegalArgumentException("Invalid realtime event.", error); }
    }
    public String toJson() {
        try { ObjectNode root = JSON.createObjectNode(); root.put("type", type); if (requestId != null && !requestId.isBlank()) root.put("requestId", requestId); root.put("timestamp", timestamp); root.set("payload", payload); return JSON.writeValueAsString(root); }
        catch (Exception error) { throw new IllegalStateException("Cannot encode realtime event.", error); }
    }
    /** FOUNDATION handshake compatibility: server reads hello fields at envelope top level. */
    public String toJsonWithFlatPayload() {
        try {
            ObjectNode root = JSON.readTree(toJson()).deepCopy();
            if (payload.isObject()) payload.fields().forEachRemaining(field -> root.set(field.getKey(), field.getValue()));
            return JSON.writeValueAsString(root);
        } catch (Exception error) { throw new IllegalStateException("Cannot encode realtime event.", error); }
    }
    public static ObjectNode newPayload() { return JSON.createObjectNode(); }
    private static String text(JsonNode root, String field) { JsonNode value = root.get(field); if (value == null || !value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException(field + " is required."); return value.asText(); }
    private static String optionalText(JsonNode root, String field) { JsonNode value = root.get(field); return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null; }
}
