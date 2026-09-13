package com.landrop.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.auth.config.AuthConfig;
import com.landrop.auth.dto.AuthResponse;
import com.landrop.auth.dto.LoginRequest;
import com.landrop.auth.dto.RegisterRequest;
import com.landrop.auth.dto.UserDto;
import com.landrop.auth.exception.ConflictException;
import com.landrop.auth.exception.UnauthorizedException;
import com.landrop.auth.model.RefreshToken;
import com.landrop.auth.repository.RefreshTokenRepository;
import com.landrop.auth.security.JwtService;
import com.landrop.auth.security.PasswordHasher;
import com.landrop.auth.server.AuthHttpServer;
import com.landrop.auth.server.AuthMiddleware;
import com.landrop.auth.service.AuthService;
import com.landrop.database.DatabaseConfig;
import com.landrop.database.DatabaseManager;
import com.landrop.database.migration.DatabaseMigrator;
import com.landrop.database.model.User;
import com.landrop.database.repository.UserRepository;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AuthIntegrationTest {

    private static DatabaseManager dbManager;
    private static UserRepository userRepository;
    private static RefreshTokenRepository refreshTokenRepository;
    private static JwtService jwtService;
    private static AuthService authService;
    private static AuthHttpServer httpServer;
    private static HttpClient httpClient;
    private static ObjectMapper objectMapper;

    private static String serverBaseUrl;

    @BeforeAll
    static void setUpAll() throws Exception {
        String dbUrl = DatabaseConfig.getDatabaseUrl();
        assumeTrue(dbUrl != null && !dbUrl.isBlank(), "DATABASE_URL is not configured; skipping auth integration tests.");

        dbManager = DatabaseManager.getInstance();
        assumeTrue(dbManager.testConnection(), "Neon database connection failed; skipping auth tests.");

        // Ensure schema migration is up-to-date
        DatabaseMigrator migrator = new DatabaseMigrator(dbManager);
        migrator.migrate();

        userRepository = new UserRepository(dbManager);
        refreshTokenRepository = new RefreshTokenRepository(dbManager);
        jwtService = new JwtService(
            AuthConfig.getJwtAccessSecret(),
            AuthConfig.getJwtRefreshSecret(),
            900L,
            604800L
        );
        authService = new AuthService(userRepository, refreshTokenRepository, jwtService);
        AuthMiddleware authMiddleware = new AuthMiddleware(jwtService);

        // Start Auth HTTP Server on an ephemeral port (0 selects an available port)
        httpServer = new AuthHttpServer(0, authService, authMiddleware);
        httpServer.start();
        serverBaseUrl = httpServer.getBaseUrl();

        httpClient = HttpClient.newHttpClient();
        objectMapper = AuthHttpServer.createObjectMapper();
    }

    @AfterAll
    static void tearDownAll() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @Test
    @Order(1)
    @DisplayName("Test 1: RefreshTokenRepository CRUD and revocation operations")
    void testRefreshTokenRepository() throws SQLException {
        // Create dummy user for FK
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        User user = userRepository.create(new User(
            "refreshtest_" + uniqueSuffix + "@landrop.local",
            "refreshtest_" + uniqueSuffix,
            "Refresh Test",
            null,
            PasswordHasher.hash("Pass123456!")
        ));

        String tokenHash = JwtService.hashToken("dummy-refresh-token-" + uniqueSuffix);
        Instant expiresAt = Instant.now().plusSeconds(3600);
        RefreshToken token = new RefreshToken(user.id(), tokenHash, expiresAt);

        RefreshToken saved = refreshTokenRepository.create(token);
        assertNotNull(saved.id());
        assertEquals(user.id(), saved.userId());
        assertFalse(saved.revoked());

        Optional<RefreshToken> found = refreshTokenRepository.findByTokenHash(tokenHash);
        assertTrue(found.isPresent());
        assertEquals(saved.id(), found.get().id());

        // Revoke token
        boolean revoked = refreshTokenRepository.revokeByTokenHash(tokenHash);
        assertTrue(revoked);

        Optional<RefreshToken> foundRevoked = refreshTokenRepository.findByTokenHash(tokenHash);
        assertTrue(foundRevoked.isPresent());
        assertTrue(foundRevoked.get().revoked());
    }

    @Test
    @Order(2)
    @DisplayName("Test 2: AuthService register and password hashing")
    void testAuthServiceRegister() throws SQLException {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest req = new RegisterRequest(
            "service_reg_" + suffix + "@landrop.local",
            "srv_reg_" + suffix,
            "Password123!",
            "Service Reg " + suffix
        );

        AuthResponse resp = authService.register(req);
        assertNotNull(resp.accessToken());
        assertNotNull(resp.refreshToken());
        assertEquals("Bearer", resp.tokenType());
        assertEquals(900L, resp.expiresIn());
        assertEquals(req.email(), resp.user().email());
        assertEquals(req.username(), resp.user().username());

        // Verify password is not plaintext in DB
        Optional<User> dbUser = userRepository.findByEmail(req.email());
        assertTrue(dbUser.isPresent());
        assertTrue(dbUser.get().passwordHash().startsWith("PBKDF2$600000$"));
        assertTrue(PasswordHasher.verify("Password123!", dbUser.get().passwordHash()));
    }

    @Test
    @Order(3)
    @DisplayName("Test 3: AuthService rejects duplicate email and username")
    void testAuthServiceDuplicateRejection() throws SQLException {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest req1 = new RegisterRequest(
            "dup_" + suffix + "@landrop.local",
            "dup_user_" + suffix,
            "Password123!",
            "Dup Test"
        );
        authService.register(req1);

        // Same email, different username
        RegisterRequest req2 = new RegisterRequest(
            "dup_" + suffix + "@landrop.local",
            "other_user_" + suffix,
            "Password123!",
            "Other Test"
        );
        assertThrows(ConflictException.class, () -> authService.register(req2));

        // Different email, same username
        RegisterRequest req3 = new RegisterRequest(
            "other_" + suffix + "@landrop.local",
            "dup_user_" + suffix,
            "Password123!",
            "Other Test"
        );
        assertThrows(ConflictException.class, () -> authService.register(req3));
    }

    @Test
    @Order(4)
    @DisplayName("Test 4: AuthService login with email and with username")
    void testAuthServiceLogin() throws SQLException {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "login_" + suffix + "@landrop.local";
        String username = "login_" + suffix;
        String password = "SecretPassword123!";

        authService.register(new RegisterRequest(email, username, password, "Login Test"));

        // Login using email
        AuthResponse loginEmail = authService.login(new LoginRequest(email, null, null, password));
        assertNotNull(loginEmail.accessToken());
        assertEquals(username, loginEmail.user().username());

        // Login using username
        AuthResponse loginUsername = authService.login(new LoginRequest(null, null, username, password));
        assertNotNull(loginUsername.accessToken());
        assertEquals(email, loginUsername.user().email());

        // Login with wrong password
        assertThrows(UnauthorizedException.class, () ->
            authService.login(new LoginRequest(email, null, null, "WrongPassword!"))
        );

        // Login with non-existent user
        assertThrows(UnauthorizedException.class, () ->
            authService.login(new LoginRequest("nonexistent@domain.com", null, null, password))
        );
    }

    @Test
    @Order(5)
    @DisplayName("Test 5: HTTP POST /api/auth/register returns 201 Created and tokens")
    void testHttpRegister() throws IOException, InterruptedException {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String json = """
            {
                "email": "http_reg_%s@landrop.local",
                "username": "http_reg_%s",
                "password": "SecurePassword123!",
                "displayName": "HTTP Reg %s"
            }
        """.formatted(suffix, suffix, suffix);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(serverBaseUrl + "/api/auth/register"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode());

        JsonNode body = objectMapper.readTree(response.body());
        assertTrue(body.has("accessToken"));
        assertTrue(body.has("refreshToken"));
        assertEquals("Bearer", body.get("tokenType").asText());
        assertEquals(900L, body.get("expiresIn").asLong());

        JsonNode userNode = body.get("user");
        assertNotNull(userNode);
        assertEquals("http_reg_" + suffix + "@landrop.local", userNode.get("email").asText());
        assertFalse(userNode.has("passwordHash"), "Security Invariant: user response must NEVER contain passwordHash");
    }

    @Test
    @Order(6)
    @DisplayName("Test 6: HTTP POST /api/auth/login returns 200 OK and tokens")
    void testHttpLogin() throws IOException, InterruptedException {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "http_login_" + suffix + "@landrop.local";
        String username = "http_login_" + suffix;
        String password = "SecurePassword123!";

        // Register first
        String regJson = """
            {
                "email": "%s",
                "username": "%s",
                "password": "%s",
                "displayName": "Login User"
            }
        """.formatted(email, username, password);

        HttpRequest regReq = HttpRequest.newBuilder()
            .uri(URI.create(serverBaseUrl + "/api/auth/register"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(regJson))
            .build();
        httpClient.send(regReq, HttpResponse.BodyHandlers.ofString());

        // Login
        String loginJson = """
            {
                "emailOrUsername": "%s",
                "password": "%s"
            }
        """.formatted(email, password);

        HttpRequest loginReq = HttpRequest.newBuilder()
            .uri(URI.create(serverBaseUrl + "/api/auth/login"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(loginJson))
            .build();

        HttpResponse<String> loginResp = httpClient.send(loginReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, loginResp.statusCode());

        JsonNode body = objectMapper.readTree(loginResp.body());
        assertTrue(body.has("accessToken"));
        assertTrue(body.has("refreshToken"));
        assertEquals(username, body.get("user").get("username").asText());
    }

    @Test
    @Order(7)
    @DisplayName("Test 7: HTTP GET /api/auth/me with Bearer token returns 200 OK, rejects invalid/missing")
    void testHttpMeEndpoint() throws IOException, InterruptedException {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String regJson = """
            {
                "email": "http_me_%s@landrop.local",
                "username": "http_me_%s",
                "password": "SecurePassword123!",
                "displayName": "Me User"
            }
        """.formatted(suffix, suffix);

        HttpRequest regReq = HttpRequest.newBuilder()
            .uri(URI.create(serverBaseUrl + "/api/auth/register"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(regJson))
            .build();
        HttpResponse<String> regResp = httpClient.send(regReq, HttpResponse.BodyHandlers.ofString());
        JsonNode regBody = objectMapper.readTree(regResp.body());
        String accessToken = regBody.get("accessToken").asText();

        // 1. Success with valid token
        HttpRequest meReq = HttpRequest.newBuilder()
            .uri(URI.create(serverBaseUrl + "/api/auth/me"))
            .header("Authorization", "Bearer " + accessToken)
            .GET()
            .build();
        HttpResponse<String> meResp = httpClient.send(meReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, meResp.statusCode());

        JsonNode userNode = objectMapper.readTree(meResp.body());
        assertEquals("http_me_" + suffix + "@landrop.local", userNode.get("email").asText());
        assertFalse(userNode.has("passwordHash"), "Security Invariant: /api/auth/me must NEVER expose passwordHash");

        // 2. Reject missing token
        HttpRequest noAuthReq = HttpRequest.newBuilder()
            .uri(URI.create(serverBaseUrl + "/api/auth/me"))
            .GET()
            .build();
        HttpResponse<String> noAuthResp = httpClient.send(noAuthReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, noAuthResp.statusCode());

        // 3. Reject invalid token
        HttpRequest invalidTokenReq = HttpRequest.newBuilder()
            .uri(URI.create(serverBaseUrl + "/api/auth/me"))
            .header("Authorization", "Bearer invalid.jwt.token")
            .GET()
            .build();
        HttpResponse<String> invalidTokenResp = httpClient.send(invalidTokenReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, invalidTokenResp.statusCode());
    }

    @Test
    @Order(8)
    @DisplayName("Test 8: HTTP POST /api/auth/refresh rotates tokens, rejects revoked token")
    void testHttpRefreshAndTokenRotation() throws IOException, InterruptedException {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String regJson = """
            {
                "email": "http_rot_%s@landrop.local",
                "username": "http_rot_%s",
                "password": "SecurePassword123!",
                "displayName": "Rotation User"
            }
        """.formatted(suffix, suffix);

        HttpRequest regReq = HttpRequest.newBuilder()
            .uri(URI.create(serverBaseUrl + "/api/auth/register"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(regJson))
            .build();
        HttpResponse<String> regResp = httpClient.send(regReq, HttpResponse.BodyHandlers.ofString());
        JsonNode regBody = objectMapper.readTree(regResp.body());
        String initialRefreshToken = regBody.get("refreshToken").asText();

        // 1. Refresh with initial token
        String refreshJson = """
            {
                "refreshToken": "%s"
            }
        """.formatted(initialRefreshToken);

        HttpRequest refreshReq = HttpRequest.newBuilder()
            .uri(URI.create(serverBaseUrl + "/api/auth/refresh"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(refreshJson))
            .build();

        HttpResponse<String> refreshResp = httpClient.send(refreshReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, refreshResp.statusCode());

        JsonNode refreshBody = objectMapper.readTree(refreshResp.body());
        String newAccessToken = refreshBody.get("accessToken").asText();
        String newRefreshToken = refreshBody.get("refreshToken").asText();
        assertNotNull(newAccessToken);
        assertNotNull(newRefreshToken);
        assertNotEquals(initialRefreshToken, newRefreshToken, "Rotation must issue a new refresh token");

        // 2. Re-using previous refresh token must fail (it was revoked)
        HttpResponse<String> reuseResp = httpClient.send(refreshReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, reuseResp.statusCode());
    }

    @Test
    @Order(9)
    @DisplayName("Test 9: HTTP POST /api/auth/logout revokes session")
    void testHttpLogout() throws IOException, InterruptedException {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String regJson = """
            {
                "email": "http_logout_%s@landrop.local",
                "username": "http_logout_%s",
                "password": "SecurePassword123!",
                "displayName": "Logout User"
            }
        """.formatted(suffix, suffix);

        HttpRequest regReq = HttpRequest.newBuilder()
            .uri(URI.create(serverBaseUrl + "/api/auth/register"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(regJson))
            .build();
        HttpResponse<String> regResp = httpClient.send(regReq, HttpResponse.BodyHandlers.ofString());
        JsonNode regBody = objectMapper.readTree(regResp.body());
        String refreshToken = regBody.get("refreshToken").asText();

        // Logout
        String logoutJson = """
            {
                "refreshToken": "%s"
            }
        """.formatted(refreshToken);

        HttpRequest logoutReq = HttpRequest.newBuilder()
            .uri(URI.create(serverBaseUrl + "/api/auth/logout"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(logoutJson))
            .build();

        HttpResponse<String> logoutResp = httpClient.send(logoutReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, logoutResp.statusCode());

        // Attempting to refresh with the logged-out token must fail
        String refreshJson = """
            {
                "refreshToken": "%s"
            }
        """.formatted(refreshToken);

        HttpRequest refreshReq = HttpRequest.newBuilder()
            .uri(URI.create(serverBaseUrl + "/api/auth/refresh"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(refreshJson))
            .build();

        HttpResponse<String> refreshResp = httpClient.send(refreshReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, refreshResp.statusCode());
    }

    @Test
    @Order(10)
    @DisplayName("Test 10: OPTIONS preflight request returns 204 No Content with CORS headers")
    void testCorsPreflight() throws IOException, InterruptedException {
        HttpRequest optionsReq = HttpRequest.newBuilder()
            .uri(URI.create(serverBaseUrl + "/api/auth/login"))
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .build();

        HttpResponse<Void> optionsResp = httpClient.send(optionsReq, HttpResponse.BodyHandlers.discarding());
        assertEquals(204, optionsResp.statusCode());
        assertEquals("*", optionsResp.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
        assertTrue(optionsResp.headers().firstValue("Access-Control-Allow-Methods").orElse("").contains("POST"));
    }

    @Test
    @Order(11)
    @DisplayName("Test 11: GET /api/auth/health returns 200 OK with status UP")
    void testHealthEndpoint() throws IOException, InterruptedException {
        HttpRequest healthReq = HttpRequest.newBuilder()
            .uri(URI.create(serverBaseUrl + "/api/auth/health"))
            .GET()
            .build();

        HttpResponse<String> healthResp = httpClient.send(healthReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, healthResp.statusCode());
        JsonNode node = objectMapper.readTree(healthResp.body());
        assertEquals("UP", node.get("status").asText());
        assertEquals("15A", node.get("phase").asText());
    }
}
