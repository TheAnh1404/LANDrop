package com.landrop.ui.controllers;

import com.landrop.discovery.PeerInfo;
import com.landrop.ui.models.NavigationTab;
import com.landrop.ui.services.UIStateService;
import com.landrop.ui.utils.AlertUtils;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/**
 * Controller for the Main Dashboard view displaying network metrics and discovered peers.
 */
public class DashboardController {

    @FXML
    private Label onlinePeersLabel;

    @FXML
    private Label p2pConnectionsLabel;

    @FXML
    private Label messagesLabel;

    @FXML
    private VBox recentPeersContainer;

    @FXML
    private VBox emptyStateContainer;

    @FXML
    private Button viewAllPeersButton;

    private UIStateService stateService;
    private MainController mainController;

    public void init(UIStateService stateService, MainController mainController) {
        this.stateService = stateService;
        this.mainController = mainController;

        // Data bindings
        onlinePeersLabel.textProperty().bind(stateService.onlinePeersCountProperty().asString());
        p2pConnectionsLabel.textProperty().bind(stateService.p2pConnectionsCountProperty().asString());
        messagesLabel.textProperty().bind(stateService.totalMessagesCountProperty().asString());

        // Update peers list reactively
        stateService.getDiscoveredPeers().addListener((ListChangeListener<PeerInfo>) c -> {
            Platform.runLater(this::renderPeers);
        });

        renderPeers();
    }

    private void renderPeers() {
        recentPeersContainer.getChildren().clear();
        var peers = stateService.getDiscoveredPeers();

        boolean hasPeers = !peers.isEmpty();
        emptyStateContainer.setVisible(!hasPeers);
        emptyStateContainer.setManaged(!hasPeers);
        recentPeersContainer.setVisible(hasPeers);
        recentPeersContainer.setManaged(hasPeers);

        for (PeerInfo peer : peers) {
            recentPeersContainer.getChildren().add(createPeerRow(peer));
        }
    }

    private HBox createPeerRow(PeerInfo peer) {
        HBox row = new HBox(12.0);
        row.getStyleClass().add("peer-card");
        row.setAlignment(Pos.CENTER_LEFT);

        Circle dot = new Circle(4.0, Color.web("#10b981"));

        VBox details = new VBox(2.0);
        Label nameLabel = new Label(peer.getDisplayName());
        nameLabel.getStyleClass().add("peer-card-name");

        Label addressLabel = new Label(peer.getHost() + ":" + peer.getPort());
        addressLabel.getStyleClass().add("peer-card-address");

        details.getChildren().addAll(nameLabel, addressLabel);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label statusBadge = new Label("ONLINE");
        statusBadge.getStyleClass().add("status-badge-online");

        Button connectBtn = new Button("Connect");
        connectBtn.getStyleClass().add("primary-button");

        // If already connected to this peer, mark as Connected
        if (stateService.isP2pConnected() &&
                peer.getDisplayName().equals(stateService.activeRemotePeerNameProperty().get())) {
            connectBtn.setText("Connected");
            connectBtn.setDisable(true);
        }

        connectBtn.setOnAction(e -> {
            connectBtn.setDisable(true);
            connectBtn.setText("Connecting...");

            stateService.connectToPeer(peer)
                    .thenRun(() -> Platform.runLater(() -> {
                        mainController.navigateTo(NavigationTab.CHAT);
                    }))
                    .exceptionally(ex -> {
                        Platform.runLater(() -> {
                            connectBtn.setDisable(false);
                            connectBtn.setText("Connect");
                            String msg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                            AlertUtils.showError(
                                    "Peer Connection Failed",
                                    "Unable to connect to " + peer.getDisplayName(),
                                    msg
                            );
                        });
                        return null;
                    });
        });

        row.getChildren().addAll(dot, details, spacer, statusBadge, connectBtn);
        return row;
    }

    @FXML
    private void onViewAllPeersClicked() {
        if (mainController != null) {
            mainController.navigateTo(NavigationTab.PEERS);
        }
    }
}
