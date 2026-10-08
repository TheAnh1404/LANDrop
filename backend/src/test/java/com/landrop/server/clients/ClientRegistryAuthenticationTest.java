package com.landrop.server.clients;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.WebSocket;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ClientRegistryAuthenticationTest {

    @Test
    void untrustedHelloCannotDisplaceAuthenticatedInstallation() {
        ClientRegistry registry = new ClientRegistry();
        SocketProbe originalSocket = new SocketProbe();
        ClientConnection original = connection("original", "installation-1", originalSocket.socket());
        registry.register(original);
        UUID userId = UUID.randomUUID();
        registry.bindIdentity("original", userId, "alice", "device-1", "session-1");

        SocketProbe imposterSocket = new SocketProbe();
        ClientConnection imposter = connection("imposter", "installation-1", imposterSocket.socket());
        registry.register(imposter);

        assertTrue(originalSocket.open.get());
        assertEquals("original", registry.findByInstanceId("installation-1").orElseThrow().connectionId());
        assertFalse(imposter.isAuthenticated());

        registry.bindIdentity("imposter", userId, "alice", "device-1", "session-2");
        assertFalse(originalSocket.open.get());
        assertEquals("imposter", registry.findByInstanceId("installation-1").orElseThrow().connectionId());
        assertTrue(registry.findBySessionId("session-1").isEmpty());
        assertTrue(registry.findBySessionId("session-2").isPresent());
    }

    @Test
    void sessionRevocationSendsCodeAndDisconnectsOnlyThatSession() throws Exception {
        ClientRegistry registry = new ClientRegistry();
        UUID userId = UUID.randomUUID();
        SocketProbe firstSocket = new SocketProbe();
        SocketProbe secondSocket = new SocketProbe();
        registry.register(connection("first", "installation-1", firstSocket.socket()));
        registry.register(connection("second", "installation-2", secondSocket.socket()));
        registry.bindIdentity("first", userId, "alice", "device-1", "session-1");
        registry.bindIdentity("second", userId, "alice", "device-2", "session-2");

        assertTrue(registry.disconnectSession("session-1", "SESSION_REVOKED", "Revoked by admin"));
        assertFalse(firstSocket.open.get());
        assertTrue(secondSocket.open.get());
        assertEquals("SESSION_REVOKED", new ObjectMapper().readTree(firstSocket.messages.getFirst()).path("error").path("code").asText());
        assertTrue(registry.findBySessionId("session-1").isEmpty());
        assertTrue(registry.findBySessionId("session-2").isPresent());
    }

    private ClientConnection connection(String id, String installation, WebSocket socket) {
        return new ClientConnection(id, installation, "Workstation", "127.0.0.1:5000",
                Instant.now(), 1, "1.0", "Windows", "11", "amd64", socket);
    }

    @Test
    void revokeSessionClosesAllTabsAndLateCloseKeepsOtherSessionsIndexed() {
        ClientRegistry registry = new ClientRegistry();
        UUID user = UUID.randomUUID();
        SocketProbe tab1 = new SocketProbe();
        SocketProbe tab2 = new SocketProbe();
        SocketProbe independent = new SocketProbe();
        registry.register(connection("tab1", "installation1", tab1.socket()));
        registry.register(connection("tab2", "installation2", tab2.socket()));
        registry.register(connection("independent", "installation3", independent.socket()));
        registry.bindIdentity("tab1", user, "alice", null, "shared");
        registry.bindIdentity("tab2", user, "alice", null, "shared");
        registry.bindIdentity("independent", user, "alice", null, "other");
        assertEquals(2, registry.findAllBySessionId("shared").size());
        assertTrue(registry.disconnectSession("shared", "SESSION_REVOKED", "Revoked"));
        assertFalse(tab1.open.get());
        assertFalse(tab2.open.get());
        assertTrue(independent.open.get());
        registry.remove("tab1");
        registry.remove("tab2");
        assertEquals(1, registry.size());
        assertTrue(registry.findBySessionId("other").isPresent());
    }

    @Test
    void anotherAuthenticatedUserCannotEvictClaimedInstance() {
        ClientRegistry registry = new ClientRegistry();
        SocketProbe original = new SocketProbe();
        registry.register(connection("original", "installation", original.socket()));
        registry.bindIdentity("original", UUID.randomUUID(), "alice", null, "session-a");
        registry.register(connection("attacker", "installation", new SocketProbe().socket()));
        assertThrows(com.landrop.auth.exception.UnauthorizedException.class,
                () -> registry.bindIdentity("attacker", UUID.randomUUID(), "bob", null, "session-b"));
        assertTrue(original.open.get());
        assertEquals("original", registry.findByInstanceId("installation").orElseThrow().connectionId());
        assertFalse(registry.get("attacker").orElseThrow().isAuthenticated());
    }

    private static final class SocketProbe {
        final AtomicBoolean open = new AtomicBoolean(true);
        final List<String> messages = new ArrayList<>();

        WebSocket socket() {
            return (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(),
                    new Class<?>[] {WebSocket.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "isOpen" -> open.get();
                        case "send" -> { if (args != null && args.length > 0 && args[0] instanceof String text) messages.add(text); yield null; }
                        case "close" -> { open.set(false); yield null; }
                        default -> null;
                    });
        }
    }
}
