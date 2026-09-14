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

        String fallbackName = "User";
        if (mainApp != null && mainApp.getAuthStateService() != null && mainApp.getAuthStateService().user() != null) {
            String name = mainApp.getAuthStateService().user().displayName();
            if (name != null && !name.isBlank()) {
                fallbackName = name.trim();
            }
        }
        displayNameField.setText(initialDisplayName != null && !initialDisplayName.isBlank() ? initialDisplayName : fallbackName);
        localPortField.setText(String.valueOf(initialLocalPort > 0 ? initialLocalPort : 5001));
        advertisedHostField.setText(initialAdvertisedHost != null && !initialAdvertisedHost.isBlank() ? initialAdvertisedHost : "127.0.0.1");
        discoveryHostField.setText(initialDiscoveryHost != null && !initialDiscoveryHost.isBlank() ? initialDiscoveryHost : "127.0.0.1");
        discoveryPortField.setText(String.valueOf(initialDiscoveryPort > 0 ? initialDiscoveryPort : 8888));

        if (autoConnect) {
            Platform.runLater(this::onConnectClicked);
        }
    }

    @FXML
    private void onConnectClicked() {
        String displayName = displayNameField.getText() != null ? displayNameField.getText().trim() : "";
        if (displayName.isEmpty()) {
            statusLabel.setText("Please enter a display name.");
            return;
        }

        int localPort;
        try {
            localPort = Integer.parseInt(localPortField.getText().trim());
            if (localPort < 1024 || localPort > 65535) {
                statusLabel.setText("Local P2P Port must be between 1024 and 65535.");
                return;
            }
        } catch (NumberFormatException e) {
            statusLabel.setText("Invalid local P2P port number.");
            return;
        }

        String discHost = discoveryHostField.getText() != null ? discoveryHostField.getText().trim() : "";
        if (discHost.isEmpty()) {
            statusLabel.setText("Please enter a discovery server host.");
            return;
        }

        int discPort;
        try {
            discPort = Integer.parseInt(discoveryPortField.getText().trim());
            if (discPort < 1 || discPort > 65535) {
                statusLabel.setText("Discovery port must be between 1 and 65535.");
                return;
            }
        } catch (NumberFormatException e) {
            statusLabel.setText("Invalid discovery port number.");
            return;
        }

        String advHost = advertisedHostField.getText() != null ? advertisedHostField.getText().trim() : "127.0.0.1";

        connectButton.setDisable(true);
        connectButton.setText("Connecting...");
        statusLabel.setText("Connecting to Discovery Server at " + discHost + ":" + discPort + "...");
        statusLabel.setStyle("-fx-text-fill: #4f46e5;");

        stateService.connectAndRegister(displayName, localPort, discHost, discPort, advHost)
                .thenRun(() -> Platform.runLater(() -> {
                    statusLabel.setText("");
                    mainApp.showMainView();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        connectButton.setDisable(false);
                        connectButton.setText("Connect & Join Network");
                        String msg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                        statusLabel.setText(msg);
                        statusLabel.setStyle("-fx-text-fill: #ef4444;");

                        if (msg != null && msg.contains("Local P2P Port")) {
                            AlertUtils.showError(
                                    "Local Port Conflict",
                                    "Port " + localPort + " is already in use",
                                    msg
                            );
                        } else {
                            AlertUtils.showError(
                                    "Discovery Connection Failed",
                                    "Unable to connect to Discovery Server at " + discHost + ":" + discPort,
                                    msg
                            );
                        }
                    });
                    return null;
                });
    }
}
