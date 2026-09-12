package com.landrop.transfer.messages;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Objects;

/**
 * Control message sent by the file sender in response to a RESUME_REQUEST.
 * Encoded as JSON payload in RESUME_RESPONSE (type 19) packet.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResumeResponseMessage {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static final String REASON_TRANSFER_NOT_FOUND = "TRANSFER_NOT_FOUND";
    public static final String REASON_METADATA_MISMATCH = "METADATA_MISMATCH";
    public static final String REASON_FILE_SIZE_MISMATCH = "FILE_SIZE_MISMATCH";
    public static final String REASON_HASH_MISMATCH = "HASH_MISMATCH";
    public static final String REASON_CHUNK_SIZE_MISMATCH = "CHUNK_SIZE_MISMATCH";
    public static final String REASON_INVALID_TRANSFER_STATE = "INVALID_TRANSFER_STATE";
    public static final String REASON_SOURCE_FILE_CHANGED = "SOURCE_FILE_CHANGED";

    @JsonProperty("transferId")
    private String transferId;

    @JsonProperty("accepted")
    private boolean accepted;

    @JsonProperty("reason")
    private String reason;

    public ResumeResponseMessage() {
    }

    public ResumeResponseMessage(String transferId, boolean accepted, String reason) {
        this.transferId = Objects.requireNonNull(transferId, "transferId cannot be null");
        this.accepted = accepted;
        this.reason = reason;
    }

    public static ResumeResponseMessage accept(String transferId) {
        return new ResumeResponseMessage(transferId, true, null);
    }

    public static ResumeResponseMessage reject(String transferId, String reason) {
        return new ResumeResponseMessage(transferId, false, reason != null ? reason : "Resume rejected");
    }

    public String getTransferId() {
        return transferId;
    }

    public void setTransferId(String transferId) {
        this.transferId = transferId;
    }

    public boolean isAccepted() {
        return accepted;
    }

    public void setAccepted(boolean accepted) {
        this.accepted = accepted;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public byte[] toBytes() throws IOException {
        return OBJECT_MAPPER.writeValueAsBytes(this);
    }

    public static ResumeResponseMessage fromBytes(byte[] payload) throws IOException {
        return OBJECT_MAPPER.readValue(payload, ResumeResponseMessage.class);
    }
}
