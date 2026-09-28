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
}
