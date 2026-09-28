package com.landrop.server.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProductionAdminPasswordTest {
    @Test void rejectsMissingAndWeakPasswords() {
        assertThrows(IllegalStateException.class, () -> ServerBootstrap.requireProductionAdminPassword(null));
        assertThrows(IllegalStateException.class, () -> ServerBootstrap.requireProductionAdminPassword("admin123"));
        assertThrows(IllegalStateException.class, () -> ServerBootstrap.requireProductionAdminPassword("short"));
        assertThrows(IllegalStateException.class, () -> ServerBootstrap.requireProductionAdminPassword("replace-with-a-secret"));
        assertDoesNotThrow(() -> ServerBootstrap.requireProductionAdminPassword("a-long-unique-secret"));
    }
}
