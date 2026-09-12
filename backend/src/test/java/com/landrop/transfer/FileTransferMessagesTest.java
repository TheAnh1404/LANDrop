package com.landrop.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.transfer.messages.FileOfferMessage;
import com.landrop.transfer.messages.FileRequestMessage;
import com.landrop.transfer.messages.TransferAckMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FileTransferMessagesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void testFileOfferMessageSerialization() throws Exception {
        FileOfferMessage msg = new FileOfferMessage("uuid-123", "report.pdf", 1024L, "peer-a", "Alice");
        byte[] bytes = MAPPER.writeValueAsBytes(msg);

        FileOfferMessage deserialized = MAPPER.readValue(bytes, FileOfferMessage.class);
        assertEquals("uuid-123", deserialized.getTransferId());
        assertEquals("report.pdf", deserialized.getFileName());
        assertEquals(1024L, deserialized.getFileSize());
        assertEquals("peer-a", deserialized.getSenderPeerId());
        assertEquals("Alice", deserialized.getSenderDisplayName());
    }

    @Test
    void testFileRequestMessageSerialization() throws Exception {
        FileRequestMessage accept = FileRequestMessage.accept("uuid-456");
        byte[] bytes = MAPPER.writeValueAsBytes(accept);

        FileRequestMessage deserialized = MAPPER.readValue(bytes, FileRequestMessage.class);
        assertEquals("uuid-456", deserialized.getTransferId());
        assertTrue(deserialized.isAccepted());

        FileRequestMessage reject = FileRequestMessage.reject("uuid-456", "User declined");
        byte[] rejectBytes = MAPPER.writeValueAsBytes(reject);

        FileRequestMessage deserializedReject = MAPPER.readValue(rejectBytes, FileRequestMessage.class);
        assertFalse(deserializedReject.isAccepted());
        assertEquals("User declined", deserializedReject.getReason());
    }

    @Test
    void testTransferAckMessageSerialization() throws Exception {
        TransferAckMessage ack = TransferAckMessage.success("uuid-789", 5000L);
        byte[] bytes = MAPPER.writeValueAsBytes(ack);

        TransferAckMessage deserialized = MAPPER.readValue(bytes, TransferAckMessage.class);
        assertEquals("uuid-789", deserialized.getTransferId());
        assertTrue(deserialized.isSuccess());
        assertEquals(5000L, deserialized.getTransferredBytes());
    }
}
