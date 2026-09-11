package com.landrop.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PacketEncoderTest {

    private final PacketEncoder encoder = new PacketEncoder();

    @Test
    @DisplayName("Verify wire format: 14-byte header structure and payload serialization")
    void testWireFormatHeaderStructure() throws IOException {
        String content = "Hello";
        byte[] payload = content.getBytes(StandardCharsets.UTF_8);
        Packet packet = Packet.of(MessageType.CHAT_MESSAGE, 0x0102030405060708L, payload);

        byte[] encoded = encoder.encodeToBytes(packet);

        // Header size (14) + payload size (5) = 19 bytes
        assertEquals(14 + payload.length, encoded.length);

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(encoded));
        // 1. Version (1 byte)
        assertEquals(1, dis.readByte());
        // 2. MessageType code (1 byte) -> CHAT_MESSAGE = 2
        assertEquals(MessageType.CHAT_MESSAGE.getCode(), dis.readByte());
        // 3. Payload length (4 bytes)
        assertEquals(5, dis.readInt());
        // 4. Request ID (8 bytes)
        assertEquals(0x0102030405060708L, dis.readLong());
        // 5. Payload
        byte[] readPayload = new byte[5];
        dis.readFully(readPayload);
        assertArrayEquals(payload, readPayload);
    }

    @Test
    @DisplayName("Verify encoding empty payload produces exact 14-byte header with 0 payload length")
    void testEncodeEmptyPayload() throws IOException {
        Packet packet = Packet.empty(MessageType.PING, 100L);
        byte[] encoded = encoder.encodeToBytes(packet);

        assertEquals(14, encoded.length);

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(encoded));
        assertEquals(1, dis.readByte());
        assertEquals(MessageType.PING.getCode(), dis.readByte());
        assertEquals(0, dis.readInt());
        assertEquals(100L, dis.readLong());
    }

    @Test
    @DisplayName("Verify null packet or stream throws IllegalArgumentException or NullPointerException")
    void testNullValidation() {
        assertThrows(NullPointerException.class, () -> encoder.encode(null, System.out));
        assertThrows(NullPointerException.class, () -> encoder.encode(Packet.empty(MessageType.PING, 1L), null));
    }
}
