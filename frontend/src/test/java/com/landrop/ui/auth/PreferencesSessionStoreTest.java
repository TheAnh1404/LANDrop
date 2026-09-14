package com.landrop.ui.auth;

import com.landrop.ui.auth.api.PreferencesSessionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class PreferencesSessionStoreTest {

    private final PreferencesSessionStore defaultStore = new PreferencesSessionStore();
    private final PreferencesSessionStore profileA = new PreferencesSessionStore("User1");
    private final PreferencesSessionStore profileB = new PreferencesSessionStore("User2");

    @BeforeEach
    @AfterEach
    void cleanUp() {
        defaultStore.clear();
        profileA.clear();
        profileB.clear();
    }

    @Test
    @DisplayName("15.5B.8: Save and load refresh token in default profile")
    void testDefaultProfileStorage() {
        assertTrue(defaultStore.loadRefreshToken().isEmpty());

        defaultStore.saveRefreshToken("refresh-token-default");
        assertEquals(Optional.of("refresh-token-default"), defaultStore.loadRefreshToken());

        defaultStore.clear();
        assertTrue(defaultStore.loadRefreshToken().isEmpty());
    }

    @Test
    @DisplayName("15.5B.8: Partitioned profiles isolate sessions for multi-instance login on same machine")
    void testProfileSessionIsolation() {
        profileA.saveRefreshToken("token-user1-device1");
        profileB.saveRefreshToken("token-user2-device2");

        assertEquals(Optional.of("token-user1-device1"), profileA.loadRefreshToken());
        assertEquals(Optional.of("token-user2-device2"), profileB.loadRefreshToken());
        assertTrue(defaultStore.loadRefreshToken().isEmpty(), "Default store must remain unaffected");

        // Clearing Profile A leaves Profile B intact
        profileA.clear();
        assertTrue(profileA.loadRefreshToken().isEmpty());
        assertEquals(Optional.of("token-user2-device2"), profileB.loadRefreshToken());
    }
}
