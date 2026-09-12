package com.landrop.transfer.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.protocol.ProtocolException;

import java.io.IOException;
import java.util.Objects;

/**
 * Control message payload for requesting a specific chunk from the sender (CHUNK_REQUEST, code 7).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChunkRequestMessage {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String transferId;
    private final int chunkIndex;

    @JsonCreator
    public ChunkRequestMessage(
            @JsonProperty("transferId") String transferId,
            @JsonProperty("chunkIndex") int chunkIndex) {
        this.transferId = Objects.requireNonNull(transferId, "transferId cannot be null");
        this.chunkIndex = chunkIndex;
    }

    public static ChunkRequestMessage of(String transferId, int chunkIndex) {
        return new ChunkRequestMessage(transferId, chunkIndex);
    }

    public void validate() throws ProtocolException {
        if (transferId == null || transferId.isBlank()) {
            throw new ProtocolException("Invalid transferId in CHUNK_REQUEST: null or blank");
        }
        if (chunkIndex < 0) {
            throw new ProtocolException("Invalid chunkIndex in CHUNK_REQUEST: " + chunkIndex + " (cannot be negative)");
        }
    }

    public String getTransferId() {
        return transferId;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public byte[] toBytes() throws IOException {
        return MAPPER.writeValueAsBytes(this);
    }

    public static ChunkRequestMessage fromBytes(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length == 0) {
            throw new ProtocolException("Empty payload for CHUNK_REQUEST");
        }
        try {
            ChunkRequestMessage msg = MAPPER.readValue(payload, ChunkRequestMessage.class);
            msg.validate();
            return msg;
        } catch (IOException e) {
            throw new ProtocolException("Failed to parse CHUNK_REQUEST JSON payload", e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChunkRequestMessage that)) return false;
        return chunkIndex == that.chunkIndex && transferId.equals(that.transferId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transferId, chunkIndex);
    }

    @Override
    public String toString() {
        return "ChunkRequestMessage{" +
                "transferId='" + transferId + '\'' +
                ", chunkIndex=" + chunkIndex +
                '}';
    }
}
