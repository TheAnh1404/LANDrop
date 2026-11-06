package com.landrop.auth.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.auth.dto.AuthResponse;
import com.landrop.auth.dto.LoginRequest;
import com.landrop.auth.dto.RegisterRequest;
import com.landrop.auth.dto.UserDto;
import com.landrop.auth.exception.AuthException;
import com.landrop.auth.exception.RateLimitException;
import com.landrop.auth.model.AuthErrorCode;
import com.landrop.auth.security.AuthRateLimiter;
import com.landrop.auth.security.JwtService;
import com.landrop.auth.service.AuthService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class AuthRateLimitTest {

    private static final ObjectMapper JSON = AuthHttpServer.createObjectMapper();
    private HttpServer server;
    private int port;
    private HttpClient http;
    private AuthRateLimiter rateLimiter;
    private MockAuthService mockAuthService;

    @BeforeEach
    void setUp() throws IOException {
        mockAuthService = new MockAuthService();
        rateLimiter = new AuthRateLimiter(3, 2, 2, Duration.ofSeconds(30)); // 3 IP logins, 2 IP registers, 2 account logins

        AuthMiddleware middleware = new AuthMiddleware(new JwtService());
        AuthHttpHandler handler = new AuthHttpHandler(mockAuthService, middleware, null, JSON, rateLimiter);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        handler.register(server);
        server.start();

        port = server.getAddress().getPort();
        http = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Unit: AuthRateLimiter enforces IP login threshold and reports retry-after")
    void testRateLimiterIpLogin() {
        AuthRateLimiter limiter = new AuthRateLimiter(2, 2, 2, Duration.ofSeconds(60));
        String ip = "192.168.1.100";

        assertDoesNotThrow(() -> limiter.checkIpLogin(ip));
        limiter.recordIpLoginAttempt(ip);

        assertDoesNotThrow(() -> limiter.checkIpLogin(ip));
        limiter.recordIpLoginAttempt(ip);

        RateLimitException ex = assertThrows(RateLimitException.class, () -> limiter.checkIpLogin(ip));
        assertEquals(429, ex.getStatusCode());
        assertEquals(AuthErrorCode.TOO_MANY_REQUESTS, ex.getErrorCode());
        assertTrue(ex.getRetryAfterSeconds() > 0 && ex.getRetryAfterSeconds() <= 60);

        // Different IP is unaffected
        assertDoesNotThrow(() -> limiter.checkIpLogin("192.168.1.200"));
    }

    @Test
    @DisplayName("Unit: AuthRateLimiter enforces account failed login threshold and clears on success")
    void testRateLimiterAccountFailedLogin() {
        AuthRateLimiter limiter = new AuthRateLimiter(10, 5, 2, Duration.ofSeconds(60));
        String account = "Alice@landrop.local";

        assertDoesNotThrow(() -> limiter.checkAccountLogin(account));
        limiter.recordAccountLoginFailure(account);

        assertDoesNotThrow(() -> limiter.checkAccountLogin("  alice@landrop.local "));
        limiter.recordAccountLoginFailure("  alice@landrop.local ");

        // Exceeded
        RateLimitException ex = assertThrows(RateLimitException.class, () -> limiter.checkAccountLogin("alice@landrop.local"));
        assertEquals(429, ex.getStatusCode());

        // Successful login resets account failed counter
        limiter.recordAccountLoginSuccess("alice@landrop.local");
        assertDoesNotThrow(() -> limiter.checkAccountLogin("alice@landrop.local"));
    }

    @Test
    @DisplayName("HTTP: IP login rate limit exceeded returns HTTP 429 and Retry-After header")
    void testHttpIpLoginRateLimit() throws Exception {
        mockAuthService.failLogins = true;

        // First 3 attempts fail with 401 using distinct accounts to isolate IP limit
        for (int i = 0; i < 3; i++) {
            String body = JSON.writeValueAsString(new LoginRequest("user_" + i, "wrong-password"));
            HttpResponse<String> res = post("/api/auth/login", body, "10.0.0.1");
            assertEquals(401, res.statusCode(), "Attempt " + (i + 1) + " should be 401");
        }

        // 4th attempt from same IP is blocked before running login (HTTP 429)
        String body4 = JSON.writeValueAsString(new LoginRequest("user_4", "wrong-password"));
        HttpResponse<String> resBlocked = post("/api/auth/login", body4, "10.0.0.1");
        assertEquals(429, resBlocked.statusCode(), "4th attempt should be blocked with HTTP 429");
        assertTrue(resBlocked.headers().firstValue("Retry-After").isPresent(), "Retry-After header must be present");
        JsonNode json = JSON.readTree(resBlocked.body());
        assertEquals("TOO_MANY_REQUESTS", json.get("code").asText());

        // A different IP can still make login attempts
        HttpResponse<String> resOtherIp = post("/api/auth/login", body4, "10.0.0.2");
        assertEquals(401, resOtherIp.statusCode(), "Different IP should not be blocked by 10.0.0.1");
    }

    @Test
    @DisplayName("HTTP: Account login rate limit blocks targeted brute-force across different IPs")
    void testHttpAccountRateLimitAcrossIps() throws Exception {
        mockAuthService.failLogins = true;

        String body = JSON.writeValueAsString(new LoginRequest("target_admin", "wrong-password"));

        // Attempt 1 from IP A
        HttpResponse<String> res1 = post("/api/auth/login", body, "192.168.10.1");
        assertEquals(401, res1.statusCode());

        // Attempt 2 from IP B
        HttpResponse<String> res2 = post("/api/auth/login", body, "192.168.10.2");
        assertEquals(401, res2.statusCode());

        // Attempt 3 from IP C: Even though IP C is fresh, the targeted account has reached limit
        HttpResponse<String> resBlocked = post("/api/auth/login", body, "192.168.10.3");
        assertEquals(429, resBlocked.statusCode(), "Account target_admin should be rate limited across IPs");
        assertTrue(resBlocked.body().contains("account"), "Error message should mention account rate limit: " + resBlocked.body());
    }

    @Test
    @DisplayName("HTTP: Registration rate limit by IP blocks flooding")
    void testHttpRegisterRateLimit() throws Exception {
        String body = JSON.writeValueAsString(new RegisterRequest("Test User", "testuser", "test@landrop.local", "Password123!"));

        // Max IP registers is 2
        HttpResponse<String> res1 = post("/api/auth/register", body, "172.16.0.5");
        assertEquals(201, res1.statusCode());

        HttpResponse<String> res2 = post("/api/auth/register", body, "172.16.0.5");
        assertEquals(201, res2.statusCode());

        // 3rd attempt from same IP blocked with 429
        HttpResponse<String> res3 = post("/api/auth/register", body, "172.16.0.5");
        assertEquals(429, res3.statusCode());
        assertTrue(res3.headers().firstValue("Retry-After").isPresent());
    }

    private HttpResponse<String> post(String path, String body, String clientIp) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));

        if (clientIp != null) {
            builder.header("X-Forwarded-For", clientIp);
        }

        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static class MockAuthService extends AuthService {
        boolean failLogins = false;

        MockAuthService() {
            super(null, null, new JwtService());
        }

        @Override
        public AuthResponse loginClient(LoginRequest req) {
            if (failLogins) {
                throw new AuthException("Invalid email/username or password", 401, AuthErrorCode.INVALID_CREDENTIALS);
            }
            return new AuthResponse("mock-access", "mock-refresh", 900,
                new UserDto(UUID.randomUUID(), "test@landrop.local", req.emailOrUsername(), "Test", null, java.time.Instant.now(), java.time.Instant.now()));
        }

        @Override
        public AuthResponse register(RegisterRequest req) {
            return new AuthResponse("mock-access", "mock-refresh", 900,
                new UserDto(UUID.randomUUID(), req.email(), req.username(), req.displayName(), null, java.time.Instant.now(), java.time.Instant.now()));
        }
    }
}
