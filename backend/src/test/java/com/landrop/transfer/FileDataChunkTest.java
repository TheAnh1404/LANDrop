package com.landrop.transfer;

import com.landrop.protocol.ProtocolException;
import com.landrop.transfer.messages.FileDataChunk;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FileDataChunkTest {

    @Test
    void testEncodeAndDecode_roundtrip() throws ProtocolException {
        String transferId = UUID.randomUUID().toString();
        long offset = 123456L;
        byte[] data = "Hello, binary file chunk payload!".getBytes(StandardCharsets.UTF_8);

        byte[] encoded = FileDataChunk.encode(transferId, offset, data, data.length);
        assertNotNull(encoded);

        FileDataChunk decoded = FileDataChunk.decode(encoded);
        assertEquals(transferId, decoded.transferId());
        assertEquals(offset, decoded.offset());
        assertArrayEquals(data, decoded.data());
    }

    @Test
    void testEncodeAndDecode_zeroOffsetAndSlice() throws ProtocolException {
        String transferId = "simple-id";
        long offset = 0L;
        byte[] buffer = new byte[100];
        buffer[0] = 42;
        buffer[1] = 43;

        // Encode only first 2 bytes
        byte[] encoded = FileDataChunk.encode(transferId, offset, buffer, 2);
        FileDataChunk decoded = FileDataChunk.decode(encoded);

        assertEquals(transferId, decoded.transferId());
        assertEquals(0L, decoded.offset());
        assertEquals(2, decoded.data().length);
        assertEquals(42, decoded.data()[0]);
        assertEquals(43, decoded.data()[1]);
    }

    @Test
    void testDecode_corruptPayloadThrowsProtocolException() {
        assertThrows(ProtocolException.class, () -> FileDataChunk.decode(null));
        assertThrows(ProtocolException.class, () -> FileDataChunk.decode(new byte[5])); // too short
        assertThrows(ProtocolException.class, () -> FileDataChunk.decode(new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0})); // idLen = 0
    }

    @Test
    void testNegativeOffset_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> {
            FileDataChunk.encode("id", -1L, new byte[10], 10);
        });
    }
}
