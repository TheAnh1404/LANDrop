package com.landrop.auth.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.HexFormat;

/**
 * Production-grade password hashing service implementing OWASP recommendations.
 * Uses PBKDF2WithHmacSHA256 with 600,000 iterations and 16-byte cryptographically secure salt.
 * <p>
 * Format: {@code PBKDF2$600000$<saltHex>$<hashHex>}
 */
public final class PasswordHasher {

    public static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    public static final int ITERATIONS = 600_000;
    public static final int SALT_LENGTH_BYTES = 16;
    public static final int KEY_LENGTH_BITS = 256;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private PasswordHasher() {}

    /**
     * Hashes a raw password using PBKDF2-HMAC-SHA256 with a freshly generated secure random salt.
     *
     * @param rawPassword Plaintext password
     * @return Formatted hash string: PBKDF2$600000$&lt;saltHex&gt;$&lt;hashHex&gt;
     */
    public static String hash(String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new IllegalArgumentException("Password cannot be null or empty");
        }

        byte[] salt = new byte[SALT_LENGTH_BYTES];
        SECURE_RANDOM.nextBytes(salt);

        byte[] hash = pbkdf2(rawPassword.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS);

        return "PBKDF2$" + ITERATIONS + "$" + HEX.formatHex(salt) + "$" + HEX.formatHex(hash);
    }

    /**
     * Verifies a raw plaintext password against a stored hash string in constant time.
     *
     * @param rawPassword Plaintext password to verify
     * @param storedHash  Stored formatted hash string
     * @return True if matches, false otherwise
     */
    public static boolean verify(String rawPassword, String storedHash) {
        if (rawPassword == null || storedHash == null || storedHash.isBlank()) {
            return false;
        }

        String[] parts = storedHash.split("\\$");
        if (parts.length != 4 || !parts[0].equals("PBKDF2")) {
            return false;
        }

        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = HEX.parseHex(parts[2]);
            byte[] expectedHash = HEX.parseHex(parts[3]);

            byte[] actualHash = pbkdf2(rawPassword.toCharArray(), salt, iterations, expectedHash.length * 8);

            return MessageDigest.isEqual(expectedHash, actualHash);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int keyLengthBits) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyLengthBits);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            return factory.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("Failed to hash password with algorithm " + ALGORITHM, e);
        }
    }
}
