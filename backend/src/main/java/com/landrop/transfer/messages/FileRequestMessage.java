package com.landrop.transfer.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Control message payload for accepting or rejecting a file offer (FILE_REQUEST, code 6).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FileRequestMessage {

    private final String transferId;
    private final boolean accepted;
    private final String reason;

    @JsonCreator
    public FileRequestMessage(
            @JsonProperty("transferId") String transferId,
            @JsonProperty("accepted") boolean accepted,
            @JsonProperty("reason") String reason) {
        this.transferId = Objects.requireNonNull(transferId, "transferId cannot be null");
        this.accepted = accepted;
        this.reason = reason != null ? reason : "";
    }

    public static FileRequestMessage accept(String transferId) {
        return new FileRequestMessage(transferId, true, "");
    }

    public static FileRequestMessage reject(String transferId, String reason) {
        return new FileRequestMessage(transferId, false, reason);
    }

    public String getTransferId() {
        return transferId;
    }

    public boolean isAccepted() {
        return accepted;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        return "FileRequestMessage{" +
                "transferId='" + transferId + '\'' +
                ", accepted=" + accepted +
                ", reason='" + reason + '\'' +
                '}';
    }
}
