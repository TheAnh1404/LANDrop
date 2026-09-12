package com.landrop.transfer.messages;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.protocol.ProtocolException;
import com.landrop.transfer.FileTransferUtils;

import java.io.IOException;
import java.util.Objects;

/**
 * Control message response sent by a peer indicating file availability for multi-peer download.
 * Encoded as JSON payload in FILE_QUERY_RESPONSE (type 21) packet.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FileQueryResponseMessage {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static final String REASON_FILE_NOT_FOUND = "FILE_NOT_FOUND";
    public static final String REASON_FILE_SIZE_MISMATCH = "FILE_SIZE_MISMATCH";
    public static final String REASON_HASH_MISMATCH = "HASH_MISMATCH";
    public static final String REASON_BUSY = "PEER_BUSY";

    @JsonProperty("queryId")
    private String queryId;

    @JsonProperty("available")
    private boolean available;

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

    @JsonProperty("responderPeerId")
    private String responderPeerId;

    @JsonProperty("responderPeerName")
    private String responderPeerName;

    @JsonProperty("reason")
    private String reason;

    public FileQueryResponseMessage() {
    }

    public FileQueryResponseMessage(
            String queryId,
            boolean available,
            String fileName,
            long fileSize,
            int chunkSize,
            int totalChunks,
            String fileSha256,
            String responderPeerId,
            String responderPeerName,
            String reason) {
        this.queryId = queryId;
        this.available = available;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.chunkSize = chunkSize;
        this.totalChunks = totalChunks;
        this.fileSha256 = fileSha256;
        this.responderPeerId = responderPeerId;
        this.responderPeerName = responderPeerName;
        this.reason = reason;
    }

    public static FileQueryResponseMessage available(
            String queryId,
            String fileName,
            long fileSize,
            int chunkSize,
            int totalChunks,
            String fileSha256,
            String responderPeerId,
            String responderPeerName) {
        return new FileQueryResponseMessage(
                queryId,
                true,
                fileName,
                fileSize,
                chunkSize,
                totalChunks,
                fileSha256,
                responderPeerId,
                responderPeerName,
                null
        );
    }

    public static FileQueryResponseMessage unavailable(String queryId, String reason) {
        return new FileQueryResponseMessage(
                queryId,
                false,
                null,
                0L,
                0,
                0,
                null,
                null,
                null,
                reason != null ? reason : REASON_FILE_NOT_FOUND
        );
    }

    public void validate() throws ProtocolException {
        if (queryId == null || queryId.isBlank()) {
            throw new ProtocolException("FileQueryResponseMessage missing queryId");
        }
        if (available) {
            if (fileName == null || fileName.isBlank()) {
                throw new ProtocolException("Available FileQueryResponseMessage missing fileName");
            }
            if (fileSize < 0) {
                throw new ProtocolException("FileQueryResponseMessage fileSize cannot be negative: " + fileSize);
            }
            if (chunkSize <= 0) {
                throw new ProtocolException("FileQueryResponseMessage chunkSize must be positive: " + chunkSize);
            }
            if (totalChunks < 0) {
                throw new ProtocolException("FileQueryResponseMessage totalChunks cannot be negative: " + totalChunks);
            }
            if (!FileTransferUtils.isValidSha256(fileSha256)) {
                throw new ProtocolException("FileQueryResponseMessage invalid SHA-256: " + fileSha256);
            }
        }
    }

    public byte[] toBytes() throws IOException {
        return OBJECT_MAPPER.writeValueAsBytes(this);
    }

    public static FileQueryResponseMessage fromBytes(byte[] payload) throws IOException {
        Objects.requireNonNull(payload, "payload cannot be null");
        return OBJECT_MAPPER.readValue(payload, FileQueryResponseMessage.class);
    }

    public String getQueryId() {
        return queryId;
    }

    public void setQueryId(String queryId) {
        this.queryId = queryId;
    }

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
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
        this.fileSha256 = fileSha256;
    }

    public String getResponderPeerId() {
        return responderPeerId;
    }

    public void setResponderPeerId(String responderPeerId) {
        this.responderPeerId = responderPeerId;
    }

    public String getResponderPeerName() {
        return responderPeerName;
    }

    public void setResponderPeerName(String responderPeerName) {
        this.responderPeerName = responderPeerName;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FileQueryResponseMessage that = (FileQueryResponseMessage) o;
        return available == that.available &&
                fileSize == that.fileSize &&
                chunkSize == that.chunkSize &&
                totalChunks == that.totalChunks &&
                Objects.equals(queryId, that.queryId) &&
                Objects.equals(fileName, that.fileName) &&
                Objects.equals(fileSha256, that.fileSha256);
    }

    @Override
    public int hashCode() {
        return Objects.hash(queryId, available, fileName, fileSize, chunkSize, totalChunks, fileSha256);
    }

    @Override
    public String toString() {
        return "FileQueryResponseMessage{" +
                "queryId='" + queryId + '\'' +
                ", available=" + available +
                ", fileName='" + fileName + '\'' +
                ", totalChunks=" + totalChunks +
                ", responderPeerName='" + responderPeerName + '\'' +
                ", reason='" + reason + '\'' +
                '}';
    }
}
