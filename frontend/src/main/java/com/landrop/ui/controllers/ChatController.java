package com.landrop.ui.controllers;

import com.landrop.ui.models.NavigationTab;
import com.landrop.ui.models.P2PStatus;
import com.landrop.ui.models.UIMessage;
import com.landrop.ui.services.UIStateService;
import com.landrop.ui.utils.AlertUtils;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.util.concurrent.CompletableFuture;

/**
 * Controller for the Real-Time Peer-to-Peer Chat interface.
 */
public class ChatController {

    @FXML
    private Label remotePeerNameLabel;

    @FXML
    private Circle connectionDot;

    @FXML
    private Label connectionStatusLabel;

    @FXML
    private Button pingButton;

    @FXML
    private Button disconnectButton;

    @FXML
    private ScrollPane chatScrollPane;

    @FXML
    private VBox messagesContainer;

    @FXML
    private VBox emptyMessagesContainer;

    @FXML
    private VBox disconnectedOverlay;

    @FXML
    private Button attachFileButton;

    @FXML
    private TextField messageInput;

    @FXML
    private Button sendButton;

    private UIStateService stateService;
    private MainController mainController;

    public void init(UIStateService stateService, MainController mainController) {
        this.stateService = stateService;
        this.mainController = mainController;

        // Send button disabled state when input is empty or whitespace
        sendButton.disableProperty().bind(
                messageInput.textProperty().map(text -> text == null || text.trim().isEmpty())
        );

        // Reactive P2P connection status updates
        stateService.p2pStatusProperty().addListener((obs, oldVal, newVal) -> {
            Platform.runLater(this::updateConnectionState);
        });

        stateService.activeRemotePeerNameProperty().addListener((obs, oldVal, newVal) -> {
            Platform.runLater(this::updateConnectionState);
        });

        stateService.activeRemotePeerAddressProperty().addListener((obs, oldVal, newVal) -> {
            Platform.runLater(this::updateConnectionState);
        });

        // Reactive messages list updates
        stateService.getActiveChatMessages().addListener((ListChangeListener<UIMessage>) c -> {
            Platform.runLater(this::renderMessages);
        });

        updateConnectionState();
        renderMessages();
    }

    private void updateConnectionState() {
        boolean connected = stateService.isP2pConnected();

        disconnectedOverlay.setVisible(!connected);
        disconnectedOverlay.setManaged(!connected);

        if (connected) {
            String peerName = stateService.activeRemotePeerNameProperty().get();
            String peerAddr = stateService.activeRemotePeerAddressProperty().get();

            remotePeerNameLabel.setText(peerName != null && !peerName.isBlank() ? peerName : "Peer");
            connectionDot.setFill(Color.web("#10b981"));
            connectionStatusLabel.setText("Online   " + peerAddr);
            pingButton.setDisable(false);
            disconnectButton.setDisable(false);
            messageInput.setDisable(false);
        } else {
            remotePeerNameLabel.setText("No Active Peer");
            connectionDot.setFill(Color.web("#94a3b8"));
            connectionStatusLabel.setText("Disconnected");
            pingButton.setDisable(true);
            disconnectButton.setDisable(true);
            messageInput.setDisable(true);
        }
    }

    private void renderMessages() {
        messagesContainer.getChildren().clear();
        var messages = stateService.getActiveChatMessages();

        boolean hasMessages = !messages.isEmpty();
        emptyMessagesContainer.setVisible(!hasMessages);
        emptyMessagesContainer.setManaged(!hasMessages);

        for (UIMessage msg : messages) {
            messagesContainer.getChildren().add(createMessageBubble(msg));
        }

        // Auto-scroll to bottom of chat
        Platform.runLater(() -> chatScrollPane.setVvalue(1.0));
    }

    private HBox createMessageBubble(UIMessage msg) {
        HBox row = new HBox();
        row.getStyleClass().add(msg.isLocal() ? "message-row-local" : "message-row-remote");

        VBox bubble = new VBox(4.0);
        bubble.getStyleClass().add(msg.isLocal() ? "message-bubble-local" : "message-bubble-remote");

        // Sender header if remote
        if (!msg.isLocal()) {
            Label senderLabel = new Label(msg.sender());
            senderLabel.getStyleClass().add("message-sender");
            bubble.getChildren().add(senderLabel);
        }

        Label contentLabel = new Label(msg.content());
        contentLabel.getStyleClass().add("message-text");
        contentLabel.setWrapText(true);

        Label timeLabel = new Label(msg.formattedTime());
        timeLabel.getStyleClass().add("message-time");

        HBox metaBox = new HBox(timeLabel);
        metaBox.setAlignment(Pos.BOTTOM_RIGHT);

        bubble.getChildren().addAll(contentLabel, metaBox);
        row.getChildren().add(bubble);

        return row;
    }

    @FXML
    private void onSendMessage() {
        String text = messageInput.getText();
        if (text == null || text.trim().isEmpty()) {
            return;
        }

        String content = text.trim();
        messageInput.clear();

        stateService.sendMessage(content).exceptionally(ex -> {
            Platform.runLater(() -> {
                String msg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                AlertUtils.showError("Message Error", "Failed to send chat message", msg);
            });
            return null;
        });
    }

    @FXML
    private void onPingClicked() {
        pingButton.setDisable(true);
        pingButton.setText("Pinging...");

        stateService.pingPeer()
                .thenAccept(latencyMs -> Platform.runLater(() -> {
                    pingButton.setText(String.format("Pong: %.1f ms", latencyMs));
                    // Reset after 3 seconds
                    CompletableFuture.delayedExecutor(3, java.util.concurrent.TimeUnit.SECONDS).execute(() -> {
                        Platform.runLater(() -> {
                            pingButton.setText("Ping");
                            pingButton.setDisable(false);
                        });
                    });
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        pingButton.setText("Ping");
                        pingButton.setDisable(false);
                        String msg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                        AlertUtils.showWarning("Ping Timeout", "No response from peer", msg);
                    });
                    return null;
                });
    }

    @FXML
    private void onDisconnectClicked() {
        stateService.disconnectP2P();
        if (mainController != null) {
            mainController.navigateTo(NavigationTab.PEERS);
        }
    }

    @FXML
    private void onGoToPeersClicked() {
        if (mainController != null) {
            mainController.navigateTo(NavigationTab.PEERS);
        }
    }
}
