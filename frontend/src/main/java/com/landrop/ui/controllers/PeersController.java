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
 * Controller for the Peers list view displaying discovered peers and direct connect actions.
 */
public class PeersController {

    @FXML
    private Label peersSubtitleLabel;

    @FXML
    private Button refreshButton;

    @FXML
    private VBox peersContainer;

    @FXML
    private VBox emptyStateContainer;

    private UIStateService stateService;
    private MainController mainController;

    public void init(UIStateService stateService, MainController mainController) {
        this.stateService = stateService;
        this.mainController = mainController;

        // Reactive peer count in subtitle
        stateService.onlinePeersCountProperty().addListener((obs, oldVal, newVal) -> {
            Platform.runLater(() -> {
                int count = newVal.intValue();
                peersSubtitleLabel.setText(count + (count == 1 ? " peer online" : " peers online"));
            });
        });
        int initialCount = stateService.onlinePeersCountProperty().get();
        peersSubtitleLabel.setText(initialCount + (initialCount == 1 ? " peer online" : " peers online"));

        // Reactive peers list rendering
        stateService.getDiscoveredPeers().addListener((ListChangeListener<PeerInfo>) c -> {
            Platform.runLater(this::renderPeers);
        });

        // Reactive P2P status updates (e.g. to update Connected buttons)
        stateService.p2pStatusProperty().addListener((obs, oldVal, newVal) -> {
            Platform.runLater(this::renderPeers);
        });

        renderPeers();
    }

    private void renderPeers() {
        peersContainer.getChildren().clear();
        var peers = stateService.getDiscoveredPeers();

        boolean hasPeers = !peers.isEmpty();
        emptyStateContainer.setVisible(!hasPeers);
        emptyStateContainer.setManaged(!hasPeers);
        peersContainer.setVisible(hasPeers);
        peersContainer.setManaged(hasPeers);

        for (PeerInfo peer : peers) {
            peersContainer.getChildren().add(createPeerCard(peer));
        }
    }

    private HBox createPeerCard(PeerInfo peer) {
        HBox card = new HBox(16.0);
        card.getStyleClass().add("peer-card");
        card.setAlignment(Pos.CENTER_LEFT);

        // Status indicator dot
        Circle statusDot = new Circle(5.0, Color.web("#10b981"));

        // Peer metadata details: distinguish Product User vs LAN Peer Device (Phase 15.5B.11)
        VBox infoBox = new VBox(3.0);
        Label nameLabel = new Label(peer.getDisplayName());
        nameLabel.getStyleClass().add("peer-card-name");

        Label addressLabel = new Label("Device: " + peer.getDisplayName() + "-Node (" + peer.getHost() + ":" + peer.getPort() + ")");
        addressLabel.getStyleClass().add("peer-card-address");

        Label idLabel = new Label("LAN Transport ID: " + peer.getPeerId());
        idLabel.getStyleClass().add("peer-card-id");

        infoBox.getChildren().addAll(nameLabel, addressLabel, idLabel);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Online badge
        Label badge = new Label("ONLINE");
        badge.getStyleClass().add("status-badge-online");

        // Action button
        Button connectBtn = new Button("Connect");
        connectBtn.getStyleClass().add("primary-button");

        boolean isConnectedToThis = stateService.isP2pConnected() &&
                peer.getDisplayName().equals(stateService.activeRemotePeerNameProperty().get());

        if (isConnectedToThis) {
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
                                    "Unable to connect to " + peer.getDisplayName() + " at " + peer.getHost() + ":" + peer.getPort(),
                                    msg
                            );
                        });
                        return null;
                    });
        });

        card.getChildren().addAll(statusDot, infoBox, spacer, badge, connectBtn);
        return card;
    }

    @FXML
    private void onRefreshClicked() {
        refreshButton.setDisable(true);
        refreshButton.setText("Refreshing...");

        stateService.refreshPeerList()
                .thenRun(() -> Platform.runLater(() -> {
                    refreshButton.setDisable(false);
                    refreshButton.setText("⟳ Refresh");
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        refreshButton.setDisable(false);
                        refreshButton.setText("⟳ Refresh");
                        String msg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                        AlertUtils.showError(
                                "Refresh Failed",
                                "Unable to fetch peer list from Discovery Server",
                                msg
                        );
                    });
                    return null;
                });
    }
}
