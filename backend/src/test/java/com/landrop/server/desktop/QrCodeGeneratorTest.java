package com.landrop.server.desktop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("QrCodeGenerator Unit Tests")
class QrCodeGeneratorTest {

    @Test
    @DisplayName("Should generate valid QR code BufferedImage for URL")
    void testGenerateQrCodeForUrl() {
        String testUrl = "http://192.168.1.15:3000";
        int size = 160;

        BufferedImage image = QrCodeGenerator.generateQrCode(testUrl, size);
        assertNotNull(image, "Generated QR image should not be null");
        assertEquals(size, image.getWidth(), "QR width should match requested size");
        assertEquals(size, image.getHeight(), "QR height should match requested size");
    }

    @Test
    @DisplayName("Should generate valid QR code with custom colors")
    void testGenerateQrCodeWithColors() {
        String testUrl = "http://10.0.0.5:8080";
        int size = 120;

        BufferedImage image = QrCodeGenerator.generateQrCode(testUrl, size, Color.BLUE, Color.WHITE);
        assertNotNull(image);
        assertEquals(size, image.getWidth());
        assertEquals(size, image.getHeight());
    }

    @Test
    @DisplayName("Should handle empty or null URL without throwing exception")
    void testGenerateQrCodeEmptyContent() {
        BufferedImage imgNull = QrCodeGenerator.generateQrCode(null, 100);
        assertNotNull(imgNull, "Should return placeholder for null input");

        BufferedImage imgBlank = QrCodeGenerator.generateQrCode("   ", 100);
        assertNotNull(imgBlank, "Should return placeholder for blank input");
    }
}
