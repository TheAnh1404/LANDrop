package com.landrop.protocol;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Decodes raw byte streams into Packet instances adhering to the LANDrop custom TCP protocol format.
 * <p>
 * Ensures robustness against TCP stream fragmentation by using {@link DataInputStream#readFully(byte[])},
 * and guards against memory exhaustion by enforcing a strict maximum payload size.
 */
public class PacketDecoder {

    /** Default maximum payload size: 2 MB (2,097,152 bytes) to accommodate 1 MiB chunks plus headers. */
    public static final int DEFAULT_MAX_PAYLOAD_SIZE = 2 * 1024 * 1024;

    private final int maxPayloadSize;

    /**
     * Constructs a PacketDecoder with the default maximum payload limit (2 MB).
     */
    public PacketDecoder() {
        this(DEFAULT_MAX_PAYLOAD_SIZE);
    }

    /**
     * Constructs a PacketDecoder with a custom maximum payload limit in bytes.
     *
     * @param maxPayloadSize the maximum allowed payload size in bytes
     */
    public PacketDecoder(int maxPayloadSize) {
        if (maxPayloadSize <= 0) {
            throw new IllegalArgumentException("maxPayloadSize must be positive: " + maxPayloadSize);
        }
        this.maxPayloadSize = maxPayloadSize;
    }

    /**
     * Gets the configured maximum payload size limit in bytes.
     *
     * @return the max payload size
     */
    public int getMaxPayloadSize() {
        return maxPayloadSize;
    }

    /**
     * Decodes the next Packet from the specified input stream.
     *
     * @param inputStream the source InputStream
     * @return the decoded Packet, or {@code null} if the stream cleanly reached EOF before any byte
     * @throws IOException if an I/O error or stream corruption occurs
     * @throws ProtocolException if any protocol validation rule fails
     */
    public Packet decode(InputStream inputStream) throws IOException {
        Objects.requireNonNull(inputStream, "InputStream cannot be null");

        DataInputStream in = (inputStream instanceof DataInputStream dis)
                ? dis
                : new DataInputStream(inputStream);

        int firstByte = in.read();
        if (firstByte == -1) {
            return null; // Clean EOF
        }

        int version = firstByte;
        if (version != Packet.CURRENT_VERSION) {
            throw new ProtocolException("Unsupported protocol version: " + version +
                    " (expected: " + Packet.CURRENT_VERSION + ")");
        }

        byte typeCode;
        try {
            typeCode = in.readByte();
        } catch (EOFException e) {
            throw new ProtocolException("Unexpected EOF while reading message type", e);
        }

        MessageType type = MessageType.fromCode(typeCode);

        int payloadLength;
        try {
            payloadLength = in.readInt();
        } catch (EOFException e) {
            throw new ProtocolException("Unexpected EOF while reading payload length", e);
        }

        if (payloadLength < 0) {
            throw new ProtocolException("Invalid negative payload length: " + payloadLength);
        }

        if (payloadLength > maxPayloadSize) {
            throw new ProtocolException("Payload length " + payloadLength +
                    " exceeds maximum allowed limit of " + maxPayloadSize + " bytes");
        }

        long requestId;
        try {
            requestId = in.readLong();
        } catch (EOFException e) {
            throw new ProtocolException("Unexpected EOF while reading request ID", e);
        }

        byte[] payload = new byte[payloadLength];
        if (payloadLength > 0) {
            try {
                in.readFully(payload);
            } catch (EOFException e) {
                throw new ProtocolException("Unexpected EOF while reading payload (" +
                        payloadLength + " bytes expected)", e);
            }
        }

        return Packet.of(version, type, requestId, payload);
    }

    /**
     * Helper method to decode a Packet from a byte array.
     *
     * @param bytes the raw bytes to decode
     * @return the decoded Packet
     * @throws IOException if an error occurs during decoding
     */
    public Packet decodeFromBytes(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes cannot be null");
        return decode(new ByteArrayInputStream(bytes));
    }
}
