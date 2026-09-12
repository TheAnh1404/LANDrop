package com.landrop.transfer.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Control message payload for acknowledging file transfer outcome (TRANSFER_ACK, code 9).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransferAckMessage {

    private final String transferId;
    private final boolean success;
    private final long transferredBytes;
    private final String message;

    @JsonCreator
    public TransferAckMessage(
            @JsonProperty("transferId") String transferId,
            @JsonProperty("success") boolean success,
            @JsonProperty("transferredBytes") long transferredBytes,
            @JsonProperty("message") String message) {
        this.transferId = Objects.requireNonNull(transferId, "transferId cannot be null");
        this.success = success;
        this.transferredBytes = transferredBytes;
        this.message = message != null ? message : "";
    }

    public static TransferAckMessage success(String transferId, long transferredBytes) {
        return new TransferAckMessage(transferId, true, transferredBytes, "Transfer completed successfully");
    }

    public static TransferAckMessage failure(String transferId, long transferredBytes, String reason) {
        return new TransferAckMessage(transferId, false, transferredBytes, reason);
    }

    public String getTransferId() {
        return transferId;
    }

    public boolean isSuccess() {
        return success;
    }

    public long getTransferredBytes() {
        return transferredBytes;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return "TransferAckMessage{" +
                "transferId='" + transferId + '\'' +
                ", success=" + success +
                ", transferredBytes=" + transferredBytes +
                ", message='" + message + '\'' +
                '}';
    }
}
