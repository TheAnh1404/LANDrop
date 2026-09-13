package com.landrop.auth.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.auth.config.AuthConfig;
import com.landrop.auth.exception.UnauthorizedException;
import com.landrop.database.model.User;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Standard RFC 7519 JSON Web Token (JWT) service using HMAC-SHA256 signatures.
 * Zero external JWT library dependencies; uses standard Java SE cryptography and Jackson Databind.
 */
public class JwtService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
    private static final String ENCODED_HEADER = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(HEADER_JSON.getBytes(StandardCharsets.UTF_8));

    private final ObjectMapper objectMapper;
    private final String accessSecret;
    private final String refreshSecret;
    private final long accessExpirySeconds;
    private final long refreshExpirySeconds;

    public JwtService() {
        this(
            AuthConfig.getJwtAccessSecret(),
            AuthConfig.getJwtRefreshSecret(),
            AuthConfig.getAccessTokenExpirySeconds(),
            AuthConfig.getRefreshTokenExpirySeconds()
        );
    }

    public JwtService(String accessSecret, String refreshSecret, long accessExpirySeconds, long refreshExpirySeconds) {
        this.objectMapper = new ObjectMapper();
        this.accessSecret = accessSecret;
        this.refreshSecret = refreshSecret;
        this.accessExpirySeconds = accessExpirySeconds;
        this.refreshExpirySeconds = refreshExpirySeconds;
    }

    public String generateAccessToken(User user) {
        return createToken(user, "access", accessExpirySeconds, accessSecret);
    }

    public String generateRefreshToken(User user) {
        return createToken(user, "refresh", refreshExpirySeconds, refreshSecret);
    }

    public String createToken(User user, String tokenType, long durationSeconds, String secret) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(durationSeconds);
        String jti = UUID.randomUUID().toString();

        Map<String, Object> claims = Map.of(
            "sub", user.id().toString(),
            "email", user.email(),
            "username", user.username(),
            "type", tokenType,
            "iat", now.getEpochSecond(),
            "exp", expiresAt.getEpochSecond(),
            "jti", jti
        );

        try {
            String payloadJson = objectMapper.writeValueAsString(claims);
            String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));

            String dataToSign = ENCODED_HEADER + "." + encodedPayload;
            byte[] signature = hmacSha256(dataToSign, secret);
            String encodedSignature = Base64.getUrlEncoder().withoutPadding().encodeToString(signature);

            return dataToSign + "." + encodedSignature;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate JWT token", e);
        }
    }

    public JwtClaims verifyToken(String token, boolean isRefresh) {
        if (token == null || token.isBlank()) {
            throw new UnauthorizedException("Missing authentication token");
        }

        String[] parts = token.trim().split("\\.");
        if (parts.length != 3) {
            throw new UnauthorizedException("Malformed authentication token");
        }

        String encodedHeader = parts[0];
        String encodedPayload = parts[1];
        String encodedSignature = parts[2];

        String secret = isRefresh ? refreshSecret : accessSecret;
        String expectedTokenType = isRefresh ? "refresh" : "access";

        // 1. Verify signature in constant time
        String dataToSign = encodedHeader + "." + encodedPayload;
        byte[] expectedSig = hmacSha256(dataToSign, secret);
        byte[] providedSig;
        try {
            providedSig = Base64.getUrlDecoder().decode(encodedSignature);
        } catch (IllegalArgumentException e) {
            throw new UnauthorizedException("Invalid token encoding");
        }

        if (!MessageDigest.isEqual(expectedSig, providedSig)) {
            throw new UnauthorizedException("Invalid token signature");
        }

        // 2. Parse claims JSON
        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(encodedPayload);
            JsonNode payload = objectMapper.readTree(payloadBytes);

            String sub = payload.hasNonNull("sub") ? payload.get("sub").asText() : null;
            String email = payload.hasNonNull("email") ? payload.get("email").asText() : null;
            String username = payload.hasNonNull("username") ? payload.get("username").asText() : null;
            String type = payload.hasNonNull("type") ? payload.get("type").asText() : null;
            long iat = payload.hasNonNull("iat") ? payload.get("iat").asLong() : 0;
            long exp = payload.hasNonNull("exp") ? payload.get("exp").asLong() : 0;
            String jti = payload.hasNonNull("jti") ? payload.get("jti").asText() : null;

            if (sub == null || sub.isBlank()) {
                throw new UnauthorizedException("Invalid token claims: missing subject");
            }

            if (!expectedTokenType.equalsIgnoreCase(type)) {
                throw new UnauthorizedException("Invalid token type: expected " + expectedTokenType + ", got " + type);
            }

            Instant expiresAt = Instant.ofEpochSecond(exp);
            if (Instant.now().isAfter(expiresAt)) {
                throw new UnauthorizedException("Token has expired");
            }

            return new JwtClaims(
                UUID.fromString(sub),
                email,
                username,
                type,
                Instant.ofEpochSecond(iat),
                expiresAt,
                jti
            );
        } catch (UnauthorizedException e) {
            throw e;
        } catch (Exception e) {
            throw new UnauthorizedException("Failed to parse token payload: " + e.getMessage());
        }
    }

    /**
     * Computes a SHA-256 hash of the token for safe storage in the database.
     */
    public static String hashToken(String token) {
        if (token == null) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not found", e);
        }
    }

    private static byte[] hmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(secretKey);
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256", e);
        }
    }

    public long getAccessExpirySeconds() {
        return accessExpirySeconds;
    }

    public long getRefreshExpirySeconds() {
        return refreshExpirySeconds;
    }
}
