package com.landrop.server.desktop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("NetworkHelper Unit Tests")
class NetworkHelperTest {

    @Test
    @DisplayName("Should retrieve primary LAN IP without error")
    void testGetPrimaryLanIp() {
        String ip = NetworkHelper.getPrimaryLanIp();
        assertNotNull(ip, "Primary LAN IP should not be null");
        assertFalse(ip.isBlank(), "Primary LAN IP should not be blank");
        // Should look like a valid IPv4 address
        assertTrue(ip.matches("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"),
                "Expected IPv4 address format, got: " + ip);
    }

    @Test
    @DisplayName("Should list LAN interfaces or empty list gracefully")
    void testGetAvailableLanInterfaces() {
        List<NetworkHelper.LanInterfaceInfo> ifaces = NetworkHelper.getAvailableLanInterfaces();
        assertNotNull(ifaces, "Interfaces list should not be null");
        for (NetworkHelper.LanInterfaceInfo info : ifaces) {
            assertNotNull(info.ip());
            assertNotNull(info.displayName());
            assertNotNull(info.interfaceName());
            assertFalse(info.ip().startsWith("127."), "Should not include loopback addresses");
            assertFalse(info.ip().startsWith("169.254."), "Should not include link-local addresses");
        }
    }
}
