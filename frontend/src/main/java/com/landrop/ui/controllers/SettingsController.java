package com.landrop.ui.controllers;

import com.landrop.discovery.DiscoveryClient;
import com.landrop.ui.services.UIStateService;
import com.landrop.ui.realtime.ClientRealtimeService;
import com.landrop.ui.connection.ConnectionState;
import com.landrop.ui.protocol.RealtimeEnvelope;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.fxml.FXML;
import javafx.scene.control.TextField;

/** Client diagnostics and user-editable server endpoint configuration. */
public class SettingsController {

    @FXML
    private TextField peerIdField;

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
    private TextField protocolVersionField;

    @FXML
    private TextField heartbeatIntervalField;

    @FXML private TextField apiUrlField;
    @FXML private TextField wsUrlField;
    @FXML private TextField serverNameField;
    @FXML private TextField serverAddressField;
    @FXML private TextField connectionIdField;
    @FXML private TextField clientInstanceIdField;
    @FXML private TextField deviceNameField;
    @FXML private TextField clientVersionField;
    @FXML private TextField stateDiagnosticField;
    @FXML private TextField serverProtocolField;
    @FXML private TextField heartbeatDiagnosticField;
    @FXML private Label serverStatusLabel;
    @FXML private Label signedInLabel;
    @FXML private Label enrolledDeviceLabel;
    @FXML private TextField renameDeviceField;
    @FXML private javafx.scene.control.Button renameDeviceButton;

    private MainController mainController;

    public void init(UIStateService stateService, MainController mainController) {
        this.mainController = mainController;
        if (stateService.getIdentity() != null) {
            peerIdField.setText(stateService.getIdentity().getPeerId());
            displayNameField.setText(stateService.getIdentity().getDisplayName());
        }

        localPortField.setText(String.valueOf(stateService.getLocalP2pPort()));
        advertisedHostField.setText(stateService.getAdvertisedHost());
        discoveryHostField.setText(stateService.getDiscoveryHost());
        discoveryPortField.setText(String.valueOf(stateService.getDiscoveryPort()));
        protocolVersionField.setText("v" + stateService.getProtocolVersion());
        heartbeatIntervalField.setText(DiscoveryClient.DEFAULT_HEARTBEAT_INTERVAL_SEC + " seconds");
        if (mainController.getMainApp() != null) {
            var app = mainController.getMainApp();
            var user = app.getAuthStateService() == null ? null : app.getAuthStateService().getCurrentUser();
            signedInLabel.setText("Signed in as: " + (user == null ? "" : user.displayName()));
            var device = app.getCurrentDeviceService() == null ? null : app.getCurrentDeviceService().currentDevice();
            enrolledDeviceLabel.setText(device == null ? "Device not enrolled" : "Device: " + device.label() + " | Device ID: " + device.id());
            renameDeviceField.setText(device == null ? "" : device.label());
            apiUrlField.setText(String.valueOf(mainController.getMainApp().getResolvedApiUri()));
            wsUrlField.setText(String.valueOf(mainController.getMainApp().getResolvedWsUri()));
            ClientRealtimeService server = mainController.getMainApp().getServerRealtimeService();
            if (server != null) {
                updateServerStatus(server.state());
                updateDiagnostics(server);
                server.stateProperty().addListener((obs, old, next) -> Platform.runLater(() -> updateServerStatus(next)));
                server.connectionIdProperty().addListener((obs, old, next) -> Platform.runLater(() -> updateDiagnostics(server)));
                server.serverNameProperty().addListener((obs, old, next) -> Platform.runLater(() -> updateDiagnostics(server)));
                server.serverAddressProperty().addListener((obs, old, next) -> Platform.runLater(() -> updateDiagnostics(server)));
                server.serverVersionProperty().addListener((obs, old, next) -> Platform.runLater(() -> updateDiagnostics(server)));
                server.serverProtocolVersionProperty().addListener((obs, old, next) -> Platform.runLater(() -> updateDiagnostics(server)));
                server.deviceNameProperty().addListener((obs, old, next) -> Platform.runLater(() -> updateDiagnostics(server)));
                server.clientVersionProperty().addListener((obs, old, next) -> Platform.runLater(() -> updateDiagnostics(server)));
                server.lastHeartbeatProperty().addListener((obs, old, next) -> Platform.runLater(() -> updateDiagnostics(server)));
                server.reconnectAttemptsProperty().addListener((obs, old, next) -> Platform.runLater(() -> updateDiagnostics(server)));
                server.disconnectReasonProperty().addListener((obs, old, next) -> Platform.runLater(() -> updateDiagnostics(server)));
            }
        }
    }

    @FXML private void onRenameDevice() {
        var app = mainController.getMainApp();
        if (app.getBootstrap() == null || !app.getBootstrap().isReady()) return;
        renameDeviceButton.setDisable(true);
        app.getCurrentDeviceService().rename(renameDeviceField.getText()).whenComplete((device, error) -> Platform.runLater(() -> {
            renameDeviceButton.setDisable(false);
            if (error == null) {
                enrolledDeviceLabel.setText("Device: " + device.label() + " | Device ID: " + device.id());
                deviceNameField.setText(device.label());
                serverStatusLabel.setText("Device name updated.");
            } else {
                String code = com.landrop.ui.bootstrap.BootstrapErrors.code(error);
                serverStatusLabel.setText("HTTP_ERROR".equals(code) ? "This server does not support device rename."
                        : com.landrop.ui.bootstrap.BootstrapErrors.message(code));
            }
        }));
    }

    @FXML private void onApplyServerClicked() {
        if (mainController == null || mainController.getMainApp() == null) return;
        try {
            mainController.getMainApp().configureServerConnection(apiUrlField.getText(), wsUrlField.getText());
            serverStatusLabel.setText("Server configuration saved; connecting...");
        } catch (IllegalArgumentException error) {
            serverStatusLabel.setText("Invalid server address: " + message(error));
        }
    }

    @FXML private void onCheckServerClicked() {
        ClientRealtimeService server = server(); if (server == null) return;
        serverStatusLabel.setText("Checking server health...");
        server.checkHealth().whenComplete((ignored, error) -> Platform.runLater(() ->
                serverStatusLabel.setText(error == null ? "Server reachable" : "Server unavailable: " + message(error))));
    }
    @FXML private void onReconnectClicked() { ClientRealtimeService server = server(); if (server != null) { server.reconnectNow(); serverStatusLabel.setText("Reconnecting..."); } }
    @FXML private void onEchoClicked() {
        ClientRealtimeService server = server(); if (server == null) return;
        var payload = RealtimeEnvelope.newPayload(); payload.put("message", "hello server");
        server.request("echo", payload).whenComplete((event, error) -> Platform.runLater(() ->
                serverStatusLabel.setText(error == null ? "Echo acknowledged" : "Echo failed: " + message(error))));
    }
    private ClientRealtimeService server() { return mainController == null || mainController.getMainApp() == null ? null : mainController.getMainApp().getServerRealtimeService(); }
    private void updateServerStatus(ConnectionState state) {
        ClientRealtimeService server = server();
        String reason = server == null ? "" : server.disconnectReasonProperty().get();
        if (reason != null && !reason.isBlank()) reason = com.landrop.ui.bootstrap.BootstrapErrors.message(reason.split(":", 2)[0]);
        serverStatusLabel.setText("Server: " + state.name() + (reason == null || reason.isBlank() ? "" : " (" + reason + ")"));
    }
    private void updateDiagnostics(ClientRealtimeService server) {
        clientInstanceIdField.setText(server.clientInstanceId());
        deviceNameField.setText(server.deviceNameProperty().get());
        clientVersionField.setText(server.clientVersionProperty().get());
        stateDiagnosticField.setText(server.state().name());
        serverNameField.setText(server.serverNameProperty().get());
        serverAddressField.setText(server.serverAddressProperty().get());
        connectionIdField.setText(server.connectionIdProperty().get());
        serverProtocolField.setText((server.serverVersionProperty().get().isBlank() ? "Waiting for WELCOME" : server.serverVersionProperty().get())
                + " / v" + server.serverProtocolVersionProperty().get());
        heartbeatDiagnosticField.setText((server.lastHeartbeatProperty().get().isBlank() ? "No heartbeat yet" : server.lastHeartbeatProperty().get())
                + " / reconnect " + server.reconnectAttemptsProperty().get());
    }
    private static String message(Throwable error) { Throwable cause = error.getCause() == null ? error : error.getCause(); return cause.getMessage() == null ? "request failed" : cause.getMessage(); }
}
