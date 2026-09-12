package com.landrop.ui.utils;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

/**
 * Utility methods for showing user-friendly JavaFX dialogs without exposing raw stack traces.
 */
public final class AlertUtils {

    private AlertUtils() {
    }

    public static void showError(String title, String header, String content) {
        runOnFxThread(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR, content, ButtonType.OK);
            alert.setTitle(title);
            alert.setHeaderText(header);
            alert.showAndWait();
        });
    }

    public static void showWarning(String title, String header, String content) {
        runOnFxThread(() -> {
            Alert alert = new Alert(Alert.AlertType.WARNING, content, ButtonType.OK);
            alert.setTitle(title);
            alert.setHeaderText(header);
            alert.showAndWait();
        });
    }

    public static void showInfo(String title, String header, String content) {
        runOnFxThread(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, content, ButtonType.OK);
            alert.setTitle(title);
            alert.setHeaderText(header);
            alert.showAndWait();
        });
    }

    private static void runOnFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }
}
