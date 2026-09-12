package com.landrop.transfer.messages;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.protocol.ProtocolException;
import com.landrop.transfer.FileTransferUtils;

import java.io.IOException;
import java.util.Objects;

/**
 * Control message sent to a remote peer to query if it has a specific file available for multi-peer download.
 * Encoded as JSON payload in FILE_QUERY (type 20) packet.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FileQueryMessage {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @JsonProperty("queryId")
    private String queryId;

    @JsonProperty("fileName")
    private String fileName;

    @JsonProperty("fileSize")
    private long fileSize;

    @JsonProperty("fileSha256")
    private String fileSha256;

    @JsonProperty("requesterPeerId")
    private String requesterPeerId;

    @JsonProperty("requesterPeerName")
    private String requesterPeerName;

    public FileQueryMessage() {
    }

    public FileQueryMessage(
            String queryId,
            String fileName,
            long fileSize,
            String fileSha256,
            String requesterPeerId,
            String requesterPeerName) {
        this.queryId = queryId;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.fileSha256 = fileSha256;
        this.requesterPeerId = requesterPeerId;
        this.requesterPeerName = requesterPeerName;
    }

    public void validate() throws ProtocolException {
        if (queryId == null || queryId.isBlank()) {
            throw new ProtocolException("FileQueryMessage missing queryId");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new ProtocolException("FileQueryMessage missing fileName");
        }
        if (fileSize < 0) {
            throw new ProtocolException("FileQueryMessage fileSize cannot be negative: " + fileSize);
        }
        if (!FileTransferUtils.isValidSha256(fileSha256)) {
            throw new ProtocolException("FileQueryMessage has invalid SHA-256 hash: " + fileSha256);
        }
    }

    public byte[] toBytes() throws IOException {
        return OBJECT_MAPPER.writeValueAsBytes(this);
    }

    public static FileQueryMessage fromBytes(byte[] payload) throws IOException {
        Objects.requireNonNull(payload, "payload cannot be null");
        return OBJECT_MAPPER.readValue(payload, FileQueryMessage.class);
    }

    public String getQueryId() {
        return queryId;
    }

    public void setQueryId(String queryId) {
        this.queryId = queryId;
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

    public String getFileSha256() {
        return fileSha256;
    }

    public void setFileSha256(String fileSha256) {
        this.fileSha256 = fileSha256;
    }

    public String getRequesterPeerId() {
        return requesterPeerId;
    }

    public void setRequesterPeerId(String requesterPeerId) {
        this.requesterPeerId = requesterPeerId;
    }

    public String getRequesterPeerName() {
        return requesterPeerName;
    }

    public void setRequesterPeerName(String requesterPeerName) {
        this.requesterPeerName = requesterPeerName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FileQueryMessage that = (FileQueryMessage) o;
        return fileSize == that.fileSize &&
                Objects.equals(queryId, that.queryId) &&
                Objects.equals(fileName, that.fileName) &&
                Objects.equals(fileSha256, that.fileSha256);
    }

    @Override
    public int hashCode() {
        return Objects.hash(queryId, fileName, fileSize, fileSha256);
    }

    @Override
    public String toString() {
        return "FileQueryMessage{" +
                "queryId='" + queryId + '\'' +
                ", fileName='" + fileName + '\'' +
                ", fileSize=" + fileSize +
                ", fileSha256='" + fileSha256 + '\'' +
                ", requesterPeerName='" + requesterPeerName + '\'' +
                '}';
    }
}
