package com.landrop.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class FolderArchiveServiceTest {

    @Test
    void testCreateAndDeleteArchive(@TempDir Path tempDir) throws IOException {
        Path folder = tempDir.resolve("test-folder");
        Files.createDirectories(folder);
        Path subFolder = folder.resolve("sub");
        Files.createDirectories(subFolder);

        Files.writeString(folder.resolve("file1.txt"), "Hello World");
        Files.writeString(subFolder.resolve("file2.txt"), "Sub folder file content");

        Path archive = FolderArchiveService.createArchive(folder);
        assertNotNull(archive);
        assertTrue(Files.exists(archive));
        assertTrue(archive.toString().endsWith(".zip"));

        try (ZipFile zip = new ZipFile(archive.toFile())) {
            assertNotNull(zip.getEntry("file1.txt"));
            assertNotNull(zip.getEntry("sub/file2.txt"));
        }

        FolderArchiveService.deleteArchive(archive);
        assertFalse(Files.exists(archive));
    }

    @Test
    void testCreateArchiveThrowsOnNonDirectory(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("single.txt");
        Files.writeString(file, "single file");

        assertThrows(IllegalArgumentException.class, () -> FolderArchiveService.createArchive(file));
    }

    @Test
    void testExtractArchiveSafe(@TempDir Path tempDir) throws IOException {
        Path sourceFolder = tempDir.resolve("source");
        Files.createDirectories(sourceFolder.resolve("nested"));
        Files.writeString(sourceFolder.resolve("hello.txt"), "LANDrop v2.0 Safe Transfer");
        Files.writeString(sourceFolder.resolve("nested/doc.json"), "{\"status\": \"secure\"}");

        Path archive = FolderArchiveService.createArchive(sourceFolder);
        assertNotNull(archive);

        Path destDir = tempDir.resolve("extracted");
        Path extractedRoot = FolderArchiveService.extractArchive(archive, destDir);
        assertEquals(destDir.toAbsolutePath().normalize(), extractedRoot);

        assertTrue(Files.exists(destDir.resolve("hello.txt")));
        assertEquals("LANDrop v2.0 Safe Transfer", Files.readString(destDir.resolve("hello.txt")));

        assertTrue(Files.exists(destDir.resolve("nested/doc.json")));
        assertEquals("{\"status\": \"secure\"}", Files.readString(destDir.resolve("nested/doc.json")));

        FolderArchiveService.deleteArchive(archive);
    }

    @Test
    void testExtractArchiveZipSlipRejected(@TempDir Path tempDir) throws IOException {
        // Create a malicious ZIP file containing an entry with path traversal: ../evil.txt
        Path maliciousZip = tempDir.resolve("malicious.zip");
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(maliciousZip))) {
            java.util.zip.ZipEntry maliciousEntry = new java.util.zip.ZipEntry("../evil.txt");
            zos.putNextEntry(maliciousEntry);
            zos.write("Malicious payload escaping sandbox".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        Path targetDir = tempDir.resolve("sandbox");
        Files.createDirectories(targetDir);

        SecurityException ex = assertThrows(SecurityException.class, () ->
                FolderArchiveService.extractArchive(maliciousZip, targetDir));
        assertTrue(ex.getMessage().contains("Zip Slip"), "Exception message should indicate Zip Slip attack");

        // Verify that evil.txt was NEVER created outside targetDir
        assertFalse(Files.exists(tempDir.resolve("evil.txt")), "File must not be written outside target directory");
    }
}
