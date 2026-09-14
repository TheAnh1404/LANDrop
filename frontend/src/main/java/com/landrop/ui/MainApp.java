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

import com.landrop.ui.auth.model.AuthModels;
import com.landrop.ui.config.FrontendConfig;
import com.landrop.ui.services.RealtimeChatService;
import com.landrop.ui.workspace.services.WorkspaceStateService;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
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
    private volatile RealtimeChatService realtimeChatService;
    private volatile WorkspaceStateService workspaceStateService;
    private volatile com.landrop.ui.workspace.chat.services.WorkspaceChatStateService workspaceChatStateService;
    private volatile com.landrop.ui.workspace.files.services.FileManagerStateService fileManagerStateService;
    private volatile com.landrop.ui.realtime.RealtimeGateway realtimeGateway;
    private volatile com.landrop.ui.lan.UIStateLanServices lanServices;
    private volatile com.landrop.ui.device.CurrentDeviceService currentDeviceService;
    private volatile URI resolvedWsUri;
    private volatile URI resolvedApiUri;

    // Parsed configuration
    private String configuredDisplayName = null;
    private int configuredLocalPort = 5001;
    private String configuredDiscoveryHost = FrontendConfig.DEFAULT_DISCOVERY_HOST;
    private int configuredDiscoveryPort = 8888;
    private String configuredAdvertisedHost = FrontendConfig.DEFAULT_ADVERTISED_HOST;
    private boolean configuredAutoConnect = false;

    @Override
    public void init() {
        this.stateService = new UIStateService();
        Map<String, String> named = (getParameters() != null) ? getParameters().getNamed() : Collections.emptyMap();
        String apiBaseUrl = FrontendConfig.resolveApiBaseUrl(named);
        String wsUrl = FrontendConfig.resolveWsUrl(named, apiBaseUrl);
        String sessionProfile = FrontendConfig.resolveSessionProfile(named);

        this.resolvedApiUri = URI.create(apiBaseUrl);
        this.resolvedWsUri = URI.create(wsUrl);
        initializeAuth(new HttpAuthApi(resolvedApiUri), new PreferencesSessionStore(sessionProfile));
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
    public RealtimeChatService getRealtimeChatService() { return realtimeChatService; }
    public void setRealtimeChatService(RealtimeChatService service) { this.realtimeChatService = service; }
    public com.landrop.ui.workspace.services.WorkspaceStateService getWorkspaceStateService() { return workspaceStateService; }
    public void setWorkspaceStateService(com.landrop.ui.workspace.services.WorkspaceStateService service) { this.workspaceStateService = service; }
    public com.landrop.ui.workspace.chat.services.WorkspaceChatStateService getWorkspaceChatStateService() { return workspaceChatStateService; }
    public com.landrop.ui.workspace.files.services.FileManagerStateService getFileManagerStateService() { return fileManagerStateService; }
    public com.landrop.ui.lan.LanDiscoveryService getLanDiscoveryService() { return lanServices; }
    public com.landrop.ui.lan.LanTransferService getLanTransferService() { return lanServices; }
    public com.landrop.ui.device.CurrentDeviceService getCurrentDeviceService() { return currentDeviceService; }

    /** Called only after restore, login, or register has established a valid session. */
    public void onAuthenticated() {
        if (stateService != null) stateService.shutdown();
        stateService = new UIStateService();
        lanServices = new com.landrop.ui.lan.UIStateLanServices(stateService);

        AuthModels.AuthUser user = authStateService.user();
        if (user != null) {
            stateService.setAuthenticatedIdentity(user.displayName());
            if (configuredDisplayName == null || configuredDisplayName.isBlank() || "Peer A".equalsIgnoreCase(configuredDisplayName)) {
                configuredDisplayName = user.displayName();
            }
        }

        // Phase 17B opens a single authenticated channel socket only after a channel is selected.
        // The old global socket had no workspace/channel authorization context.

        // Initialize WorkspaceStateService (Phase 16B)
        if (resolvedApiUri != null) {
            this.currentDeviceService = new com.landrop.ui.device.CurrentDeviceService(resolvedApiUri, authStateService);
            this.realtimeGateway = new com.landrop.ui.realtime.RealtimeGateway(resolvedWsUri, authStateService);
            com.landrop.ui.workspace.api.HttpWorkspaceApi wsApi =
                    new com.landrop.ui.workspace.api.HttpWorkspaceApi(resolvedApiUri, authStateService);
            this.workspaceStateService = new com.landrop.ui.workspace.services.WorkspaceStateService(wsApi, authStateService);
            this.workspaceChatStateService = new com.landrop.ui.workspace.chat.services.WorkspaceChatStateService(
                    workspaceStateService,
                    new com.landrop.ui.workspace.chat.api.HttpTeamChatApi(resolvedApiUri, authStateService),
                    realtimeGateway);
            this.fileManagerStateService = new com.landrop.ui.workspace.files.services.FileManagerStateService(
                    workspaceStateService,
                    new com.landrop.ui.workspace.files.api.HttpFileManagerApi(resolvedApiUri, authStateService));
            workspaceStateService.loadWorkspaces().exceptionally(ex -> {
                System.out.println("[LANDrop] Workspaces load notice: " + ex.getMessage());
                return java.util.Collections.emptyList();
            });
        }

        showSetupView();
    }

    public void logout() {
        if (realtimeGateway != null) {
            try { realtimeGateway.close(); } catch (Exception ignored) { }
            realtimeGateway = null;
        }
        if (fileManagerStateService != null) {
            try { fileManagerStateService.close(); } catch (Exception ignored) { }
            fileManagerStateService = null;
        }
        if (workspaceChatStateService != null) {
            try { workspaceChatStateService.close(); } catch (Exception ignored) { }
            workspaceChatStateService = null;
        }
        if (realtimeChatService != null) {
            try {
                realtimeChatService.close();
            } catch (Exception ignored) {
            }
            realtimeChatService = null;
        }
        this.workspaceStateService = null;
        this.lanServices = null;
        this.currentDeviceService = null;
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
        if (realtimeGateway != null) {
            try { realtimeGateway.close(); } catch (Exception ignored) { }
        }
        if (fileManagerStateService != null) {
            try { fileManagerStateService.close(); } catch (Exception ignored) { }
        }
        if (workspaceChatStateService != null) {
            try { workspaceChatStateService.close(); } catch (Exception ignored) { }
        }
        if (realtimeChatService != null) {
            try {
                realtimeChatService.close();
            } catch (Exception ignored) {
            }
        }
        if (stateService != null) {
            stateService.shutdown();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
