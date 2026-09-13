package com.landrop.transfer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SharedFileScannerTest {

    private static final String EMPTY_FILE_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    @DisplayName("Test 1: Startup directory scan indexes regular files correctly")
    void testStartupScanWithRegularFiles(@TempDir Path tempDir) throws Exception {
        Path f1 = tempDir.resolve("doc1.txt");
        Path f2 = tempDir.resolve("image.png");
        Files.writeString(f1, "Hello World from Doc 1", StandardCharsets.UTF_8);
        Files.writeString(f2, "Binary dummy data for PNG image", StandardCharsets.UTF_8);

        try (FileTransferService service = new FileTransferService(null, "peer1", "Peer 1", tempDir)) {
            int indexed = SharedFileScanner.scanAndRegister(tempDir, service);
            assertEquals(2, indexed);

            String hash1 = FileTransferUtils.calculateSha256(f1);
            String hash2 = FileTransferUtils.calculateSha256(f2);

            Map<String, Path> shared = service.getSharedFiles();
            assertEquals(f1, shared.get(hash1.toLowerCase() + ":" + Files.size(f1)));
            assertEquals(f2, shared.get(hash2.toLowerCase() + ":" + Files.size(f2)));
        }
    }

    @Test
    @DisplayName("Test 2: Missing directory handles gracefully and returns 0")
    void testMissingDirectoryHandledGracefully(@TempDir Path tempDir) {
        Path nonExistent = tempDir.resolve("non_existent_folder");
        try (FileTransferService service = new FileTransferService(null, "peer1", "Peer 1", tempDir)) {
            int count = SharedFileScanner.scanAndRegister(nonExistent, service);
            assertEquals(0, count);
        }
    }

    @Test
    @DisplayName("Test 3: Empty file (0 bytes) is correctly indexed with standard empty SHA-256")
    void testEmptyFileIndexing(@TempDir Path tempDir) throws Exception {
        Path emptyFile = tempDir.resolve("empty.dat");
        Files.createFile(emptyFile);
        assertEquals(0, Files.size(emptyFile));

        try (FileTransferService service = new FileTransferService(null, "peer1", "Peer 1", tempDir)) {
            int count = SharedFileScanner.scanAndRegister(tempDir, service);
            assertEquals(1, count);

            Map<String, Path> shared = service.getSharedFiles();
            String key = EMPTY_FILE_SHA256 + ":0";
            assertTrue(shared.containsKey(key), "Should contain empty file SHA-256 with size 0");
            assertEquals(emptyFile, shared.get(key));
        }
    }

    @Test
    @DisplayName("Test 4: Non-regular and .landrop.part partial files are strictly ignored")
    void testPartFilesIgnored(@TempDir Path tempDir) throws Exception {
        Path normal = tempDir.resolve("valid.txt");
        Path partial1 = tempDir.resolve("downloading.txt.landrop.part");
        Path partial2 = tempDir.resolve("partial_file.part");
        Files.writeString(normal, "Normal content", StandardCharsets.UTF_8);
        Files.writeString(partial1, "Partial unfinished chunk data", StandardCharsets.UTF_8);
        Files.writeString(partial2, "Other partial content", StandardCharsets.UTF_8);

        try (FileTransferService service = new FileTransferService(null, "peer1", "Peer 1", tempDir)) {
            int count = SharedFileScanner.scanAndRegister(tempDir, service);
            assertEquals(1, count, "Only valid.txt should be indexed, partial files must be ignored");

            Map<String, Path> shared = service.getSharedFiles();
            String validHash = FileTransferUtils.calculateSha256(normal);
            assertTrue(shared.containsKey(validHash.toLowerCase() + ":" + Files.size(normal)));
        }
    }

    @Test
    @DisplayName("Test 5: Nested subdirectories within max depth are scanned and indexed")
    void testNestedDirectoryScan(@TempDir Path tempDir) throws Exception {
        Path sub1 = tempDir.resolve("sub1");
        Path sub2 = sub1.resolve("sub2");
        Files.createDirectories(sub2);

        Path rootFile = tempDir.resolve("root.txt");
        Path subFile = sub2.resolve("nested.txt");
        Files.writeString(rootFile, "Root file", StandardCharsets.UTF_8);
        Files.writeString(subFile, "Nested file", StandardCharsets.UTF_8);

        try (FileTransferService service = new FileTransferService(null, "peer1", "Peer 1", tempDir)) {
            int count = SharedFileScanner.scanAndRegister(tempDir, service);
            assertEquals(2, count, "Both root and nested file should be indexed");

            String rootHash = FileTransferUtils.calculateSha256(rootFile);
            String subHash = FileTransferUtils.calculateSha256(subFile);

            Map<String, Path> shared = service.getSharedFiles();
            assertTrue(shared.containsKey(rootHash.toLowerCase() + ":" + Files.size(rootFile)));
            assertTrue(shared.containsKey(subHash.toLowerCase() + ":" + Files.size(subFile)));
        }
    }

    @Test
    @DisplayName("Test 6: Asynchronous scanning completes without blocking")
    void testAsyncScanCompletes(@TempDir Path tempDir) throws Exception {
        Path f1 = tempDir.resolve("async1.txt");
        Path f2 = tempDir.resolve("async2.txt");
        Files.writeString(f1, "Async content 1", StandardCharsets.UTF_8);
        Files.writeString(f2, "Async content 2", StandardCharsets.UTF_8);

        try (FileTransferService service = new FileTransferService(null, "peer1", "Peer 1", tempDir)) {
            CompletableFuture<Integer> future = service.scanSharedDirectoryAsync();
            Integer count = future.get(5, TimeUnit.SECONDS);
            assertEquals(2, count);
            assertEquals(2, service.getSharedFiles().size());
        }
    }
}
