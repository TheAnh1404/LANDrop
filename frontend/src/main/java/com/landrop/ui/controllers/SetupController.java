package com.landrop.ui.controllers;

import com.landrop.ui.MainApp;
import com.landrop.ui.services.UIStateService;
import com.landrop.ui.utils.AlertUtils;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

/**
 * Controller for the initial connection and peer setup screen.
 * Network parameters (ports, hosts) are automatically configured.
 */
public class SetupController {

    @FXML
    private TextField displayNameField;

    @FXML
    private TextField localPortField;

    @FXML
    private TextField advertisedHostField;

    @FXML
    private TextField discoveryHostField;

    @FXML
    private TextField discoveryPortField;

    @FXML
    private Label statusLabel;

    @FXML
    private Button connectButton;

    private MainApp mainApp;
    private UIStateService stateService;

    private int configuredLocalPort = 5001;
    private String configuredDiscoveryHost = "127.0.0.1";
    private int configuredDiscoveryPort = 8888;
    private String configuredAdvertisedHost = "127.0.0.1";

    public void init(
            MainApp mainApp,
            UIStateService stateService,
            String initialDisplayName,
            int initialLocalPort,
            String initialDiscoveryHost,
            int initialDiscoveryPort,
            String initialAdvertisedHost,
            boolean autoConnect) {

        this.mainApp = mainApp;
        this.stateService = stateService;

        this.configuredLocalPort = initialLocalPort > 0 ? initialLocalPort : 5001;
        this.configuredDiscoveryHost = initialDiscoveryHost != null && !initialDiscoveryHost.isBlank() ? initialDiscoveryHost : "127.0.0.1";
        this.configuredDiscoveryPort = initialDiscoveryPort > 0 ? initialDiscoveryPort : 8888;
        this.configuredAdvertisedHost = initialAdvertisedHost != null && !initialAdvertisedHost.isBlank() ? initialAdvertisedHost : "127.0.0.1";

        String fallbackName = "User";
        if (mainApp != null && mainApp.getAuthStateService() != null && mainApp.getAuthStateService().user() != null) {
            String name = mainApp.getAuthStateService().user().displayName();
            if (name != null && !name.isBlank()) {
                fallbackName = name.trim();
            }
        }
        if (displayNameField != null) {
            displayNameField.setText(initialDisplayName != null && !initialDisplayName.isBlank() ? initialDisplayName : fallbackName);
        }
        if (localPortField != null) {
            localPortField.setText(String.valueOf(this.configuredLocalPort));
        }
        if (advertisedHostField != null) {
            advertisedHostField.setText(this.configuredAdvertisedHost);
        }
        if (discoveryHostField != null) {
            discoveryHostField.setText(this.configuredDiscoveryHost);
        }
        if (discoveryPortField != null) {
            discoveryPortField.setText(String.valueOf(this.configuredDiscoveryPort));
        }

        if (autoConnect) {
            Platform.runLater(this::onConnectClicked);
        }
    }

    @FXML
    private void onConnectClicked() {
        String displayName = (displayNameField != null && displayNameField.getText() != null)
                ? displayNameField.getText().trim() : "";
        if (displayName.isEmpty()) {
            if (statusLabel != null) statusLabel.setText("Vui lòng nhập tên hiển thị.");
            return;
        }

        int localPort = this.configuredLocalPort;
        if (localPortField != null && localPortField.getText() != null && !localPortField.getText().isBlank()) {
            try {
                localPort = Integer.parseInt(localPortField.getText().trim());
            } catch (NumberFormatException ignored) {}
        }

        String discHost = this.configuredDiscoveryHost;
        if (discoveryHostField != null && discoveryHostField.getText() != null && !discoveryHostField.getText().isBlank()) {
            discHost = discoveryHostField.getText().trim();
        }

        int discPort = this.configuredDiscoveryPort;
        if (discoveryPortField != null && discoveryPortField.getText() != null && !discoveryPortField.getText().isBlank()) {
            try {
                discPort = Integer.parseInt(discoveryPortField.getText().trim());
            } catch (NumberFormatException ignored) {}
        }

        String advHost = this.configuredAdvertisedHost;
        if (advertisedHostField != null && advertisedHostField.getText() != null && !advertisedHostField.getText().isBlank()) {
            advHost = advertisedHostField.getText().trim();
        }

        if (connectButton != null) {
            connectButton.setDisable(true);
            connectButton.setText("Đang kết nối...");
        }
        if (statusLabel != null) {
            statusLabel.setText("Đang khởi tạo kết nối mạng nội bộ...");
            statusLabel.setStyle("-fx-text-fill: #4f46e5;");
        }

        final int finalLocalPort = localPort;
        final String finalDiscHost = discHost;
        final int finalDiscPort = discPort;
        stateService.connectAndRegister(displayName, localPort, discHost, discPort, advHost)
                .thenRun(() -> Platform.runLater(() -> {
                    if (statusLabel != null) statusLabel.setText("");
                    mainApp.showMainView();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        if (connectButton != null) {
                            connectButton.setDisable(false);
                            connectButton.setText("Tham gia mạng & Bắt đầu");
                        }
                        String msg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                        if (statusLabel != null) {
                            statusLabel.setText(msg != null ? msg : "Lỗi kết nối");
                            statusLabel.setStyle("-fx-text-fill: #ef4444;");
                        }

                        if (msg != null && msg.contains("Local P2P Port")) {
                            AlertUtils.showError(
                                    "Xung đột cổng",
                                    "Cổng " + finalLocalPort + " đang được sử dụng bởi ứng dụng khác.",
                                    msg
                            );
                        } else {
                            AlertUtils.showError(
                                    "Kết nối mạng nội bộ",
                                    "Không thể kết nối đến máy chủ tại " + finalDiscHost + ":" + finalDiscPort,
                                    msg
                            );
                        }
                    });
                    return null;
                });
    }
}
