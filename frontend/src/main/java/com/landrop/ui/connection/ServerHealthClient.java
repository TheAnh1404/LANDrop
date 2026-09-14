package com.landrop.ui.connection;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Small unauthenticated probe used before realtime connection attempts. */
public final class ServerHealthClient {
    private final URI healthUri;
    private final HttpClient http;

    public ServerHealthClient(URI apiBase) { this(apiBase, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()); }
    ServerHealthClient(URI apiBase, HttpClient http) {
        Objects.requireNonNull(apiBase); this.http = Objects.requireNonNull(http);
        String authority = apiBase.getRawAuthority();
        this.healthUri = URI.create(apiBase.getScheme() + "://" + authority + "/health");
    }
    public URI healthUri() { return healthUri; }
    public CompletableFuture<Void> check() {
        HttpRequest request = HttpRequest.newBuilder(healthUri).GET().timeout(Duration.ofSeconds(5)).build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).thenCompose(response ->
                response.statusCode() >= 200 && response.statusCode() < 300
                        ? CompletableFuture.completedFuture(null)
                        : CompletableFuture.failedFuture(new IllegalStateException("Server health check returned HTTP " + response.statusCode())));
    }
}
