package com.landrop.transfer;

/**
 * Direction of a P2P file transfer.
 */
public enum FileTransferDirection {
    OUTGOING("Outgoing"),
    INCOMING("Incoming");

    private final String label;

    FileTransferDirection(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
