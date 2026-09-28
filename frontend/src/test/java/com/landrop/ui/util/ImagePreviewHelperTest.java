package com.landrop.ui.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImagePreviewHelperTest {

    @Test
    void testIsImageFile() {
        assertTrue(ImagePreviewHelper.isImageFile("screenshot.png"));
        assertTrue(ImagePreviewHelper.isImageFile("avatar.JPG"));
        assertTrue(ImagePreviewHelper.isImageFile("banner.JPEG"));
        assertTrue(ImagePreviewHelper.isImageFile("animation.gif"));
        assertTrue(ImagePreviewHelper.isImageFile("photo.webp"));
        assertTrue(ImagePreviewHelper.isImageFile("icon.bmp"));

        assertFalse(ImagePreviewHelper.isImageFile("document.pdf"));
        assertFalse(ImagePreviewHelper.isImageFile("archive.zip"));
        assertFalse(ImagePreviewHelper.isImageFile("code.java"));
        assertFalse(ImagePreviewHelper.isImageFile("noextension"));
        assertFalse(ImagePreviewHelper.isImageFile((String) null));
    }
}
