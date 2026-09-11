package com.landrop.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Represents a discrete packet in the LANDrop custom TCP protocol.
 * <p>
 * Binary wire format:
 * <pre>
 * +----------+----------+----------+----------+
 * | Version  | Msg Type | Length   | RequestID|
 * | (1 byte) | (1 byte) | (4 bytes)| (8 bytes)|
 * +----------+----------+----------+----------+
 * |                 Payload                    |
 * |                 (Length bytes)             |
 * +--------------------------------------------+
 * </pre>
 */
public final class Packet {

    public static final int CURRENT_VERSION = 1;
    private static final byte[] EMPTY_PAYLOAD = new byte[0];

    private final int version;
    private final MessageType type;
    private final int payloadLength;
    private final long requestId;
    private final byte[] payload;

    /**
     * Constructs a new Packet with full parameter validation.
     *
     * @param version the protocol version (1-255)
     * @param type the MessageType
     * @param requestId the unique request correlation ID
     * @param payload the byte payload (null is converted to an empty array)
     */
    public Packet(int version, MessageType type, long requestId, byte[] payload) {
        if (version < 1 || version > 255) {
            throw new IllegalArgumentException("Version must be between 1 and 255, got: " + version);
        }
        this.version = version;
        this.type = Objects.requireNonNull(type, "MessageType cannot be null");
        this.requestId = requestId;
        byte[] p = (payload != null) ? payload.clone() : EMPTY_PAYLOAD;
        this.payload = p;
        this.payloadLength = p.length;
    }

    /**
     * Factory method creating a Packet with the default protocol version (1).
     *
     * @param type the MessageType
     * @param requestId the request ID
     * @param payload the byte payload
     * @return a new Packet instance
     */
    public static Packet of(MessageType type, long requestId, byte[] payload) {
        return new Packet(CURRENT_VERSION, type, requestId, payload);
    }

    /**
     * Factory method creating a Packet with a specific protocol version.
     *
     * @param version the protocol version
     * @param type the MessageType
     * @param requestId the request ID
     * @param payload the byte payload
     * @return a new Packet instance
     */
    public static Packet of(int version, MessageType type, long requestId, byte[] payload) {
        return new Packet(version, type, requestId, payload);
    }

    /**
     * Factory method creating a Packet with a UTF-8 string payload and default version (1).
     *
     * @param type the MessageType
     * @param requestId the request ID
     * @param message the string payload
     * @return a new Packet instance
     */
    public static Packet text(MessageType type, long requestId, String message) {
        byte[] data = (message != null) ? message.getBytes(StandardCharsets.UTF_8) : EMPTY_PAYLOAD;
        return new Packet(CURRENT_VERSION, type, requestId, data);
    }

    /**
     * Factory method creating a Packet with an empty payload and default version (1).
     *
     * @param type the MessageType
     * @param requestId the request ID
     * @return a new Packet instance
     */
    public static Packet empty(MessageType type, long requestId) {
        return new Packet(CURRENT_VERSION, type, requestId, EMPTY_PAYLOAD);
    }

    public int getVersion() {
        return version;
    }

    public MessageType getType() {
        return type;
    }

    public int getPayloadLength() {
        return payloadLength;
    }

    public long getRequestId() {
        return requestId;
    }

    public byte[] getPayload() {
        return payload.clone();
    }

    /**
     * Decodes the payload as a UTF-8 string.
     *
     * @return the string representation of the payload
     */
    public String payloadAsString() {
        return new String(payload, StandardCharsets.UTF_8);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Packet packet)) return false;
        return version == packet.version &&
                payloadLength == packet.payloadLength &&
                requestId == packet.requestId &&
                type == packet.type &&
                Arrays.equals(payload, packet.payload);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(version, type, payloadLength, requestId);
        result = 31 * result + Arrays.hashCode(payload);
        return result;
    }

    @Override
    public String toString() {
        return "Packet{" +
                "version=" + version +
                ", type=" + type +
                ", requestId=" + requestId +
                ", payloadLength=" + payloadLength +
                ", payload='" + payloadAsString() + '\'' +
                '}';
    }
}
