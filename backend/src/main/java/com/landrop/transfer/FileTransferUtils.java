package com.landrop.transfer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Utility functions for file system safety, filename sanitization,
 * unique destination resolution, and metric formatting.
 */
public final class FileTransferUtils {

    public static final String DEFAULT_DOWNLOAD_FOLDER_NAME = "LANDrop";
    private static final String FALLBACK_FILE_NAME = "downloaded_file";
    private static final java.text.DecimalFormatSymbols SYMBOLS = new java.text.DecimalFormatSymbols(java.util.Locale.US);
    private static final DecimalFormat ONE_DECIMAL_FORMAT = new DecimalFormat("#,##0.0", SYMBOLS);
    private static final DecimalFormat TWO_DECIMAL_FORMAT = new DecimalFormat("#,##0.00", SYMBOLS);

    private FileTransferUtils() {}

    /**
     * Sanitizes a remote filename to prevent directory traversal and filesystem attacks.
     * Strips directory components, path separators, control characters, and reserved symbols.
     *
     * @param rawName the raw filename received over the network
     * @return a safe, sanitized filename
     */
    public static String sanitizeFilename(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return FALLBACK_FILE_NAME;
        }

        // 1. Strip any directory path components (both UNIX / and Windows \)
        String clean = rawName.trim();
        clean = clean.replace('\\', '/');
        int lastSlash = clean.lastIndexOf('/');
        if (lastSlash >= 0) {
            clean = clean.substring(lastSlash + 1);
        }

        // 2. Replace null characters and control characters
        clean = clean.replaceAll("[\\p{Cntrl}]", "");

        // 3. Remove characters invalid in Windows / POSIX file systems: / \ : * ? " < > |
        clean = clean.replaceAll("[/:*?\"<>|\\\\]", "_");

        // 4. Remove leading and trailing spaces, dots, or '..' sequences
        clean = clean.replaceAll("^\\.+", ""); // No leading hidden dots
        clean = clean.trim();

        if (clean.isBlank() || clean.equals("..") || clean.equals(".")) {
            return FALLBACK_FILE_NAME;
        }

        return clean;
    }

    /**
     * Resolves the default download directory (~/Downloads/LANDrop/) and ensures it exists.
     *
     * @return the resolved Path
     * @throws IOException if directory creation fails
     */
    public static Path getDefaultDownloadDirectory() throws IOException {
        String userHome = System.getProperty("user.home", ".");
        Path downloads = Paths.get(userHome, "Downloads", DEFAULT_DOWNLOAD_FOLDER_NAME);
        if (!Files.exists(downloads)) {
            Files.createDirectories(downloads);
        }
        return downloads.toAbsolutePath().normalize();
    }

    /**
     * Verifies that the candidate path strictly resides inside the allowed directory.
     *
     * @param baseDir the trusted base directory
     * @param targetPath the candidate file path
     * @return the normalized absolute target path
     * @throws SecurityException if target path attempts path traversal outside baseDir
     */
    public static Path verifyPathContained(Path baseDir, Path targetPath) {
        Objects.requireNonNull(baseDir, "baseDir cannot be null");
        Objects.requireNonNull(targetPath, "targetPath cannot be null");

        Path normalizedBase = baseDir.toAbsolutePath().normalize();
        Path normalizedTarget = targetPath.toAbsolutePath().normalize();

        if (!normalizedTarget.startsWith(normalizedBase)) {
            throw new SecurityException("Path traversal attempt detected: " + targetPath + " is outside " + baseDir);
        }
        return normalizedTarget;
    }

    /**
     * Resolves a unique destination file path within the specified directory.
     * If a file already exists, appends an incrementing counter: name (1).ext, name (2).ext.
     *
     * @param directory the destination directory
     * @param safeFileName the sanitized filename
     * @return a unique, non-colliding destination Path
     */
    public static Path resolveUniqueDestination(Path directory, String safeFileName) {
        Objects.requireNonNull(directory, "directory cannot be null");
        String sanitized = sanitizeFilename(safeFileName);
        Path target = directory.resolve(sanitized);
        verifyPathContained(directory, target);

        if (!Files.exists(target)) {
            return target;
        }

        // Split base name and extension
        String baseName;
        String extension = "";
        int dotIndex = sanitized.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < sanitized.length() - 1) {
            baseName = sanitized.substring(0, dotIndex);
            extension = sanitized.substring(dotIndex);
        } else {
            baseName = sanitized;
        }

        int counter = 1;
        while (true) {
            String candidateName = String.format("%s (%d)%s", baseName, counter, extension);
            Path candidatePath = directory.resolve(candidateName);
            verifyPathContained(directory, candidatePath);
            if (!Files.exists(candidatePath)) {
                return candidatePath;
            }
            counter++;
        }
    }

    /**
     * Formats a byte size into a human-readable string (e.g. 512 B, 4.5 KB, 10.2 MB, 1.2 GB).
     *
     * @param bytes the size in bytes
     * @return formatted string
     */
    public static String formatFileSize(long bytes) {
        if (bytes < 0) {
            return "0 B";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return ONE_DECIMAL_FORMAT.format(kb) + " KB";
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return ONE_DECIMAL_FORMAT.format(mb) + " MB";
        }
        double gb = mb / 1024.0;
        return TWO_DECIMAL_FORMAT.format(gb) + " GB";
    }

    /**
     * Formats speed in bytes per second into human-readable rate (e.g. 2.4 MB/s).
     *
     * @param bytesPerSec transfer speed
     * @return formatted speed string
     */
    public static String formatSpeed(double bytesPerSec) {
        if (bytesPerSec <= 0) {
            return "0 KB/s";
        }
        double kbPerSec = bytesPerSec / 1024.0;
        if (kbPerSec < 1024) {
            return ONE_DECIMAL_FORMAT.format(kbPerSec) + " KB/s";
        }
        double mbPerSec = kbPerSec / 1024.0;
        return ONE_DECIMAL_FORMAT.format(mbPerSec) + " MB/s";
    }

    /**
     * Calculates the SHA-256 hash of a file using streaming I/O.
     * Memory usage remains constant regardless of file size.
     *
     * @param path path to the file
     * @return 64-character lowercase hex SHA-256 string
     * @throws IOException if file reading fails
     */
    public static String calculateSha256(Path path) throws IOException {
        Objects.requireNonNull(path, "path cannot be null");
        try (InputStream in = Files.newInputStream(path)) {
            return calculateSha256(in);
        }
    }

    /**
     * Calculates the SHA-256 hash of data from an InputStream.
     *
     * @param in the source InputStream
     * @return 64-character lowercase hex SHA-256 string
     * @throws IOException if stream reading fails
     */
    public static String calculateSha256(InputStream in) throws IOException {
        Objects.requireNonNull(in, "InputStream cannot be null");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024]; // 64 KB buffer
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Calculates the SHA-256 hash of a byte array.
     *
     * @param data input bytes
     * @return 64-character lowercase hex SHA-256 string
     */
    public static String calculateSha256(byte[] data) {
        Objects.requireNonNull(data, "data cannot be null");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(data);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Validates whether the given string is a valid 64-character hexadecimal SHA-256 hash.
     *
     * @param hash the hash string to validate
     * @return true if valid SHA-256 hex string, false otherwise
     */
    public static boolean isValidSha256(String hash) {
        return hash != null && hash.matches("^[a-fA-F0-9]{64}$");
    }

    /**
     * Case-insensitively checks if two SHA-256 hex strings match.
     *
     * @param hash1 first hash
     * @param hash2 second hash
     * @return true if both hashes are non-null and equal ignoring case
     */
    public static boolean hashesMatch(String hash1, String hash2) {
        return hash1 != null && hash1.equalsIgnoreCase(hash2);
    }

    /**
     * Shortens a 64-character hash for concise UI presentation (e.g. "ba7816bf8f...").
     *
     * @param hash full hash string
     * @return shortened hash
     */
    public static String shortenHash(String hash) {
        if (hash == null || hash.isBlank()) {
            return "";
        }
        if (hash.length() <= 12) {
            return hash;
        }
        return hash.substring(0, 10) + "...";
    }

    /**
     * Efficiently transfers data from a FileChannel to a WritableByteChannel (e.g. SocketChannel)
     * using OS zero-copy mechanisms to eliminate user/kernel context switches.
     */
    public static long transferFromFileChannel(java.nio.channels.FileChannel source, long position, long count, java.nio.channels.WritableByteChannel target) throws IOException {
        long transferred = 0;
        while (transferred < count) {
            long written = source.transferTo(position + transferred, count - transferred, target);
            if (written <= 0) break;
            transferred += written;
        }
        return transferred;
    }

    /**
     * Efficiently transfers data from a ReadableByteChannel into a FileChannel at a given position.
     */
    public static long transferToFileChannel(java.nio.channels.ReadableByteChannel source, java.nio.channels.FileChannel target, long position, long count) throws IOException {
        long transferred = 0;
        while (transferred < count) {
            long read = target.transferFrom(source, position + transferred, count - transferred);
            if (read <= 0) break;
            transferred += read;
        }
        return transferred;
    }
}
