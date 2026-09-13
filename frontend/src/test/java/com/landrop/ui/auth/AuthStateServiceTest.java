package com.landrop.ui.auth;

import com.landrop.ui.auth.api.AuthApi;
import com.landrop.ui.auth.api.InMemorySessionStore;
import com.landrop.ui.auth.dto.AuthDto;
import com.landrop.ui.auth.model.AuthModels;
import com.landrop.ui.auth.services.AuthStateService;
import com.landrop.ui.cloud.api.ApiException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AuthStateServiceTest {
    private static final String CREATED = "2026-09-13T00:00:00Z";
    private static final String UPDATED = "2026-09-13T00:01:00Z";

    @Test
    void loginStoresRefreshTokenAndPublishesCurrentUser() {
        FakeAuthApi api = new FakeAuthApi();
        InMemorySessionStore store = new InMemorySessionStore();
        AuthStateService state = new AuthStateService(api, store);

        var user = state.login(new AuthModels.LoginRequest("ada@example.com", "password")).join();

        assertEquals("user-1", user.id());
        assertTrue(state.isAuthenticated());
        assertSame(user, state.user());
        assertEquals("refresh-1", store.loadRefreshToken().orElseThrow());
        assertEquals("", state.authError());
    }

    @Test
    void validationPreventsRequestsAndUsesSafeUserFacingErrors() {
        FakeAuthApi api = new FakeAuthApi();
        AuthStateService state = new AuthStateService(api, new InMemorySessionStore());

        assertThrows(CompletionException.class,
                () -> state.login(new AuthModels.LoginRequest("bad", "password")).join());
        assertEquals("Enter a valid email address.", state.authError());
        assertEquals(0, api.loginCalls.get());

        api.loginFailure = new ApiException(401, "INTERNAL_DETAIL", "database stack trace");
        assertThrows(Exception.class,
                () -> state.login(new AuthModels.LoginRequest("ada@example.com", "password")).join());
        assertEquals("Email or password is incorrect.", state.authError());
        assertFalse(state.authError().contains("database"));
    }

    @Test
    void restoreRefreshesThenVerifiesCurrentUser() {
        FakeAuthApi api = new FakeAuthApi();
        InMemorySessionStore store = new InMemorySessionStore();
        store.saveRefreshToken("persisted-refresh");
        AuthStateService state = new AuthStateService(api, store);

        state.restoreSession().join();

        assertTrue(state.isAuthenticated());
        assertEquals("persisted-refresh", store.loadRefreshToken().orElseThrow());
        assertEquals(1, api.refreshCalls.get());
        assertEquals(1, api.meCalls.get());
    }

    @Test
    void invalidRestoreClearsStaleSessionAndDoesNotExposeServerDetails() {
        FakeAuthApi api = new FakeAuthApi();
        api.refreshFailure = new ApiException(401, "TOKEN_REVOKED", "refresh token is revoked internally");
        InMemorySessionStore store = new InMemorySessionStore();
        store.saveRefreshToken("stale");
        AuthStateService state = new AuthStateService(api, store);

        assertThrows(Exception.class, () -> state.restoreSession().join());

        assertFalse(state.isAuthenticated());
        assertTrue(store.loadRefreshToken().isEmpty());
        assertEquals("Your session expired. Please sign in again.", state.authError());
        assertFalse(state.authError().contains("revoked"));
    }

    @Test
    void concurrentExpiredRequestsShareOneRefreshOperation() {
        FakeAuthApi api = new FakeAuthApi();
        AuthStateService state = new AuthStateService(api, new InMemorySessionStore());
        state.login(new AuthModels.LoginRequest("ada@example.com", "password")).join();

        api.deferRefresh = true;
        CompletableFuture<String> first = state.refreshAccessToken();
        CompletableFuture<String> second = state.refreshAccessToken();

        assertSame(first, second);
        api.pendingRefresh.complete(new AuthDto.RefreshResponse("access-2", "refresh-2", "Bearer", 3600));
        assertEquals("access-2", first.join());
        assertEquals(1, api.refreshCalls.get());
    }

    @Test
    void logoutClearsStateEvenWhenServerIsUnavailable() {
        FakeAuthApi api = new FakeAuthApi();
        AuthStateService state = new AuthStateService(api, new InMemorySessionStore());
        state.login(new AuthModels.LoginRequest("ada@example.com", "password")).join();
        api.logoutFailure = new ApiException(0, "NETWORK_ERROR", "connection details");

        assertThrows(Exception.class, () -> state.logout().join());
        assertFalse(state.isAuthenticated());
        assertNull(state.user());
        assertTrue(state.accessToken().isEmpty());
        assertEquals("", state.authError());
    }

    private static final class FakeAuthApi implements AuthApi {
        private final AtomicInteger loginCalls = new AtomicInteger();
        private final AtomicInteger refreshCalls = new AtomicInteger();
        private final AtomicInteger meCalls = new AtomicInteger();
        private Throwable loginFailure;
        private Throwable refreshFailure;
        private Throwable logoutFailure;
        private boolean deferRefresh;
        private CompletableFuture<AuthDto.RefreshResponse> pendingRefresh;

        @Override public CompletableFuture<AuthDto.AuthResponse> register(AuthDto.RegisterRequest request) {
            return CompletableFuture.completedFuture(auth("access-register", "refresh-register"));
        }

        @Override public CompletableFuture<AuthDto.AuthResponse> login(AuthDto.LoginRequest request) {
            loginCalls.incrementAndGet();
            return loginFailure == null ? CompletableFuture.completedFuture(auth("access-1", "refresh-1"))
                    : CompletableFuture.failedFuture(loginFailure);
        }

        @Override public CompletableFuture<AuthDto.RefreshResponse> refresh(AuthDto.RefreshRequest request) {
            int call = refreshCalls.incrementAndGet();
            if (deferRefresh) {
                pendingRefresh = new CompletableFuture<>();
                return pendingRefresh;
            }
            return refreshFailure == null ? CompletableFuture.completedFuture(
                    new AuthDto.RefreshResponse("access-" + (call + 1), null, "Bearer", 3600))
                    : CompletableFuture.failedFuture(refreshFailure);
        }

        @Override public CompletableFuture<AuthDto.AuthUserResponse> me(String accessToken) {
            meCalls.incrementAndGet();
            return CompletableFuture.completedFuture(user());
        }

        @Override public CompletableFuture<Void> logout(String accessToken, String refreshToken) {
            return logoutFailure == null ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.failedFuture(logoutFailure);
        }

        private static AuthDto.AuthResponse auth(String accessToken, String refreshToken) {
            return new AuthDto.AuthResponse(accessToken, refreshToken, "Bearer", 3600, user());
        }

        private static AuthDto.AuthUserResponse user() {
            return new AuthDto.AuthUserResponse("user-1", "ada", "Ada", "ada@example.com", null, CREATED, UPDATED);
        }
    }
}
