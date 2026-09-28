package com.landrop.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClipboardMessageTest {

    @Test
    void testEncodeAndDecodePayload() {
        long reqId = 987654321L;
        String sender = "Máy Tính Dell XPS (Nguyễn Thế Anh)";
        String text = "https://github.com/TheAnh1404/LANDrop - Mã OTP xác thực: 849201. Kèm ký tự đặc biệt: 🔥🚀✨";
        long now = System.currentTimeMillis();

        ClipboardMessage original = new ClipboardMessage(reqId, sender, text, now);
        byte[] payload = original.encodePayload();

        assertNotNull(payload);
        assertTrue(payload.length > 20);

        ClipboardMessage decoded = ClipboardMessage.decodePayload(reqId, payload);
        assertEquals(reqId, decoded.requestId());
        assertEquals(sender, decoded.sender());
        assertEquals(text, decoded.text());
        assertEquals(now, decoded.timestamp());
    }

    @Test
    void testMalformedPayloadThrows() {
        assertThrows(IllegalArgumentException.class, () -> ClipboardMessage.decodePayload(1L, null));
        assertThrows(IllegalArgumentException.class, () -> ClipboardMessage.decodePayload(1L, new byte[5]));
    }
}
