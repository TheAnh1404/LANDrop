package com.landrop.ui.bootstrap;

import com.landrop.ui.auth.model.AuthModels;
import com.landrop.ui.auth.services.AuthStateService;
import com.landrop.ui.cloud.model.Domain;
import com.landrop.ui.connection.ConnectionState;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Owns startup ordering and rejects completions from a logged-out or replaced bootstrap. */
public final class ClientBootstrapService implements AutoCloseable {
    public interface DeviceEnrollment {
        CompletableFuture<Domain.Device> enroll();
        void clear();
    }
    public interface RealtimeBinding {
        void bind(AuthModels.AuthUser user, Domain.Device device);
        void close();
    }
    private final AuthStateService auth;
    private final Supplier<CompletableFuture<Void>> health;
    private final DeviceEnrollment devices;
    private final RealtimeBinding realtime;
    private final ReadOnlyObjectWrapper<BootstrapState> state = new ReadOnlyObjectWrapper<>(BootstrapState.INITIALIZING);
    private String errorCode = "";
    private long generation;
    private boolean closed;
    private Domain.Device currentDevice;
    private ConnectionState serverConnection = ConnectionState.IDLE;
    private ConnectionState realtimeConnection = ConnectionState.IDLE;
    public synchronized Domain.Device currentDevice() { return currentDevice; }
    public AuthModels.AuthUser currentUser() { return auth.getCurrentUser(); }
    public synchronized ConnectionState serverConnection() { return serverConnection; }
    public synchronized ConnectionState realtimeConnection() { return realtimeConnection; }

    public ClientBootstrapService(AuthStateService auth, Supplier<CompletableFuture<Void>> health,
                                  DeviceEnrollment devices, RealtimeBinding realtime) {
        this.auth = auth; this.health = health; this.devices = devices; this.realtime = realtime;
    }
    public ReadOnlyObjectProperty<BootstrapState> stateProperty() { return state.getReadOnlyProperty(); }
    public BootstrapState state() { return state.get(); }
    public synchronized String errorCode() { return errorCode; }
    public boolean isReady() { return state() == BootstrapState.READY; }
    public synchronized void configurationError(String code) { fail(code); }

    public synchronized CompletableFuture<Void> start() {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Bootstrap closed."));
        long expected = ++generation;
        errorCode = "";
        state.set(BootstrapState.CONNECTING);
        serverConnection = ConnectionState.CONNECTING;
        return health.get().handle((ignored, failure) -> {
            synchronized (this) {
                if (!active(expected)) return false;
                if (failure != null) { serverConnection = ConnectionState.ERROR; fail("SERVER_UNREACHABLE"); return false; }
                serverConnection = ConnectionState.CONNECTED;
                state.set(BootstrapState.AUTHENTICATING);
                return true;
            }
        }).thenCompose(reachable -> {
            if (!reachable) return CompletableFuture.completedFuture(null);
            return auth.restoreSession().handle((user, failure) -> {
                synchronized (this) {
                    if (!active(expected)) return null;
                    if (failure != null) { fail(BootstrapErrors.code(failure)); return null; }
                    if (user == null) { state.set(BootstrapState.AUTH_REQUIRED); return null; }
                    return user;
                }
            }).thenCompose(user -> user == null ? CompletableFuture.completedFuture(null) : enroll(expected, user));
        });
    }
    public synchronized CompletableFuture<AuthModels.AuthUser> login(AuthModels.LoginRequest request) {
        if (closed || state() != BootstrapState.AUTH_REQUIRED) return CompletableFuture.failedFuture(new IllegalStateException("Login is not available."));
        long expected = ++generation;
        errorCode = "";
        state.set(BootstrapState.AUTHENTICATING);
        return auth.login(request).thenCompose(user -> enroll(expected, user).thenApply(ignored -> user))
                .whenComplete((user, failure) -> {
                    synchronized (this) {
                        if (active(expected) && failure != null) {
                            errorCode = BootstrapErrors.code(failure);
                            if (BootstrapErrors.invalidAuth(errorCode)) auth.invalidate(errorCode);
                            state.set(BootstrapState.AUTH_REQUIRED);
                        }
                    }
                });
    }
    private synchronized CompletableFuture<Void> enroll(long expected, AuthModels.AuthUser user) {
        if (!active(expected)) return CompletableFuture.completedFuture(null);
        state.set(BootstrapState.ENROLLING_DEVICE);
        return devices.enroll().handle((device, failure) -> {
            synchronized (this) {
                if (!active(expected)) return null;
                if (failure != null) { fail(BootstrapErrors.code(failure)); return null; }
                currentDevice = device;
                state.set(BootstrapState.CONNECTING);
                realtime.bind(user, device);
                return null;
            }
        });
    }
    public synchronized void connectionChanged(ConnectionState next, String reason) {
        if (closed) return;
        realtimeConnection = next;
        String code = reason == null ? "" : reason.split(":", 2)[0];
        if (BootstrapErrors.invalidAuth(code)) { expire(code); return; }
        if (!code.isBlank() && next == ConnectionState.DISCONNECTED) { fail(code); return; }
        if (next == ConnectionState.CONNECTED && auth.isAuthenticated() && currentDevice != null
                && serverConnection == ConnectionState.CONNECTED) { errorCode = ""; state.set(BootstrapState.READY); }
        else if (next == ConnectionState.RECONNECTING || next == ConnectionState.DISCONNECTED) state.set(BootstrapState.RECONNECTING);
        else if (next == ConnectionState.ERROR) fail(code.isBlank() ? "SERVER_UNREACHABLE" : code);
    }
    public synchronized void expire(String code) {
        if (closed) return;
        generation++;
        errorCode = code;
        state.set(BootstrapState.SESSION_EXPIRED);
        auth.invalidate(code);
        devices.clear();
        currentDevice = null;
        realtimeConnection = ConnectionState.DISCONNECTED;
        // Ignore the socket's synchronous close callback while cleaning up.
        closed = true;
        realtime.close();
        closed = false;
        state.set(BootstrapState.AUTH_REQUIRED);
    }
    public synchronized CompletableFuture<Void> logout() {
        generation++;
        closed = true;
        realtime.close();
        devices.clear();
        currentDevice = null;
        realtimeConnection = ConnectionState.DISCONNECTED;
        CompletableFuture<Void> result = auth.logout();
        closed = false;
        errorCode = "";
        state.set(BootstrapState.AUTH_REQUIRED);
        return result;
    }
    private boolean active(long expected) { return !closed && generation == expected; }
    private void fail(String code) {
        if (BootstrapErrors.invalidAuth(code)) { expire(code); return; }
        errorCode = code;
        state.set(java.util.Set.of("SERVER_UNREACHABLE", "NETWORK_ERROR", "TIMEOUT").contains(code)
                ? BootstrapState.SERVER_UNREACHABLE : BootstrapState.ERROR);
    }
    @Override public synchronized void close() {
        closed = true; generation++; realtime.close(); devices.clear(); currentDevice = null;
        serverConnection = ConnectionState.DISCONNECTED; realtimeConnection = ConnectionState.DISCONNECTED;
    }
}
