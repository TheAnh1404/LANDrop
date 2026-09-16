package com.landrop.ui.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class LocalClientProfileCoordinatorTest {
    @Test
    void concurrentlyRunningClientsReceiveSeparateProfiles() {
        try (LocalClientProfileCoordinator first = LocalClientProfileCoordinator.acquire(null);
             LocalClientProfileCoordinator second = LocalClientProfileCoordinator.acquire(null)) {
            assertEquals("default", first.profile());
            assertNotEquals(first.profile(), second.profile());
            assertNotEquals(new ClientInstanceService(first.profile()).clientInstanceId(),
                    new ClientInstanceService(second.profile()).clientInstanceId());
        }
    }
}
