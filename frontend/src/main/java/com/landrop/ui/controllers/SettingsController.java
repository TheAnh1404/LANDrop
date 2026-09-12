package com.landrop.ui.controllers;

import com.landrop.discovery.DiscoveryClient;
import com.landrop.ui.services.UIStateService;
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

    public void init(UIStateService stateService, MainController mainController) {
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
    }
}
