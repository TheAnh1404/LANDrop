package com.landrop.transfer;

/**
 * Lifecycle states of a P2P file transfer.
 */
public enum FileTransferStatus {
    PENDING("Pending"),
    ACCEPTED("Accepted"),
    REJECTED("Rejected"),
    TRANSFERRING("Transferring"),
    VERIFYING("Verifying"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    CANCELLED("Cancelled");

    private final String label;

    FileTransferStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == REJECTED || this == CANCELLED;
    }
}
