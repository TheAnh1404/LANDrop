package com.landrop.server.desktop;

import com.formdev.flatlaf.FlatLightLaf;
import com.landrop.server.LandropServerMain;
import com.landrop.util.AppLogger;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;

/**
 * Main application entrypoint for LANDrop Server Desktop Application.
 * Supports native desktop GUI mode as well as headless/CLI mode.
 */
public class ServerDesktopApp {

    private static final String TAG = "ServerDesktopApp";

    public static void main(String[] args) {
        boolean headless = false;
        for (String arg : args) {
            if ("--headless".equalsIgnoreCase(arg) || "--cli".equalsIgnoreCase(arg) || "-headless".equalsIgnoreCase(arg)) {
                headless = true;
                break;
            }
        }

        if (headless || GraphicsEnvironment.isHeadless()) {
            AppLogger.info(TAG, "Starting LANDrop Server in headless/CLI mode...");
            LandropServerMain.main(args);
            return;
        }

        // Initialize Native Desktop GUI
        AppLogger.info(TAG, "Launching LANDrop Server Desktop Application...");

        System.setProperty("sun.java2d.uiScale.enabled", "true");
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");

        SwingUtilities.invokeLater(() -> {
            try {
                FlatLightLaf.setup();

                ServerMainFrame frame = new ServerMainFrame();
                frame.setVisible(true);

                // Auto-start server for seamless user experience
                frame.startServer();
            } catch (Exception e) {
                AppLogger.error(TAG, "Failed to initialize Server Desktop GUI: " + e.getMessage(), e);
                // Fallback to headless
                LandropServerMain.main(args);
            }
        });
    }
}
