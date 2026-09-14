package com.landrop.file.model;

/**
 * Transfer lifecycle status state machine according to Phase 18A.11 specifications.
 */
public enum TransferStatus {
    PENDING,
    ACCEPTED,
    TRANSFERRING,
    COMPLETED,
    REJECTED,
    FAILED,
    CANCELLED;

    public boolean canTransitionTo(TransferStatus target) {
        if (target == null || this == target) return false;
        return switch (this) {
            case PENDING -> target == ACCEPTED || target == REJECTED || target == CANCELLED;
            case ACCEPTED -> target == TRANSFERRING || target == CANCELLED || target == FAILED;
            case TRANSFERRING -> target == COMPLETED || target == FAILED || target == CANCELLED;
            case COMPLETED, REJECTED, FAILED, CANCELLED -> false; // Terminal states
        };
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == REJECTED || this == FAILED || this == CANCELLED;
    }
}
