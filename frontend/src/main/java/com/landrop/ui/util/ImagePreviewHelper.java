package com.landrop.ui.util;

import javafx.application.Platform;
import javafx.scene.image.Image;

import java.io.File;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Utility for background asynchronous thumbnail generation and caching for image files.
 */
public final class ImagePreviewHelper {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "bmp", "webp"
    );

    private static final Map<String, Image> THUMBNAIL_CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "ImagePreviewLoader");
        t.setDaemon(true);
        return t;
    });

    private ImagePreviewHelper() {}

    /**
     * Checks if a file name corresponds to a supported image format.
     */
    public static boolean isImageFile(String fileName) {
        if (fileName == null || !fileName.contains(".")) return false;
        String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return IMAGE_EXTENSIONS.contains(ext);
    }

    /**
     * Checks if a File is an image.
     */
    public static boolean isImageFile(File file) {
        return file != null && file.isFile() && isImageFile(file.getName());
    }

    /**
     * Asynchronously loads a scaled thumbnail image on a background thread and caches it.
     *
     * @param file the image file to load
     * @param width target width
     * @param height target height
     * @param callback consumer invoked on the JavaFX Application Thread with the loaded Image
     */
    public static void loadThumbnailAsync(File file, double width, double height, Consumer<Image> callback) {
        Objects.requireNonNull(callback, "callback cannot be null");
        if (!isImageFile(file) || !file.exists()) {
            return;
        }
        String key = file.getAbsolutePath() + "@" + (int) width + "x" + (int) height;
        Image cached = THUMBNAIL_CACHE.get(key);
        if (cached != null) {
            callback.accept(cached);
            return;
        }

        EXECUTOR.submit(() -> {
            try {
                String uri = file.toURI().toString();
                Image img = new Image(uri, width, height, true, true, false);
                if (!img.isError()) {
                    THUMBNAIL_CACHE.put(key, img);
                    Platform.runLater(() -> callback.accept(img));
                }
            } catch (Exception ignored) {
            }
        });
    }
}
