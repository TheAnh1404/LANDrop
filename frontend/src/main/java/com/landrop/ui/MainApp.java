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
import com.landrop.ui.config.ServerConnectionPreferences;
import com.landrop.ui.services.RealtimeChatService;
import com.landrop.ui.workspace.services.WorkspaceStateService;
import com.landrop.ui.bootstrap.ClientInstanceService;
import com.landrop.ui.bootstrap.LocalClientProfileCoordinator;
import com.landrop.ui.connection.ServerHealthClient;
import com.landrop.ui.realtime.ClientRealtimeService;
import com.landrop.ui.realtime.TransferControlService;
import com.landrop.ui.connection.ServerDiscoveryService;

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
    private volatile ClientRealtimeService serverRealtimeService;
    private volatile TransferControlService transferControlService;
    private volatile ClientInstanceService clientInstanceService;
    private LocalClientProfileCoordinator localProfileCoordinator;
    private volatile com.landrop.ui.lan.UIStateLanServices lanServices;
    private volatile com.landrop.ui.device.CurrentDeviceService currentDeviceService;
    private volatile URI resolvedWsUri;
    private volatile URI resolvedApiUri;
    private ServerConnectionPreferences serverConnectionPreferences;
    private com.landrop.ui.bootstrap.ClientBootstrapService bootstrap;
    private String sessionProfile;
    private boolean featuresMounted;
    private Scene readyScene;
    private String configurationError;
    private boolean showServerConfiguration;

    private final ServerDiscoveryService serverDiscoveryService = new ServerDiscoveryService();
    private javafx.scene.control.Label discoveryStatusLabel;

    public com.landrop.ui.bootstrap.ClientBootstrapService getBootstrap() { return bootstrap; }

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
        this.serverConnectionPreferences = new ServerConnectionPreferences();
        if (!FrontendConfig.hasExplicitApiConfiguration(named) && !serverConnectionPreferences.apiUrl().isBlank()) {
            apiBaseUrl = serverConnectionPreferences.apiUrl();
        }
        if (!FrontendConfig.hasExplicitWsConfiguration(named) && !serverConnectionPreferences.wsUrl().isBlank()) {
            wsUrl = serverConnectionPreferences.wsUrl();
        } else if (!FrontendConfig.hasExplicitWsConfiguration(named)) {
            wsUrl = FrontendConfig.resolveWsUrl(named, apiBaseUrl);
        }
        localProfileCoordinator = LocalClientProfileCoordinator.acquire(FrontendConfig.resolveSessionProfile(named));
        sessionProfile = localProfileCoordinator.profile();

        try {
            this.resolvedApiUri = URI.create(FrontendConfig.validateApiBaseUrl(apiBaseUrl));
            this.resolvedWsUri = URI.create(FrontendConfig.validateWsUrl(wsUrl));
            validateEndpointPair(resolvedApiUri, resolvedWsUri);
        } catch (IllegalArgumentException invalid) {
            this.resolvedApiUri = URI.create(FrontendConfig.DEFAULT_API_BASE_URL);
            this.resolvedWsUri = URI.create(FrontendConfig.DEFAULT_WS_URL);
            configurationError = "INVALID_SERVER_CONFIG";
        }
        this.clientInstanceService = new ClientInstanceService(sessionProfile);
        System.out.println("[LANDrop Client] CLIENT_STARTED (profile=" + sessionProfile + ")");
        new PreferencesSessionStore(sessionProfile).clear();
        initializeAuth(new HttpAuthApi(resolvedApiUri), new com.landrop.ui.auth.api.ServerSessionStore(resolvedApiUri, sessionProfile));
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
        this.showServerConfiguration = false;

        stage.setTitle("LANDrop");
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);
        stage.setWidth(DEFAULT_WIDTH);
        stage.setHeight(DEFAULT_HEIGHT);

        stage.show();
        stage.toFront();
        stage.requestFocus();

        initializeBootstrap();
        startServerDiscovery();
    }

    private void initializeBootstrap() {
        currentDeviceService = new com.landrop.ui.device.CurrentDeviceService(resolvedApiUri, authStateService, clientInstanceService);
        var devices = currentDeviceService;
        var health = new ServerHealthClient(resolvedApiUri);
        bootstrap = new com.landrop.ui.bootstrap.ClientBootstrapService(authStateService, health::check,
                new com.landrop.ui.bootstrap.ClientBootstrapService.DeviceEnrollment() {
                    public java.util.concurrent.CompletableFuture<com.landrop.ui.cloud.model.Domain.Device> enroll() { return devices.enroll(); }
                    public void clear() { devices.clear(); }
                }, new com.landrop.ui.bootstrap.ClientBootstrapService.RealtimeBinding() {
                    public void bind(AuthModels.AuthUser user, com.landrop.ui.cloud.model.Domain.Device device) {
                        if (serverRealtimeService != null) serverRealtimeService.dispose();
                        var owner = bootstrap;
                        var server = new ClientRealtimeService(resolvedWsUri, health, clientInstanceService,
                                () -> authStateService.accessToken().orElse(null), () -> devices.currentDevice() == null ? clientInstanceService.deviceName() : devices.currentDevice().label());
                        server.requireAuthenticatedBinding(user.id(), device.id(), authStateService::validAccessToken);
                        serverRealtimeService = server;
                        server.stateProperty().addListener((obs, old, next) -> {
                            String reason = server.disconnectReasonProperty().get();
                            Platform.runLater(() -> { if (bootstrap == owner && serverRealtimeService == server) owner.connectionChanged(next, reason); });
                        });
                        server.start();
                    }
                    public void close() {
                        var server = serverRealtimeService;
                        serverRealtimeService = null;
                        if (server != null) server.dispose();
                    }
                });
        var owner = bootstrap;
        var ownerAuth = authStateService;
        authStateService.isAuthenticatedProperty().addListener((obs, old, authenticated) -> {
            if (!authenticated) Platform.runLater(() -> {
                if (bootstrap == owner && !ownerAuth.isAuthenticated() && owner.state() != com.landrop.ui.bootstrap.BootstrapState.AUTH_REQUIRED
                        && owner.state() != com.landrop.ui.bootstrap.BootstrapState.SESSION_EXPIRED) {
                    owner.expire(ownerAuth.invalidationCode());
                }
            });
        });
        bootstrap.stateProperty().addListener((obs, old, next) -> Platform.runLater(() -> {
            if (bootstrap != owner || owner.state() != next) return;
            switch (next) {
                case READY -> { if (!featuresMounted) onAuthenticated(); else if (readyScene != null) primaryStage.setScene(readyScene); }
                case AUTH_REQUIRED -> { cleanupFeatures(); showLoginView(); }
                case AUTHENTICATING -> { }
                case SERVER_UNREACHABLE -> startServerDiscovery();
                default -> showAuthLoadingView();
            }
        }));
    }

    public void startServerDiscovery() {
        if (bootstrap != null && bootstrap.isReady()) {
            return;
        }
        showServerDiscoveryView();
        serverDiscoveryService.setPreferredUrl(resolvedApiUri != null ? resolvedApiUri.toString() : null);
        serverDiscoveryService.startContinuousDiscovery(this::onServerDiscovered, this::updateDiscoveryStatus);
    }

    private void onServerDiscovered(ServerDiscoveryService.DiscoveredServer server) {
        System.out.println("[LANDrop Auto-Discovery] Found server at " + server.displayAddress() + " (v" + server.serverVersion() + ")");
        showServerConfiguration = false;
        if (discoveryStatusLabel != null) {
            discoveryStatusLabel.setText("Đã tìm thấy máy chủ tại " + server.displayAddress() + "! Đang kết nối...");
            discoveryStatusLabel.setStyle("-fx-text-fill: #10b981; -fx-font-weight: bold; -fx-font-size: 13px;");
        }
        configureServerConnection(server.apiUrl(), server.wsUrl());
    }

    private void updateDiscoveryStatus(String status) {
        if (discoveryStatusLabel != null) {
            discoveryStatusLabel.setText(status);
        }
    }

    public void showServerConnectionView() {
        startServerDiscovery();
    }

    public void showServerDiscoveryView() {
        if (primaryStage == null) return;
        var root = new javafx.scene.layout.VBox(20);
        root.setAlignment(javafx.geometry.Pos.CENTER);
        root.getStyleClass().add("auth-page");

        var card = new javafx.scene.layout.VBox(16);
        card.setAlignment(javafx.geometry.Pos.CENTER);
        card.setMaxWidth(480);
        card.getStyleClass().add("card");
        card.setStyle("-fx-padding: 32px; -fx-background-radius: 12px;");

        // Header logo
        var logoBox = new javafx.scene.layout.HBox(8);
        logoBox.setAlignment(javafx.geometry.Pos.CENTER);
        var title = new javafx.scene.control.Label("LANDrop");
        title.getStyleClass().add("app-title");
        title.setStyle("-fx-font-size: 28px;");
        var badge = new javafx.scene.control.Label("v1.0");
        badge.getStyleClass().add("app-version-badge");
        logoBox.getChildren().addAll(title, badge);

        var subtitle = new javafx.scene.control.Label("Hệ thống chia sẻ tệp & liên lạc mạng nội bộ");
        subtitle.getStyleClass().add("page-subtitle");
        subtitle.setStyle("-fx-font-size: 13px; -fx-text-fill: #64748b;");

        // Visual Spinner
        var spinner = new javafx.scene.control.ProgressIndicator();
        spinner.setPrefSize(48, 48);
        spinner.setMaxSize(48, 48);

        var heading = new javafx.scene.control.Label("Đang tự động dò tìm máy chủ LANDrop...");
        heading.getStyleClass().add("card-title");
        heading.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        discoveryStatusLabel = new javafx.scene.control.Label("Đang quét máy chủ trên localhost:3000 và mạng LAN...");
        discoveryStatusLabel.setWrapText(true);
        discoveryStatusLabel.setStyle("-fx-text-fill: #475569; -fx-font-size: 13px; -fx-text-alignment: center;");
        discoveryStatusLabel.setMaxWidth(400);

        // Information banner
        var infoBox = new javafx.scene.layout.VBox(4);
        infoBox.setStyle("-fx-background-color: #f1f5f9; -fx-padding: 12px; -fx-background-radius: 8px; -fx-border-color: #e2e8f0; -fx-border-radius: 8px;");
        var infoHeader = new javafx.scene.control.Label("⚡ Tự động nhận diện máy chủ");
        infoHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: #1e293b;");
        var infoText = new javafx.scene.control.Label("Khi máy chủ LANDrop được bật, ứng dụng sẽ tự động phát hiện và mở giao diện. Bạn không cần nhập thủ công cổng (port) hay địa chỉ IP.");
        infoText.setWrapText(true);
        infoText.setStyle("-fx-font-size: 12px; -fx-text-fill: #64748b;");
        infoBox.getChildren().addAll(infoHeader, infoText);

        var scanNowBtn = new javafx.scene.control.Button("🔍 Quét lại ngay");
        scanNowBtn.getStyleClass().add("primary-button");
        scanNowBtn.setMaxWidth(Double.MAX_VALUE);
        scanNowBtn.setOnAction(e -> {
            if (discoveryStatusLabel != null) discoveryStatusLabel.setText("Đang quét lại máy chủ...");
            serverDiscoveryService.scanOnce(this::updateDiscoveryStatus).thenAccept(opt -> {
                if (opt.isPresent()) {
                    Platform.runLater(() -> onServerDiscovered(opt.get()));
                }
            });
        });

        card.getChildren().addAll(logoBox, subtitle, spinner, heading, discoveryStatusLabel, infoBox, scanNowBtn);
        root.getChildren().add(card);

        var scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        applyStylesheets(scene);
        primaryStage.setScene(scene);
    }

    private void showAuthLoadingView() {
        if (primaryStage == null) return;
        var bootstrapState = bootstrap == null ? com.landrop.ui.bootstrap.BootstrapState.INITIALIZING : bootstrap.state();
        if (bootstrapState == com.landrop.ui.bootstrap.BootstrapState.SERVER_UNREACHABLE
                || bootstrapState == com.landrop.ui.bootstrap.BootstrapState.ERROR) {
            startServerDiscovery();
            return;
        }
        var root = new javafx.scene.layout.VBox(16);
        root.setAlignment(javafx.geometry.Pos.CENTER);
        root.getStyleClass().add("auth-page");
        String code = bootstrap == null ? "" : bootstrap.errorCode();
        var spinner = new javafx.scene.control.ProgressIndicator();
        spinner.setPrefSize(42, 42);
        spinner.setMaxSize(42, 42);
        var title = new javafx.scene.control.Label("Đang kết nối tới LANDrop Server");
        title.getStyleClass().add("card-title");
        var label = new javafx.scene.control.Label(code.isBlank()
                ? "Đang xác thực và khôi phục phiên làm việc..."
                : com.landrop.ui.bootstrap.BootstrapErrors.message(code));
        label.setWrapText(true);
        root.getChildren().addAll(spinner, title, label);
        var scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        applyStylesheets(scene);
        primaryStage.setScene(scene);
    }

    public void showLoginView() {
        showServerConfiguration = false;
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
    public ClientRealtimeService getServerRealtimeService() { return serverRealtimeService; }
    public TransferControlService getTransferControlService() { return transferControlService; }
    public URI getResolvedApiUri() { return resolvedApiUri; }
    public URI getResolvedWsUri() { return resolvedWsUri; }

    /** A server switch replaces all server-bound auth, device and transport state. */
    public synchronized void configureServerConnection(String apiUrl, String wsUrl) {
        showServerConfiguration = false;
        String api = FrontendConfig.validateApiBaseUrl(apiUrl);
        String ws = wsUrl == null || wsUrl.isBlank() ? FrontendConfig.deriveWsUrl(api) : FrontendConfig.validateWsUrl(wsUrl);
        URI nextApi = URI.create(api), nextWs = URI.create(ws);
        validateEndpointPair(nextApi, nextWs);
        configurationError = null;
        if (nextApi.equals(resolvedApiUri) && nextWs.equals(resolvedWsUri) && bootstrap != null) {
            bootstrap.close();
            cleanupFeatures();
            initializeBootstrap();
            showAuthLoadingView();
            bootstrap.start();
            return;
        }
        if (bootstrap != null) bootstrap.close();
        cleanupFeatures();
        if (authStateService != null) authStateService.invalidate("SESSION_EXPIRED");
        resolvedApiUri = nextApi;
        resolvedWsUri = nextWs;
        if (serverConnectionPreferences == null) serverConnectionPreferences = new ServerConnectionPreferences();
        serverConnectionPreferences.save(api, ws);
        var store = new com.landrop.ui.auth.api.ServerSessionStore(resolvedApiUri, sessionProfile);
        store.clear();
        initializeAuth(new HttpAuthApi(resolvedApiUri), store);
        if (clientInstanceService == null) clientInstanceService = new ClientInstanceService(sessionProfile);
        initializeBootstrap();
        showAuthLoadingView();
        bootstrap.start();
    }

    private static void validateEndpointPair(URI api, URI ws) {
        if (!api.getHost().equalsIgnoreCase(ws.getHost())
                || ("https".equals(api.getScheme()) && !"wss".equals(ws.getScheme()))) {
            throw new IllegalArgumentException("API and WebSocket must use the same server host and transport security.");
        }
    }

    /** Called only after restore, login, or register has established a valid session. */
    public void onAuthenticated() {
        if (bootstrap == null || !bootstrap.isReady() || featuresMounted) return;
        featuresMounted = true;
        if (stateService != null) stateService.shutdown();
        stateService = new UIStateService();
        lanServices = new com.landrop.ui.lan.UIStateLanServices(stateService);

        AuthModels.AuthUser user = authStateService.user();
        if (user != null) {
            stateService.setAuthenticatedIdentity(user.displayName());
            if (configuredDisplayName == null || configuredDisplayName.isBlank() || "Peer A".equalsIgnoreCase(configuredDisplayName)) {
                configuredDisplayName = clientInstanceService.deviceName();
            }
        }

        if (resolvedApiUri != null) {
            this.realtimeGateway = new com.landrop.ui.realtime.RealtimeGateway(serverRealtimeService);
            this.transferControlService = new TransferControlService(serverRealtimeService, event -> {
            });

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

        startLocalClientRuntime();
    }

    private void startLocalClientRuntime() {
        if (stateService == null) {
            showMainView();
            return;
        }
        String displayName = configuredDisplayName;
        if (displayName == null || displayName.isBlank() || "Peer A".equalsIgnoreCase(displayName)) {
            displayName = clientInstanceService == null ? "LANDrop Client" : clientInstanceService.deviceName();
        }
        String finalDisplayName = displayName;
        stateService.connectAndRegister(finalDisplayName, configuredLocalPort,
                configuredDiscoveryHost, configuredDiscoveryPort, configuredAdvertisedHost)
                .whenComplete((ignored, error) -> Platform.runLater(() -> {
                    if (error == null) {
                        configuredLocalPort = stateService.getLocalP2pPort();
                    }
                    showMainView();
                }));
    }

    public void logout() {
        if (bootstrap != null) bootstrap.logout();
        cleanupFeatures();
        showLoginView();
    }

    private void cleanupFeatures() {
        featuresMounted = false;
        readyScene = null;
        if (transferControlService != null) {
            try { transferControlService.close(); } catch (Exception ignored) { }
            transferControlService = null;
        }
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

        if (stateService != null) stateService.shutdown();
    }

    /**
     * Displays the initial peer setup and network connection view.
     */
    public void showSetupView() {
        if (bootstrap == null || !bootstrap.isReady()) return;
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
            readyScene = scene;
            primaryStage.setScene(scene);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Transitions from setup to the 3-section Main Dashboard interface.
     */
    public void showMainView() {
        if (bootstrap == null || !bootstrap.isReady()) return;
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/landrop/ui/main-view.fxml"));
            Parent root = loader.load();

            MainController controller = loader.getController();
            controller.init(stateService, this);

            Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
            applyStylesheets(scene);
            readyScene = scene;
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
        serverDiscoveryService.close();
        if (localProfileCoordinator != null) localProfileCoordinator.close();
        if (bootstrap != null) bootstrap.close();
        if (realtimeGateway != null) {
            try { realtimeGateway.close(); } catch (Exception ignored) { }
        }
        if (serverRealtimeService != null) {
            try { serverRealtimeService.close(); } catch (Exception ignored) { }
        }
        if (transferControlService != null) {
            try { transferControlService.close(); } catch (Exception ignored) { }
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
            realtimeChatService = null;
        }
        if (stateService != null) {
            stateService.shutdown();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
