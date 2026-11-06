package com.landrop.auth.config;

import com.landrop.server.bootstrap.ServerBootstrap;
import com.landrop.server.config.ServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuthConfigTest {

    private static final String PROP_ENV = "landrop.environment";
    private static final String PROP_ACCESS = "landrop.jwt.access.secret";
    private static final String PROP_REFRESH = "landrop.jwt.refresh.secret";
    private static final String PROP_ADMIN = "landrop.admin.password";

    @BeforeEach
    @AfterEach
    void cleanupSystemProperties() {
        System.clearProperty(PROP_ENV);
        System.clearProperty(PROP_ACCESS);
        System.clearProperty(PROP_REFRESH);
        System.clearProperty(PROP_ADMIN);
    }

    @Test
    @DisplayName("Development environment uses fallback secrets when not configured")
    void testDevelopmentFallbackSecrets() {
        System.setProperty(PROP_ENV, "development");
        assertFalse(AuthConfig.isProduction());
        assertNotNull(AuthConfig.getJwtAccessSecret());
        assertNotNull(AuthConfig.getJwtRefreshSecret());
        assertDoesNotThrow(AuthConfig::getJwtAccessSecret);
        assertDoesNotThrow(AuthConfig::getJwtRefreshSecret);
    }

    @Test
    @DisplayName("Production environment rejects startup when access secret is missing or default")
    void testProductionRejectsMissingOrDefaultSecret() {
        System.setProperty(PROP_ENV, "production");
        assertTrue(AuthConfig.isProduction());

        // Missing
        IllegalStateException exNull = assertThrows(IllegalStateException.class,
            () -> AuthConfig.validateProductionSecret("JWT_ACCESS_SECRET", null, "default"));
        assertTrue(exNull.getMessage().contains("JWT_ACCESS_SECRET"), exNull.getMessage());

        IllegalStateException exBlank = assertThrows(IllegalStateException.class,
            () -> AuthConfig.validateProductionSecret("JWT_ACCESS_SECRET", "   ", "default"));
        assertTrue(exBlank.getMessage().contains("JWT_ACCESS_SECRET"), exBlank.getMessage());

        // Hardcoded default
        System.setProperty(PROP_ACCESS, "landrop_phase15a_jwt_access_secret_key_minimum_256_bits_for_hmac_sha256_security_production_grade");
        IllegalStateException exDef = assertThrows(IllegalStateException.class, AuthConfig::getJwtAccessSecret);
        assertTrue(exDef.getMessage().contains("default"), exDef.getMessage());
    }

    @Test
    @DisplayName("Production environment rejects placeholder or short secrets")
    void testProductionRejectsPlaceholderOrShortSecrets() {
        System.setProperty(PROP_ENV, "production");

        // Placeholder
        System.setProperty(PROP_ACCESS, "replace-with-a-secure-random-256-bit-secret-for-access-tokens");
        IllegalStateException ex1 = assertThrows(IllegalStateException.class, AuthConfig::getJwtAccessSecret);
        assertTrue(ex1.getMessage().contains("placeholder"), ex1.getMessage());

        // Too short (< 32 chars)
        System.setProperty(PROP_ACCESS, "short-secret-key-12345");
        IllegalStateException ex2 = assertThrows(IllegalStateException.class, AuthConfig::getJwtAccessSecret);
        assertTrue(ex2.getMessage().contains("32 characters"), ex2.getMessage());
    }

    @Test
    @DisplayName("Production environment rejects identical access and refresh secrets")
    void testProductionRejectsIdenticalSecrets() {
        System.setProperty(PROP_ENV, "production");
        String secret = "this-is-a-valid-length-secret-for-jwt-signing-0123456789";
        System.setProperty(PROP_ACCESS, secret);
        System.setProperty(PROP_REFRESH, secret);

        IllegalStateException ex = assertThrows(IllegalStateException.class, AuthConfig::validateProductionSecrets);
        assertTrue(ex.getMessage().contains("distinct"), ex.getMessage());
    }

    @Test
    @DisplayName("Production accepts valid distinct secrets >= 32 characters")
    void testProductionAcceptsValidSecrets() {
        System.setProperty(PROP_ENV, "production");
        String accessSecret = "a-very-strong-production-access-secret-minimum-32-chars";
        String refreshSecret = "a-very-strong-production-refresh-secret-minimum-32-chars";
        System.setProperty(PROP_ACCESS, accessSecret);
        System.setProperty(PROP_REFRESH, refreshSecret);

        assertEquals(accessSecret, AuthConfig.getJwtAccessSecret());
        assertEquals(refreshSecret, AuthConfig.getJwtRefreshSecret());
        assertDoesNotThrow(AuthConfig::validateProductionSecrets);
    }

    @Test
    @DisplayName("ServerBootstrap start fails in production when secrets are invalid")
    void testServerBootstrapFailsInProductionWithBadSecrets() {
        System.setProperty(PROP_ENV, "production");
        System.setProperty(PROP_ADMIN, "valid-admin-password-123456"); // Admin password is valid
        // But JWT secret is set to placeholder
        System.setProperty(PROP_ACCESS, "replace-with-a-bad-secret");
        ServerConfig config = new ServerConfig("127.0.0.1", 0, 0, 1, 1000, 5000, "production", "INFO");
        ServerBootstrap bootstrap = new ServerBootstrap(config);

        IllegalStateException ex = assertThrows(IllegalStateException.class, bootstrap::start);
        assertTrue(ex.getMessage().contains("JWT_ACCESS_SECRET"), ex.getMessage());
    }
}
