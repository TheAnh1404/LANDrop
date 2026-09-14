package com.landrop.ui.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class FrontendConfigTest {

    @AfterEach
    void clearSystemProperties() {
        System.clearProperty("landrop.api.base-url");
        System.clearProperty("landrop.auth.base-url");
        System.clearProperty("landrop.ws.url");
        System.clearProperty("landrop.session.profile");
    }

    @Test
    @DisplayName("15.5B.7: Default configuration fallback when no parameters provided")
    void testDefaultResolution() {
        String api = FrontendConfig.resolveApiBaseUrl(Collections.emptyMap());
        assertEquals("http://localhost:3000/api", api);

        String ws = FrontendConfig.resolveWsUrl(Collections.emptyMap(), null);
        assertEquals("ws://localhost:3001", ws);

        String profile = FrontendConfig.resolveSessionProfile(Collections.emptyMap());
        assertNull(profile);
    }

    @Test
    @DisplayName("15.5B.7: CLI named parameter takes highest precedence")
    void testCliArgumentsPrecedence() {
        System.setProperty("landrop.api.base-url", "http://sysprop-host:4000/api");
        System.setProperty("landrop.ws.url", "ws://sysprop-host:4000/ws");
        System.setProperty("landrop.session.profile", "sysprop-profile");

        Map<String, String> cliArgs = Map.of(
                "api-url", "http://192.168.1.50:8080/api/",
                "ws-url", "ws://192.168.1.50:8080/ws/",
                "session-profile", "User1"
        );

        String api = FrontendConfig.resolveApiBaseUrl(cliArgs);
        assertEquals("http://192.168.1.50:8080/api", api, "Trailing slash should be normalized");

        String ws = FrontendConfig.resolveWsUrl(cliArgs, api);
        assertEquals("ws://192.168.1.50:8080/ws", ws, "Trailing slash should be normalized");

        String profile = FrontendConfig.resolveSessionProfile(cliArgs);
        assertEquals("User1", profile);
    }

    @Test
    @DisplayName("15.5B.7: System properties take precedence over defaults when CLI absent")
    void testSystemPropertiesPrecedence() {
        System.setProperty("landrop.api.base-url", "\"http://remote-srv:5000/api\"");
        System.setProperty("landrop.ws.url", "'ws://remote-srv:5000/ws'");
        System.setProperty("landrop.session.profile", "InstanceB");

        String api = FrontendConfig.resolveApiBaseUrl(Collections.emptyMap());
        assertEquals("http://remote-srv:5000/api", api, "Surrounding quotes must be stripped");

        String ws = FrontendConfig.resolveWsUrl(Collections.emptyMap(), api);
        assertEquals("ws://remote-srv:5000/ws", ws, "Surrounding quotes must be stripped");

        String profile = FrontendConfig.resolveSessionProfile(Collections.emptyMap());
        assertEquals("InstanceB", profile);
    }

    @Test
    @DisplayName("15.5B.7: Derive WebSocket URL automatically from HTTP/HTTPS API URL")
    void testDeriveWsUrl() {
        assertEquals("ws://192.168.1.10:3001/ws", FrontendConfig.deriveWsUrl("http://192.168.1.10:3000/api"));
        assertEquals("wss://cloud.landrop.net:443/ws", FrontendConfig.deriveWsUrl("https://cloud.landrop.net:443/api/v1"));
        assertEquals("ws://my-box/ws", FrontendConfig.deriveWsUrl("http://my-box"));
    }
}
