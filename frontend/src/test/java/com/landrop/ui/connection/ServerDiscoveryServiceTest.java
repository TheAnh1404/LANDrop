package com.landrop.ui.connection;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ServerDiscoveryServiceTest {

    private HttpServer mockServer;
    private int mockPort;
    private ServerDiscoveryService discoveryService;

    @BeforeEach
    void setUp() throws IOException {
        discoveryService = new ServerDiscoveryService();
    }

    @AfterEach
    void tearDown() {
        if (discoveryService != null) {
            discoveryService.close();
        }
        if (mockServer != null) {
            mockServer.stop(0);
        }
    }

    private void startMockHealthServer(java.util.function.IntFunction<String> payloadSupplier, int statusCode) throws IOException {
        mockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockPort = mockServer.getAddress().getPort();
        String responseBody = payloadSupplier.apply(mockPort);
        mockServer.createContext("/health", exchange -> {
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        mockServer.start();
    }

    @Test
    void testProbeSuccessfulServer() throws IOException {
        startMockHealthServer(port -> """
            {
                "status": "ok",
                "serverVersion": "1.0.0-test",
                "protocolVersion": 1,
                "httpPort": %d,
                "wsPort": 3001
            }
            """.formatted(port), 200);

        ServerDiscoveryService.DiscoveredServer discovered = discoveryService.probe("127.0.0.1", mockPort);

        assertNotNull(discovered, "Server should be successfully discovered via probe");
        assertEquals("127.0.0.1", discovered.host());
        assertEquals(mockPort, discovered.httpPort());
        assertEquals(3001, discovered.wsPort());
        assertEquals("1.0.0-test", discovered.serverVersion());
        assertEquals(1, discovered.protocolVersion());
        assertEquals("http://127.0.0.1:" + mockPort + "/api", discovered.apiUrl());
        assertEquals("ws://127.0.0.1:3001/api/realtime", discovered.wsUrl());
        assertEquals("http://127.0.0.1:" + mockPort, discovered.displayAddress());
    }

    @Test
    void testProbeUnresponsivePortReturnsNull() {
        // Probe a port that is unlikely to be open
        ServerDiscoveryService.DiscoveredServer discovered = discoveryService.probe("127.0.0.1", 59999);
        assertNull(discovered);
    }

    @Test
    void testProbeNonOkStatusReturnsNull() throws IOException {
        startMockHealthServer(port -> "{\"status\":\"error\"}", 500);

        ServerDiscoveryService.DiscoveredServer discovered = discoveryService.probe("127.0.0.1", mockPort);
        assertNull(discovered);
    }

    @Test
    void testPreferredUrlTakesPriorityInScanOnce() throws Exception {
        startMockHealthServer(port -> """
            {
                "status": "ok",
                "serverVersion": "2.0.0",
                "protocolVersion": 1,
                "httpPort": %d,
                "wsPort": %d
            }
            """.formatted(port, port + 1), 200);

        discoveryService.setPreferredUrl("http://127.0.0.1:" + mockPort + "/api");

        AtomicReference<String> statusReceived = new AtomicReference<>();
        CompletableFuture<Optional<ServerDiscoveryService.DiscoveredServer>> future =
                discoveryService.scanOnce(statusReceived::set);

        Optional<ServerDiscoveryService.DiscoveredServer> result = future.get(5, TimeUnit.SECONDS);

        assertTrue(result.isPresent());
        assertEquals(mockPort, result.get().httpPort());
        assertNotNull(statusReceived.get());
    }

    @Test
    void testResolveLocalIpv4AddressesDoesNotThrow() {
        List<String> ips = ServerDiscoveryService.resolveLocalIpv4Addresses();
        assertNotNull(ips);
        for (String ip : ips) {
            assertFalse(ip.isBlank());
            assertFalse(ip.startsWith("127."));
        }
    }
}
