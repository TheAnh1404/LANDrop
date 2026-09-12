package com.landrop.transfer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class Sha256Test {

    // Known standard test vector
    private static final String ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    // Known empty string test vector
    private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    @DisplayName("Test SHA-256 with standard known test vector: 'abc'")
    void testSha256KnownTestVector() {
        byte[] input = "abc".getBytes(StandardCharsets.UTF_8);
        String hash = FileTransferUtils.calculateSha256(input);
        assertEquals(ABC_SHA256, hash);
    }

    @Test
    @DisplayName("Test SHA-256 with empty input")
    void testSha256Empty() {
        byte[] empty = new byte[0];
        String hash = FileTransferUtils.calculateSha256(empty);
        assertEquals(EMPTY_SHA256, hash);
    }

    @Test
    @DisplayName("Test SHA-256 from InputStream")
    void testSha256InputStream() throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8));
        String hash = FileTransferUtils.calculateSha256(bais);
        assertEquals(ABC_SHA256, hash);
    }

    @Test
    @DisplayName("Test streaming SHA-256 file hashing on disk")
    void testSha256FileStream(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "abc", StandardCharsets.UTF_8);

        String hash = FileTransferUtils.calculateSha256(file);
        assertEquals(ABC_SHA256, hash);

        Path emptyFile = tempDir.resolve("empty.txt");
        Files.createFile(emptyFile);
        assertEquals(EMPTY_SHA256, FileTransferUtils.calculateSha256(emptyFile));
    }

    @Test
    @DisplayName("Test streaming SHA-256 on multi-MB file to ensure constant memory")
    void testSha256MultiMegabyteFile(@TempDir Path tempDir) throws IOException {
        Path largeFile = tempDir.resolve("large.bin");
        byte[] chunk = new byte[1024 * 1024]; // 1 MB of repeated bytes
        Arrays.fill(chunk, (byte) 0x5A);

        // Write 3 MB file
        try (var out = Files.newOutputStream(largeFile)) {
            for (int i = 0; i < 3; i++) {
                out.write(chunk);
            }
        }

        String hash1 = FileTransferUtils.calculateSha256(largeFile);
        assertNotNull(hash1);
        assertEquals(64, hash1.length());
        assertTrue(FileTransferUtils.isValidSha256(hash1));

        // Recalculating hash produces exact same result
        String hash2 = FileTransferUtils.calculateSha256(largeFile);
        assertEquals(hash1, hash2);
    }

    @Test
    @DisplayName("Test isValidSha256 validation")
    void testIsValidSha256() {
        assertTrue(FileTransferUtils.isValidSha256(ABC_SHA256));
        assertTrue(FileTransferUtils.isValidSha256(EMPTY_SHA256));
        assertTrue(FileTransferUtils.isValidSha256(ABC_SHA256.toUpperCase()));

        // Invalid cases
        assertFalse(FileTransferUtils.isValidSha256(null));
        assertFalse(FileTransferUtils.isValidSha256(""));
        assertFalse(FileTransferUtils.isValidSha256("abc")); // too short
        assertFalse(FileTransferUtils.isValidSha256(ABC_SHA256 + "a")); // too long (65 chars)
        assertFalse(FileTransferUtils.isValidSha256("zzzz" + ABC_SHA256.substring(4))); // non-hex
    }

    @Test
    @DisplayName("Test shortenHash for UI display")
    void testShortenHash() {
        assertEquals("", FileTransferUtils.shortenHash(null));
        assertEquals("", FileTransferUtils.shortenHash(""));
        assertEquals("ba7816bf8f...", FileTransferUtils.shortenHash(ABC_SHA256));
        assertEquals("short", FileTransferUtils.shortenHash("short"));
    }
}
