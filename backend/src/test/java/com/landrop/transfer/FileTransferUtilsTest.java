package com.landrop.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileTransferUtilsTest {

    @Test
    void testSanitizeFilename_normal() {
        assertEquals("report.pdf", FileTransferUtils.sanitizeFilename("report.pdf"));
        assertEquals("vacation photo.jpg", FileTransferUtils.sanitizeFilename("vacation photo.jpg"));
    }

    @Test
    void testSanitizeFilename_pathTraversalDefenses() {
        assertEquals("passwd", FileTransferUtils.sanitizeFilename("../../etc/passwd"));
        assertEquals("cmd.exe", FileTransferUtils.sanitizeFilename("..\\..\\Windows\\System32\\cmd.exe"));
        assertEquals("test.txt", FileTransferUtils.sanitizeFilename("../../../test.txt"));
    }

    @Test
    void testSanitizeFilename_illegalCharacters() {
        assertEquals("file_with_colons.txt", FileTransferUtils.sanitizeFilename("file:with:colons.txt"));
        assertEquals("document___draft_.docx", FileTransferUtils.sanitizeFilename("document<*?draft>.docx"));
    }

    @Test
    void testSanitizeFilename_emptyAndDotCases() {
        assertEquals("downloaded_file", FileTransferUtils.sanitizeFilename(null));
        assertEquals("downloaded_file", FileTransferUtils.sanitizeFilename(""));
        assertEquals("downloaded_file", FileTransferUtils.sanitizeFilename("   "));
        assertEquals("downloaded_file", FileTransferUtils.sanitizeFilename("."));
        assertEquals("downloaded_file", FileTransferUtils.sanitizeFilename(".."));
    }

    @Test
    void testResolveUniqueDestination(@TempDir Path tempDir) throws IOException {
        Path p1 = FileTransferUtils.resolveUniqueDestination(tempDir, "document.pdf");
        assertEquals(tempDir.resolve("document.pdf"), p1);

        // Create the file so next call collides
        Files.createFile(p1);

        Path p2 = FileTransferUtils.resolveUniqueDestination(tempDir, "document.pdf");
        assertEquals(tempDir.resolve("document (1).pdf"), p2);

        // Create document (1).pdf
        Files.createFile(p2);

        Path p3 = FileTransferUtils.resolveUniqueDestination(tempDir, "document.pdf");
        assertEquals(tempDir.resolve("document (2).pdf"), p3);
    }

    @Test
    void testVerifyPathContained_safe(@TempDir Path tempDir) {
        Path child = tempDir.resolve("subfolder/file.txt");
        assertDoesNotThrow(() -> FileTransferUtils.verifyPathContained(tempDir, child));
    }

    @Test
    void testVerifyPathContained_escapeThrowsSecurityException(@TempDir Path tempDir) {
        Path outside = tempDir.resolve("../escaped.txt");
        assertThrows(SecurityException.class, () -> FileTransferUtils.verifyPathContained(tempDir, outside));
    }

    @Test
    void testFormatFileSize() {
        assertEquals("0 B", FileTransferUtils.formatFileSize(-5));
        assertEquals("500 B", FileTransferUtils.formatFileSize(500));
        assertEquals("1.0 KB", FileTransferUtils.formatFileSize(1024));
        assertEquals("10.0 MB", FileTransferUtils.formatFileSize(10 * 1024 * 1024));
        assertEquals("1.50 GB", FileTransferUtils.formatFileSize((long) (1.5 * 1024 * 1024 * 1024)));
    }

    @Test
    void testFormatSpeed() {
        assertEquals("0 KB/s", FileTransferUtils.formatSpeed(0));
        assertEquals("1.0 KB/s", FileTransferUtils.formatSpeed(1024));
        assertEquals("2.4 MB/s", FileTransferUtils.formatSpeed(2.4 * 1024 * 1024));
    }
}
