package com.landrop.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Representation of a clipboard text or snippet shared between peers over the LAN network.
 *
 * @param requestId the correlation ID
 * @param sender the sender display name or peer identifier
 * @param text the clipboard text payload
 * @param timestamp epoch millisecond timestamp
 */
public record ClipboardMessage(long requestId, String sender, String text, long timestamp) {

    public ClipboardMessage {
        Objects.requireNonNull(sender, "Sender cannot be null");
        Objects.requireNonNull(text, "Text cannot be null");
    }

    public ClipboardMessage(long requestId, String sender, String text) {
        this(requestId, sender, text, System.currentTimeMillis());
    }

    /**
     * Serializes this ClipboardMessage into binary payload format:
     * [Timestamp: 8B] [SenderLen: 2B] [SenderBytes] [TextLen: 4B] [TextBytes]
     *
     * @return the serialized byte array
     */
    public byte[] encodePayload() {
        byte[] senderBytes = sender.getBytes(StandardCharsets.UTF_8);
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);

        ByteBuffer buffer = ByteBuffer.allocate(8 + 2 + senderBytes.length + 4 + textBytes.length);
        buffer.putLong(timestamp);
        buffer.putShort((short) senderBytes.length);
        buffer.put(senderBytes);
        buffer.putInt(textBytes.length);
        buffer.put(textBytes);
        return buffer.array();
    }

    /**
     * Deserializes a ClipboardMessage from raw payload bytes.
     *
     * @param requestId the packet correlation ID
     * @param payload the binary payload
     * @return a new ClipboardMessage instance
     */
    public static ClipboardMessage decodePayload(long requestId, byte[] payload) {
        if (payload == null || payload.length < 14) {
            throw new IllegalArgumentException("Payload too short for ClipboardMessage");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        long timestamp = buffer.getLong();
        int senderLen = buffer.getShort() & 0xFFFF;
        if (buffer.remaining() < senderLen + 4) {
            throw new IllegalArgumentException("Malformed sender length in ClipboardMessage");
        }
        byte[] senderBytes = new byte[senderLen];
        buffer.get(senderBytes);
        String sender = new String(senderBytes, StandardCharsets.UTF_8);

        int textLen = buffer.getInt();
        if (buffer.remaining() < textLen) {
            throw new IllegalArgumentException("Malformed text length in ClipboardMessage");
        }
        byte[] textBytes = new byte[textLen];
        buffer.get(textBytes);
        String text = new String(textBytes, StandardCharsets.UTF_8);

        return new ClipboardMessage(requestId, sender, text, timestamp);
    }
}
