package com.landrop.ui.auth.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.ui.auth.dto.AuthDto;
import com.landrop.ui.auth.mapping.AuthJson;
import com.landrop.ui.cloud.api.ApiException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** HTTP implementation for the Phase 15A auth routes. */
public final class HttpAuthApi implements AuthApi {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final URI baseUri;
    private final HttpClient client;
    private final Duration timeout;

    public HttpAuthApi(URI apiBaseUri) {
        this(apiBaseUri, HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build(), DEFAULT_TIMEOUT);
    }

    public HttpAuthApi(URI apiBaseUri, HttpClient client, Duration timeout) {
        Objects.requireNonNull(apiBaseUri, "apiBaseUri");
        if (!("http".equalsIgnoreCase(apiBaseUri.getScheme()) || "https".equalsIgnoreCase(apiBaseUri.getScheme()))
                || apiBaseUri.getHost() == null || apiBaseUri.getRawUserInfo() != null
                || apiBaseUri.getRawQuery() != null || apiBaseUri.getRawFragment() != null) {
            throw new IllegalArgumentException("Auth API base URI must be an HTTP(S) URL without credentials or query");
        }
        this.baseUri = URI.create(apiBaseUri.toASCIIString().replaceAll("/+$", "") + "/");
        this.client = Objects.requireNonNull(client, "client");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
    }

    @Override public CompletableFuture<AuthDto.AuthResponse> register(AuthDto.RegisterRequest request) {
        return post("register", AuthJson.write(request), null).thenApply(AuthJson::readAuthResponse);
    }

    @Override public CompletableFuture<AuthDto.AuthResponse> login(AuthDto.LoginRequest request) {
        return post("login", AuthJson.write(Map.of("identifier", request.email(), "password", request.password())), null).thenApply(AuthJson::readAuthResponse);
    }

    @Override public CompletableFuture<AuthDto.RefreshResponse> refresh(AuthDto.RefreshRequest request) {
        return post("refresh", AuthJson.write(request), null).thenApply(AuthJson::readRefreshResponse);
    }

    @Override public CompletableFuture<AuthDto.AuthUserResponse> me(String accessToken) {
        return request("GET", "me", null, accessToken).thenApply(AuthJson::readMeResponse);
    }

    @Override public CompletableFuture<Void> logout(String accessToken, String refreshToken) {
        return post("logout", AuthJson.write(new AuthDto.RefreshRequest(refreshToken)), accessToken)
                .thenApply(ignored -> null);
    }

    private CompletableFuture<String> post(String path, String body, String accessToken) {
        return request("POST", path, body, accessToken);
    }

    private CompletableFuture<String> request(String method, String path, String body, String accessToken) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUri.toASCIIString() + "auth/" + path))
                .header("Accept", "application/json")
                .timeout(timeout);
        if (body != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        if (accessToken != null && !accessToken.isBlank()) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                .handle((response, error) -> {
                    if (error != null) {
                        Throwable cause = unwrap(error);
                        String code = cause instanceof HttpTimeoutException ? "TIMEOUT" : "NETWORK_ERROR";
                        throw new ApiException(0, code, "The authentication service is unavailable", cause);
                    }
                    if (response.statusCode() != 200 && response.statusCode() != 201 && response.statusCode() != 204) {
                        throw parseError(response);
                    }
                    if (response.statusCode() == 204) return "{\"data\":{}}";
                    String mediaType = response.headers().firstValue("Content-Type").orElse("")
                            .split(";", 2)[0].trim();
                    if (!"application/json".equalsIgnoreCase(mediaType)) {
                        throw new ApiException(0, "INVALID_RESPONSE", "Authentication service returned an invalid response");
                    }
                    return response.body();
                });
    }

    private static ApiException parseError(HttpResponse<String> response) {
        try {
            JsonNode root = JSON.readTree(response.body());
            JsonNode error = root == null ? null : root.get("error");
            if (root != null && root.path("code").isTextual()) {
                return new ApiException(response.statusCode(), root.path("code").asText(), "Authentication request failed.");
            }
            if (error != null && error.isObject() && error.path("code").isTextual()
                    && error.path("message").isTextual()) {
                return new ApiException(response.statusCode(), error.path("code").asText(),
                        error.path("message").asText());
            }
        } catch (Exception ignored) {
            // Do not expose proxy/server response bodies to the UI.
        }
        return new ApiException(response.statusCode(), "HTTP_ERROR",
                "Authentication request failed (HTTP " + response.statusCode() + ")");
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) cause = cause.getCause();
        return cause;
    }
}
