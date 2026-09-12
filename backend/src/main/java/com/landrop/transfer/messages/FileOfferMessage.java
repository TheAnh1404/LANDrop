package com.landrop.transfer.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Control message payload for offering a file to a remote peer (FILE_OFFER, code 5).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FileOfferMessage {

    private final String transferId;
    private final String fileName;
    private final long fileSize;
    private final String senderPeerId;
    private final String senderDisplayName;

    @JsonCreator
    public FileOfferMessage(
            @JsonProperty("transferId") String transferId,
            @JsonProperty("fileName") String fileName,
            @JsonProperty("fileSize") long fileSize,
            @JsonProperty("senderPeerId") String senderPeerId,
            @JsonProperty("senderDisplayName") String senderDisplayName) {
        this.transferId = Objects.requireNonNull(transferId, "transferId cannot be null");
        this.fileName = Objects.requireNonNull(fileName, "fileName cannot be null");
        this.fileSize = fileSize;
        this.senderPeerId = senderPeerId != null ? senderPeerId : "";
        this.senderDisplayName = senderDisplayName != null ? senderDisplayName : "Peer";
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

    @Override
    public String toString() {
        return "FileOfferMessage{" +
                "transferId='" + transferId + '\'' +
                ", fileName='" + fileName + '\'' +
                ", fileSize=" + fileSize +
                ", senderPeerId='" + senderPeerId + '\'' +
                ", senderDisplayName='" + senderDisplayName + '\'' +
                '}';
    }
}
