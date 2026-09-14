package com.landrop.server.transfers;

/**
 * Status lifecycle for in-flight transfer control coordination (FOUNDATION-01A).
 */
public enum TransferStatus {
    PENDING,      // Requested, awaiting receiver response
    ACCEPTED,     // Accepted by receiver; ready for peer-to-peer data plane transfer
    REJECTED,     // Rejected by receiver
    CANCELLED,    // Cancelled by sender or receiver
    COMPLETED,    // Successfully finished
    FAILED;       // Transfer failed or error occurred

    public boolean isTerminal() {
        return this == REJECTED || this == CANCELLED || this == COMPLETED || this == FAILED;
    }
}
