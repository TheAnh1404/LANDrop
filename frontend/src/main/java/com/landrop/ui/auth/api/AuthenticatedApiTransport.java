package com.landrop.ui.auth.api;

import com.landrop.ui.auth.services.AuthStateService;
import com.landrop.ui.cloud.api.ApiException;
import com.landrop.ui.cloud.api.ApiTransport;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

/** Adds the current access token and retries one expired request after a coordinated refresh. */
public final class AuthenticatedApiTransport implements ApiTransport {
    private final ApiTransport delegate;
    private final AuthStateService authState;

    public AuthenticatedApiTransport(ApiTransport delegate, AuthStateService authState) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.authState = Objects.requireNonNull(authState, "authState");
    }

    @Override public CompletableFuture<String> get(String path, Map<String, String> query) {
        return request(path, query, false);
    }

    private CompletableFuture<String> request(String path, Map<String, String> query, boolean retried) {
        String accessToken = authState.accessToken().orElse(null);
        if (accessToken == null) {
            return CompletableFuture.failedFuture(new ApiException(401, "UNAUTHENTICATED", "Sign in is required."));
        }
        return delegate.get(path, query, Map.of("Authorization", "Bearer " + accessToken))
                .handle((body, failure) -> {
                    Throwable error = unwrap(failure);
                    if (!retried && error instanceof ApiException apiError && apiError.statusCode() == 401) {
                        return authState.refreshAccessToken().thenCompose(ignored -> request(path, query, true));
                    }
                    return failure == null ? CompletableFuture.completedFuture(body) : CompletableFuture.<String>failedFuture(error);
                }).thenCompose(Function.identity());
    }

    private static Throwable unwrap(Throwable error) {
        if (error == null) return null;
        Throwable result = error;
        while ((result instanceof CompletionException || result instanceof java.util.concurrent.ExecutionException)
                && result.getCause() != null) result = result.getCause();
        return result;
    }
}
