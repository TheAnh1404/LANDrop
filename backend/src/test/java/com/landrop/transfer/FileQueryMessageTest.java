package com.landrop.transfer;

import com.landrop.protocol.ProtocolException;
import com.landrop.transfer.messages.FileQueryMessage;
import com.landrop.transfer.messages.FileQueryResponseMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

public class FileQueryMessageTest {

    private static final String VALID_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    @DisplayName("Verify FileQueryMessage serialization and deserialization")
    void testFileQueryMessageRoundTrip() throws IOException, ProtocolException {
        FileQueryMessage query = new FileQueryMessage(
                "q-12345",
                "movie.mp4",
                104857600L,
                VALID_SHA256,
                "peer-1",
                "Peer 1"
        );
        query.validate();

        byte[] bytes = query.toBytes();
        assertNotNull(bytes);

        FileQueryMessage decoded = FileQueryMessage.fromBytes(bytes);
        assertEquals("q-12345", decoded.getQueryId());
        assertEquals("movie.mp4", decoded.getFileName());
        assertEquals(104857600L, decoded.getFileSize());
        assertEquals(VALID_SHA256, decoded.getFileSha256());
        assertEquals("peer-1", decoded.getRequesterPeerId());
        assertEquals("Peer 1", decoded.getRequesterPeerName());
        assertEquals(query, decoded);
    }

    @Test
    @DisplayName("Verify FileQueryMessage validation checks")
    void testFileQueryMessageValidation() {
        // Missing queryId
        FileQueryMessage q1 = new FileQueryMessage("", "file.txt", 100L, VALID_SHA256, "p1", "Peer 1");
        assertThrows(ProtocolException.class, q1::validate);

        // Missing fileName
        FileQueryMessage q2 = new FileQueryMessage("qid", "", 100L, VALID_SHA256, "p1", "Peer 1");
        assertThrows(ProtocolException.class, q2::validate);

        // Negative fileSize
        FileQueryMessage q3 = new FileQueryMessage("qid", "file.txt", -1L, VALID_SHA256, "p1", "Peer 1");
        assertThrows(ProtocolException.class, q3::validate);

        // Invalid SHA-256
        FileQueryMessage q4 = new FileQueryMessage("qid", "file.txt", 100L, "bad-hash", "p1", "Peer 1");
        assertThrows(ProtocolException.class, q4::validate);
    }

    @Test
    @DisplayName("Verify FileQueryResponseMessage available factory and round-trip")
    void testFileQueryResponseMessageAvailable() throws IOException, ProtocolException {
        FileQueryResponseMessage resp = FileQueryResponseMessage.available(
                "q-12345",
                "movie.mp4",
                104857600L,
                1048576,
                100,
                VALID_SHA256,
                "peer-2",
                "Peer 2"
        );
        resp.validate();
        assertTrue(resp.isAvailable());
        assertNull(resp.getReason());

        byte[] bytes = resp.toBytes();
        FileQueryResponseMessage decoded = FileQueryResponseMessage.fromBytes(bytes);
        assertTrue(decoded.isAvailable());
        assertEquals("q-12345", decoded.getQueryId());
        assertEquals("movie.mp4", decoded.getFileName());
        assertEquals(104857600L, decoded.getFileSize());
        assertEquals(1048576, decoded.getChunkSize());
        assertEquals(100, decoded.getTotalChunks());
        assertEquals(VALID_SHA256, decoded.getFileSha256());
        assertEquals(resp, decoded);
    }

    @Test
    @DisplayName("Verify FileQueryResponseMessage unavailable factory")
    void testFileQueryResponseMessageUnavailable() throws IOException, ProtocolException {
        FileQueryResponseMessage resp = FileQueryResponseMessage.unavailable(
                "q-12345",
                FileQueryResponseMessage.REASON_FILE_NOT_FOUND
        );
        resp.validate();
        assertFalse(resp.isAvailable());
        assertEquals(FileQueryResponseMessage.REASON_FILE_NOT_FOUND, resp.getReason());

        byte[] bytes = resp.toBytes();
        FileQueryResponseMessage decoded = FileQueryResponseMessage.fromBytes(bytes);
        assertFalse(decoded.isAvailable());
        assertEquals(FileQueryResponseMessage.REASON_FILE_NOT_FOUND, decoded.getReason());
    }
}
