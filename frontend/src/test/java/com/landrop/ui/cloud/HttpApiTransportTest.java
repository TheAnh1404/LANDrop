package com.landrop.ui.cloud;

import com.landrop.ui.cloud.api.ApiException;
import com.landrop.ui.cloud.api.CloudApi;
import com.landrop.ui.cloud.api.HttpApiTransport;
import com.landrop.ui.cloud.api.PageRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HttpApiTransportTest {
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> query = new AtomicReference<>();
    private final AtomicReference<String> accept = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String contentType = "application/json; charset=utf-8";
    private volatile String body = "";
    private volatile long delayMillis;
    private URI baseUri;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getRawPath());
            query.set(exchange.getRequestURI().getRawQuery());
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            method.set(exchange.getRequestMethod());
            try {
                if (delayMillis > 0) Thread.sleep(delayMillis);
                exchange.getResponseHeaders().set("Content-Type", contentType);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api");
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void encodesIdentifiersAndMapsJsonIntoDomainRecords() {
        body = """
                {"data":{"id":"a/b +?#ü","displayName":"Avery","email":null,"avatarUrl":null,
                "createdAt":"2026-09-13T07:00:00+07:00","updatedAt":"2026-09-13T00:00:00Z",
                "futureField":true}}
                """;
        var user = CloudApi.http(baseUri).users().get("a/b +?#ü").join();
        assertEquals("a/b +?#ü", user.id());
        assertNull(user.email());
        assertEquals(Instant.parse("2026-09-13T00:00:00Z"), user.createdAt());
        assertEquals("/api/users/a%2Fb%20%2B%3F%23%C3%BC", path.get());
        assertEquals("application/json", accept.get());
        assertEquals("GET", method.get());
    }

    @Test
    void preservesApiBasePathAndEncodesOpaqueCursor() {
        body = "{\"data\":[],\"page\":{\"nextCursor\":null}}";
        var page = CloudApi.http(URI.create(baseUri + "/")).messages()
                .list("channel 1", new PageRequest(7, "a+/= ?")).join();
        assertTrue(page.items().isEmpty());
        assertNull(page.nextCursor());
        assertEquals("/api/channels/channel%201/messages", path.get());
        assertEquals("cursor=a%2B%2F%3D%20%3F&limit=7", query.get());
    }

    @Test
    void propagatesStructuredHttpErrorsWithoutUsingMocks() {
        status = 404;
        body = "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"User does not exist\"}}";
        var error = failure(CloudApi.http(baseUri).users().get("missing"));
        assertEquals(404, error.statusCode());
        assertEquals("NOT_FOUND", error.code());
        assertEquals("User does not exist", error.getMessage());
    }

    @Test
    void reportsProxyErrorsWithoutExposingHtmlBody() {
        status = 502;
        contentType = "text/html";
        body = "<html>upstream failure with internal details</html>";
        var error = failure(CloudApi.http(baseUri).workspaces().list(PageRequest.first()));
        assertEquals(502, error.statusCode());
        assertEquals("HTTP_ERROR", error.code());
        assertFalse(error.getMessage().contains("internal details"));
    }

    @Test
    void rejectsWrongContentTypeMalformedJsonAndMissingFields() {
        contentType = "text/html";
        body = "{}";
        assertEquals("INVALID_RESPONSE", failure(CloudApi.http(baseUri).users().get("u")).code());
        contentType = "application/json";
        body = "not json";
        assertEquals("INVALID_RESPONSE", failure(CloudApi.http(baseUri).users().get("u")).code());
        body = "{\"data\":{\"id\":\"u\"}}";
        assertEquals("INVALID_RESPONSE", failure(CloudApi.http(baseUri).users().get("u")).code());
    }

    @Test
    void reportsRequestTimeout() {
        delayMillis = 500;
        var transport = new HttpApiTransport(baseUri, HttpClient.newHttpClient(), Duration.ofMillis(50));
        var error = failure(new CloudApi(transport).users().get("u"));
        assertEquals(0, error.statusCode());
        assertEquals("TIMEOUT", error.code());
    }

    @Test
    void reportsUnreachableServer() {
        server.stop(0);
        var error = failure(CloudApi.http(baseUri).users().get("u"));
        assertEquals(0, error.statusCode());
        assertEquals("NETWORK_ERROR", error.code());
    }

    @Test
    void rejectsInvalidConfigurationAndPagingBeforeIo() {
        assertThrows(IllegalArgumentException.class, () -> CloudApi.http(URI.create("file:///api")));
        assertThrows(IllegalArgumentException.class, () -> CloudApi.http(URI.create("http://user:secret@localhost/api")));
        assertThrows(IllegalArgumentException.class, () -> CloudApi.http(URI.create(baseUri + "?query=1")));
        assertThrows(IllegalArgumentException.class, () -> CloudApi.http(baseUri).users().get(".."));
        assertThrows(IllegalArgumentException.class, () -> CloudApi.http(baseUri).users().get(" "));
        assertThrows(IllegalArgumentException.class, () -> new PageRequest(0, null));
        assertThrows(IllegalArgumentException.class, () -> new PageRequest(101, null));
        assertThrows(IllegalArgumentException.class, () -> new PageRequest(10, ""));
        assertNull(path.get());
    }

    private static ApiException failure(CompletableFuture<?> future) {
        return assertInstanceOf(ApiException.class, assertThrows(CompletionException.class, future::join).getCause());
    }
}
