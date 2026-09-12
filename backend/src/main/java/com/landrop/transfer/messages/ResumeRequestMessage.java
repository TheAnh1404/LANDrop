package com.landrop.transfer.messages;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.protocol.ProtocolException;
import com.landrop.transfer.FileChunk;
import com.landrop.transfer.FileTransfer;
import com.landrop.transfer.FileTransferUtils;

import java.io.IOException;
import java.util.Base64;
import java.util.BitSet;
import java.util.Objects;

/**
 * Control message sent by the receiving peer to request resuming an interrupted file transfer.
 * Encoded as JSON payload in RESUME_REQUEST (type 18) packet.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResumeRequestMessage {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @JsonProperty("transferId")
    private String transferId;

    @JsonProperty("fileName")
    private String fileName;

    @JsonProperty("fileSize")
    private long fileSize;

    @JsonProperty("chunkSize")
    private int chunkSize;

    @JsonProperty("totalChunks")
    private int totalChunks;

    @JsonProperty("fileSha256")
    private String fileSha256;

    @JsonProperty("completedChunks")
    private String completedChunks;

    public ResumeRequestMessage() {
    }

    public ResumeRequestMessage(
            String transferId,
            String fileName,
            long fileSize,
            int chunkSize,
            int totalChunks,
            String fileSha256,
            String completedChunks) {
        this.transferId = transferId;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.chunkSize = chunkSize;
        this.totalChunks = totalChunks;
        this.fileSha256 = fileSha256 != null ? fileSha256.trim().toLowerCase() : "";
        this.completedChunks = completedChunks != null ? completedChunks : "";
    }

    public static ResumeRequestMessage of(FileTransfer transfer, BitSet completedChunksBitSet) {
        Objects.requireNonNull(transfer, "transfer cannot be null");
        byte[] bytes = completedChunksBitSet != null ? completedChunksBitSet.toByteArray() : new byte[0];
        String base64 = Base64.getEncoder().encodeToString(bytes);
        return new ResumeRequestMessage(
                transfer.getTransferId(),
                transfer.getFileName(),
                transfer.getFileSize(),
                transfer.getChunkSize(),
                transfer.getTotalChunks(),
                transfer.getFileSha256(),
                base64
        );
    }

    public String getTransferId() {
        return transferId;
    }

    public void setTransferId(String transferId) {
        this.transferId = transferId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public void setTotalChunks(int totalChunks) {
        this.totalChunks = totalChunks;
    }

    public String getFileSha256() {
        return fileSha256;
    }

    public void setFileSha256(String fileSha256) {
        this.fileSha256 = fileSha256 != null ? fileSha256.trim().toLowerCase() : "";
    }

    public String getCompletedChunks() {
        return completedChunks;
    }

    public void setCompletedChunks(String completedChunks) {
        this.completedChunks = completedChunks != null ? completedChunks : "";
    }

    public BitSet getCompletedChunksBitSet() {
        if (completedChunks == null || completedChunks.isBlank()) {
            return new BitSet(totalChunks);
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(completedChunks);
            return BitSet.valueOf(bytes);
        } catch (IllegalArgumentException e) {
            return new BitSet(totalChunks);
        }
    }

    public void validate() throws ProtocolException {
        if (transferId == null || transferId.isBlank()) {
            throw new ProtocolException("ResumeRequestMessage transferId cannot be null or blank");
        }
        if (fileSize < 0) {
            throw new ProtocolException("ResumeRequestMessage fileSize cannot be negative: " + fileSize);
        }
        if (chunkSize <= 0) {
            throw new ProtocolException("ResumeRequestMessage chunkSize must be positive: " + chunkSize);
        }
        if (totalChunks < 0) {
            throw new ProtocolException("ResumeRequestMessage totalChunks cannot be negative: " + totalChunks);
        }
        int expectedTotal = FileChunk.calculateTotalChunks(fileSize, chunkSize);
        if (totalChunks != expectedTotal) {
            throw new ProtocolException("ResumeRequestMessage totalChunks mismatch: declared " +
                    totalChunks + ", expected " + expectedTotal + " for fileSize " + fileSize);
        }
        if (fileSize > 0 && (fileSha256 == null || !FileTransferUtils.isValidSha256(fileSha256))) {
            throw new ProtocolException("ResumeRequestMessage fileSha256 is invalid or malformed: " + fileSha256);
        }
    }

    public byte[] toBytes() throws IOException {
        return OBJECT_MAPPER.writeValueAsBytes(this);
    }

    public static ResumeRequestMessage fromBytes(byte[] payload) throws IOException {
        return OBJECT_MAPPER.readValue(payload, ResumeRequestMessage.class);
    }
}
