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

/**
 * Controller for the Settings view displaying read-only identity and network parameters.
 */
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
    @FXML private TextField connectionIdField;
    @FXML private TextField clientInstanceIdField;
    @FXML private TextField serverProtocolField;
    @FXML private TextField heartbeatDiagnosticField;
    @FXML private Label serverStatusLabel;

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
            apiUrlField.setText(String.valueOf(mainController.getMainApp().getResolvedApiUri()));
            wsUrlField.setText(String.valueOf(mainController.getMainApp().getResolvedWsUri()));
            ClientRealtimeService server = mainController.getMainApp().getServerRealtimeService();
            if (server != null) {
                updateServerStatus(server.state());
                updateDiagnostics(server);
                server.stateProperty().addListener((obs, old, next) -> Platform.runLater(() -> updateServerStatus(next)));
                server.connectionIdProperty().addListener((obs, old, next) -> Platform.runLater(() -> updateDiagnostics(server)));
                server.serverVersionProperty().addListener((obs, old, next) -> Platform.runLater(() -> updateDiagnostics(server)));
                server.serverProtocolVersionProperty().addListener((obs, old, next) -> Platform.runLater(() -> updateDiagnostics(server)));
                server.lastHeartbeatProperty().addListener((obs, old, next) -> Platform.runLater(() -> updateDiagnostics(server)));
                server.reconnectAttemptsProperty().addListener((obs, old, next) -> Platform.runLater(() -> updateDiagnostics(server)));
            }
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
    private void updateServerStatus(ConnectionState state) { serverStatusLabel.setText("Server: " + state.name()); }
    private void updateDiagnostics(ClientRealtimeService server) {
        clientInstanceIdField.setText(server.clientInstanceId());
        connectionIdField.setText(server.connectionIdProperty().get());
        serverProtocolField.setText((server.serverVersionProperty().get().isBlank() ? "Waiting for WELCOME" : server.serverVersionProperty().get())
                + " / v" + server.serverProtocolVersionProperty().get());
        heartbeatDiagnosticField.setText((server.lastHeartbeatProperty().get().isBlank() ? "No heartbeat yet" : server.lastHeartbeatProperty().get())
                + " / retry " + server.reconnectAttemptsProperty().get());
    }
    private static String message(Throwable error) { Throwable cause = error.getCause() == null ? error : error.getCause(); return cause.getMessage() == null ? "request failed" : cause.getMessage(); }
}
