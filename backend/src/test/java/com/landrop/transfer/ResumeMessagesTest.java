package com.landrop.transfer;

import com.landrop.protocol.ProtocolException;
import com.landrop.transfer.messages.ResumeRequestMessage;
import com.landrop.transfer.messages.ResumeResponseMessage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.*;

public class ResumeMessagesTest {

    @Test
    void testResumeRequestSerializationAndBitSetRoundtrip() throws Exception {
        BitSet bits = new BitSet();
        bits.set(0);
        bits.set(1);
        bits.set(4);
        bits.set(7);
        bits.set(42);

        String sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        FileTransfer transfer = new FileTransfer(
                "transfer-123",
                "test.dat",
                50 * 1024 * 1024L, // 50 MB
                FileTransferDirection.INCOMING,
                "peer-1",
                "Peer One",
                Path.of("/tmp/test.dat"),
                1024 * 1024,
                50,
                sha256
        );

        ResumeRequestMessage msg = ResumeRequestMessage.of(transfer, bits);
        assertEquals("transfer-123", msg.getTransferId());
        assertEquals("test.dat", msg.getFileName());
        assertEquals(50 * 1024 * 1024L, msg.getFileSize());
        assertEquals(1024 * 1024, msg.getChunkSize());
        assertEquals(50, msg.getTotalChunks());
        assertEquals(sha256, msg.getFileSha256());

        byte[] serialized = msg.toBytes();
        assertNotNull(serialized);

        ResumeRequestMessage decoded = ResumeRequestMessage.fromBytes(serialized);
        assertEquals(msg.getTransferId(), decoded.getTransferId());
        assertEquals(msg.getFileSize(), decoded.getFileSize());
        assertEquals(msg.getChunkSize(), decoded.getChunkSize());
        assertEquals(msg.getTotalChunks(), decoded.getTotalChunks());
        assertEquals(msg.getFileSha256(), decoded.getFileSha256());

        BitSet recoveredBits = decoded.getCompletedChunksBitSet();
        assertTrue(recoveredBits.get(0));
        assertTrue(recoveredBits.get(1));
        assertFalse(recoveredBits.get(2));
        assertFalse(recoveredBits.get(3));
        assertTrue(recoveredBits.get(4));
        assertTrue(recoveredBits.get(7));
        assertTrue(recoveredBits.get(42));
        assertFalse(recoveredBits.get(43));
        assertEquals(5, recoveredBits.cardinality());
    }

    @Test
    void testResumeRequestValidation() {
        String validHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        // Valid
        ResumeRequestMessage valid = new ResumeRequestMessage(
                "tx-1", "file.txt", 1000, 500, 2, validHash, ""
        );
        assertDoesNotThrow(valid::validate);

        // Blank transfer ID
        ResumeRequestMessage emptyId = new ResumeRequestMessage(
                " ", "file.txt", 1000, 500, 2, validHash, ""
        );
        assertThrows(ProtocolException.class, emptyId::validate);

        // Negative file size
        ResumeRequestMessage negSize = new ResumeRequestMessage(
                "tx-1", "file.txt", -1, 500, 2, validHash, ""
        );
        assertThrows(ProtocolException.class, negSize::validate);

        // Invalid chunk size
        ResumeRequestMessage zeroChunk = new ResumeRequestMessage(
                "tx-1", "file.txt", 1000, 0, 2, validHash, ""
        );
        assertThrows(ProtocolException.class, zeroChunk::validate);

        // Chunk count mismatch
        ResumeRequestMessage badChunks = new ResumeRequestMessage(
                "tx-1", "file.txt", 1000, 500, 5, validHash, ""
        );
        assertThrows(ProtocolException.class, badChunks::validate);

        // Malformed SHA-256
        ResumeRequestMessage badSha = new ResumeRequestMessage(
                "tx-1", "file.txt", 1000, 500, 2, "not-a-valid-sha", ""
        );
        assertThrows(ProtocolException.class, badSha::validate);
    }

    @Test
    void testResumeResponseAcceptAndReject() throws Exception {
        ResumeResponseMessage accept = ResumeResponseMessage.accept("tx-999");
        assertTrue(accept.isAccepted());
        assertEquals("tx-999", accept.getTransferId());
        assertNull(accept.getReason());

        byte[] acceptBytes = accept.toBytes();
        ResumeResponseMessage decodedAccept = ResumeResponseMessage.fromBytes(acceptBytes);
        assertTrue(decodedAccept.isAccepted());
        assertEquals("tx-999", decodedAccept.getTransferId());

        ResumeResponseMessage reject = ResumeResponseMessage.reject("tx-999", ResumeResponseMessage.REASON_METADATA_MISMATCH);
        assertFalse(reject.isAccepted());
        assertEquals("tx-999", reject.getTransferId());
        assertEquals(ResumeResponseMessage.REASON_METADATA_MISMATCH, reject.getReason());

        byte[] rejectBytes = reject.toBytes();
        ResumeResponseMessage decodedReject = ResumeResponseMessage.fromBytes(rejectBytes);
        assertFalse(decodedReject.isAccepted());
        assertEquals(ResumeResponseMessage.REASON_METADATA_MISMATCH, decodedReject.getReason());
    }
}
