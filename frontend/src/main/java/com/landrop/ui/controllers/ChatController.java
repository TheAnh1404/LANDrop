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
import javafx.scene.control.ProgressBar;
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

        // Reactive file transfer updates
        stateService.setOnFileTransferUpdatedCallback(t -> {
            Platform.runLater(this::renderMessages);
        });
        stateService.getTransfers().addListener((ListChangeListener<com.landrop.transfer.FileTransfer>) c -> {
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
            attachFileButton.setDisable(false);
        } else {
            remotePeerNameLabel.setText("No Active Peer");
            connectionDot.setFill(Color.web("#94a3b8"));
            connectionStatusLabel.setText("Disconnected");
            pingButton.setDisable(true);
            disconnectButton.setDisable(true);
            messageInput.setDisable(true);
            attachFileButton.setDisable(true);
        }
    }

    private record ChatEntry(long timestamp, UIMessage message, com.landrop.transfer.FileTransfer transfer) {}

    private void renderMessages() {
        messagesContainer.getChildren().clear();
        var messages = stateService.getActiveChatMessages();
        var allTransfers = stateService.getTransfers();

        String activePeer = stateService.activeRemotePeerNameProperty().get();
        java.util.List<ChatEntry> entries = new java.util.ArrayList<>();

        for (UIMessage msg : messages) {
            entries.add(new ChatEntry(msg.timestamp(), msg, null));
        }

        for (com.landrop.transfer.FileTransfer t : allTransfers) {
            if (activePeer != null && (activePeer.equalsIgnoreCase(t.getRemotePeerName()) || activePeer.equalsIgnoreCase(t.getRemotePeerId()))) {
                entries.add(new ChatEntry(t.getStartTimeMs(), null, t));
            }
        }

        entries.sort(java.util.Comparator.comparingLong(ChatEntry::timestamp));

        boolean hasItems = !entries.isEmpty();
        emptyMessagesContainer.setVisible(!hasItems);
        emptyMessagesContainer.setManaged(!hasItems);

        for (ChatEntry entry : entries) {
            if (entry.message() != null) {
                messagesContainer.getChildren().add(createMessageBubble(entry.message()));
            } else if (entry.transfer() != null) {
                messagesContainer.getChildren().add(createTransferCard(entry.transfer()));
            }
        }

        // Auto-scroll to bottom of chat
        Platform.runLater(() -> chatScrollPane.setVvalue(1.0));
    }

    private HBox createTransferCard(com.landrop.transfer.FileTransfer transfer) {
        boolean isLocal = transfer.getDirection() == com.landrop.transfer.FileTransferDirection.OUTGOING;
        HBox row = new HBox();
        row.getStyleClass().add(isLocal ? "message-row-local" : "message-row-remote");

        VBox card = new VBox(6.0);
        card.getStyleClass().addAll("transfer-card-bubble", isLocal ? "transfer-card-bubble-local" : "message-bubble-remote");

        // Header: Direction Icon and File Name
        HBox header = new HBox(6.0);
        header.setAlignment(Pos.CENTER_LEFT);
        Label iconLabel = new Label(isLocal ? "⬆" : "⬇");
        iconLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #4f46e5;");
        Label titleLabel = new Label(transfer.getFileName());
        titleLabel.getStyleClass().add("transfer-file-name");
        header.getChildren().addAll(iconLabel, titleLabel);

        // Metadata: size & status
        HBox metaBox = new HBox(8.0);
        metaBox.setAlignment(Pos.CENTER_LEFT);
        Label sizeLabel = new Label(transfer.getFormattedSize());
        sizeLabel.getStyleClass().add("transfer-file-meta");
        Label dot = new Label("•");
        dot.setStyle("-fx-text-fill: #94a3b8;");
        Label statusBadge = new Label(transfer.getStatus().getLabel());
        statusBadge.getStyleClass().addAll("transfer-status-label", "transfer-status-" + transfer.getStatus().name().toLowerCase());
        metaBox.getChildren().addAll(sizeLabel, dot, statusBadge);

        card.getChildren().addAll(header, metaBox);

        // Interactive body depending on status
        if (transfer.getStatus() == com.landrop.transfer.FileTransferStatus.TRANSFERRING ||
                transfer.getStatus() == com.landrop.transfer.FileTransferStatus.ACCEPTED) {

            ProgressBar progressBar = new ProgressBar(transfer.getProgress());
            progressBar.setMaxWidth(Double.MAX_VALUE);
            progressBar.getStyleClass().add("transfer-progress-bar");

            String detailStr = String.format("%d%%   %s / %s   (%s)   %s",
                    transfer.getProgressPercent(),
                    transfer.getFormattedTransferred(),
                    transfer.getFormattedSize(),
                    transfer.getChunkProgressText(),
                    transfer.getFormattedSpeed());
            Label progressDetail = new Label(detailStr);
            progressDetail.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748b;");

            Button cancelBtn = new Button("Cancel");
            cancelBtn.getStyleClass().add("secondary-button");
            cancelBtn.setStyle("-fx-font-size: 10px; -fx-padding: 2px 8px;");
            cancelBtn.setOnAction(e -> stateService.cancelTransfer(transfer.getTransferId()));

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox progressRow = new HBox(10.0, progressDetail, spacer, cancelBtn);
            progressRow.setAlignment(Pos.CENTER_LEFT);

            card.getChildren().addAll(progressBar, progressRow);

        } else if (transfer.getStatus() == com.landrop.transfer.FileTransferStatus.VERIFYING) {
            ProgressBar progressBar = new ProgressBar(-1.0);
            progressBar.setMaxWidth(Double.MAX_VALUE);
            progressBar.getStyleClass().add("transfer-progress-bar");

            Label verifyLabel = new Label("Verifying SHA-256 integrity...");
            verifyLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #d97706; -fx-font-weight: bold;");

            card.getChildren().addAll(progressBar, verifyLabel);

        } else if (transfer.getStatus() == com.landrop.transfer.FileTransferStatus.PENDING) {
            if (transfer.getDirection() == com.landrop.transfer.FileTransferDirection.INCOMING) {
                HBox actionBox = new HBox(8.0);
                actionBox.setAlignment(Pos.CENTER_LEFT);

                Button acceptBtn = new Button("Accept");
                acceptBtn.getStyleClass().add("primary-button");
                acceptBtn.setStyle("-fx-font-size: 11px; -fx-padding: 4px 12px;");
                acceptBtn.setOnAction(e -> {
                    acceptBtn.setDisable(true);
                    stateService.acceptTransfer(transfer.getTransferId()).exceptionally(ex -> {
                        Platform.runLater(() -> AlertUtils.showError("Transfer Error", "Failed to accept transfer", ex.getMessage()));
                        return null;
                    });
                });

                Button rejectBtn = new Button("Reject");
                rejectBtn.getStyleClass().add("danger-button");
                rejectBtn.setStyle("-fx-font-size: 11px; -fx-padding: 4px 12px;");
                rejectBtn.setOnAction(e -> {
                    rejectBtn.setDisable(true);
                    stateService.rejectTransfer(transfer.getTransferId(), "Declined by receiver");
                });

                actionBox.getChildren().addAll(acceptBtn, rejectBtn);
                card.getChildren().add(actionBox);
            } else {
                Label waitingLabel = new Label("Waiting for " + transfer.getRemotePeerName() + " to accept...");
                waitingLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #94a3b8; -fx-font-style: italic;");
                card.getChildren().add(waitingLabel);
            }
        } else if (transfer.getStatus() == com.landrop.transfer.FileTransferStatus.COMPLETED) {
            VBox completedBox = new VBox(4.0);
            HBox topRow = new HBox(8.0);
            topRow.setAlignment(Pos.CENTER_LEFT);
            Label checkLabel = new Label("✓ Transfer completed");
            checkLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #10b981; -fx-font-weight: bold;");
            topRow.getChildren().add(checkLabel);

            if (transfer.getLocalPath() != null) {
                Button openBtn = new Button("Open File");
                openBtn.getStyleClass().add("secondary-button");
                openBtn.setStyle("-fx-font-size: 10px; -fx-padding: 2px 8px;");
                openBtn.setOnAction(e -> openFileSafely(transfer.getLocalPath()));
                topRow.getChildren().add(openBtn);
            }

            String hashInfo = "Integrity verified • SHA-256: " + transfer.getShortenedSha256();
            Label hashLabel = new Label(hashInfo);
            hashLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #64748b; -fx-font-family: monospace;");

            completedBox.getChildren().addAll(topRow, hashLabel);
            card.getChildren().add(completedBox);
        } else if (transfer.getStatus() == com.landrop.transfer.FileTransferStatus.FAILED ||
                transfer.getStatus() == com.landrop.transfer.FileTransferStatus.REJECTED ||
                transfer.getStatus() == com.landrop.transfer.FileTransferStatus.CANCELLED) {

            String err = transfer.getErrorMessage();
            Label statusMsg = new Label(err.isBlank() ? transfer.getStatus().getLabel() : err);
            statusMsg.setStyle("-fx-font-size: 11px; -fx-text-fill: #ef4444;");
            statusMsg.setWrapText(true);
            card.getChildren().add(statusMsg);
        }

        row.getChildren().add(card);
        return row;
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

    @FXML
    private void onAttachFileClicked() {
        if (!stateService.isP2pConnected()) {
            AlertUtils.showWarning("No Peer Connected", "Connect to a peer first", "You must be connected to a peer before sending a file.");
            return;
        }

        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Select File to Send via LANDrop P2P");
        java.io.File file = chooser.showOpenDialog(attachFileButton.getScene().getWindow());
        if (file != null) {
            stateService.offerFile(file).exceptionally(ex -> {
                Platform.runLater(() -> {
                    String msg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                    AlertUtils.showError("File Transfer Error", "Failed to offer file", msg);
                });
                return null;
            });
        }
    }

    private void openFileSafely(java.nio.file.Path path) {
        if (path == null) return;
        Thread.ofVirtual().start(() -> {
            try {
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
                    if (java.nio.file.Files.exists(path)) {
                        desktop.open(path.toFile());
                    } else if (path.getParent() != null && java.nio.file.Files.exists(path.getParent())) {
                        desktop.open(path.getParent().toFile());
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }
}
