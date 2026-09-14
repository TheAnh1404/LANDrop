package com.landrop.server.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.server.clients.ClientRegistry;
import com.landrop.server.health.HealthService;
import com.landrop.server.logging.ServerEventLogger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Lightweight HTTP server serving /health and status inspection.
 * Uses Java 21 standard HttpServer with Virtual Threads.
 */
public class FoundationHttpServer {

    private final String host;
    private final int port;
    private final HealthService healthService;
    private final ClientRegistry clientRegistry;
    private final int wsPort;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    public FoundationHttpServer(String host, int port, int wsPort, HealthService healthService, ClientRegistry clientRegistry) {
        this.host = host;
        this.port = port;
        this.wsPort = wsPort;
        this.healthService = healthService;
        this.clientRegistry = clientRegistry;
    }

    public synchronized void start() throws IOException {
        if (server != null) return;

        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/health", this::handleHealth);
        server.createContext("/api/health", this::handleHealth);
        server.createContext("/status", this::handleStatus);
        server.createContext("/api/status", this::handleStatus);

        server.start();
    }

    public synchronized void stop(int delaySeconds) {
        if (server != null) {
            server.stop(delaySeconds);
            server = null;
        }
    }

    public int getPort() {
        if (server != null) {
            return server.getAddress().getPort();
        }
        return port;
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        byte[] bytes = objectMapper.writeValueAsBytes(healthService.getHealthReport());
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        int activeClients = clientRegistry != null ? clientRegistry.activeConnections().size() : 0;
        Map<String, Object> status = healthService.getStatusReport(activeClients, host, getPort(), wsPort);
        byte[] bytes = objectMapper.writeValueAsBytes(status);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
