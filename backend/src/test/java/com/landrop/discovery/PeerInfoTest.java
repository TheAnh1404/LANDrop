package com.landrop.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerInfoTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Verify PeerInfo construction, getters and validation")
    void testPeerInfoCreationAndValidation() {
        PeerInfo info = new PeerInfo("peer-1", "Alice", "192.168.1.50", 5001);

        assertEquals("peer-1", info.getPeerId());
        assertEquals("Alice", info.getDisplayName());
        assertEquals("192.168.1.50", info.getHost());
        assertEquals(5001, info.getPort());
        assertEquals(PeerStatus.ONLINE, info.getStatus());
        assertTrue(info.getLastSeen() > 0);
        assertTrue(info.isValid());

        // Invalid cases
        assertFalse(new PeerInfo(null, "Alice", "127.0.0.1", 5001).isValid());
        assertFalse(new PeerInfo("", "Alice", "127.0.0.1", 5001).isValid());
        assertFalse(new PeerInfo("p1", "", "127.0.0.1", 5001).isValid());
        assertFalse(new PeerInfo("p1", "Alice", "", 5001).isValid());
        assertFalse(new PeerInfo("p1", "Alice", "127.0.0.1", -1).isValid());
        assertFalse(new PeerInfo("p1", "Alice", "127.0.0.1", 70000).isValid());
    }

    @Test
    @DisplayName("Verify JSON serialization and deserialization of PeerInfo")
    void testJsonSerialization() throws Exception {
        PeerInfo original = new PeerInfo("uuid-123", "Bob", "127.0.0.1", 5002, 1700000000L, PeerStatus.ONLINE);
        String json = mapper.writeValueAsString(original);

        assertNotNull(json);
        assertTrue(json.contains("uuid-123"));
        assertTrue(json.contains("Bob"));

        PeerInfo deserialized = mapper.readValue(json, PeerInfo.class);
        assertEquals(original, deserialized);
        assertEquals(original.getDisplayName(), deserialized.getDisplayName());
        assertEquals(original.getHost(), deserialized.getHost());
        assertEquals(original.getPort(), deserialized.getPort());
        assertEquals(original.getStatus(), deserialized.getStatus());
    }
}
