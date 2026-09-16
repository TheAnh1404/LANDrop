package com.landrop.ui.device;

import com.landrop.ui.auth.api.*;
import com.landrop.ui.auth.dto.AuthDto;
import com.landrop.ui.auth.model.AuthModels;
import com.landrop.ui.auth.services.AuthStateService;
import com.landrop.ui.bootstrap.ClientInstanceService;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class CurrentDeviceServiceTest {
    @Test void enrollmentAndRenameUseServerIdentityWithoutLanPeerOrSelfAssignedUser() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var instance = new ClientInstanceService("enrollment-" + System.nanoTime());
        instance.setDeviceName("Anh-Laptop");
        AtomicReference<String> body = new AtomicReference<>(), method = new AtomicReference<>(), authorization = new AtomicReference<>();
        server.createContext("/api/devices", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            method.set(exchange.getRequestMethod()); authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String name = method.get().equals("PATCH") ? "Anh-PC-Office" : "Anh-Laptop";
            byte[] response = ("{\"id\":\"server-device\",\"userId\":\"user-1\",\"clientInstanceId\":\""
                    + instance.clientInstanceId() + "\",\"name\":\"" + name + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(method.get().equals("POST") ? 201 : 200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            var auth = new AuthStateService(new FakeApi(), new InMemorySessionStore());
            auth.login(new AuthModels.LoginRequest("anh@example.com", "password")).join();
            var service = new CurrentDeviceService(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api"), auth, instance);
            var device = service.enroll().join();
            assertEquals("server-device", device.id()); assertEquals("user-1", device.userId());
            var request = JsonMapper.builder().build().readTree(body.get());
            assertEquals(instance.clientInstanceId(), request.path("clientInstanceId").asText());
            assertTrue(request.has("platform")); assertTrue(request.has("clientVersion"));
            assertFalse(request.has("userId")); assertFalse(request.has("deviceId")); assertFalse(request.has("peerId"));
            assertEquals("Bearer access", authorization.get());
            var renamed = service.rename("Anh-PC-Office").join();
            assertEquals("PATCH", method.get()); assertEquals(device.id(), renamed.id());
            assertEquals("Anh-PC-Office", service.currentDevice().label());
            assertEquals("Anh-PC-Office", instance.deviceName());
            service.clear(); assertNull(service.currentDevice());
        } finally { server.stop(0); }
    }
    private static class FakeApi implements AuthApi {
        final AuthDto.AuthUserResponse user = new AuthDto.AuthUserResponse("user-1", "anh", "Anh", "anh@example.com", null, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z");
        public CompletableFuture<AuthDto.AuthResponse> login(AuthDto.LoginRequest request) { return CompletableFuture.completedFuture(new AuthDto.AuthResponse("access", "refresh", "Bearer", 3600, user)); }
        public CompletableFuture<AuthDto.AuthResponse> register(AuthDto.RegisterRequest request) { throw new UnsupportedOperationException(); }
        public CompletableFuture<AuthDto.RefreshResponse> refresh(AuthDto.RefreshRequest request) { throw new UnsupportedOperationException(); }
        public CompletableFuture<AuthDto.AuthUserResponse> me(String token) { return CompletableFuture.completedFuture(user); }
        public CompletableFuture<Void> logout(String a, String r) { return CompletableFuture.completedFuture(null); }
    }
}
