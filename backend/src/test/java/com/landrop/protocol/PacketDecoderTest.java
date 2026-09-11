package com.landrop.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PacketDecoderTest {

    private final PacketEncoder encoder = new PacketEncoder();
    private final PacketDecoder decoder = new PacketDecoder();

    @Test
    @DisplayName("Test 1: Encode/Decode packet round-trip matches all fields exactly")
    void testEncodeDecodeRoundTrip() throws IOException {
        String content = "Phase 2 Protocol Test";
        Packet original = Packet.text(MessageType.CHAT_MESSAGE, 123456789L, content);

        byte[] encoded = encoder.encodeToBytes(original);
        Packet decoded = decoder.decodeFromBytes(encoded);

        assertNotNull(decoded);
        assertEquals(original.getVersion(), decoded.getVersion());
        assertEquals(original.getType(), decoded.getType());
        assertEquals(original.getRequestId(), decoded.getRequestId());
        assertEquals(original.getPayloadLength(), decoded.getPayloadLength());
        assertArrayEquals(original.getPayload(), decoded.getPayload());
        assertEquals(content, decoded.payloadAsString());
    }

    @Test
    @DisplayName("Test 2: Empty payload packet (PING) encodes and decodes successfully")
    void testEmptyPayload() throws IOException {
        Packet original = Packet.empty(MessageType.PING, 42L);

        byte[] encoded = encoder.encodeToBytes(original);
        Packet decoded = decoder.decodeFromBytes(encoded);

        assertNotNull(decoded);
        assertEquals(MessageType.PING, decoded.getType());
        assertEquals(42L, decoded.getRequestId());
        assertEquals(0, decoded.getPayloadLength());
        assertEquals(0, decoded.getPayload().length);
        assertEquals("", decoded.payloadAsString());
    }

    @Test
    @DisplayName("Test 3: UTF-8 payload with Vietnamese characters preserved without loss")
    void testUtf8VietnamesePayload() throws IOException {
        String vietnameseText = "Xin chào LANDrop - Chia sẻ file mạng cục bộ nhanh chóng! 🚀";
        Packet original = Packet.text(MessageType.CHAT_MESSAGE, 77L, vietnameseText);

        byte[] encoded = encoder.encodeToBytes(original);
        Packet decoded = decoder.decodeFromBytes(encoded);

        assertNotNull(decoded);
        assertEquals(vietnameseText, decoded.payloadAsString());
        assertEquals(vietnameseText.getBytes(StandardCharsets.UTF_8).length, decoded.getPayloadLength());
    }

    @Test
    @DisplayName("Test 4: Multiple sequential packets in single stream are decoded in order")
    void testMultiplePacketsInStream() throws IOException {
        Packet p1 = Packet.text(MessageType.HELLO, 1L, "Hello from Peer A");
        Packet p2 = Packet.text(MessageType.CHAT_MESSAGE, 2L, "How are you?");
        Packet p3 = Packet.empty(MessageType.PING, 3L);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        encoder.encode(p1, baos);
        encoder.encode(p2, baos);
        encoder.encode(p3, baos);

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());

        Packet d1 = decoder.decode(bais);
        assertNotNull(d1);
        assertEquals(p1, d1);

        Packet d2 = decoder.decode(bais);
        assertNotNull(d2);
        assertEquals(p2, d2);

        Packet d3 = decoder.decode(bais);
        assertNotNull(d3);
        assertEquals(p3, d3);

        // After all packets read, next read should return null (clean EOF)
        assertNull(decoder.decode(bais));
    }

    @Test
    @DisplayName("Test 5a: Invalid negative payload length is rejected with ProtocolException")
    void testNegativePayloadLength() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeByte(1);                       // version = 1
        dos.writeByte(MessageType.PING.getCode()); // type = PING
        dos.writeInt(-10);                      // negative payload length
        dos.writeLong(1L);                      // requestId = 1
        dos.flush();

        ProtocolException ex = assertThrows(ProtocolException.class, () ->
                decoder.decodeFromBytes(baos.toByteArray()));
        assertEquals("Invalid negative payload length: -10", ex.getMessage());
    }

    @Test
    @DisplayName("Test 5b: Payload length exceeding MAX_PAYLOAD_SIZE is rejected with ProtocolException")
    void testPayloadLengthExceedsLimit() throws IOException {
        PacketDecoder smallDecoder = new PacketDecoder(100); // 100-byte limit

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeByte(1);                              // version = 1
        dos.writeByte(MessageType.CHAT_MESSAGE.getCode()); // type = CHAT_MESSAGE
        dos.writeInt(101);                             // payload length > 100
        dos.writeLong(1L);                             // requestId = 1
        dos.write(new byte[101]);
        dos.flush();

        ProtocolException ex = assertThrows(ProtocolException.class, () ->
                smallDecoder.decodeFromBytes(baos.toByteArray()));
        assertEquals("Payload length 101 exceeds maximum allowed limit of 100 bytes", ex.getMessage());
    }

    @Test
    @DisplayName("Test 6: Unknown message type code is rejected with ProtocolException")
    void testUnknownMessageTypeCode() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeByte(1);     // version = 1
        dos.writeByte(99);    // unknown type code = 99
        dos.writeInt(0);      // length = 0
        dos.writeLong(1L);    // requestId = 1
        dos.flush();

        ProtocolException ex = assertThrows(ProtocolException.class, () ->
                decoder.decodeFromBytes(baos.toByteArray()));
        assertEquals("Unknown message type code: 99", ex.getMessage());
    }

    @Test
    @DisplayName("Test 7: Unsupported protocol version is rejected with ProtocolException")
    void testUnsupportedProtocolVersion() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeByte(2);                       // version = 2 (unsupported)
        dos.writeByte(MessageType.PING.getCode());
        dos.writeInt(0);
        dos.writeLong(1L);
        dos.flush();

        ProtocolException ex = assertThrows(ProtocolException.class, () ->
                decoder.decodeFromBytes(baos.toByteArray()));
        assertEquals("Unsupported protocol version: 2 (expected: 1)", ex.getMessage());
    }

    @Test
    @DisplayName("Test 8: Incomplete packet header or payload causes ProtocolException")
    void testTruncatedPacket() throws IOException {
        // Only 5 bytes written instead of 14 bytes header
        byte[] truncatedHeader = new byte[]{1, 2, 0, 0, 0};
        assertThrows(ProtocolException.class, () -> decoder.decodeFromBytes(truncatedHeader));

        // Header claims 10 bytes payload, but only 3 bytes provided
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeByte(1);
        dos.writeByte(MessageType.CHAT_MESSAGE.getCode());
        dos.writeInt(10);
        dos.writeLong(1L);
        dos.write(new byte[]{1, 2, 3}); // only 3 bytes
        dos.flush();

        assertThrows(ProtocolException.class, () -> decoder.decodeFromBytes(baos.toByteArray()));
    }

    @Test
    @DisplayName("Test 9: Empty stream cleanly returns null")
    void testEmptyStream() throws IOException {
        assertNull(decoder.decode(new ByteArrayInputStream(new byte[0])));
    }
}
