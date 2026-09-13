package com.landrop.peer;

/**
 * Distinguishes the intended purpose of a PeerConnection.
 * Allows peers to maintain one active interactive chat session
 * while concurrently handling multiple background file transfer sessions.
 */
public enum ConnectionPurpose {
    /**
     * Primary interactive real-time text chat connection.
     */
    CHAT,

    /**
     * Background peer-to-peer file query and chunk transfer connection.
     */
    FILE_TRANSFER
}
