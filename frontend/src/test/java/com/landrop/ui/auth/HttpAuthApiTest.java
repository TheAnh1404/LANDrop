package com.landrop.ui.auth;

import com.landrop.ui.auth.api.HttpAuthApi;
import com.landrop.ui.auth.dto.AuthDto;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class HttpAuthApiTest {
    private HttpServer server;
    private ExecutorService executor;
    private final List<String> requests = new ArrayList<>();
    private URI baseUri;
    private boolean foundationResponse;
    private String errorCode;
    private String loginBody;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/api/auth", exchange -> {
            synchronized (requests) {
                requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " "
                        + exchange.getRequestHeaders().getFirst("Authorization"));
            }
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/login")) loginBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String response;
            if (path.endsWith("/login") || path.endsWith("/register")) {
                response = "{\"data\":{\"accessToken\":\"access\",\"refreshToken\":\"refresh\","
                        + "\"tokenType\":\"Bearer\",\"expiresIn\":3600,\"user\":{"
                        + "\"id\":\"u1\",\"username\":\"ada\",\"displayName\":\"Ada\","
                        + "\"email\":\"ada@example.com\",\"avatarUrl\":null,"
                        + "\"createdAt\":\"2026-09-13T00:00:00Z\",\"updatedAt\":\"2026-09-13T00:00:00Z\"}}}";
            } else if (path.endsWith("/refresh")) {
                response = "{\"data\":{\"accessToken\":\"access-2\",\"refreshToken\":\"refresh-2\","
                        + "\"tokenType\":\"Bearer\",\"expiresIn\":3600}}";
            } else if (path.endsWith("/me")) {
                response = "{\"data\":{\"id\":\"u1\",\"username\":\"ada\",\"displayName\":\"Ada\","
                        + "\"email\":\"ada@example.com\",\"avatarUrl\":null,"
                        + "\"createdAt\":\"2026-09-13T00:00:00Z\",\"updatedAt\":\"2026-09-13T00:00:00Z\"}}";
            } else {
                response = "{\"data\":{}}";
            }
            if (foundationResponse) response = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("data").toString();
            if (errorCode != null) response = "{\"code\":\"" + errorCode + "\",\"error\":\"Forbidden\",\"message\":\"private backend detail\",\"status\":403}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(errorCode == null ? 200 : 403, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api");
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void foundationFlatResponsesAndUsernameLoginUseExistingAuthService() throws Exception {
        foundationResponse = true;
        var api = new HttpAuthApi(baseUri);
        assertEquals("access", api.login(new AuthDto.LoginRequest("ada", "password")).join().accessToken());
        var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(loginBody);
        assertEquals("ada", body.path("identifier").asText()); assertFalse(body.has("userId"));
        assertEquals("u1", api.me("access").join().id());
        errorCode = "ACCOUNT_DISABLED";
        var state = new com.landrop.ui.auth.services.AuthStateService(api, new com.landrop.ui.auth.api.InMemorySessionStore());
        assertThrows(Exception.class, () -> state.login(new com.landrop.ui.auth.model.AuthModels.LoginRequest("ada", "password")).join());
        assertTrue(state.authError().contains("disabled")); assertFalse(state.authError().contains("private"));
    }

    @Test
    void routesAllAuthenticationOperationsAndCarriesBearerToken() {
        var api = new HttpAuthApi(baseUri);
        var request = new AuthDto.LoginRequest("ada@example.com", "password");
        assertEquals("access", api.login(request).join().accessToken());
        assertEquals("access", api.register(new AuthDto.RegisterRequest("ada", "Ada", "ada@example.com", "password")).join().accessToken());
        assertEquals("access-2", api.refresh(new AuthDto.RefreshRequest("refresh")).join().accessToken());
        assertEquals("u1", api.me("access").join().id());
        assertDoesNotThrow(() -> api.logout("access", "refresh").join());

        synchronized (requests) {
            assertEquals(5, requests.size());
            assertEquals("POST /api/auth/login null", requests.get(0));
            assertEquals("POST /api/auth/register null", requests.get(1));
            assertEquals("POST /api/auth/refresh null", requests.get(2));
            assertEquals("GET /api/auth/me Bearer access", requests.get(3));
            assertEquals("POST /api/auth/logout Bearer access", requests.get(4));
        }
    }
}
