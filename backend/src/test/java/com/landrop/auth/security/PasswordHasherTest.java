package com.landrop.auth.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PasswordHasherTest {

    @Test
    @DisplayName("Hash generates expected PBKDF2 format with 600,000 iterations")
    void testHashFormat() {
        String hash = PasswordHasher.hash("SecurePassword123!");
        assertNotNull(hash);
        assertTrue(hash.startsWith("PBKDF2$600000$"));

        String[] parts = hash.split("\\$");
        assertEquals(4, parts.length);
        assertEquals("PBKDF2", parts[0]);
        assertEquals("600000", parts[1]);
        assertEquals(32, parts[2].length(), "Salt should be 16 bytes = 32 hex chars");
        assertEquals(64, parts[3].length(), "Hash should be 32 bytes = 64 hex chars");
    }

    @Test
    @DisplayName("Two hashes of same password produce different salts")
    void testSaltRandomness() {
        String hash1 = PasswordHasher.hash("MyPassword123");
        String hash2 = PasswordHasher.hash("MyPassword123");

        assertNotEquals(hash1, hash2, "Salts must be cryptographically random and unique per hash");
    }

    @Test
    @DisplayName("Verify returns true for correct password")
    void testVerifySuccess() {
        String password = "CorrectHorseBatteryStaple!";
        String hash = PasswordHasher.hash(password);

        assertTrue(PasswordHasher.verify(password, hash));
    }

    @Test
    @DisplayName("Verify returns false for incorrect password")
    void testVerifyFailure() {
        String password = "CorrectPassword";
        String hash = PasswordHasher.hash(password);

        assertFalse(PasswordHasher.verify("WrongPassword", hash));
        assertFalse(PasswordHasher.verify("", hash));
        assertFalse(PasswordHasher.verify(null, hash));
    }

    @Test
    @DisplayName("Verify returns false for invalid hash string")
    void testVerifyCorruptedHash() {
        assertFalse(PasswordHasher.verify("password", "invalid_format"));
        assertFalse(PasswordHasher.verify("password", null));
        assertFalse(PasswordHasher.verify("password", ""));
        assertFalse(PasswordHasher.verify("password", "PBKDF2$600000$bad$hash"));
    }

    @Test
    @DisplayName("Null or empty password throws IllegalArgumentException")
    void testEmptyPasswordThrows() {
        assertThrows(IllegalArgumentException.class, () -> PasswordHasher.hash(null));
        assertThrows(IllegalArgumentException.class, () -> PasswordHasher.hash(""));
    }
}
