package com.landrop.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.protocol.ProtocolException;
import com.landrop.transfer.messages.ChunkRequestMessage;
import com.landrop.transfer.messages.ChunkResponseMessage;
import com.landrop.transfer.messages.FileOfferMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ChunkMessagesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DUMMY_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    @DisplayName("FileOfferMessage serialization and validation with chunk & hash metadata")
    void testFileOfferMessageSerializationAndValidation() throws Exception {
        String transferId = "uuid-offer-1";
        String fileName = "project_plan.docx";
        long fileSize = 2500000L; // 2.5 MB
        int chunkSize = 1024 * 1024;
        int totalChunks = FileChunk.calculateTotalChunks(fileSize, chunkSize); // 3

        FileOfferMessage msg = new FileOfferMessage(
                transferId, fileName, fileSize, "peer-1", "Bob",
                chunkSize, totalChunks, DUMMY_SHA256
        );

        assertDoesNotThrow(msg::validate);
        assertEquals(chunkSize, msg.getChunkSize());
        assertEquals(3, msg.getTotalChunks());
        assertEquals(DUMMY_SHA256, msg.getFileSha256());

        byte[] json = MAPPER.writeValueAsBytes(msg);
        FileOfferMessage deserialized = MAPPER.readValue(json, FileOfferMessage.class);

        assertEquals(transferId, deserialized.getTransferId());
        assertEquals(fileName, deserialized.getFileName());
        assertEquals(fileSize, deserialized.getFileSize());
        assertEquals(chunkSize, deserialized.getChunkSize());
        assertEquals(3, deserialized.getTotalChunks());
        assertEquals(DUMMY_SHA256, deserialized.getFileSha256());
        assertDoesNotThrow(deserialized::validate);
    }

    @Test
    @DisplayName("FileOfferMessage validation rejects invalid fields")
    void testFileOfferMessageValidationFailures() {
        // Null/empty transferId
        FileOfferMessage badId = new FileOfferMessage("", "a.txt", 100L, "p1", "Bob", 1024, 1, DUMMY_SHA256);
        assertThrows(ProtocolException.class, badId::validate);

        // Negative size
        FileOfferMessage badSize = new FileOfferMessage("id", "a.txt", -50L, "p1", "Bob", 1024, 1, DUMMY_SHA256);
        assertThrows(ProtocolException.class, badSize::validate);

        // Total chunks mismatch (claims 10 chunks for 100 bytes with 1KB chunk)
        FileOfferMessage badChunks = new FileOfferMessage("id", "a.txt", 100L, "p1", "Bob", 1024, 10, DUMMY_SHA256);
        assertThrows(ProtocolException.class, badChunks::validate);

        // Zero-byte file with non-zero chunks
        FileOfferMessage badZero = new FileOfferMessage("id", "a.txt", 0L, "p1", "Bob", 1024, 1, DUMMY_SHA256);
        assertThrows(ProtocolException.class, badZero::validate);

        // Invalid hash format
        FileOfferMessage badHash = new FileOfferMessage("id", "a.txt", 100L, "p1", "Bob", 1024, 1, "invalid_hash_str");
        assertThrows(ProtocolException.class, badHash::validate);
    }

    @Test
    @DisplayName("ChunkRequestMessage serialization and round-trip")
    void testChunkRequestMessage() throws Exception {
        ChunkRequestMessage req = ChunkRequestMessage.of("uuid-transfer-1", 4);
        assertDoesNotThrow(req::validate);
        assertEquals("uuid-transfer-1", req.getTransferId());
        assertEquals(4, req.getChunkIndex());

        byte[] payload = req.toBytes();
        ChunkRequestMessage decoded = ChunkRequestMessage.fromBytes(payload);

        assertEquals(req, decoded);
        assertEquals(4, decoded.getChunkIndex());
        assertEquals("uuid-transfer-1", decoded.getTransferId());
    }

    @Test
    @DisplayName("ChunkRequestMessage rejects invalid fields")
    void testChunkRequestMessageValidationFailures() {
        ChunkRequestMessage badId = new ChunkRequestMessage("", 0);
        assertThrows(ProtocolException.class, badId::validate);

        ChunkRequestMessage negIndex = new ChunkRequestMessage("uuid", -1);
        assertThrows(ProtocolException.class, negIndex::validate);

        assertThrows(ProtocolException.class, () -> ChunkRequestMessage.fromBytes(new byte[0]));
        assertThrows(ProtocolException.class, () -> ChunkRequestMessage.fromBytes("{bad json".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("ChunkResponseMessage binary encoding, decoding, and round-trip")
    void testChunkResponseMessageRoundTrip() throws Exception {
        String transferId = "test-transfer-uuid";
        int chunkIndex = 2;
        byte[] chunkData = "Phase 7 Chunk Response Binary Data".getBytes(StandardCharsets.UTF_8);

        byte[] encoded = ChunkResponseMessage.encode(transferId, chunkIndex, chunkData, chunkData.length);
        assertNotNull(encoded);

        ChunkResponseMessage decoded = ChunkResponseMessage.decode(encoded);
        assertEquals(transferId, decoded.transferId());
        assertEquals(chunkIndex, decoded.chunkIndex());
        assertEquals(chunkData.length, decoded.chunkLength());
        assertArrayEquals(chunkData, decoded.data());
    }

    @Test
    @DisplayName("ChunkResponseMessage rejects corrupted or truncated payloads")
    void testChunkResponseMessageValidationFailures() {
        // Too short (< 10 bytes)
        assertThrows(ProtocolException.class, () -> ChunkResponseMessage.decode(new byte[]{1, 2, 3}));

        // Truncated data: header says 10 bytes, but only 3 provided
        String transferId = "id";
        byte[] valid = ChunkResponseMessage.encode(transferId, 0, new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, 10);
        byte[] truncated = new byte[valid.length - 5];
        System.arraycopy(valid, 0, truncated, 0, truncated.length);

        assertThrows(ProtocolException.class, () -> ChunkResponseMessage.decode(truncated));
    }
}
