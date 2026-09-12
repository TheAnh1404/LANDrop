package com.landrop.transfer;

import com.landrop.discovery.PeerInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PeerDownloadStateTest {

    @Test
    @DisplayName("Verify PeerDownloadState initialization from PeerInfo and defaults")
    void testInitialization() {
        PeerInfo info = new PeerInfo("p-1", "Alice", "192.168.1.10", 5001);
        PeerDownloadState state = PeerDownloadState.fromPeerInfo(info);

        assertEquals("p-1", state.getPeerId());
        assertEquals("Alice", state.getPeerDisplayName());
        assertSame(info, state.getPeerInfo());
        assertTrue(state.isAvailable());
        assertTrue(state.isConnected());
        assertEquals(0, state.getActiveChunks());
        assertEquals(0, state.getCompletedChunks());
        assertEquals(0, state.getFailedChunks());
        assertEquals(0, state.getAssignedChunkCount());
        assertEquals(0L, state.getBytesReceived());
        assertTrue(state.getLastActivity() > 0);
    }

    @Test
    @DisplayName("Verify counter modifications and invariant checks")
    void testCounters() {
        PeerDownloadState state = new PeerDownloadState("p-1", "Bob", null);

        assertEquals(1, state.incrementActiveChunks());
        assertEquals(2, state.incrementActiveChunks());
        assertEquals(2, state.getAssignedChunkCount());

        assertEquals(1, state.incrementCompletedChunks());
        assertEquals(1, state.getActiveChunks()); // active decreased by 1

        assertEquals(1, state.incrementFailedChunks());
        assertEquals(0, state.getActiveChunks()); // active decreased by 1

        // Decrement below 0 is bounded to 0
        assertEquals(0, state.decrementActiveChunks());

        state.addBytesReceived(1024L);
        assertEquals(1024L, state.getBytesReceived());
    }

    @Test
    @DisplayName("Verify connection state toggle and reset active chunks")
    void testConnectionToggle() {
        PeerDownloadState state = new PeerDownloadState("p-1", "Charlie", null);
        state.incrementActiveChunks();
        state.incrementActiveChunks();
        assertEquals(2, state.getActiveChunks());

        state.setConnected(false);
        assertFalse(state.isConnected());

        state.resetActiveChunks();
        assertEquals(0, state.getActiveChunks());
    }
}
