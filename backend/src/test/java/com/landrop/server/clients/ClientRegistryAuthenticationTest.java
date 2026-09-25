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
