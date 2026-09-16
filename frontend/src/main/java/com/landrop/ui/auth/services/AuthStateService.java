package com.landrop.ui.auth.services;

import com.landrop.ui.auth.api.AuthApi;
import com.landrop.ui.auth.api.SessionStore;
import com.landrop.ui.auth.dto.AuthDto;
import com.landrop.ui.auth.mapping.AuthMapper;
import com.landrop.ui.auth.model.AuthModels;
import com.landrop.ui.cloud.api.ApiException;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Central authentication/session state. Controllers call operations; they do not call AuthApi. */
public final class AuthStateService {
    private final AuthApi api;
    private final SessionStore sessionStore;

    private final ObjectProperty<AuthModels.AuthUser> user = new SimpleObjectProperty<>();
    private final BooleanProperty authenticated = new SimpleBooleanProperty(false);
    private final BooleanProperty loading = new SimpleBooleanProperty(false);
    private final StringProperty authError = new SimpleStringProperty("");
    private volatile AuthModels.AuthSession session;
    private CompletableFuture<String> refreshInFlight;
    private long generation;
    private volatile String invalidationCode = "SESSION_EXPIRED";
    public String invalidationCode() { return invalidationCode; }

    public synchronized void invalidate(String code) {
        generation++;
        refreshInFlight = null;
        invalidationCode = code;
        clearSession();
        authError.set(com.landrop.ui.bootstrap.BootstrapErrors.message(code));
    }
    public AuthModels.AuthUser getCurrentUser() { return user(); }
    public CompletableFuture<String> validAccessToken() {
        AuthModels.AuthSession current = session;
        if (current != null && current.expiresAt().isAfter(java.time.Instant.now().plusSeconds(30))) {
            return CompletableFuture.completedFuture(current.accessToken());
        }
        return refreshAccessToken();
    }
    private synchronized long epoch() { return generation; }
    private synchronized AuthModels.AuthUser acceptIfCurrent(long expected, AuthModels.AuthSession accepted) {
        if (expected != generation) throw new java.util.concurrent.CancellationException();
        return acceptSession(accepted);
    }

    public AuthStateService(AuthApi api, SessionStore sessionStore) {
        this.api = java.util.Objects.requireNonNull(api, "api");
        this.sessionStore = java.util.Objects.requireNonNull(sessionStore, "sessionStore");
    }

    public ObjectProperty<AuthModels.AuthUser> userProperty() { return user; }
    public BooleanProperty isAuthenticatedProperty() { return authenticated; }
    public BooleanProperty isLoadingProperty() { return loading; }
    public StringProperty authErrorProperty() { return authError; }
    public AuthModels.AuthUser user() { return user.get(); }
    public boolean isAuthenticated() { return authenticated.get(); }
    public boolean isLoading() { return loading.get(); }
    public String authError() { return authError.get(); }
    public Optional<String> accessToken() {
        AuthModels.AuthSession current = session;
        return current == null ? Optional.empty() : Optional.of(current.accessToken());
    }

    public CompletableFuture<AuthModels.AuthUser> login(AuthModels.LoginRequest request) {
        String validation = validateLogin(request);
        if (validation != null) return rejected(validation);
        beginOperation();
        long expected = epoch();
        return api.login(new AuthDto.LoginRequest(request.email().trim(), request.password()))
                .thenApply(AuthMapper::session)
                .thenCompose(this::verifyCurrentUser)
                .thenApply(accepted -> acceptIfCurrent(expected, accepted))
                .whenComplete((ignored, failure) -> finishIfCurrent(expected, failure, "login"));
    }

    public CompletableFuture<AuthModels.AuthUser> register(AuthModels.RegisterRequest request) {
        String validation = validateRegister(request);
        if (validation != null) return rejected(validation);
        beginOperation();
        long expected = epoch();
        return api.register(new AuthDto.RegisterRequest(request.username().trim(), request.displayName().trim(),
                        request.email().trim(), request.password()))
                .thenApply(AuthMapper::session)
                .thenCompose(this::verifyCurrentUser)
                .thenApply(accepted -> acceptIfCurrent(expected, accepted))
                .whenComplete((ignored, failure) -> finishIfCurrent(expected, failure, "register"));
    }

    /** Restores a stored refresh-token session, then verifies identity with /auth/me. */
    public CompletableFuture<AuthModels.AuthUser> restoreSession() {
        beginOperation();
        long expected = epoch();
        Optional<String> stored = sessionStore.loadRefreshToken();
        if (stored.isEmpty()) {
            clearSession();
            finishOperation(null, "restore");
            return CompletableFuture.completedFuture(null);
        }
        return api.refresh(new AuthDto.RefreshRequest(stored.get()))
                .thenApply(AuthMapper::refresh)
                .thenCompose(tokens -> api.me(tokens.accessToken())
                        .thenApply(AuthMapper::user)
                        .thenApply(currentUser -> acceptIfCurrent(expected, new AuthModels.AuthSession(currentUser,
                                tokens.accessToken(), tokens.refreshToken() == null ? stored.get() : tokens.refreshToken(),
                                tokens.tokenType(), tokens.expiresAt()))))
                .whenComplete((ignored, failure) -> finishIfCurrent(expected, failure, "restore"));
    }

    public CompletableFuture<Void> logout() {
        AuthModels.AuthSession current = session;
        String stored = sessionStore.loadRefreshToken().orElse(null);
        invalidate("SESSION_EXPIRED");
        long expected = epoch();
        CompletableFuture<Void> request = (current == null && stored == null)
                ? CompletableFuture.completedFuture(null)
                : api.logout(current == null ? null : current.accessToken(),
                        current == null ? stored : current.refreshToken());
        return request.handle((ignored, failure) -> {
            if (expected == epoch()) authError.set("");
            if (failure != null) throw new CompletionException(safeException(failure, "logout"));
            return null;
        });
    }

    /** Called by an authenticated data transport after a 401. Concurrent callers share one refresh. */
    public CompletableFuture<String> refreshAccessToken() {
        synchronized (this) {
            if (refreshInFlight != null) return refreshInFlight;
            AuthModels.AuthSession current = session;
            long expected = epoch();
            if (current == null || current.refreshToken() == null) {
                return CompletableFuture.failedFuture(new ApiException(401, "SESSION_EXPIRED", "Your session has expired."));
            }
            CompletableFuture<String> started = api.refresh(new AuthDto.RefreshRequest(current.refreshToken()))
                    .thenApply(AuthMapper::refresh)
                    .thenApply(tokens -> {
                        synchronized (this) {
                        if (expected != generation) throw new java.util.concurrent.CancellationException();
                        AuthModels.AuthSession updated = new AuthModels.AuthSession(current.user(), tokens.accessToken(),
                                tokens.refreshToken() == null ? current.refreshToken() : tokens.refreshToken(),
                                tokens.tokenType(), tokens.expiresAt());
                        session = updated;
                        if (updated.refreshToken() != null) sessionStore.saveRefreshToken(updated.refreshToken());
                        return updated.accessToken();
                        }
                    });
            refreshInFlight = started;
            started.whenComplete((ignored, failure) -> {
                synchronized (this) {
                    if (refreshInFlight == started) refreshInFlight = null;
                    if (failure != null && expected == epoch() && !isNetworkFailure(failure)) {
                        String code = com.landrop.ui.bootstrap.BootstrapErrors.code(failure);
                        invalidate(com.landrop.ui.bootstrap.BootstrapErrors.invalidAuth(code) ? code : "SESSION_EXPIRED");
                    }
                }
                if (failure != null && expected == epoch()) setError(userMessage(failure, "refresh"));
            });
            return started;
        }
    }

    private AuthModels.AuthUser acceptSession(AuthModels.AuthSession accepted) {
        session = accepted;
        sessionStore.saveRefreshToken(accepted.refreshToken());
        user.set(accepted.user());
        authenticated.set(true);
        authError.set("");
        return accepted.user();
    }

    private CompletableFuture<AuthModels.AuthSession> verifyCurrentUser(AuthModels.AuthSession accepted) {
        return api.me(accepted.accessToken()).thenApply(AuthMapper::user)
                .thenApply(currentUser -> new AuthModels.AuthSession(currentUser, accepted.accessToken(),
                        accepted.refreshToken(), accepted.tokenType(), accepted.expiresAt()));
    }

    private void clearSession() {
        session = null;
        user.set(null);
        authenticated.set(false);
        sessionStore.clear();
    }

    private void beginOperation() {
        loading.set(true);
        authError.set("");
    }

    private void finishOperation(Throwable failure, String operation) {
        loading.set(false);
        if (failure != null) {
            if ("restore".equals(operation) && !isNetworkFailure(failure)) clearSession();
            setError(userMessage(failure, operation));
        }
    }

    private synchronized void finishIfCurrent(long expected, Throwable failure, String operation) {
        if (expected == generation) finishOperation(failure, operation);
    }
    private static boolean isNetworkFailure(Throwable failure) {
        return java.util.Set.of("NETWORK_ERROR", "TIMEOUT", "SERVER_UNREACHABLE").contains(com.landrop.ui.bootstrap.BootstrapErrors.code(failure));
    }

    private void setError(String message) { authError.set(message); }

    private <T> CompletableFuture<T> rejected(String message) {
        setError(message);
        return CompletableFuture.failedFuture(new IllegalArgumentException(message));
    }

    private static String validateLogin(AuthModels.LoginRequest request) {
        if (request == null || request.email() == null || request.email().isBlank()) return "Enter your email or username.";
        if (request.email().trim().chars().anyMatch(Character::isWhitespace)) return "Enter a valid email or username.";
        if (request.password() == null || request.password().isEmpty()) return "Enter your password.";
        return null;
    }

    private static String validateRegister(AuthModels.RegisterRequest request) {
        if (request == null || blank(request.username())) return "Enter a username.";
        if (blank(request.displayName())) return "Enter a display name.";
        if (blank(request.email()) || !validEmail(request.email())) return "Enter a valid email address.";
        if (request.password() == null || request.password().length() < 8) return "Password must be at least 8 characters.";
        return null;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean validEmail(String email) { return email != null && email.trim().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"); }

    private static String userMessage(Throwable failure, String operation) {
        Throwable error = safeException(failure, operation);
        if (error instanceof ApiException api && (com.landrop.ui.bootstrap.BootstrapErrors.invalidAuth(api.code())
                || java.util.Set.of("SERVER_UNREACHABLE", "NETWORK_ERROR", "TIMEOUT").contains(api.code()))) {
            return com.landrop.ui.bootstrap.BootstrapErrors.message(api.code());
        }
        if ("restore".equals(operation) || "refresh".equals(operation)) {
            return "Your session expired. Please sign in again.";
        }
        if (error instanceof ApiException apiError) {
            if (apiError.statusCode() == 401 || "INVALID_CREDENTIALS".equals(apiError.code())) return "Email or password is incorrect.";
            if (apiError.statusCode() == 409 || "DUPLICATE_EMAIL".equals(apiError.code()) || "DUPLICATE_USERNAME".equals(apiError.code())) return "An account with these details already exists.";
            if (apiError.statusCode() == 0 || "NETWORK_ERROR".equals(apiError.code()) || "TIMEOUT".equals(apiError.code())) return "The authentication service is unavailable. Try again.";
            if (apiError.statusCode() == 400) return "Check the information and try again.";
        }
        return "Authentication failed. Please try again.";
    }

    private static Throwable safeException(Throwable failure, String operation) {
        Throwable error = failure;
        while ((error instanceof CompletionException || error instanceof java.util.concurrent.ExecutionException)
                && error.getCause() != null) error = error.getCause();
        return error;
    }
}
