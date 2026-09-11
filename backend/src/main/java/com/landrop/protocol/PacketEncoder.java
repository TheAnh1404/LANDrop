package com.landrop.protocol;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Encodes Packet instances into raw byte streams adhering to the LANDrop custom TCP protocol format.
 * <p>
 * Wire format:
 * <ul>
 *   <li>Version: 1 byte</li>
 *   <li>Message Type Code: 1 byte</li>
 *   <li>Payload Length: 4 bytes (int)</li>
 *   <li>Request ID: 8 bytes (long)</li>
 *   <li>Payload: [Payload Length] bytes</li>
 * </ul>
 */
public class PacketEncoder {

    /**
     * Encodes a Packet and writes its raw bytes to the specified OutputStream.
     * Does NOT close the stream, but flushes buffered bytes.
     *
     * @param packet the Packet to encode
     * @param outputStream the destination OutputStream
     * @throws IOException if an I/O error occurs
     */
    public void encode(Packet packet, OutputStream outputStream) throws IOException {
        Objects.requireNonNull(packet, "Packet cannot be null");
        Objects.requireNonNull(outputStream, "OutputStream cannot be null");

        DataOutputStream out = (outputStream instanceof DataOutputStream dos)
                ? dos
                : new DataOutputStream(outputStream);

        out.writeByte(packet.getVersion());
        out.writeByte(packet.getType().getCode());
        out.writeInt(packet.getPayloadLength());
        out.writeLong(packet.getRequestId());
        if (packet.getPayloadLength() > 0) {
            out.write(packet.getPayload());
        }
        out.flush();
    }

    /**
     * Helper method to encode a Packet directly into a byte array.
     * Useful for testing and buffer allocations.
     *
     * @param packet the Packet to encode
     * @return the raw bytes of the encoded packet
     * @throws IOException if an error occurs during encoding
     */
    public byte[] encodeToBytes(Packet packet) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(14 + (packet != null ? packet.getPayloadLength() : 0));
        encode(packet, baos);
        return baos.toByteArray();
    }
}
