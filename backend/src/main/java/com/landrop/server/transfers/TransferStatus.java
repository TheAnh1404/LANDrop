package com.landrop.server.transfers;

/**
 * Status lifecycle for in-flight transfer control coordination (FOUNDATION-01A).
 */
public enum TransferStatus {
    REQUESTED,    // Awaiting receiver consent
    ACCEPTED,     // Accepted by receiver; ready for peer-to-peer data plane transfer
    CONNECTING,
    P2P_CONNECTED,
    REJECTED,     // Rejected by receiver
    CANCELLED,    // Cancelled by sender or receiver
    COMPLETED,    // Successfully finished
    FAILED,
    EXPIRED;

    public boolean isTerminal() {
        return this == REJECTED || this == CANCELLED || this == COMPLETED || this == FAILED || this == EXPIRED;
    }
}
