package com.landrop.transfer.messages;

import com.landrop.protocol.ProtocolException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Encapsulates a framed binary chunk of a file transmitted via FILE_DATA (code 17).
 * <p>
 * Binary payload format:
 * <ul>
 *   <li>transferId length (1 byte, unsigned: 1-255)</li>
 *   <li>transferId string bytes (UTF-8)</li>
 *   <li>offset within file (8 bytes, long)</li>
 *   <li>raw chunk bytes (remaining payload)</li>
 * </ul>
 */
public record FileDataChunk(String transferId, long offset, byte[] data) {

    public FileDataChunk {
        Objects.requireNonNull(transferId, "transferId cannot be null");
        Objects.requireNonNull(data, "data cannot be null");
        if (offset < 0) {
            throw new IllegalArgumentException("offset cannot be negative: " + offset);
        }
    }

    /**
     * Encodes transferId, offset, and data slice into a raw byte payload for FILE_DATA packet.
     *
     * @param transferId unique transfer identifier
     * @param offset byte offset position in the file
     * @param data source buffer containing bytes
     * @param length number of bytes from source buffer to include
     * @return raw payload byte array
     */
    public static byte[] encode(String transferId, long offset, byte[] data, int length) {
        Objects.requireNonNull(transferId, "transferId cannot be null");
        Objects.requireNonNull(data, "data cannot be null");
        if (offset < 0) {
            throw new IllegalArgumentException("offset cannot be negative: " + offset);
        }
        if (length < 0 || length > data.length) {
            throw new IllegalArgumentException("Invalid length: " + length);
        }

        byte[] idBytes = transferId.getBytes(StandardCharsets.UTF_8);
        if (idBytes.length > 255 || idBytes.length == 0) {
            throw new IllegalArgumentException("transferId length must be between 1 and 255 bytes, got: " + idBytes.length);
        }

        int totalLen = 1 + idBytes.length + 8 + length;
        ByteBuffer buffer = ByteBuffer.allocate(totalLen);
        buffer.put((byte) idBytes.length);
        buffer.put(idBytes);
        buffer.putLong(offset);
        if (length > 0) {
            buffer.put(data, 0, length);
        }
        return buffer.array();
    }

    /**
     * Decodes a FILE_DATA payload into a FileDataChunk.
     *
     * @param payload the raw payload bytes from the Packet
     * @return the decoded FileDataChunk
     * @throws ProtocolException if payload structure is invalid or corrupt
     */
    public static FileDataChunk decode(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 10) { // 1 byte len + at least 1 byte id + 8 bytes offset
            throw new ProtocolException("FILE_DATA payload too short: " + (payload == null ? 0 : payload.length));
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        int idLen = Byte.toUnsignedInt(buffer.get());
        if (idLen == 0 || buffer.remaining() < idLen + 8) {
            throw new ProtocolException("Invalid transferId length in FILE_DATA: " + idLen + ", remaining: " + buffer.remaining());
        }

        byte[] idBytes = new byte[idLen];
        buffer.get(idBytes);
        String transferId = new String(idBytes, StandardCharsets.UTF_8);

        long offset = buffer.getLong();
        if (offset < 0) {
            throw new ProtocolException("Negative file offset in FILE_DATA: " + offset);
        }

        byte[] chunk = new byte[buffer.remaining()];
        buffer.get(chunk);

        return new FileDataChunk(transferId, offset, chunk);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FileDataChunk that)) return false;
        return offset == that.offset &&
                transferId.equals(that.transferId) &&
                Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(transferId, offset);
        result = 31 * result + Arrays.hashCode(data);
        return result;
    }

    @Override
    public String toString() {
        return "FileDataChunk{" +
                "transferId='" + transferId + '\'' +
                ", offset=" + offset +
                ", dataLength=" + data.length +
                '}';
    }
}
