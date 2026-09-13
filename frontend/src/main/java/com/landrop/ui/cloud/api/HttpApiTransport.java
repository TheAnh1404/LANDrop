package com.landrop.ui.cloud.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/** Asynchronous GET transport. It never falls back to mock data on failure. */
public final class HttpApiTransport implements ApiTransport {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final URI baseUri;
    private final HttpClient client;
    private final Duration timeout;

    public HttpApiTransport(URI baseUri) {
        this(baseUri, HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER).build(), DEFAULT_TIMEOUT);
    }

    public HttpApiTransport(URI baseUri, HttpClient client, Duration timeout) {
        Objects.requireNonNull(baseUri, "baseUri");
        String scheme = baseUri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || baseUri.getHost() == null || baseUri.getRawUserInfo() != null
                || baseUri.getRawQuery() != null || baseUri.getRawFragment() != null) {
            throw new IllegalArgumentException("baseUri must be an HTTP(S) API URL without credentials, query or fragment");
        }
        this.baseUri = URI.create(baseUri.toASCIIString().replaceAll("/+$", "") + "/");
        this.client = Objects.requireNonNull(client, "client");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    @Override
    public CompletableFuture<String> get(String path, Map<String, String> query) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(query, "query");
        URI relative = URI.create(path);
        if (path.isBlank() || relative.isAbsolute() || path.startsWith("/")
                || relative.getRawQuery() != null || relative.getRawFragment() != null
                || !relative.normalize().equals(relative)) {
            throw new IllegalArgumentException("path must be a relative API resource path");
        }
        String suffix = query.isEmpty() ? "" : "?" + query.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> ApiPaths.encode(entry.getKey()) + "=" + ApiPaths.encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUri.toASCIIString() + path + suffix))
                .header("Accept", "application/json").timeout(timeout).GET().build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle((response, error) -> {
                    if (error != null) {
                        Throwable cause = error;
                        while (cause instanceof CompletionException && cause.getCause() != null) {
                            cause = cause.getCause();
                        }
                        String code = cause instanceof HttpTimeoutException ? "TIMEOUT" : "NETWORK_ERROR";
                        throw new ApiException(0, code, "Unable to read the persistent-data API", cause);
                    }
                    if (response.statusCode() != 200) {
                        throw httpError(response);
                    }
                    String mediaType = response.headers().firstValue("Content-Type").orElse("")
                            .split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                    if (!mediaType.equals("application/json")) {
                        throw new ApiException(0, "INVALID_RESPONSE", "Expected an application/json response");
                    }
                    return response.body();
                });
    }

    private static ApiException httpError(HttpResponse<String> response) {
        try {
            JsonNode root = JSON.readTree(response.body());
            JsonNode error = root == null ? null : root.get("error");
            if (error != null && error.isObject() && error.path("code").isTextual()
                    && !error.path("code").asText().isBlank() && error.path("message").isTextual()
                    && !error.path("message").asText().isBlank()) {
                return new ApiException(response.statusCode(), error.get("code").asText(),
                        error.get("message").asText());
            }
        } catch (Exception ignored) {
            // HTML/proxy errors are represented without exposing their raw response body.
        }
        return new ApiException(response.statusCode(), "HTTP_ERROR",
                "Persistent-data API returned HTTP " + response.statusCode());
    }
}
