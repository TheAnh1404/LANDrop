package com.landrop.ui.bootstrap;

import com.landrop.ui.auth.api.*;
import com.landrop.ui.auth.dto.AuthDto;
import com.landrop.ui.auth.model.AuthModels;
import com.landrop.ui.auth.services.AuthStateService;
import com.landrop.ui.cloud.api.ApiException;
import com.landrop.ui.cloud.model.Domain;
import com.landrop.ui.connection.ConnectionState;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class ClientBootstrapServiceTest {
    private static final AuthModels.LoginRequest LOGIN = new AuthModels.LoginRequest("anh@example.com", "password");
    @Test void firstStartRequiresLoginWithoutEnrollingOrOpeningSocket() {
        Fixture f = new Fixture(); f.bootstrap.start().join();
        assertEquals(BootstrapState.AUTH_REQUIRED, f.bootstrap.state());
        assertEquals(0, f.enrollCalls); assertEquals(0, f.bindCalls);
    }
    @Test void readyRequiresMeEnrollmentAndAuthenticatedWelcome() {
        Fixture f = new Fixture(); f.bootstrap.start().join();
        var login = f.bootstrap.login(LOGIN);
        assertEquals(BootstrapState.ENROLLING_DEVICE, f.bootstrap.state());
        assertEquals("user-1", f.auth.getCurrentUser().id());
        assertEquals(1, f.api.meCalls); assertEquals(0, f.bindCalls);
        f.enrolled.complete(f.device); login.join();
        assertEquals(BootstrapState.CONNECTING, f.bootstrap.state());
        assertEquals(1, f.bindCalls);
        f.bootstrap.connectionChanged(ConnectionState.CONNECTED, "");
        assertTrue(f.bootstrap.isReady());
    }
    @Test void restartRestoresAndResolvesDeviceBeforeBinding() {
        Fixture f = new Fixture(); f.store.saveRefreshToken("stored");
        var restore = f.bootstrap.start();
        assertEquals(1, f.api.refreshCalls); assertEquals(1, f.api.meCalls);
        assertEquals(BootstrapState.ENROLLING_DEVICE, f.bootstrap.state());
        f.enrolled.complete(f.device); restore.join(); assertEquals(1, f.bindCalls);
    }
    @Test void revokedExpiredAndDisabledSessionsCleanUpWithoutRestart() {
        for (String code : new String[]{"SESSION_REVOKED", "SESSION_EXPIRED", "ACCOUNT_DISABLED"}) {
            Fixture f = ready(); f.bootstrap.connectionChanged(ConnectionState.DISCONNECTED, code);
            assertEquals(BootstrapState.AUTH_REQUIRED, f.bootstrap.state());
            assertEquals(code, f.bootstrap.errorCode()); assertFalse(f.auth.isAuthenticated());
            assertNull(f.auth.getCurrentUser()); assertTrue(f.store.loadRefreshToken().isEmpty());
            assertEquals(1, f.closeCalls); assertEquals(1, f.clearCalls);
        }
    }
    @Test void temporaryNetworkLossKeepsSessionAndRestoresReady() {
        Fixture f = ready(); f.bootstrap.connectionChanged(ConnectionState.RECONNECTING, "");
        assertEquals(BootstrapState.RECONNECTING, f.bootstrap.state()); assertTrue(f.auth.isAuthenticated());
        f.bootstrap.connectionChanged(ConnectionState.CONNECTED, ""); assertTrue(f.bootstrap.isReady());
        assertEquals(1, f.api.loginCalls); assertEquals(1, f.bindCalls);
    }
    @Test void logoutDuringEnrollmentRejectsLateBindingAndPreservesInstallation() {
        Fixture f = new Fixture(); f.bootstrap.start().join(); f.bootstrap.login(LOGIN);
        String profile = "logout-" + System.nanoTime(); String instance = new ClientInstanceService(profile).clientInstanceId();
        f.bootstrap.logout().join(); f.enrolled.complete(f.device);
        assertEquals(BootstrapState.AUTH_REQUIRED, f.bootstrap.state()); assertEquals(0, f.bindCalls);
        assertEquals(1, f.api.logoutCalls); assertFalse(f.auth.isAuthenticated());
        assertEquals(instance, new ClientInstanceService(profile).clientInstanceId());
    }
    @Test void badCredentialsAndDisabledAccountUseSafeMessages() {
        for (String code : new String[]{"INVALID_CREDENTIALS", "ACCOUNT_DISABLED"}) {
            Fixture f = new Fixture(); f.bootstrap.start().join(); f.api.failure = new ApiException(403, code, "private backend stack");
            assertThrows(Exception.class, () -> f.bootstrap.login(LOGIN).join());
            assertEquals(BootstrapState.AUTH_REQUIRED, f.bootstrap.state()); assertEquals(0, f.enrollCalls);
            assertFalse(f.auth.authError().contains("private"));
        }
    }
    @Test void unreachableServerDoesNotSendStoredCredentials() {
        Fixture f = new Fixture(); f.store.saveRefreshToken("stored");
        f.health = CompletableFuture.failedFuture(new java.io.IOException()); f.bootstrap.start().join();
        assertEquals(BootstrapState.SERVER_UNREACHABLE, f.bootstrap.state()); assertEquals(0, f.api.refreshCalls);
        assertTrue(f.store.loadRefreshToken().isPresent());
    }
    @Test void closedBootstrapCannotRestoreOrBindLateRequests() {
        Fixture f = new Fixture(); f.health = new CompletableFuture<>(); var start = f.bootstrap.start();
        f.bootstrap.close(); f.auth.invalidate("SESSION_EXPIRED"); f.health.complete(null); start.join();
        assertEquals(0, f.api.refreshCalls); assertEquals(0, f.bindCalls);
    }
    private static Fixture ready() {
        Fixture f = new Fixture(); f.bootstrap.start().join(); f.enrolled.complete(f.device); f.bootstrap.login(LOGIN).join();
        f.bootstrap.connectionChanged(ConnectionState.CONNECTED, ""); return f;
    }
    private static class Fixture {
        final FakeApi api = new FakeApi(); final InMemorySessionStore store = new InMemorySessionStore();
        final AuthStateService auth = new AuthStateService(api, store);
        CompletableFuture<Void> health = CompletableFuture.completedFuture(null);
        final Domain.Device device = new Domain.Device("device-1", "user-1", "Anh-Laptop", false);
        final CompletableFuture<Domain.Device> enrolled = new CompletableFuture<>();
        int enrollCalls, bindCalls, closeCalls, clearCalls;
        final ClientBootstrapService bootstrap = new ClientBootstrapService(auth, () -> health,
                new ClientBootstrapService.DeviceEnrollment() {
                    public CompletableFuture<Domain.Device> enroll() { enrollCalls++; return enrolled; }
                    public void clear() { clearCalls++; }
                }, new ClientBootstrapService.RealtimeBinding() {
                    public void bind(AuthModels.AuthUser user, Domain.Device device) { assertEquals(user.id(), device.userId()); bindCalls++; }
                    public void close() { closeCalls++; }
                });
    }
    private static class FakeApi implements AuthApi {
        int loginCalls, refreshCalls, meCalls, logoutCalls; Throwable failure;
        final AuthDto.AuthUserResponse user = new AuthDto.AuthUserResponse("user-1", "anh", "Anh", "anh@example.com", null, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z");
        public CompletableFuture<AuthDto.AuthResponse> login(AuthDto.LoginRequest request) {
            loginCalls++; return failure == null ? CompletableFuture.completedFuture(new AuthDto.AuthResponse("access", "refresh", "Bearer", 3600, user)) : CompletableFuture.failedFuture(failure);
        }
        public CompletableFuture<AuthDto.AuthResponse> register(AuthDto.RegisterRequest request) { throw new UnsupportedOperationException(); }
        public CompletableFuture<AuthDto.RefreshResponse> refresh(AuthDto.RefreshRequest request) {
            refreshCalls++; return CompletableFuture.completedFuture(new AuthDto.RefreshResponse("restored", "rotated", "Bearer", 3600));
        }
        public CompletableFuture<AuthDto.AuthUserResponse> me(String token) { meCalls++; return CompletableFuture.completedFuture(user); }
        public CompletableFuture<Void> logout(String access, String refresh) { logoutCalls++; return CompletableFuture.completedFuture(null); }
    }
}
