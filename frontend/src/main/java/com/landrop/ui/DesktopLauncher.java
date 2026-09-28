package com.landrop.ui;

import javafx.application.Application;

/** Plain Java entry point for native launchers using JavaFX jars on the classpath. */
public final class DesktopLauncher {
    private DesktopLauncher() {}

    public static void main(String[] args) {
        Application.launch(MainApp.class, args);
    }
}
