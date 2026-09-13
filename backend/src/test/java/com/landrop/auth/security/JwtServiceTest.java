package com.landrop.auth.security;

import com.landrop.auth.exception.UnauthorizedException;
import com.landrop.database.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class JwtServiceTest {

    private JwtService jwtService;
    private User testUser;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(
            "test_access_secret_key_minimum_256_bits_length_for_hmac_sha256",
            "test_refresh_secret_key_minimum_256_bits_length_for_hmac_sha256",
            900L,
            604800L
        );

        testUser = new User(
            UUID.randomUUID(),
            "test@landrop.local",
            "testuser",
            "Test User",
            null,
            "hash",
            Instant.now(),
            Instant.now()
        );
    }

    @Test
    @DisplayName("Generate and verify valid access token")
    void testGenerateAndVerifyAccessToken() {
        String token = jwtService.generateAccessToken(testUser);
        assertNotNull(token);
        assertEquals(3, token.split("\\.").length);

        JwtClaims claims = jwtService.verifyToken(token, false);
        assertEquals(testUser.id(), claims.userId());
        assertEquals(testUser.email(), claims.email());
        assertEquals(testUser.username(), claims.username());
        assertEquals("access", claims.tokenType());
        assertFalse(claims.isExpired());
    }

    @Test
    @DisplayName("Generate and verify valid refresh token")
    void testGenerateAndVerifyRefreshToken() {
        String token = jwtService.generateRefreshToken(testUser);
        assertNotNull(token);

        JwtClaims claims = jwtService.verifyToken(token, true);
        assertEquals(testUser.id(), claims.userId());
        assertEquals("refresh", claims.tokenType());
    }

    @Test
    @DisplayName("Reject access token when expecting refresh token")
    void testTypeMismatchRejection() {
        String accessToken = jwtService.generateAccessToken(testUser);
        assertThrows(UnauthorizedException.class, () -> jwtService.verifyToken(accessToken, true));
    }

    @Test
    @DisplayName("Reject token with tampered signature")
    void testTamperedSignatureRejection() {
        String token = jwtService.generateAccessToken(testUser);
        String[] parts = token.split("\\.");

        // Tamper with payload
        String tamperedToken = parts[0] + ".eyJuYW1lIjoidGFtcGVyZWQifQ." + parts[2];
        assertThrows(UnauthorizedException.class, () -> jwtService.verifyToken(tamperedToken, false));
    }

    @Test
    @DisplayName("Reject expired token")
    void testExpiredTokenRejection() throws InterruptedException {
        // Create service with 1 second expiry
        JwtService shortLivedService = new JwtService(
            "test_access_secret_key_minimum_256_bits_length_for_hmac_sha256",
            "test_refresh_secret_key_minimum_256_bits_length_for_hmac_sha256",
            -10L, // Already expired 10 seconds ago
            604800L
        );

        String expiredToken = shortLivedService.generateAccessToken(testUser);
        UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                () -> shortLivedService.verifyToken(expiredToken, false));
        assertTrue(ex.getMessage().contains("expired"));
    }

    @Test
    @DisplayName("Hash token produces deterministic SHA-256 hex string")
    void testHashToken() {
        String token = "sample.jwt.token";
        String hash1 = JwtService.hashToken(token);
        String hash2 = JwtService.hashToken(token);

        assertNotNull(hash1);
        assertEquals(64, hash1.length());
        assertEquals(hash1, hash2);
    }
}
