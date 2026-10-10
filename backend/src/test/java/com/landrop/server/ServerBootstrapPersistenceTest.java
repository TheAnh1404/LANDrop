package com.landrop.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.database.DatabaseManager;
import com.landrop.server.bootstrap.ServerBootstrap;
import com.landrop.server.clients.ClientRegistry;
import com.landrop.server.config.ServerConfig;
import com.landrop.server.health.HealthService;
import com.landrop.server.http.FoundationHttpServer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.*;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ServerBootstrapPersistenceTest {
    @Test void configuredDatabaseFailureAbortsStartupBeforeOpeningListeners() throws Exception {
        var values = new HashMap<String, String>();
        for (String key : new String[]{"landrop.database.enabled", "landrop.database.url", "landrop.database.pool.timeout.ms"}) values.put(key, System.getProperty(key));
        DatabaseManager.closeIfInitialized();
        var server = new ServerBootstrap(new ServerConfig("127.0.0.1", 0, 0, 1, 30000, 90000, "test", "INFO"));
        try {
            System.setProperty("landrop.database.enabled", "true");
            System.setProperty("landrop.database.url", "postgresql://test:test@127.0.0.1:1/test?connectTimeout=1&socketTimeout=1");
            System.setProperty("landrop.database.pool.timeout.ms", "250");
            assertThrows(Exception.class, server::start);
            assertFalse(server.isRunning());
            assertNull(server.getHttpServer());
            assertNull(server.getRealtimeServer());
        } finally {
            server.stop();
            DatabaseManager.closeIfInitialized();
            values.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); });
        }
    }

    @Test void standaloneServerCannotReturnSuccessfulCatalogForRegistrationOrUnknownRoutes() throws Exception {
        var server = new FoundationHttpServer("127.0.0.1", 0, 0, new HealthService(), new ClientRegistry());
        var json = new ObjectMapper();
        try (var http = HttpClient.newHttpClient()) {
            server.start();
            URI base = URI.create("http://127.0.0.1:" + server.getPort());
            var registration = http.send(HttpRequest.newBuilder(base.resolve("/api/auth/register"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .header("Content-Type", "application/json").build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(503, registration.statusCode());
            assertEquals("PERSISTENCE_UNAVAILABLE", json.readTree(registration.body()).path("error").asText());
            var unknown = http.send(HttpRequest.newBuilder(base.resolve("/api/unknown")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, unknown.statusCode());
            var health = http.send(HttpRequest.newBuilder(base.resolve("/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertFalse(json.readTree(health.body()).path("persistenceAvailable").asBoolean());
        } finally {
            server.stop(0);
        }
    }
}
