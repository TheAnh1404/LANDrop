package com.landrop.server.desktop;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Map;

/**
 * Generates crisp QR code images using ZXing for client URL sharing in the Desktop App.
 */
public final class QrCodeGenerator {

    private QrCodeGenerator() {}

    /**
     * Generates a QR Code as a {@link BufferedImage}.
     *
     * @param content Text or URL to encode
     * @param size    Width and height in pixels
     * @param fgColor Foreground color (dots)
     * @param bgColor Background color
     * @return Rendered image
     */
    public static BufferedImage generateQrCode(String content, int size, Color fgColor, Color bgColor) {
        if (content == null || content.isBlank()) {
            return createPlaceholder(size, "Chưa có URL", fgColor, bgColor);
        }

        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);

            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints);

            int width = bitMatrix.getWidth();
            int height = bitMatrix.getHeight();
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

            int fgRgb = fgColor.getRGB();
            int bgRgb = bgColor.getRGB();

            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    image.setRGB(x, y, bitMatrix.get(x, y) ? fgRgb : bgRgb);
                }
            }

            return image;
        } catch (Exception e) {
            return createPlaceholder(size, "Lỗi tạo QR", fgColor, bgColor);
        }
    }

    /**
     * Convenience method with standard high-contrast dark-on-white QR (or custom border).
     */
    public static BufferedImage generateQrCode(String content, int size) {
        return generateQrCode(content, size, new Color(0x0f, 0x17, 0x2a), Color.WHITE);
    }

    private static BufferedImage createPlaceholder(int size, String text, Color fgColor, Color bgColor) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(bgColor);
        g.fillRect(0, 0, size, size);
        g.setColor(fgColor);
        g.drawRect(2, 2, size - 4, size - 4);
        g.drawString(text, size / 4, size / 2);
        g.dispose();
        return img;
    }
}
