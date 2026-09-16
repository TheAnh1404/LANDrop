package com.landrop.ui.util;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

class FileUtilsTest {

    @Test
    void testFormatSizeVariousMagnitudes() {
        assertEquals("0 B", FileUtils.formatSize(0));
        assertEquals("500 B", FileUtils.formatSize(500));
        assertEquals("1.0 KB", FileUtils.formatSize(1024));
        assertEquals("2.5 KB", FileUtils.formatSize(2560));
        assertEquals("1.0 MB", FileUtils.formatSize(1024 * 1024));
        assertEquals("450.5 MB", FileUtils.formatSize((long)(450.5 * 1024 * 1024)));
        assertEquals("1.00 GB", FileUtils.formatSize(1024L * 1024L * 1024L));
        assertEquals("4.80 GB", FileUtils.formatSize((long)(4.8 * 1024L * 1024L * 1024L)));
        assertEquals("24.50 GB", FileUtils.formatSize((long)(24.5 * 1024L * 1024L * 1024L)));
        assertEquals("1.50 TB", FileUtils.formatSize(BigInteger.valueOf((long)(1.5 * 1024 * 1024)).multiply(BigInteger.valueOf(1024 * 1024))));
    }

    @Test
    void testGetFileIcon() {
        assertEquals("🎬", FileUtils.getFileIcon("movie.mp4", "video/mp4"));
        assertEquals("🎬", FileUtils.getFileIcon("raw_footage.mkv", null));
        assertEquals("🎵", FileUtils.getFileIcon("song.mp3", "audio/mpeg"));
        assertEquals("📦", FileUtils.getFileIcon("project.zip", "application/zip"));
        assertEquals("📦", FileUtils.getFileIcon("archive.tar.gz", null));
        assertEquals("🖼️", FileUtils.getFileIcon("photo.png", "image/png"));
        assertEquals("💿", FileUtils.getFileIcon("ubuntu-24.04.iso", null));
        assertEquals("💿", FileUtils.getFileIcon("installer.exe", null));
        assertEquals("📕", FileUtils.getFileIcon("specification.pdf", "application/pdf"));
        assertEquals("💻", FileUtils.getFileIcon("MainApp.java", "text/x-java-source"));
        assertEquals("📄", FileUtils.getFileIcon("notes.txt", "text/plain"));
    }

    @Test
    void testBuildAndParseFileShareTag() {
        String fileId = "f47ac10b-58cc-4372-a567-0e02b2c3d479";
        String fileName = "presentation_final.mp4";
        long size = 4850000000L; // ~4.85 GB
        String sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        String tag = FileUtils.buildFileShareTag(fileId, fileName, size, sha256);
        assertEquals("[LANDROP_FILE:f47ac10b-58cc-4372-a567-0e02b2c3d479|presentation_final.mp4|4850000000|e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855]", tag);

        String chatMessage = tag + " Mọi người xem qua video này nhé!";
        FileUtils.FileShareInfo parsed = FileUtils.parseFileShareInfo(chatMessage);

        assertNotNull(parsed);
        assertEquals(fileId, parsed.fileId());
        assertEquals(fileName, parsed.fileName());
        assertEquals(size, parsed.sizeBytes());
        assertEquals(sha256, parsed.sha256());
        assertEquals("Mọi người xem qua video này nhé!", parsed.accompanyingText());
        assertEquals("4.52 GB", parsed.formattedSize());
        assertEquals("🎬", parsed.icon());
        assertTrue(parsed.shortHash().startsWith("e3b0c44298"));
    }

    @Test
    void testParseFileShareInfoWithoutTagReturnsNull() {
        assertNull(FileUtils.parseFileShareInfo("Hello everyone!"));
        assertNull(FileUtils.parseFileShareInfo(""));
        assertNull(FileUtils.parseFileShareInfo(null));
    }
}
