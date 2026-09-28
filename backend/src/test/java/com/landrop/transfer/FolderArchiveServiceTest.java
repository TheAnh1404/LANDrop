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
}
