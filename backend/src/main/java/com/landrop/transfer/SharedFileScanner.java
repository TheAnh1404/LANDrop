package com.landrop.transfer;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Scans a designated shared/download folder for regular files and registers them
 * into FileTransferService's shared files registry for instant multi-peer availability.
 * Uses streaming Java NIO SHA-256 calculation with constant memory usage.
 */
public final class SharedFileScanner {

    private SharedFileScanner() {}

    /**
     * Synchronously scans the specified directory and registers all valid regular files into the service.
     * Safe against missing directory, empty files, nested subdirectories, and inaccessible files.
     *
     * @param directory target directory to scan
     * @param service FileTransferService instance where discovered files will be indexed
     * @return count of successfully indexed files
     */
    public static int scanAndRegister(Path directory, FileTransferService service) {
        if (directory == null || service == null) {
            return 0;
        }

        if (!Files.exists(directory)) {
            try {
                Files.createDirectories(directory);
            } catch (IOException e) {
                System.err.println("[SharedFileScanner] Cannot create directory: " + directory + " (" + e.getMessage() + ")");
                return 0;
            }
        }

        if (!Files.isDirectory(directory)) {
            return 0;
        }

        int indexedCount = 0;
        try (Stream<Path> stream = Files.walk(directory, 5, FileVisitOption.FOLLOW_LINKS)) {
            var regularFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return !name.startsWith(".") && !name.endsWith(".landrop.part") && !name.endsWith(".part");
                    })
                    .toList();

            for (Path filePath : regularFiles) {
                try {
                    if (Files.isReadable(filePath)) {
                        service.shareFile(filePath);
                        indexedCount++;
                        System.out.println("[SharedFileScanner] Indexed shared file: " + filePath.getFileName() +
                                " (" + Files.size(filePath) + " bytes)");
                    }
                } catch (Exception e) {
                    System.err.println("[SharedFileScanner] Skipped file " + filePath + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("[SharedFileScanner] Error traversing " + directory + ": " + e.getMessage());
        }

        return indexedCount;
    }

    /**
     * Asynchronously scans the specified directory on a virtual thread without blocking the calling thread.
     *
     * @param directory target directory to scan
     * @param service FileTransferService instance
     * @return CompletableFuture completing with count of indexed files
     */
    public static CompletableFuture<Integer> scanAndRegisterAsync(Path directory, FileTransferService service) {
        return CompletableFuture.supplyAsync(() -> scanAndRegister(directory, service),
                r -> Thread.ofVirtual().name("shared-file-scanner").start(r));
    }
}
