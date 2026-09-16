package com.landrop.ui.cloud.api;

import com.landrop.ui.auth.api.AuthApi;
import com.landrop.ui.auth.api.InMemorySessionStore;
import com.landrop.ui.auth.dto.AuthDto;
import com.landrop.ui.auth.model.AuthModels;
import com.landrop.ui.auth.services.AuthStateService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiClientTest {
    private HttpServer server;
    private ExecutorService executor;
    private final AtomicInteger requests = new AtomicInteger();
    private URI baseUri;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/api/files", exchange -> {
            int request = requests.incrementAndGet();
            boolean refreshedToken = "Bearer access-2".equals(exchange.getRequestHeaders().getFirst("Authorization"));
            String response = request == 1
                    ? "{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"access token expired\"}}"
                    : "{\"data\":\"ok\"}";
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(refreshedToken ? 200 : 401, body.length);
            exchange.getResponseBody().write(body);
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
    void refreshesUnauthorizedAccessTokenBeforeInvalidatingTheSession() {
        AtomicInteger refreshes = new AtomicInteger();
        AuthApi authApi = new AuthApi() {
            @Override public CompletableFuture<AuthDto.AuthResponse> register(AuthDto.RegisterRequest request) { return unsupported(); }
            @Override public CompletableFuture<AuthDto.AuthResponse> login(AuthDto.LoginRequest request) {
                return CompletableFuture.completedFuture(auth("access-1", "refresh-1"));
            }
            @Override public CompletableFuture<AuthDto.RefreshResponse> refresh(AuthDto.RefreshRequest request) {
                refreshes.incrementAndGet();
                return CompletableFuture.completedFuture(new AuthDto.RefreshResponse("access-2", "refresh-2", "Bearer", 3600));
            }
            @Override public CompletableFuture<AuthDto.AuthUserResponse> me(String accessToken) { return CompletableFuture.completedFuture(user()); }
            @Override public CompletableFuture<Void> logout(String accessToken, String refreshToken) { return CompletableFuture.completedFuture(null); }
        };
        AuthStateService state = new AuthStateService(authApi, new InMemorySessionStore());
        state.login(new AuthModels.LoginRequest("ada@example.com", "password")).join();

        assertEquals("{\"data\":\"ok\"}", new ApiClient(baseUri, state).request("GET", "files", null).join());
        assertEquals(1, refreshes.get());
        assertEquals(2, requests.get());
        assertEquals("access-2", state.accessToken().orElseThrow());
        assertTrue(state.isAuthenticated());
    }

    private static AuthDto.AuthResponse auth(String access, String refresh) {
        return new AuthDto.AuthResponse(access, refresh, "Bearer", 3600, user());
    }

    private static AuthDto.AuthUserResponse user() {
        return new AuthDto.AuthUserResponse("u1", "ada", "Ada", "ada@example.com", null,
                "2026-09-13T00:00:00Z", "2026-09-13T00:00:00Z");
    }

    private static <T> CompletableFuture<T> unsupported() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }
}
