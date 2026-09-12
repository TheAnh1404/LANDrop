package com.landrop.transfer.messages;

import com.landrop.protocol.ProtocolException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Encapsulates a framed binary chunk response transmitted via CHUNK_RESPONSE (code 8).
 * <p>
 * Binary payload format:
 * <ul>
 *   <li>transferId length (1 byte, unsigned: 1-255)</li>
 *   <li>transferId string bytes (UTF-8)</li>
 *   <li>chunkIndex (4 bytes, int, >= 0)</li>
 *   <li>chunkLength (4 bytes, int, >= 0)</li>
 *   <li>chunkData (raw bytes matching chunkLength)</li>
 * </ul>
 */
public record ChunkResponseMessage(String transferId, int chunkIndex, int chunkLength, byte[] data) {

    public ChunkResponseMessage {
        Objects.requireNonNull(transferId, "transferId cannot be null");
        Objects.requireNonNull(data, "data cannot be null");
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex cannot be negative: " + chunkIndex);
        }
        if (chunkLength < 0) {
            throw new IllegalArgumentException("chunkLength cannot be negative: " + chunkLength);
        }
        if (data.length != chunkLength) {
            throw new IllegalArgumentException("data array length (" + data.length +
                    ") does not match chunkLength (" + chunkLength + ")");
        }
    }

    /**
     * Encodes transferId, chunkIndex, and data slice into a raw byte payload for CHUNK_RESPONSE packet.
     *
     * @param transferId unique transfer identifier
     * @param chunkIndex 0-based chunk index
     * @param data source buffer containing chunk bytes
     * @param length number of bytes from source buffer to include
     * @return raw payload byte array
     */
    public static byte[] encode(String transferId, int chunkIndex, byte[] data, int length) {
        Objects.requireNonNull(transferId, "transferId cannot be null");
        Objects.requireNonNull(data, "data cannot be null");
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex cannot be negative: " + chunkIndex);
        }
        if (length < 0 || length > data.length) {
            throw new IllegalArgumentException("Invalid length: " + length + ", buffer length: " + data.length);
        }

        byte[] idBytes = transferId.getBytes(StandardCharsets.UTF_8);
        if (idBytes.length > 255 || idBytes.length == 0) {
            throw new IllegalArgumentException("transferId length must be between 1 and 255 bytes, got: " + idBytes.length);
        }

        int totalLen = 1 + idBytes.length + 4 + 4 + length;
        ByteBuffer buffer = ByteBuffer.allocate(totalLen);
        buffer.put((byte) idBytes.length);
        buffer.put(idBytes);
        buffer.putInt(chunkIndex);
        buffer.putInt(length);
        if (length > 0) {
            buffer.put(data, 0, length);
        }
        return buffer.array();
    }

    /**
     * Encodes an entire byte array as chunk data.
     */
    public static byte[] encode(String transferId, int chunkIndex, byte[] data) {
        return encode(transferId, chunkIndex, data, data != null ? data.length : 0);
    }

    /**
     * Decodes a CHUNK_RESPONSE payload into a ChunkResponseMessage.
     *
     * @param payload the raw payload bytes from the Packet
     * @return the decoded ChunkResponseMessage
     * @throws ProtocolException if payload structure is invalid or corrupt
     */
    public static ChunkResponseMessage decode(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 10) { // 1 byte len + at least 1 byte id + 4 bytes index + 4 bytes len
            throw new ProtocolException("CHUNK_RESPONSE payload too short: " + (payload == null ? 0 : payload.length));
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        int idLen = Byte.toUnsignedInt(buffer.get());
        if (idLen == 0 || buffer.remaining() < idLen + 8) { // idLen + 4 (index) + 4 (chunkLen)
            throw new ProtocolException("Invalid transferId length in CHUNK_RESPONSE: " + idLen +
                    ", remaining: " + buffer.remaining());
        }

        byte[] idBytes = new byte[idLen];
        buffer.get(idBytes);
        String transferId = new String(idBytes, StandardCharsets.UTF_8);

        int chunkIndex = buffer.getInt();
        if (chunkIndex < 0) {
            throw new ProtocolException("Negative chunkIndex in CHUNK_RESPONSE: " + chunkIndex);
        }

        int chunkLength = buffer.getInt();
        if (chunkLength < 0) {
            throw new ProtocolException("Negative chunkLength in CHUNK_RESPONSE: " + chunkLength);
        }

        if (buffer.remaining() != chunkLength) {
            throw new ProtocolException("CHUNK_RESPONSE payload size mismatch: header specifies " +
                    chunkLength + " bytes, but remaining payload is " + buffer.remaining() + " bytes");
        }

        byte[] chunkData = new byte[chunkLength];
        if (chunkLength > 0) {
            buffer.get(chunkData);
        }

        return new ChunkResponseMessage(transferId, chunkIndex, chunkLength, chunkData);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChunkResponseMessage that)) return false;
        return chunkIndex == that.chunkIndex &&
                chunkLength == that.chunkLength &&
                transferId.equals(that.transferId) &&
                Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(transferId, chunkIndex, chunkLength);
        result = 31 * result + Arrays.hashCode(data);
        return result;
    }

    @Override
    public String toString() {
        return "ChunkResponseMessage{" +
                "transferId='" + transferId + '\'' +
                ", chunkIndex=" + chunkIndex +
                ", chunkLength=" + chunkLength +
                '}';
    }
}
