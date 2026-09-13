package com.landrop.ui;

import com.landrop.ui.controllers.MainController;
import com.landrop.ui.controllers.SetupController;
import com.landrop.ui.services.UIStateService;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.Map;

/**
 * Main JavaFX Desktop Application entry point for LANDrop.
 */
public class MainApp extends Application {

    public static final double DEFAULT_WIDTH = 1200.0;
    public static final double DEFAULT_HEIGHT = 760.0;
    public static final double MIN_WIDTH = 1000.0;
    public static final double MIN_HEIGHT = 650.0;

    private Stage primaryStage;
    private UIStateService stateService;

    // Parsed configuration
    private String configuredDisplayName = "Peer A";
    private int configuredLocalPort = 5001;
    private String configuredDiscoveryHost = "localhost";
    private int configuredDiscoveryPort = 8888;
    private String configuredAdvertisedHost = "127.0.0.1";
    private boolean configuredAutoConnect = false;

    @Override
    public void init() {
        this.stateService = new UIStateService();
        parseParameters();
    }

    private void parseParameters() {
        Parameters params = getParameters();
        Map<String, String> named = params.getNamed();

        // 1. Display Name
        if (named.containsKey("display-name")) {
            this.configuredDisplayName = stripQuotes(named.get("display-name"));
        } else if (System.getProperty("display.name") != null) {
            this.configuredDisplayName = stripQuotes(System.getProperty("display.name"));
        }

        // 2. Peer Port
        if (named.containsKey("peer-port")) {
            try {
                this.configuredLocalPort = Integer.parseInt(stripQuotes(named.get("peer-port")));
            } catch (NumberFormatException ignored) {
            }
        } else if (System.getProperty("peer.port") != null) {
            try {
                this.configuredLocalPort = Integer.parseInt(stripQuotes(System.getProperty("peer.port")));
            } catch (NumberFormatException ignored) {
            }
        }

        // 3. Discovery Host
        if (named.containsKey("discovery-host")) {
            this.configuredDiscoveryHost = stripQuotes(named.get("discovery-host"));
        } else if (System.getProperty("discovery.host") != null) {
            this.configuredDiscoveryHost = stripQuotes(System.getProperty("discovery.host"));
        }

        // 4. Discovery Port
        if (named.containsKey("discovery-port")) {
            try {
                this.configuredDiscoveryPort = Integer.parseInt(stripQuotes(named.get("discovery-port")));
            } catch (NumberFormatException ignored) {
            }
        } else if (System.getProperty("discovery.port") != null) {
            try {
                this.configuredDiscoveryPort = Integer.parseInt(stripQuotes(System.getProperty("discovery.port")));
            } catch (NumberFormatException ignored) {
            }
        }

        // 5. Advertised Host
        if (named.containsKey("advertised-host")) {
            this.configuredAdvertisedHost = stripQuotes(named.get("advertised-host"));
        } else if (System.getProperty("advertised.host") != null) {
            this.configuredAdvertisedHost = stripQuotes(System.getProperty("advertised.host"));
        }

        // 6. Auto-connect flag
        if (named.containsKey("auto-connect")) {
            this.configuredAutoConnect = Boolean.parseBoolean(stripQuotes(named.get("auto-connect")));
        } else if (System.getProperty("auto.connect") != null) {
            this.configuredAutoConnect = Boolean.parseBoolean(stripQuotes(System.getProperty("auto.connect")));
        }

        // 7. Custom Download Directory
        String customDownloadDir = null;
        if (named.containsKey("download-dir")) {
            customDownloadDir = stripQuotes(named.get("download-dir"));
        } else if (System.getProperty("download.dir") != null) {
            customDownloadDir = stripQuotes(System.getProperty("download.dir"));
        }
        if (customDownloadDir != null && !customDownloadDir.isBlank()) {
            this.stateService.setConfiguredDownloadDirectory(java.nio.file.Paths.get(customDownloadDir));
        }
    }

    private String stripQuotes(String val) {
        if (val == null) return "";
        String s = val.trim();
        if ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\""))) {
            if (s.length() >= 2) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    @Override
    public void start(Stage stage) throws Exception {
        this.primaryStage = stage;

        stage.setTitle("LANDrop");
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);
        stage.setWidth(DEFAULT_WIDTH);
        stage.setHeight(DEFAULT_HEIGHT);

        showSetupView();
        stage.show();
        stage.toFront();
        stage.requestFocus();
        System.out.println("[LANDrop] JavaFX UI initialized successfully. Window: LANDrop (" + configuredDisplayName + ")");
    }

    /**
     * Displays the initial peer setup and network connection view.
     */
    public void showSetupView() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/landrop/ui/setup-view.fxml"));
            Parent root = loader.load();

            SetupController controller = loader.getController();
            controller.init(
                    this,
                    stateService,
                    configuredDisplayName,
                    configuredLocalPort,
                    configuredDiscoveryHost,
                    configuredDiscoveryPort,
                    configuredAdvertisedHost,
                    configuredAutoConnect
            );

            Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
            applyStylesheets(scene);
            primaryStage.setScene(scene);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Transitions from setup to the 3-section Main Dashboard interface.
     */
    public void showMainView() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/landrop/ui/main-view.fxml"));
            Parent root = loader.load();

            MainController controller = loader.getController();
            controller.init(stateService, this);

            Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
            applyStylesheets(scene);
            primaryStage.setScene(scene);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void applyStylesheets(Scene scene) {
        var css = getClass().getResource("/com/landrop/ui/styles.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
    }

    @Override
    public void stop() {
        if (stateService != null) {
            stateService.shutdown();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
