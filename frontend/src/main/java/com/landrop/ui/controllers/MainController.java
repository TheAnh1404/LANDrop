package com.landrop.ui.controllers;

import com.landrop.ui.MainApp;
import com.landrop.ui.models.DiscoveryStatus;
import com.landrop.ui.models.NavigationTab;
import com.landrop.ui.models.P2PStatus;
import com.landrop.ui.services.UIStateService;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

/**
 * Main application frame controller coordinating navigation, top bar status, and status bar metrics.
 */
public class MainController {

    @FXML
    private Circle topDiscoveryDot;

    @FXML
    private Label topDiscoveryStatusLabel;

    @FXML
    private Label topWorkspaceBadge;

    @FXML
    private Label topPeerIdentityBadge;

    @FXML
    private Button logoutButton;

    @FXML
    private Button dashboardNavBtn;

    @FXML
    private Button workspacesNavBtn;

    @FXML
    private Button peersNavBtn;

    @FXML
    private Button chatNavBtn;

    @FXML
    private Button transfersNavBtn;

    @FXML
    private Button settingsNavBtn;

    @FXML
    private StackPane contentArea;

    @FXML
    private Label bottomPeerStatusLabel;

    @FXML
    private Label bottomDiscoveryStatusLabel;

    @FXML
    private Label bottomP2pStatusLabel;

    @FXML
    private Label bottomProtocolLabel;

    private UIStateService stateService;
    private MainApp mainApp;

    private final Map<NavigationTab, Node> viewCache = new EnumMap<>(NavigationTab.class);
    private final Map<NavigationTab, Button> navButtonMap = new EnumMap<>(NavigationTab.class);

    private DashboardController dashboardController;
    private WorkspaceController workspaceController;
    private PeersController peersController;
    private ChatController chatController;
    private TransfersController transfersController;
    private SettingsController settingsController;

    public void init(UIStateService stateService, MainApp mainApp) throws IOException {
        this.stateService = stateService;
        this.mainApp = mainApp;

        // Map navigation buttons
        navButtonMap.put(NavigationTab.DASHBOARD, dashboardNavBtn);
        navButtonMap.put(NavigationTab.WORKSPACES, workspacesNavBtn);
        navButtonMap.put(NavigationTab.PEERS, peersNavBtn);
        navButtonMap.put(NavigationTab.CHAT, chatNavBtn);
        navButtonMap.put(NavigationTab.TRANSFERS, transfersNavBtn);
        navButtonMap.put(NavigationTab.SETTINGS, settingsNavBtn);

        // Load and initialize sub-views
        loadSubViews();

        // Bind top bar & status bar
        bindStatusProperties();

        // Handle incoming direct connections: auto-switch to chat view
        stateService.setOnIncomingConnectionCallback(remoteName -> {
            Platform.runLater(() -> navigateTo(NavigationTab.CHAT));
        });

        // Initial view
        navigateTo(NavigationTab.DASHBOARD);
    }

    private void loadSubViews() throws IOException {
        // 1. Dashboard View
        FXMLLoader dashLoader = new FXMLLoader(getClass().getResource("/com/landrop/ui/dashboard-view.fxml"));
        Node dashNode = dashLoader.load();
        this.dashboardController = dashLoader.getController();
        dashboardController.init(stateService, this);
        viewCache.put(NavigationTab.DASHBOARD, dashNode);

        // 2. Workspaces View (Phase 16B)
        FXMLLoader wsLoader = new FXMLLoader(getClass().getResource("/com/landrop/ui/workspace-view.fxml"));
        Node wsNode = wsLoader.load();
        this.workspaceController = wsLoader.getController();
        if (mainApp != null && mainApp.getWorkspaceStateService() != null) {
            workspaceController.init(mainApp.getWorkspaceStateService(), this);
        }
        viewCache.put(NavigationTab.WORKSPACES, wsNode);

        // 3. Peers View
        FXMLLoader peersLoader = new FXMLLoader(getClass().getResource("/com/landrop/ui/peers-view.fxml"));
        Node peersNode = peersLoader.load();
        this.peersController = peersLoader.getController();
        peersController.init(stateService, this);
        viewCache.put(NavigationTab.PEERS, peersNode);

        // 4. Chat View
        FXMLLoader chatLoader = new FXMLLoader(getClass().getResource("/com/landrop/ui/chat-view.fxml"));
        Node chatNode = chatLoader.load();
        this.chatController = chatLoader.getController();
        chatController.init(stateService, this);
        viewCache.put(NavigationTab.CHAT, chatNode);

        // 5. Transfers View
        FXMLLoader transfersLoader = new FXMLLoader(getClass().getResource("/com/landrop/ui/transfers-view.fxml"));
        Node transfersNode = transfersLoader.load();
        this.transfersController = transfersLoader.getController();
        transfersController.init(stateService, this);
        viewCache.put(NavigationTab.TRANSFERS, transfersNode);

        // 6. Settings View
        FXMLLoader settingsLoader = new FXMLLoader(getClass().getResource("/com/landrop/ui/settings-view.fxml"));
        Node settingsNode = settingsLoader.load();
        this.settingsController = settingsLoader.getController();
        settingsController.init(stateService, this);
        viewCache.put(NavigationTab.SETTINGS, settingsNode);
    }

    public MainApp getMainApp() {
        return mainApp;
    }

    public com.landrop.ui.auth.services.AuthStateService getAuthStateService() {
        return mainApp != null ? mainApp.getAuthStateService() : null;
    }

    public com.landrop.ui.services.RealtimeChatService getRealtimeChatService() {
        return mainApp != null ? mainApp.getRealtimeChatService() : null;
    }

    public com.landrop.ui.workspace.services.WorkspaceStateService getWorkspaceStateService() {
        return mainApp != null ? mainApp.getWorkspaceStateService() : null;
    }

    public WorkspaceController getWorkspaceController() {
        return workspaceController;
    }

    private void bindStatusProperties() {
        // User identity from auth state
        updateUserIdentityDisplay();
        if (getAuthStateService() != null) {
            getAuthStateService().userProperty().addListener((obs, oldVal, newVal) -> {
                Platform.runLater(this::updateUserIdentityDisplay);
            });
        }

        // Discovery status bindings
        stateService.discoveryStatusProperty().addListener((obs, oldVal, newVal) -> {
            Platform.runLater(() -> updateDiscoveryDisplay(newVal));
        });
        updateDiscoveryDisplay(stateService.discoveryStatusProperty().get());

        // P2P status bindings
        stateService.p2pStatusProperty().addListener((obs, oldVal, newVal) -> {
            Platform.runLater(() -> updateP2pDisplay(newVal));
        });
        stateService.activeRemotePeerNameProperty().addListener((obs, oldVal, newVal) -> {
            Platform.runLater(() -> updateP2pDisplay(stateService.p2pStatusProperty().get()));
        });
        updateP2pDisplay(stateService.p2pStatusProperty().get());

        // Protocol version
        bottomProtocolLabel.setText("Protocol: v" + stateService.getProtocolVersion());

        // Workspace bindings
        if (getWorkspaceStateService() != null) {
            updateWorkspaceDisplay(getWorkspaceStateService().getActiveWorkspace());
            getWorkspaceStateService().activeWorkspaceProperty().addListener((obs, oldVal, newVal) -> {
                Platform.runLater(() -> updateWorkspaceDisplay(newVal));
            });
        }
    }

    private void updateWorkspaceDisplay(com.landrop.ui.cloud.model.Domain.Workspace ws) {
        if (topWorkspaceBadge != null) {
            if (ws != null) {
                topWorkspaceBadge.setText("🏢 " + ws.name());
            } else {
                topWorkspaceBadge.setText("🏢 No Workspace");
            }
        }
    }

    private void updateUserIdentityDisplay() {
        var auth = getAuthStateService();
        String userDisplayName = (auth != null && auth.user() != null) ? auth.user().displayName() : null;
        if (userDisplayName == null || userDisplayName.isBlank()) {
            userDisplayName = (auth != null && auth.user() != null) ? auth.user().username() : null;
        }
        if (userDisplayName == null || userDisplayName.isBlank()) {
            userDisplayName = stateService.getIdentity() != null ? stateService.getIdentity().getDisplayName() : "User";
        }

        topPeerIdentityBadge.setText(userDisplayName);

        String deviceName = stateService.getIdentity() != null ? stateService.getIdentity().getDisplayName() : "LAN Device";
        bottomPeerStatusLabel.setText("User: " + userDisplayName + " | LAN Peer: " + deviceName + " (" + stateService.getAdvertisedHost() + ":" + stateService.getLocalP2pPort() + ")");
    }

    private void updateDiscoveryDisplay(DiscoveryStatus status) {
        if (status == null) {
            status = DiscoveryStatus.DISCONNECTED;
        }
        topDiscoveryDot.setFill(Color.web(status.getColorHex()));
        topDiscoveryStatusLabel.setText(status.getLabel());
        bottomDiscoveryStatusLabel.setText("Discovery: " + status.name() + " (" + stateService.getDiscoveryHost() + ":" + stateService.getDiscoveryPort() + ")");
    }

    private void updateP2pDisplay(P2PStatus status) {
        if (status == null) {
            status = P2PStatus.DISCONNECTED;
        }
        String remotePeer = stateService.activeRemotePeerNameProperty().get();
        switch (status) {
            case CONNECTED -> bottomP2pStatusLabel.setText("P2P: Connected to " + (remotePeer != null ? remotePeer : "Peer"));
            case CONNECTING -> bottomP2pStatusLabel.setText("P2P: Connecting...");
            case ERROR -> bottomP2pStatusLabel.setText("P2P: Connection Failed");
            case DISCONNECTED -> bottomP2pStatusLabel.setText("P2P: Disconnected");
        }
    }

    public void navigateTo(NavigationTab tab) {
        Node targetView = viewCache.get(tab);
        if (targetView != null) {
            contentArea.getChildren().setAll(targetView);

            // Update sidebar button active state
            for (Map.Entry<NavigationTab, Button> entry : navButtonMap.entrySet()) {
                if (entry.getKey() == tab) {
                    if (!entry.getValue().getStyleClass().contains("active")) {
                        entry.getValue().getStyleClass().add("active");
                    }
                } else {
                    entry.getValue().getStyleClass().remove("active");
                }
            }
        }
    }

    @FXML
    private void onDashboardNavClicked() {
        navigateTo(NavigationTab.DASHBOARD);
    }

    @FXML
    private void onWorkspacesNavClicked() {
        navigateTo(NavigationTab.WORKSPACES);
    }

    @FXML
    private void onWorkspaceBadgeClicked() {
        navigateTo(NavigationTab.WORKSPACES);
    }

    @FXML
    private void onPeersNavClicked() {
        navigateTo(NavigationTab.PEERS);
    }

    @FXML
    private void onChatNavClicked() {
        navigateTo(NavigationTab.CHAT);
    }

    @FXML
    private void onTransfersNavClicked() {
        navigateTo(NavigationTab.TRANSFERS);
    }

    @FXML
    private void onSettingsNavClicked() {
        navigateTo(NavigationTab.SETTINGS);
    }

    @FXML
    private void onLogoutClicked() {
        if (mainApp != null) {
            logoutButton.setDisable(true);
            logoutButton.setText("Signing out...");
            mainApp.logout();
        }
    }
}
