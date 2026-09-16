package com.landrop.ui.auth;

import com.landrop.ui.auth.api.ServerSessionStore;
import org.junit.jupiter.api.Test;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;

class ServerSessionStoreTest {
    @Test void credentialsAreScopedToServerAndClearedOnLogout() {
        String profile = "credential-test-" + System.nanoTime();
        var a = new ServerSessionStore(URI.create("http://server-a/api"), profile);
        var b = new ServerSessionStore(URI.create("http://server-b/api"), profile);
        try {
            a.saveRefreshToken("synthetic-refresh-credential");
            assertTrue(b.loadRefreshToken().isEmpty());
            if (System.getProperty("os.name", "").startsWith("Windows")) {
                assertEquals("synthetic-refresh-credential", new ServerSessionStore(URI.create("http://server-a/api"), profile).loadRefreshToken().orElseThrow());
            }
            a.clear(); assertTrue(a.loadRefreshToken().isEmpty());
        } finally { a.clear(); b.clear(); }
    }
}
