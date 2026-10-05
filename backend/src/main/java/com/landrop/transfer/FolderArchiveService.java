package com.landrop.transfer;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Service for archiving directories into temporary ZIP packages for seamless P2P folder transfers.
 */
public final class FolderArchiveService {
    private FolderArchiveService() {}

    public static Path createArchive(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            throw new IllegalArgumentException("Path is not a directory: " + folder);
        }
        String folderName = folder.getFileName() != null ? folder.getFileName().toString() : "folder";
        Path tempZip = Files.createTempFile("landrop-" + folderName + "-", ".zip");

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tempZip))) {
            Files.walkFileTree(folder, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String relativePath = folder.relativize(file).toString().replace('\\', '/');
                    zos.putNextEntry(new ZipEntry(relativePath));
                    Files.copy(file, zos);
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return tempZip;
    }

    public static void deleteArchive(Path archive) throws IOException {
        if (archive != null) {
            Files.deleteIfExists(archive);
        }
    }

    /**
     * Extracts a ZIP archive safely into the target directory with strict Zip Slip protection.
     *
     * @param archive the ZIP archive path to extract
     * @param targetDir the destination directory
     * @param overwrite whether to overwrite existing destination files
     * @return the destination directory path
     * @throws IOException if an I/O error occurs
     * @throws SecurityException if an entry attempts path traversal outside targetDir (Zip Slip)
     */
    public static Path extractArchive(Path archive, Path targetDir, boolean overwrite) throws IOException {
        if (archive == null || !Files.isRegularFile(archive)) {
            throw new IllegalArgumentException("Archive file does not exist or is not a file: " + archive);
        }
        if (targetDir == null) {
            throw new IllegalArgumentException("Target directory cannot be null");
        }

        Path normalizedTargetDir = targetDir.toAbsolutePath().normalize();
        Files.createDirectories(normalizedTargetDir);

        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path destinationFile = normalizedTargetDir.resolve(entry.getName()).normalize();

                // Zip Slip Path Traversal Protection
                if (!destinationFile.startsWith(normalizedTargetDir)) {
                    throw new SecurityException("Bad zip entry (Zip Slip attack detected): " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(destinationFile);
                } else {
                    Path parent = destinationFile.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    if (overwrite) {
                        Files.copy(zis, destinationFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } else if (!Files.exists(destinationFile)) {
                        Files.copy(zis, destinationFile);
                    }
                }
                zis.closeEntry();
            }
        }
        return normalizedTargetDir;
    }

    /**
     * Extracts a ZIP archive safely into the target directory, overwriting existing files by default.
     *
     * @param archive the ZIP archive path to extract
     * @param targetDir the destination directory
     * @return the destination directory path
     * @throws IOException if an I/O error occurs
     */
    public static Path extractArchive(Path archive, Path targetDir) throws IOException {
        return extractArchive(archive, targetDir, true);
    }
}
