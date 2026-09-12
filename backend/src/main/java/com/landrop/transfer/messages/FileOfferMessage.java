package com.landrop.transfer.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.landrop.protocol.ProtocolException;
import com.landrop.transfer.FileChunk;

import java.util.Objects;

/**
 * Control message payload for offering a file to a remote peer (FILE_OFFER, code 5).
 * Extended in Phase 7 to carry chunk configuration and SHA-256 integrity metadata.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FileOfferMessage {

    public static final int DEFAULT_CHUNK_SIZE = 1024 * 1024; // 1 MiB

    private final String transferId;
    private final String fileName;
    private final long fileSize;
    private final String senderPeerId;
    private final String senderDisplayName;
    private final int chunkSize;
    private final int totalChunks;
    private final String fileSha256;

    @JsonCreator
    public FileOfferMessage(
            @JsonProperty("transferId") String transferId,
            @JsonProperty("fileName") String fileName,
            @JsonProperty("fileSize") long fileSize,
            @JsonProperty("senderPeerId") String senderPeerId,
            @JsonProperty("senderDisplayName") String senderDisplayName,
            @JsonProperty("chunkSize") Integer chunkSize,
            @JsonProperty("totalChunks") Integer totalChunks,
            @JsonProperty("fileSha256") String fileSha256) {

        this.transferId = Objects.requireNonNull(transferId, "transferId cannot be null");
        this.fileName = Objects.requireNonNull(fileName, "fileName cannot be null");
        this.fileSize = Math.max(0, fileSize);
        this.senderPeerId = senderPeerId != null ? senderPeerId : "";
        this.senderDisplayName = senderDisplayName != null ? senderDisplayName : "Peer";

        int resolvedChunkSize = (chunkSize != null && chunkSize > 0) ? chunkSize : DEFAULT_CHUNK_SIZE;
        this.chunkSize = resolvedChunkSize;
        this.totalChunks = (totalChunks != null && totalChunks >= 0)
                ? totalChunks
                : FileChunk.calculateTotalChunks(this.fileSize, resolvedChunkSize);
        this.fileSha256 = fileSha256 != null ? fileSha256.trim().toLowerCase() : "";
    }

    public FileOfferMessage(
            String transferId,
            String fileName,
            long fileSize,
            String senderPeerId,
            String senderDisplayName) {
        this(transferId, fileName, fileSize, senderPeerId, senderDisplayName,
                DEFAULT_CHUNK_SIZE, FileChunk.calculateTotalChunks(fileSize, DEFAULT_CHUNK_SIZE), "");
    }

    public void validate() throws ProtocolException {
        if (transferId == null || transferId.isBlank()) {
            throw new ProtocolException("Invalid transferId in FILE_OFFER: null or blank");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new ProtocolException("Invalid fileName in FILE_OFFER: null or blank");
        }
        if (fileSize < 0) {
            throw new ProtocolException("Invalid fileSize in FILE_OFFER: " + fileSize + " (cannot be negative)");
        }
        if (chunkSize <= 0) {
            throw new ProtocolException("Invalid chunkSize in FILE_OFFER: " + chunkSize + " (must be positive)");
        }
        if (totalChunks < 0) {
            throw new ProtocolException("Invalid totalChunks in FILE_OFFER: " + totalChunks);
        }
        if (fileSize == 0) {
            if (totalChunks != 0) {
                throw new ProtocolException("totalChunks must be 0 for zero-byte file, got: " + totalChunks);
            }
        } else {
            int expectedChunks = FileChunk.calculateTotalChunks(fileSize, chunkSize);
            if (totalChunks != expectedChunks) {
                throw new ProtocolException("totalChunks mismatch in FILE_OFFER: expected " + expectedChunks +
                        " for size " + fileSize + " and chunk " + chunkSize + ", got " + totalChunks);
            }
        }
        if (fileSha256 != null && !fileSha256.isBlank() && !fileSha256.matches("^[a-fA-F0-9]{64}$")) {
            throw new ProtocolException("Invalid SHA-256 format in FILE_OFFER: " + fileSha256);
        }
    }

    public String getTransferId() {
        return transferId;
    }

    public String getFileName() {
        return fileName;
    }

    public long getFileSize() {
        return fileSize;
    }

    public String getSenderPeerId() {
        return senderPeerId;
    }

    public String getSenderDisplayName() {
        return senderDisplayName;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public String getFileSha256() {
        return fileSha256;
    }

    @Override
    public String toString() {
        return "FileOfferMessage{" +
                "transferId='" + transferId + '\'' +
                ", fileName='" + fileName + '\'' +
                ", fileSize=" + fileSize +
                ", senderPeerId='" + senderPeerId + '\'' +
                ", senderDisplayName='" + senderDisplayName + '\'' +
                ", chunkSize=" + chunkSize +
                ", totalChunks=" + totalChunks +
                ", fileSha256='" + fileSha256 + '\'' +
                '}';
    }
}
