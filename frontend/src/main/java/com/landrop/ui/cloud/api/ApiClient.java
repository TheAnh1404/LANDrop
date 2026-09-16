package com.landrop.ui.cloud.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.landrop.ui.auth.services.AuthStateService;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Shared authenticated HTTP foundation for feature APIs after login. */
public final class ApiClient {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final URI base; private final AuthStateService auth; private final HttpClient http; private final Duration timeout;
    public ApiClient(URI base, AuthStateService auth){this(base,auth,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),Duration.ofSeconds(10));}
    public ApiClient(URI base,AuthStateService auth,HttpClient http,Duration timeout){this.base=URI.create(Objects.requireNonNull(base).toASCIIString().replaceAll("/+$","")+"/");this.auth=Objects.requireNonNull(auth);this.http=Objects.requireNonNull(http);this.timeout=Objects.requireNonNull(timeout);}
    public CompletableFuture<String> request(String method,String relativePath,String body){return request(method,relativePath,body,false);}
    private CompletableFuture<String> request(String method, String path, String body, boolean retried) {
        String token = auth.accessToken().orElse(null);
        if (token == null || token.isBlank()) {
            return CompletableFuture.failedFuture(new ApiException(401, "UNAUTHENTICATED", "Sign in is required."));
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .timeout(timeout);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));

        return http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).thenCompose(response -> {
            if (response.statusCode() < 400) return CompletableFuture.completedFuture(response.body());

            ApiException failure = error(response.statusCode(), response.body());
            // An expired access token is commonly reported as UNAUTHORIZED. Refresh before
            // treating its error code as terminal, otherwise the refresh credential is lost.
            if (failure.statusCode() == 401 && !retried) {
                return auth.refreshAccessToken().thenCompose(ignored -> request(method, path, body, true));
            }
            if (com.landrop.ui.bootstrap.BootstrapErrors.invalidAuth(failure.code())) {
                auth.invalidate(failure.code());
            } else if (failure.statusCode() == 401) {
                auth.invalidate("SESSION_EXPIRED");
            }
            return CompletableFuture.failedFuture(failure);
        });
    }
    private static ApiException error(int status,String body){try{JsonNode root=JSON.readTree(body); JsonNode error=root.path("error").isObject()?root.path("error"):root;String code=error.path("code").asText("HTTP_ERROR"),message=error.path("message").asText("API request failed.");return new ApiException(status,code,message);}catch(Exception ignored){return new ApiException(status,"HTTP_ERROR","API request failed with HTTP "+status);}}
}
