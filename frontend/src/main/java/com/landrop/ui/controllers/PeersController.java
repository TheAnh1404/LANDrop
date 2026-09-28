package com.landrop.ui.controllers;

import com.landrop.ui.cloud.model.Domain;
import com.landrop.ui.lan.LanDiscoveryService;
import com.landrop.ui.lan.UIStateLanServices;
import com.landrop.ui.lan.model.DiscoveredDevice;
import com.landrop.ui.services.UIStateService;
import com.landrop.ui.utils.AlertUtils;
import com.landrop.ui.workspace.services.WorkspaceStateService;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.util.ArrayList;
import java.util.List;

/**
 * Enhanced LAN Peer & Member Directory Controller.
 * Supports real-time peer searching, online/offline status classification,
 * filtering, and direct LAN P2P connections.
 */
public class PeersController {

    private enum FilterMode {
        ALL, ONLINE_ONLY, OFFLINE_ONLY
    }

    @FXML private Label peersSubtitleLabel, onlineSectionLabel, offlineSectionLabel, emptyTitleLabel, emptyDescLabel;
    @FXML private Button refreshButton, filterAllButton, filterOnlineButton, filterOfflineButton;
    @FXML private TextField searchField;
    @FXML private VBox onlineSection, offlineSection, onlinePeersContainer, offlineMembersContainer, emptyStateContainer;

    private UIStateService stateService;
    private LanDiscoveryService discovery;
    private WorkspaceStateService workspaceStateService;
    private MainController mainController;

    private FilterMode currentFilter = FilterMode.ALL;

    public void init(UIStateService stateService, MainController mainController) {
        this.stateService = stateService;
        this.mainController = mainController;

        if (mainController != null) {
            this.workspaceStateService = mainController.getWorkspaceStateService();
            this.discovery = mainController.getMainApp() != null && mainController.getMainApp().getLanDiscoveryService() != null
                    ? mainController.getMainApp().getLanDiscoveryService()
                    : new UIStateLanServices(stateService);
        } else {
            this.discovery = new UIStateLanServices(stateService);
        }

        // Listen for discovery updates
        discovery.onlineDeviceCountProperty().addListener((obs, old, val) -> Platform.runLater(this::renderView));
        discovery.discoveredDevices().addListener((ListChangeListener<DiscoveredDevice>) c -> Platform.runLater(this::renderView));
        stateService.p2pStatusProperty().addListener((obs, old, val) -> Platform.runLater(this::renderView));

        if (workspaceStateService != null) {
            workspaceStateService.getActiveMembers().addListener((ListChangeListener<Domain.WorkspaceMember>) c -> Platform.runLater(this::renderView));
            workspaceStateService.activeWorkspaceProperty().addListener((obs, old, val) -> Platform.runLater(this::renderView));
        }

        // Live search filtering
        if (searchField != null) {
            searchField.textProperty().addListener((obs, old, query) -> renderView());
        }

        updateFilterButtonStyles();
        renderView();
    }

    @FXML
    private void onFilterAll() {
        this.currentFilter = FilterMode.ALL;
        updateFilterButtonStyles();
        renderView();
    }

    @FXML
    private void onFilterOnline() {
        this.currentFilter = FilterMode.ONLINE_ONLY;
        updateFilterButtonStyles();
        renderView();
    }

    @FXML
    private void onFilterOffline() {
        this.currentFilter = FilterMode.OFFLINE_ONLY;
        updateFilterButtonStyles();
        renderView();
    }

    private void updateFilterButtonStyles() {
        if (filterAllButton == null || filterOnlineButton == null || filterOfflineButton == null) return;

        setButtonStyle(filterAllButton, currentFilter == FilterMode.ALL);
        setButtonStyle(filterOnlineButton, currentFilter == FilterMode.ONLINE_ONLY);
        setButtonStyle(filterOfflineButton, currentFilter == FilterMode.OFFLINE_ONLY);
    }

    private void setButtonStyle(Button btn, boolean active) {
        if (active) {
            btn.setStyle("-fx-background-color: #6366f1; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 6px;");
        } else {
            btn.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #475569; -fx-background-radius: 6px;");
        }
    }

    private void renderView() {
        if (onlinePeersContainer == null || offlineMembersContainer == null) return;

        String query = searchField != null && searchField.getText() != null ? searchField.getText().trim().toLowerCase() : "";

        // 1. Collect online peers
        List<DiscoveredDevice> onlineDevices = new ArrayList<>(discovery.discoveredDevices());
        List<DiscoveredDevice> filteredOnline = onlineDevices.stream().filter(d -> {
            if (query.isEmpty()) return true;
            return d.deviceName().toLowerCase().contains(query) || d.diagnosticEndpoint().toLowerCase().contains(query);
        }).toList();

        // 2. Collect offline workspace members
        List<Domain.WorkspaceMember> offlineMembers = new ArrayList<>();
        if (workspaceStateService != null && workspaceStateService.getActiveWorkspace() != null) {
            String myUserId = workspaceStateService.currentUserId();
            for (Domain.WorkspaceMember m : workspaceStateService.getActiveMembers()) {
                if (m.userId().equals(myUserId)) continue;

                // Check if this member has a matching online device
                boolean isOnline = onlineDevices.stream().anyMatch(d ->
                        d.deviceName().equalsIgnoreCase(m.nameOrFallback()) ||
                        d.deviceName().equalsIgnoreCase(m.displayName()));

                if (!isOnline) {
                    if (query.isEmpty() ||
                            m.nameOrFallback().toLowerCase().contains(query) ||
                            (m.email() != null && m.email().toLowerCase().contains(query))) {
                        offlineMembers.add(m);
                    }
                }
            }
        }

        // Subtitle summary
        if (peersSubtitleLabel != null) {
            peersSubtitleLabel.setText("🟢 " + onlineDevices.size() + " thiết bị trực tuyến • ⚪ " +
                    offlineMembers.size() + " người dùng ngoại tuyến");
        }

        // Render Online Section
        onlinePeersContainer.getChildren().clear();
        boolean showOnline = currentFilter != FilterMode.OFFLINE_ONLY;
        onlineSection.setVisible(showOnline && !filteredOnline.isEmpty());
        onlineSection.setManaged(showOnline && !filteredOnline.isEmpty());

        if (onlineSectionLabel != null) {
            onlineSectionLabel.setText("THIẾT BỊ ĐANG TRỰC TUYẾN (LAN) (" + filteredOnline.size() + ")");
        }
        for (DiscoveredDevice d : filteredOnline) {
            onlinePeersContainer.getChildren().add(cardOnline(d));
        }

        // Render Offline Section
        offlineMembersContainer.getChildren().clear();
        boolean showOffline = currentFilter != FilterMode.ONLINE_ONLY;
        offlineSection.setVisible(showOffline && !offlineMembers.isEmpty());
        offlineSection.setManaged(showOffline && !offlineMembers.isEmpty());

        if (offlineSectionLabel != null) {
            offlineSectionLabel.setText("NGƯỜI DÙNG CHƯA TRỰC TUYẾN (" + offlineMembers.size() + ")");
        }
        for (Domain.WorkspaceMember m : offlineMembers) {
            offlineMembersContainer.getChildren().add(cardOffline(m));
        }

        // Empty state handling
        boolean hasResults = (showOnline && !filteredOnline.isEmpty()) || (showOffline && !offlineMembers.isEmpty());
        if (emptyStateContainer != null) {
            emptyStateContainer.setVisible(!hasResults);
            emptyStateContainer.setManaged(!hasResults);
            if (!hasResults && emptyTitleLabel != null) {
                emptyTitleLabel.setText(!query.isEmpty()
                        ? "Không tìm thấy kết quả phù hợp cho \"" + query + "\""
                        : "Chưa có thiết bị nào khác trực tuyến trên mạng LAN");
            }
        }
    }

    private HBox cardOnline(DiscoveredDevice device) {
        HBox card = new HBox(14);
        card.getStyleClass().add("peer-card");
        card.setAlignment(Pos.CENTER_LEFT);
        card.setStyle("-fx-background-color: #ffffff; -fx-padding: 14px 18px; -fx-background-radius: 10px; " +
                "-fx-border-color: #a7f3d0; -fx-border-radius: 10px; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.03), 4, 0, 0, 1);");

        Label icon = new Label("💻");
        icon.setStyle("-fx-font-size: 24px;");

        VBox info = new VBox(3);
        HBox titleRow = new HBox(6);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        Label name = new Label(device.deviceName());
        name.getStyleClass().add("peer-card-name");
        name.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");

        Circle dot = new Circle(4.5, Color.web("#10b981"));
        titleRow.getChildren().addAll(dot, name);

        Label endpoint = new Label("Mạng LAN • " + device.diagnosticEndpoint());
        endpoint.getStyleClass().add("peer-card-address");
        endpoint.setStyle("-fx-font-size: 11.5px; -fx-text-fill: #059669; -fx-font-family: monospace;");
        info.getChildren().addAll(titleRow, endpoint);

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);

        Label availability = new Label("🟢 TRỰC TUYẾN");
        availability.getStyleClass().add("status-badge-online");
        availability.setStyle("-fx-background-color: #ecfdf5; -fx-text-fill: #059669; -fx-padding: 4px 10px; -fx-background-radius: 6px; -fx-font-weight: bold; -fx-font-size: 11px;");

        Button connect = new Button(stateService.isP2pConnected() ? "✓ Đã kết nối" : "Kết nối P2P");
        connect.getStyleClass().add("primary-button");
        connect.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 6px 14px;");
        connect.setDisable(stateService.isP2pConnected());

        connect.setOnAction(e -> {
            connect.setDisable(true);
            connect.setText("Đang kết nối…");
            discovery.connect(device).thenRun(() -> Platform.runLater(() -> {
                connect.setText("✓ Đã kết nối");
                if (mainController != null) {
                    mainController.navigateTo(com.landrop.ui.models.NavigationTab.CHAT);
                }
            })).exceptionally(error -> {
                Platform.runLater(() -> {
                    connect.setDisable(false);
                    connect.setText("Kết nối P2P");
                    AlertUtils.showError("Kết nối P2P thất bại", "Không thể kết nối tới " + device.deviceName(), message(error));
                });
                return null;
            });
        });

        Button clipBtn = new Button("📋 Gửi Clipboard");
        clipBtn.getStyleClass().add("secondary-button");
        clipBtn.setStyle("-fx-font-size: 11.5px; -fx-padding: 6px 12px;");
        clipBtn.setOnAction(e -> {
            javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
            if (clipboard.hasString() && !clipboard.getString().isBlank()) {
                String text = clipboard.getString();
                clipBtn.setText("Đang gửi...");
                stateService.sendClipboard(text).thenRun(() -> Platform.runLater(() -> {
                    clipBtn.setText("✓ Đã gửi!");
                    new Thread(() -> {
                        try { Thread.sleep(2000); } catch (Exception ignored) {}
                        Platform.runLater(() -> clipBtn.setText("📋 Gửi Clipboard"));
                    }).start();
                })).exceptionally(err -> {
                    Platform.runLater(() -> {
                        clipBtn.setText("📋 Gửi Clipboard");
                        AlertUtils.showError("Gửi Clipboard", "Không thể gửi clipboard", message(err));
                    });
                    return null;
                });
            } else {
                AlertUtils.showError("Clipboard trống", "Không có nội dung văn bản", "Hãy copy một đoạn text hoặc link trước khi gửi.");
            }
        });

        card.getChildren().addAll(icon, info, gap, availability, clipBtn, connect);
        return card;
    }

    private HBox cardOffline(Domain.WorkspaceMember member) {
        HBox card = new HBox(14);
        card.getStyleClass().add("peer-card");
        card.setAlignment(Pos.CENTER_LEFT);
        card.setStyle("-fx-background-color: #ffffff; -fx-padding: 12px 18px; -fx-background-radius: 10px; " +
                "-fx-border-color: #e2e8f0; -fx-border-radius: 10px; -fx-opacity: 0.85;");

        Label icon = new Label("👤");
        icon.setStyle("-fx-font-size: 24px;");

        VBox info = new VBox(3);
        HBox titleRow = new HBox(6);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        Label name = new Label(member.nameOrFallback());
        name.setStyle("-fx-font-size: 13.5px; -fx-font-weight: bold; -fx-text-fill: #334155;");

        Circle dot = new Circle(4.5, Color.web("#94a3b8"));

        Label role = new Label(member.roleBadge());
        role.setStyle("-fx-font-size: 9.5px; -fx-padding: 1px 5px; -fx-background-radius: 4px; -fx-background-color: #f1f5f9; -fx-text-fill: #64748b;");

        titleRow.getChildren().addAll(dot, name, role);

        Label status = new Label(member.email() != null && !member.email().isBlank()
                ? member.email() + " • Chưa bật thiết bị trên mạng LAN"
                : "Chưa bật thiết bị trên mạng LAN");
        status.setStyle("-fx-font-size: 11px; -fx-text-fill: #94a3b8;");
        info.getChildren().addAll(titleRow, status);

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);

        Label badge = new Label("⚪ NGOẠI TUYẾN");
        badge.setStyle("-fx-background-color: #f8fafc; -fx-text-fill: #94a3b8; -fx-padding: 4px 10px; -fx-background-radius: 6px; -fx-font-weight: bold; -fx-font-size: 11px;");

        card.getChildren().addAll(icon, info, gap, badge);
        return card;
    }

    @FXML
    private void onRefreshClicked() {
        if (refreshButton != null) {
            refreshButton.setDisable(true);
            refreshButton.setText("Đang quét…");
        }
        discovery.refresh().whenComplete((ignored, error) -> Platform.runLater(() -> {
            if (refreshButton != null) {
                refreshButton.setDisable(false);
                refreshButton.setText("⟳ Quét lại mạng");
            }
            renderView();
            if (error != null) {
                AlertUtils.showError("Quét mạng LAN thất bại", "Không thể kết nối máy chủ dò tìm", message(error));
            }
        }));
    }

    private static String message(Throwable error) {
        return error.getCause() != null && error.getCause().getMessage() != null
                ? error.getCause().getMessage()
                : error.getMessage();
    }
}
