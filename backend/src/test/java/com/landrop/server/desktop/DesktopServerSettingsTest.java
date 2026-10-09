package com.landrop.server.desktop;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DesktopServerSettingsTest {
    @Test void packagedClientAndAdminAreDifferentDestinations() {
        var settings = new DesktopServerSettings("0.0.0.0", 3100, 3001, false, 3000, true);
        assertEquals("http://192.168.1.10:3200/client", settings.clientUrl("192.168.1.10", 3200));
    }

    @Test void externalClientReceivesEncodedActualBackendUrl() {
        var settings = new DesktopServerSettings("0.0.0.0", 3100, 3001, true, 3000, true);
        assertEquals("http://192.168.1.10:3000/connect?server=http%3A%2F%2F192.168.1.10%3A3200",
                settings.clientUrl("192.168.1.10", 3200));
        var local = new DesktopServerSettings("127.0.0.1", 3100, 3001, true, 3000, true);
        assertTrue(local.clientUrl("192.168.1.10", 3100).startsWith("http://127.0.0.1:3000/"));
    }

    @Test void invalidPortsAndConflictsAreRejectedBeforeRestart() {
        assertThrows(IllegalArgumentException.class, () -> new DesktopServerSettings("0.0.0.0", 80, 3001, false, 3000, true));
        assertThrows(IllegalArgumentException.class, () -> new DesktopServerSettings("0.0.0.0", 3100, 3100, false, 3000, true));
        assertThrows(IllegalArgumentException.class, () -> new DesktopServerSettings("0.0.0.0", 3100, 3001, true, 3100, true));
        assertThrows(IllegalArgumentException.class, () -> new DesktopServerSettings("http://localhost", 3100, 3001, false, 3000, true));
    }

    @Test void specificBindAddressCannotAdvertiseAnotherNetworkInterface() {
        var settings = new DesktopServerSettings("192.168.1.10", 3100, 3001, false, 3000, true);
        assertEquals("http://192.168.1.10:3100/client", settings.clientUrl("10.0.0.20", 3100));
        var ipv6 = new DesktopServerSettings("::1", 3100, 3001, false, 3000, true);
        assertEquals("http://[::1]:3100/client", ipv6.clientUrl("10.0.0.20", 3100));
    }
}
