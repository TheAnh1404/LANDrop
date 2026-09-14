package com.landrop.server;

import com.landrop.server.bootstrap.ServerBootstrap;
import com.landrop.server.config.ServerConfig;
import com.landrop.util.AppLogger;

/**
 * Main application entrypoint for the standalone LANDrop Server (FOUNDATION-01A).
 */
public class LandropServerMain {

    private static final String TAG = "LandropServerMain";

    public static void main(String[] args) {
        try {
            ServerConfig config = ServerConfig.load();
            ServerBootstrap bootstrap = new ServerBootstrap(config);
            bootstrap.start();

            // Keep main thread alive
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            AppLogger.info(TAG, "Server main thread interrupted, exiting...");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            AppLogger.error(TAG, "Fatal error starting LANDrop Server: " + e.getMessage(), e);
            System.exit(1);
        }
    }
}
