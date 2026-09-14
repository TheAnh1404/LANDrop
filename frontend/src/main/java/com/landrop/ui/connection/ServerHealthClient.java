package com.landrop.ui.connection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Small unauthenticated probe used before realtime connection attempts. */
public final class ServerHealthClient {
    public record HealthInfo(String status, String serverVersion, int protocolVersion, long uptimeSeconds) {
        public static HealthInfo unavailable() { return new HealthInfo("unavailable", "", -1, 0); }
    }
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final URI healthUri;
    private final HttpClient http;
    private final ObjectProperty<HealthInfo> health = new SimpleObjectProperty<>(HealthInfo.unavailable());

    public ServerHealthClient(URI apiBase) { this(apiBase, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()); }
    ServerHealthClient(URI apiBase, HttpClient http) {
        Objects.requireNonNull(apiBase); this.http = Objects.requireNonNull(http);
        String authority = apiBase.getRawAuthority();
        this.healthUri = URI.create(apiBase.getScheme() + "://" + authority + "/health");
    }
    public URI healthUri() { return healthUri; }
    public ObjectProperty<HealthInfo> healthProperty() { return health; }
    public HealthInfo health() { return health.get(); }
    public CompletableFuture<Void> check() {
        HttpRequest request = HttpRequest.newBuilder(healthUri).GET().timeout(Duration.ofSeconds(5)).build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenCompose(response -> {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return CompletableFuture.<Void>failedFuture(new IllegalStateException("Server health check returned HTTP " + response.statusCode()));
            }
            try {
                JsonNode body = JSON.readTree(response.body());
                String status = body.path("status").asText("");
                if (!"ok".equalsIgnoreCase(status)) throw new IllegalStateException("Server reported status: " + status);
                health.set(new HealthInfo(status, body.path("serverVersion").asText("unknown"),
                        body.path("protocolVersion").asInt(-1), body.path("uptime").asLong(body.path("uptimeSeconds").asLong(0))));
                return CompletableFuture.<Void>completedFuture(null);
            } catch (Exception error) {
                health.set(HealthInfo.unavailable());
                return CompletableFuture.<Void>failedFuture(new IllegalStateException("Invalid server health response.", error));
            }
        }).whenComplete((ignored, error) -> { if (error != null) health.set(HealthInfo.unavailable()); });
    }
}
