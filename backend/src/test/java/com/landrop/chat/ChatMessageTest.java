package com.landrop.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMessageTest {

    @Test
    @DisplayName("Verify ChatMessage creation and field getters")
    void testChatMessageCreation() {
        long timestamp = 1700000000000L;
        ChatMessage msg = new ChatMessage(10L, "Peer A", "Hello Peer B", timestamp);

        assertEquals(10L, msg.requestId());
        assertEquals(10L, msg.getRequestId());
        assertEquals("Peer A", msg.sender());
        assertEquals("Peer A", msg.getSender());
        assertEquals("Hello Peer B", msg.content());
        assertEquals("Hello Peer B", msg.getContent());
        assertEquals(timestamp, msg.timestamp());
        assertEquals(timestamp, msg.getTimestamp());
    }

    @Test
    @DisplayName("Verify ChatMessage convenience constructor generates current timestamp")
    void testConvenienceConstructor() {
        long before = System.currentTimeMillis();
        ChatMessage msg = new ChatMessage(42L, "Alice", "Test");
        long after = System.currentTimeMillis();

        assertEquals(42L, msg.requestId());
        assertEquals("Alice", msg.sender());
        assertEquals("Test", msg.content());
        assertTrue(msg.timestamp() >= before && msg.timestamp() <= after);
    }

    @Test
    @DisplayName("Verify ChatMessage rejects null sender or content")
    void testNullValidation() {
        assertThrows(NullPointerException.class, () -> new ChatMessage(1L, null, "Hello"));
        assertThrows(NullPointerException.class, () -> new ChatMessage(1L, "Peer A", null));
    }

    @Test
    @DisplayName("Verify ChatMessage toString contains all attributes")
    void testToString() {
        ChatMessage msg = new ChatMessage(99L, "SenderX", "ContentY", 12345L);
        String str = msg.toString();

        assertNotNull(str);
        assertTrue(str.contains("99"));
        assertTrue(str.contains("SenderX"));
        assertTrue(str.contains("ContentY"));
        assertTrue(str.contains("12345"));
    }
}
