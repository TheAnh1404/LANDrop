package com.landrop.ui;

import com.landrop.ui.controllers.MainController;
import com.landrop.ui.controllers.LoginController;
import com.landrop.ui.controllers.RegisterController;
import com.landrop.ui.controllers.SetupController;
import com.landrop.ui.auth.api.AuthApi;
import com.landrop.ui.auth.api.HttpAuthApi;
import com.landrop.ui.auth.api.PreferencesSessionStore;
import com.landrop.ui.auth.api.SessionStore;
import com.landrop.ui.auth.services.AuthStateService;
import com.landrop.ui.services.UIStateService;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Objects;

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
    private AuthStateService authStateService;

    // Parsed configuration
    private String configuredDisplayName = "Peer A";
    private int configuredLocalPort = 5001;
    private String configuredDiscoveryHost = "127.0.0.1";
    private int configuredDiscoveryPort = 8888;
    private String configuredAdvertisedHost = "127.0.0.1";
    private boolean configuredAutoConnect = false;

    @Override
    public void init() {
        this.stateService = new UIStateService();
        initializeAuth(new HttpAuthApi(URI.create(System.getProperty(
                "landrop.auth.base-url", "http://localhost:3000/api"))), new PreferencesSessionStore());
        parseParameters();
    }

    /** Injectable constructor used by headless tests and local composition roots. */
    public MainApp(AuthApi authApi, SessionStore sessionStore) {
        this.stateService = new UIStateService();
        initializeAuth(authApi, sessionStore);
    }

    public MainApp() { }

    private void initializeAuth(AuthApi authApi, SessionStore sessionStore) {
        this.authStateService = new AuthStateService(Objects.requireNonNull(authApi), Objects.requireNonNull(sessionStore));
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

        showAuthLoadingView();
        stage.show();
        stage.toFront();
        stage.requestFocus();
        authStateService.restoreSession().whenComplete((user, failure) -> Platform.runLater(() -> {
            if (authStateService.isAuthenticated()) {
                onAuthenticated();
            } else {
                showLoginView();
            }
        }));
    }

    private void showAuthLoadingView() {
        var root = new javafx.scene.layout.StackPane();
        root.getStyleClass().add("auth-page");
        var label = new javafx.scene.control.Label("Checking your LANDrop session...");
        label.getStyleClass().add("page-subtitle");
        root.getChildren().add(label);
        var scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        applyStylesheets(scene);
        primaryStage.setScene(scene);
    }

    public void showLoginView() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/landrop/ui/auth-login-view.fxml"));
            Parent root = loader.load();
            loader.<LoginController>getController().init(this, authStateService);
            Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
            applyStylesheets(scene);
            primaryStage.setScene(scene);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load the login view", e);
        }
    }

    public void showRegisterView() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/landrop/ui/auth-register-view.fxml"));
            Parent root = loader.load();
            loader.<RegisterController>getController().init(this, authStateService);
            Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
            applyStylesheets(scene);
            primaryStage.setScene(scene);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load the register view", e);
        }
    }

    public AuthStateService getAuthStateService() { return authStateService; }

    /** Called only after restore, login, or register has established a valid session. */
    public void onAuthenticated() {
        if (stateService != null) stateService.shutdown();
        stateService = new UIStateService();
        showSetupView();
    }

    public void logout() {
        if (stateService != null) stateService.shutdown();
        authStateService.logout().whenComplete((ignored, failure) -> Platform.runLater(this::showLoginView));
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
            primaryStage.show();
            primaryStage.toFront();
            primaryStage.requestFocus();
            System.out.println("[LANDrop] Main dashboard transitioned successfully for " + configuredDisplayName);
        } catch (Throwable e) {
            System.err.println("[LANDrop ERROR] Failed to load main view: " + e.getMessage());
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
