package com.landrop.ui.auth;

import com.landrop.ui.auth.api.AuthApi;
import com.landrop.ui.auth.api.AuthenticatedApiTransport;
import com.landrop.ui.auth.api.InMemorySessionStore;
import com.landrop.ui.auth.dto.AuthDto;
import com.landrop.ui.auth.model.AuthModels;
import com.landrop.ui.auth.services.AuthStateService;
import com.landrop.ui.cloud.api.ApiException;
import com.landrop.ui.cloud.api.ApiTransport;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AuthenticatedApiTransportTest {
    @Test
    void retriesOneUnauthorizedRequestWithTheCoordinatedRefreshedToken() {
        AuthApi authApi = new AuthApi() {
            AtomicInteger refreshes = new AtomicInteger();
            @Override public CompletableFuture<AuthDto.AuthResponse> register(AuthDto.RegisterRequest request) { return unsupported(); }
            @Override public CompletableFuture<AuthDto.AuthResponse> login(AuthDto.LoginRequest request) { return CompletableFuture.completedFuture(auth("access-1", "refresh-1")); }
            @Override public CompletableFuture<AuthDto.RefreshResponse> refresh(AuthDto.RefreshRequest request) {
                refreshes.incrementAndGet();
                return CompletableFuture.completedFuture(new AuthDto.RefreshResponse("access-2", "refresh-2", "Bearer", 3600));
            }
            @Override public CompletableFuture<AuthDto.AuthUserResponse> me(String accessToken) { return CompletableFuture.completedFuture(user()); }
            @Override public CompletableFuture<Void> logout(String accessToken, String refreshToken) { return CompletableFuture.completedFuture(null); }
        };
        AuthStateService state = new AuthStateService(authApi, new InMemorySessionStore());
        state.login(new AuthModels.LoginRequest("ada@example.com", "password")).join();
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> firstToken = new AtomicReference<>();
        AtomicReference<String> secondToken = new AtomicReference<>();
        ApiTransport delegate = new ApiTransport() {
            @Override public CompletableFuture<String> get(String path, Map<String, String> query) { return unsupported(); }
            @Override public CompletableFuture<String> get(String path, Map<String, String> query, Map<String, String> headers) {
                if (requests.getAndIncrement() == 0) {
                    firstToken.set(headers.get("Authorization"));
                    return CompletableFuture.failedFuture(new ApiException(401, "UNAUTHORIZED", "expired"));
                }
                secondToken.set(headers.get("Authorization"));
                return CompletableFuture.completedFuture("ok");
            }
        };

        assertEquals("ok", new AuthenticatedApiTransport(delegate, state).get("users/u1", Map.of()).join());
        assertEquals("Bearer access-1", firstToken.get());
        assertEquals("Bearer access-2", secondToken.get());
        assertEquals("access-2", state.accessToken().orElseThrow());
        assertTrue(state.isAuthenticated());
        assertEquals(2, requests.get());
    }

    @Test
    void doesNotRetryTheSameRequestTwice() {
        AuthApi authApi = new AuthApi() {
            @Override public CompletableFuture<AuthDto.AuthResponse> register(AuthDto.RegisterRequest request) { return unsupported(); }
            @Override public CompletableFuture<AuthDto.AuthResponse> login(AuthDto.LoginRequest request) { return CompletableFuture.completedFuture(auth("access-1", "refresh-1")); }
            @Override public CompletableFuture<AuthDto.RefreshResponse> refresh(AuthDto.RefreshRequest request) { return CompletableFuture.completedFuture(new AuthDto.RefreshResponse("access-2", "refresh-2", "Bearer", 3600)); }
            @Override public CompletableFuture<AuthDto.AuthUserResponse> me(String accessToken) { return CompletableFuture.completedFuture(user()); }
            @Override public CompletableFuture<Void> logout(String accessToken, String refreshToken) { return CompletableFuture.completedFuture(null); }
        };
        AuthStateService state = new AuthStateService(authApi, new InMemorySessionStore());
        state.login(new AuthModels.LoginRequest("ada@example.com", "password")).join();
        ApiTransport delegate = new ApiTransport() {
            @Override public CompletableFuture<String> get(String path, Map<String, String> query) { return unsupported(); }
            @Override public CompletableFuture<String> get(String path, Map<String, String> query, Map<String, String> headers) {
                return CompletableFuture.failedFuture(new ApiException(401, "TOKEN_EXPIRED", "expired"));
            }
        };
        var failure = assertThrows(Exception.class,
                () -> new AuthenticatedApiTransport(delegate, state).get("users/u1", Map.of()).join());
        assertTrue(failure.getMessage() != null);
    }

    private static AuthDto.AuthResponse auth(String access, String refresh) {
        return new AuthDto.AuthResponse(access, refresh, "Bearer", 3600,
                new AuthDto.AuthUserResponse("u1", "ada", "Ada", "ada@example.com", null,
                        "2026-09-13T00:00:00Z", "2026-09-13T00:00:00Z"));
    }

    private static AuthDto.AuthUserResponse user() {
        return new AuthDto.AuthUserResponse("u1", "ada", "Ada", "ada@example.com", null,
                "2026-09-13T00:00:00Z", "2026-09-13T00:00:00Z");
    }

    private static <T> CompletableFuture<T> unsupported() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }
}
