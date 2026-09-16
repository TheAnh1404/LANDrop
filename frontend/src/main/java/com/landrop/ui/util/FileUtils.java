package com.landrop.ui.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for formatting large file sizes, determining file category icons,
 * and handling inline group chat file attachment tags.
 */
public final class FileUtils {

    private static final BigInteger ONE_KB = BigInteger.valueOf(1024);
    private static final BigInteger ONE_MB = ONE_KB.multiply(ONE_KB);
    private static final BigInteger ONE_GB = ONE_MB.multiply(ONE_KB);
    private static final BigInteger ONE_TB = ONE_GB.multiply(ONE_KB);

    private static final Pattern FILE_TAG_PATTERN =
            Pattern.compile("\\[LANDROP_FILE:([a-zA-Z0-9_-]+)\\|([^|\\]]+)\\|([0-9]+)\\|([a-zA-Z0-9_-]*)\\]");

    public record FileShareInfo(
            String fileId,
            String fileName,
            long sizeBytes,
            String sha256,
            String accompanyingText
    ) {
        public String formattedSize() {
            return formatSize(sizeBytes);
        }

        public String icon() {
            return getFileIcon(fileName, null);
        }

        public String shortHash() {
            if (sha256 == null || sha256.isBlank()) return "N/A";
            return sha256.length() > 12 ? sha256.substring(0, 10) + "..." : sha256;
        }
    }

    private FileUtils() {}

    /**
     * Formats raw bytes into human-readable B, KB, MB, GB, or TB.
     */
    public static String formatSize(long bytes) {
        return formatSize(BigInteger.valueOf(bytes));
    }

    /**
     * Formats a BigInteger byte count into human-readable B, KB, MB, GB, or TB.
     */
    public static String formatSize(BigInteger bytes) {
        if (bytes == null || bytes.compareTo(BigInteger.ZERO) <= 0) {
            return "0 B";
        }

        if (bytes.compareTo(ONE_TB) >= 0) {
            BigDecimal tb = new BigDecimal(bytes).divide(new BigDecimal(ONE_TB), 2, RoundingMode.HALF_UP);
            return tb + " TB";
        }
        if (bytes.compareTo(ONE_GB) >= 0) {
            BigDecimal gb = new BigDecimal(bytes).divide(new BigDecimal(ONE_GB), 2, RoundingMode.HALF_UP);
            return gb + " GB";
        }
        if (bytes.compareTo(ONE_MB) >= 0) {
            BigDecimal mb = new BigDecimal(bytes).divide(new BigDecimal(ONE_MB), 1, RoundingMode.HALF_UP);
            return mb + " MB";
        }
        if (bytes.compareTo(ONE_KB) >= 0) {
            BigDecimal kb = new BigDecimal(bytes).divide(new BigDecimal(ONE_KB), 1, RoundingMode.HALF_UP);
            return kb + " KB";
        }

        return bytes + " B";
    }

    /**
     * Selects an intuitive icon for the given filename extension or mime type.
     */
    public static String getFileIcon(String fileName, String mimeType) {
        String lower = fileName != null ? fileName.toLowerCase() : "";
        String lowerMime = mimeType != null ? mimeType.toLowerCase() : "";

        if (lowerMime.startsWith("video/") || lower.endsWith(".mp4") || lower.endsWith(".mkv")
                || lower.endsWith(".avi") || lower.endsWith(".mov") || lower.endsWith(".wmv") || lower.endsWith(".flv")) {
            return "🎬";
        }
        if (lowerMime.startsWith("audio/") || lower.endsWith(".mp3") || lower.endsWith(".wav")
                || lower.endsWith(".flac") || lower.endsWith(".aac") || lower.endsWith(".ogg") || lower.endsWith(".m4a")) {
            return "🎵";
        }
        if (lowerMime.contains("zip") || lowerMime.contains("tar") || lowerMime.contains("compressed")
                || lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z")
                || lower.endsWith(".tar") || lower.endsWith(".gz") || lower.endsWith(".bz2")) {
            return "📦";
        }
        if (lowerMime.startsWith("image/") || lower.endsWith(".png") || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg") || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".svg")) {
            return "🖼️";
        }
        if (lower.endsWith(".iso") || lower.endsWith(".img") || lower.endsWith(".dmg")
                || lower.endsWith(".exe") || lower.endsWith(".msi") || lower.endsWith(".apk")) {
            return "💿";
        }
        if (lower.endsWith(".pdf")) {
            return "📕";
        }
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) {
            return "📘";
        }
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx") || lower.endsWith(".csv")) {
            return "📊";
        }
        if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) {
            return "📙";
        }
        if (lower.endsWith(".java") || lower.endsWith(".py") || lower.endsWith(".js")
                || lower.endsWith(".ts") || lower.endsWith(".html") || lower.endsWith(".css")
                || lower.endsWith(".json") || lower.endsWith(".xml") || lower.endsWith(".sql")) {
            return "💻";
        }
        return "📄";
    }

    /**
     * Encodes file metadata into an inline chat message tag.
     */
    public static String buildFileShareTag(String fileId, String fileName, long sizeBytes, String sha256) {
        String cleanName = fileName.replace("|", "_").replace("[", "(").replace("]", ")");
        String hash = sha256 != null ? sha256 : "";
        return "[LANDROP_FILE:" + fileId + "|" + cleanName + "|" + sizeBytes + "|" + hash + "]";
    }

    /**
     * Parses an inline chat message to extract file metadata if present.
     */
    public static FileShareInfo parseFileShareInfo(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) return null;

        Matcher matcher = FILE_TAG_PATTERN.matcher(rawMessage);
        if (matcher.find()) {
            String fileId = matcher.group(1);
            String fileName = matcher.group(2);
            long sizeBytes = Long.parseLong(matcher.group(3));
            String sha256 = matcher.group(4);

            String accompanyingText = rawMessage.replace(matcher.group(0), "").trim();
            return new FileShareInfo(fileId, fileName, sizeBytes, sha256, accompanyingText);
        }
        return null;
    }
}
