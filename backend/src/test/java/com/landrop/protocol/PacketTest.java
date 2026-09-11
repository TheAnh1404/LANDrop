package com.landrop.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketTest {

    @Test
    @DisplayName("Verify Packet creation with factory method of(type, requestId, payload)")
    void testPacketCreation() {
        byte[] payload = "test payload".getBytes(StandardCharsets.UTF_8);
        Packet packet = Packet.of(MessageType.CHAT_MESSAGE, 42L, payload);

        assertEquals(Packet.CURRENT_VERSION, packet.getVersion());
        assertEquals(MessageType.CHAT_MESSAGE, packet.getType());
        assertEquals(42L, packet.getRequestId());
        assertEquals(payload.length, packet.getPayloadLength());
        assertArrayEquals(payload, packet.getPayload());
        assertEquals("test payload", packet.payloadAsString());
    }

    @Test
    @DisplayName("Verify Packet.text() helper method with UTF-8 string")
    void testPacketTextHelper() {
        String message = "Xin chào LANDrop";
        Packet packet = Packet.text(MessageType.HELLO, 1L, message);

        assertEquals(Packet.CURRENT_VERSION, packet.getVersion());
        assertEquals(MessageType.HELLO, packet.getType());
        assertEquals(1L, packet.getRequestId());
        assertEquals(message, packet.payloadAsString());
        assertEquals(message.getBytes(StandardCharsets.UTF_8).length, packet.getPayloadLength());
    }

    @Test
    @DisplayName("Verify Packet.empty() helper creates packet with zero payload length")
    void testPacketEmptyHelper() {
        Packet packet = Packet.empty(MessageType.PING, 99L);

        assertEquals(MessageType.PING, packet.getType());
        assertEquals(99L, packet.getRequestId());
        assertEquals(0, packet.getPayloadLength());
        assertEquals(0, packet.getPayload().length);
        assertEquals("", packet.payloadAsString());
    }

    @Test
    @DisplayName("Verify null payload is safely converted to empty byte array")
    void testNullPayloadHandling() {
        Packet packet = Packet.of(MessageType.PONG, 5L, null);

        assertEquals(0, packet.getPayloadLength());
        assertNotNull(packet.getPayload());
        assertEquals(0, packet.getPayload().length);
    }

    @Test
    @DisplayName("Verify payload defensive copying ensures immutability")
    void testPayloadImmutability() {
        byte[] original = new byte[]{1, 2, 3};
        Packet packet = Packet.of(MessageType.HELLO, 1L, original);

        // Mutate original array
        original[0] = 99;
        assertEquals(1, packet.getPayload()[0], "Internal packet payload should not be affected by external mutation");

        // Mutate returned array
        byte[] retrieved = packet.getPayload();
        retrieved[1] = 88;
        assertEquals(2, packet.getPayload()[1], "Internal packet payload should not be affected by getter mutation");
    }

    @Test
    @DisplayName("Verify invalid version throws IllegalArgumentException")
    void testInvalidVersion() {
        assertThrows(IllegalArgumentException.class, () ->
                Packet.of(0, MessageType.HELLO, 1L, new byte[0]));
        assertThrows(IllegalArgumentException.class, () ->
                Packet.of(256, MessageType.HELLO, 1L, new byte[0]));
    }

    @Test
    @DisplayName("Verify null MessageType throws NullPointerException")
    void testNullMessageType() {
        assertThrows(NullPointerException.class, () ->
                Packet.of(null, 1L, new byte[0]));
    }

    @Test
    @DisplayName("Verify equals, hashCode, and toString")
    void testEqualsAndHashCode() {
        Packet p1 = Packet.text(MessageType.HELLO, 1L, "Test");
        Packet p2 = Packet.text(MessageType.HELLO, 1L, "Test");
        Packet p3 = Packet.text(MessageType.HELLO, 2L, "Test");

        assertEquals(p1, p2);
        assertEquals(p1.hashCode(), p2.hashCode());
        assertNotEquals(p1, p3);
        assertTrue(p1.toString().contains("HELLO"));
        assertTrue(p1.toString().contains("Test"));
    }
}
