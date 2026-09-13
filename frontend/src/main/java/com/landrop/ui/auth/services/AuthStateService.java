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
    private final Object refreshLock = new Object();

    private final ObjectProperty<AuthModels.AuthUser> user = new SimpleObjectProperty<>();
    private final BooleanProperty authenticated = new SimpleBooleanProperty(false);
    private final BooleanProperty loading = new SimpleBooleanProperty(false);
    private final StringProperty authError = new SimpleStringProperty("");
    private volatile AuthModels.AuthSession session;
    private CompletableFuture<String> refreshInFlight;

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
        return api.login(new AuthDto.LoginRequest(request.email().trim(), request.password()))
                .thenApply(AuthMapper::session)
                .thenCompose(this::verifyCurrentUser)
                .thenApply(this::acceptSession)
                .whenComplete((ignored, failure) -> finishOperation(failure, "login"));
    }

    public CompletableFuture<AuthModels.AuthUser> register(AuthModels.RegisterRequest request) {
        String validation = validateRegister(request);
        if (validation != null) return rejected(validation);
        beginOperation();
        return api.register(new AuthDto.RegisterRequest(request.username().trim(), request.displayName().trim(),
                        request.email().trim(), request.password()))
                .thenApply(AuthMapper::session)
                .thenCompose(this::verifyCurrentUser)
                .thenApply(this::acceptSession)
                .whenComplete((ignored, failure) -> finishOperation(failure, "register"));
    }

    /** Restores a stored refresh-token session, then verifies identity with /auth/me. */
    public CompletableFuture<AuthModels.AuthUser> restoreSession() {
        beginOperation();
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
                        .thenApply(currentUser -> acceptRefreshedSession(currentUser, tokens, stored.get())))
                .whenComplete((ignored, failure) -> finishOperation(failure, "restore"));
    }

    public CompletableFuture<Void> logout() {
        AuthModels.AuthSession current = session;
        CompletableFuture<Void> request = (current == null && sessionStore.loadRefreshToken().isEmpty())
                ? CompletableFuture.completedFuture(null)
                : api.logout(current == null ? null : current.accessToken(),
                        current == null ? sessionStore.loadRefreshToken().orElse(null) : current.refreshToken());
        return request.handle((ignored, failure) -> {
            clearSession();
            authError.set("");
            if (failure != null) throw new CompletionException(safeException(failure, "logout"));
            return null;
        });
    }

    /** Called by an authenticated data transport after a 401. Concurrent callers share one refresh. */
    public CompletableFuture<String> refreshAccessToken() {
        synchronized (refreshLock) {
            if (refreshInFlight != null) return refreshInFlight;
            AuthModels.AuthSession current = session;
            if (current == null || current.refreshToken() == null) {
                return CompletableFuture.failedFuture(new ApiException(401, "SESSION_EXPIRED", "Your session has expired."));
            }
            CompletableFuture<String> started = api.refresh(new AuthDto.RefreshRequest(current.refreshToken()))
                    .thenApply(AuthMapper::refresh)
                    .thenApply(tokens -> {
                        AuthModels.AuthSession updated = new AuthModels.AuthSession(current.user(), tokens.accessToken(),
                                tokens.refreshToken() == null ? current.refreshToken() : tokens.refreshToken(),
                                tokens.tokenType(), tokens.expiresAt());
                        session = updated;
                        if (updated.refreshToken() != null) sessionStore.saveRefreshToken(updated.refreshToken());
                        return updated.accessToken();
                    });
            refreshInFlight = started;
            started.whenComplete((ignored, failure) -> {
                synchronized (refreshLock) {
                    refreshInFlight = null;
                    if (failure != null) clearSession();
                }
                if (failure != null) setError("Your session expired. Please sign in again.");
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

    private AuthModels.AuthUser acceptRefreshedSession(AuthModels.AuthUser accepted,
                                                       AuthModels.RefreshTokens tokens, String previousRefresh) {
        String refreshToken = tokens.refreshToken() == null ? previousRefresh : tokens.refreshToken();
        AuthModels.AuthSession restored = new AuthModels.AuthSession(accepted, tokens.accessToken(), refreshToken,
                tokens.tokenType(), tokens.expiresAt());
        session = restored;
        sessionStore.saveRefreshToken(refreshToken);
        user.set(accepted);
        authenticated.set(true);
        authError.set("");
        return accepted;
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
            if ("restore".equals(operation)) clearSession();
            setError(userMessage(failure, operation));
        }
    }

    private void setError(String message) { authError.set(message); }

    private <T> CompletableFuture<T> rejected(String message) {
        setError(message);
        return CompletableFuture.failedFuture(new IllegalArgumentException(message));
    }

    private static String validateLogin(AuthModels.LoginRequest request) {
        if (request == null || request.email() == null || request.email().isBlank()) return "Enter your email address.";
        if (!validEmail(request.email())) return "Enter a valid email address.";
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
