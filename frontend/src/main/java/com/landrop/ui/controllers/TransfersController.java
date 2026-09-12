package com.landrop.ui.controllers;

import com.landrop.transfer.FileTransfer;
import com.landrop.transfer.FileTransferDirection;
import com.landrop.transfer.FileTransferStatus;
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
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Controller for the dedicated Transfers history and management interface.
 */
public class TransfersController {

    @FXML
    private Label totalTransfersLabel;

    @FXML
    private Label activeTransfersLabel;

    @FXML
    private Label completedTransfersLabel;

    @FXML
    private ScrollPane transfersScrollPane;

    @FXML
    private VBox transfersContainer;

    @FXML
    private VBox emptyTransfersContainer;

    private UIStateService stateService;
    private MainController mainController;

    public void init(UIStateService stateService, MainController mainController) {
        this.stateService = stateService;
        this.mainController = mainController;

        stateService.getTransfers().addListener((ListChangeListener<FileTransfer>) c -> {
            Platform.runLater(this::renderTransfers);
        });

        stateService.setOnFileTransferUpdatedCallback(t -> {
            Platform.runLater(this::renderTransfers);
        });

        renderTransfers();
    }

    private void renderTransfers() {
        var list = stateService.getTransfers();
        int total = list.size();
        long active = list.stream().filter(t -> t.getStatus() == FileTransferStatus.TRANSFERRING || t.getStatus() == FileTransferStatus.ACCEPTED || t.getStatus() == FileTransferStatus.PENDING).count();
        long completed = list.stream().filter(t -> t.getStatus() == FileTransferStatus.COMPLETED).count();

        if (totalTransfersLabel != null) totalTransfersLabel.setText(String.valueOf(total));
        if (activeTransfersLabel != null) activeTransfersLabel.setText(String.valueOf(active));
        if (completedTransfersLabel != null) completedTransfersLabel.setText(String.valueOf(completed));

        transfersContainer.getChildren().clear();

        boolean hasItems = !list.isEmpty();
        emptyTransfersContainer.setVisible(!hasItems);
        emptyTransfersContainer.setManaged(!hasItems);

        for (FileTransfer transfer : list) {
            transfersContainer.getChildren().add(createTransferRow(transfer));
        }
    }

    private HBox createTransferRow(FileTransfer transfer) {
        HBox row = new HBox(14.0);
        row.getStyleClass().add("card");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setStyle("-fx-padding: 12px 16px;");

        // 1. Direction indicator icon
        boolean isOutgoing = transfer.getDirection() == FileTransferDirection.OUTGOING;
        Label dirBadge = new Label(isOutgoing ? "⬆ Out" : "⬇ In");
        dirBadge.setStyle(String.format(
                "-fx-background-color: %s; -fx-text-fill: %s; -fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 4px 8px; -fx-background-radius: 4px;",
                isOutgoing ? "#e0e7ff" : "#ecfdf5",
                isOutgoing ? "#4338ca" : "#059669"
        ));

        // 2. File info and remote peer
        VBox fileInfo = new VBox(2.0);
        fileInfo.setMinWidth(200.0);
        fileInfo.setPrefWidth(240.0);

        Label nameLabel = new Label(transfer.getFileName());
        nameLabel.getStyleClass().add("card-title");
        nameLabel.setStyle("-fx-font-size: 13px;");

        Label peerLabel = new Label((isOutgoing ? "To: " : "From: ") + transfer.getRemotePeerName() + "  (" + transfer.getFormattedSize() + ")");
        peerLabel.getStyleClass().add("card-desc");
        peerLabel.setStyle("-fx-font-size: 11px;");

        fileInfo.getChildren().addAll(nameLabel, peerLabel);

        // 3. Progress area
        VBox progressBox = new VBox(4.0);
        HBox.setHgrow(progressBox, Priority.ALWAYS);
        progressBox.setAlignment(Pos.CENTER_LEFT);

        boolean isIndeterminate = transfer.getStatus() == FileTransferStatus.VERIFYING ||
                transfer.getStatus() == FileTransferStatus.RECONNECTING ||
                transfer.getStatus() == FileTransferStatus.RESUMING;
        ProgressBar bar = new ProgressBar(isIndeterminate ? -1.0 : transfer.getProgress());
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.getStyleClass().add("transfer-progress-bar");

        String progressText;
        if (transfer.getStatus() == FileTransferStatus.VERIFYING) {
            progressText = "Verifying SHA-256 integrity...";
        } else if (transfer.getStatus() == FileTransferStatus.COMPLETED) {
            progressText = "✓ Transfer completed • Integrity verified • SHA-256: " + transfer.getShortenedSha256();
        } else if (transfer.getStatus() == FileTransferStatus.INTERRUPTED) {
            progressText = "Connection lost • Resuming... (" + transfer.getChunkProgressText() + ")";
        } else if (transfer.getStatus() == FileTransferStatus.RECONNECTING) {
            progressText = "Reconnecting... (Attempt " + transfer.getReconnectAttempt() + " / " + transfer.getMaxReconnectAttempts() + ") • " + transfer.getChunkProgressText();
        } else if (transfer.getStatus() == FileTransferStatus.RESUMING) {
            progressText = "Resuming transfer • " + transfer.getChunkProgressText();
        } else if (transfer.getStatus() == FileTransferStatus.FAILED) {
            progressText = "✗ Transfer failed: " + (!transfer.getErrorMessage().isBlank() ? transfer.getErrorMessage() : "Unknown error");
        } else {
            progressText = String.format("%d%%  •  %s / %s  •  %s",
                    transfer.getProgressPercent(),
                    transfer.getFormattedTransferred(),
                    transfer.getFormattedSize(),
                    transfer.getChunkProgressText());
            if (transfer.getStatus() == FileTransferStatus.TRANSFERRING && transfer.getSpeedBytesPerSec() > 0) {
                progressText += "  •  " + transfer.getFormattedSpeed();
            }
        }

        Label progressLabel = new Label(progressText);
        progressLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748b;");
        progressBox.getChildren().addAll(bar, progressLabel);

        // 4. Status Badge
        Label statusBadge = new Label(transfer.getStatus().getLabel());
        statusBadge.getStyleClass().addAll("transfer-status-label", "transfer-status-" + transfer.getStatus().name().toLowerCase());
        statusBadge.setMinWidth(80.0);
        statusBadge.setAlignment(Pos.CENTER);

        // 5. Actions Box
        HBox actionBox = new HBox(6.0);
        actionBox.setAlignment(Pos.CENTER_RIGHT);
        actionBox.setMinWidth(160.0);

        if (transfer.getStatus() == FileTransferStatus.PENDING && transfer.getDirection() == FileTransferDirection.INCOMING) {
            Button acceptBtn = new Button("Accept");
            acceptBtn.getStyleClass().add("primary-button");
            acceptBtn.setStyle("-fx-font-size: 11px; -fx-padding: 4px 10px;");
            acceptBtn.setOnAction(e -> {
                acceptBtn.setDisable(true);
                stateService.acceptTransfer(transfer.getTransferId()).exceptionally(ex -> {
                    Platform.runLater(() -> AlertUtils.showError("Transfer Error", "Failed to accept transfer", ex.getMessage()));
                    return null;
                });
            });

            Button rejectBtn = new Button("Reject");
            rejectBtn.getStyleClass().add("danger-button");
            rejectBtn.setStyle("-fx-font-size: 11px; -fx-padding: 4px 10px;");
            rejectBtn.setOnAction(e -> {
                rejectBtn.setDisable(true);
                stateService.rejectTransfer(transfer.getTransferId(), "Declined by user");
            });

            actionBox.getChildren().addAll(acceptBtn, rejectBtn);

        } else if (transfer.getStatus() == FileTransferStatus.INTERRUPTED) {
            Button resumeBtn = new Button("Resume");
            resumeBtn.getStyleClass().add("primary-button");
            resumeBtn.setStyle("-fx-font-size: 11px; -fx-padding: 4px 10px;");
            resumeBtn.setOnAction(e -> stateService.resumeTransfer(transfer.getTransferId()));

            Button cancelBtn = new Button("Cancel");
            cancelBtn.getStyleClass().add("secondary-button");
            cancelBtn.setStyle("-fx-font-size: 11px; -fx-padding: 4px 10px;");
            cancelBtn.setOnAction(e -> stateService.cancelTransfer(transfer.getTransferId()));

            actionBox.getChildren().addAll(resumeBtn, cancelBtn);

        } else if (transfer.getStatus() == FileTransferStatus.TRANSFERRING || transfer.getStatus() == FileTransferStatus.ACCEPTED
                || transfer.getStatus() == FileTransferStatus.RECONNECTING || transfer.getStatus() == FileTransferStatus.RESUMING) {
            Button cancelBtn = new Button("Cancel");
            cancelBtn.getStyleClass().add("secondary-button");
            cancelBtn.setStyle("-fx-font-size: 11px; -fx-padding: 4px 10px;");
            cancelBtn.setOnAction(e -> stateService.cancelTransfer(transfer.getTransferId()));
            actionBox.getChildren().add(cancelBtn);

        } else if (transfer.getStatus() == FileTransferStatus.COMPLETED) {
            if (transfer.getLocalPath() != null) {
                Button openBtn = new Button("Open");
                openBtn.getStyleClass().add("secondary-button");
                openBtn.setStyle("-fx-font-size: 11px; -fx-padding: 4px 10px;");
                openBtn.setOnAction(e -> openFileSafely(transfer.getLocalPath()));
                actionBox.getChildren().add(openBtn);
            }
        }

        row.getChildren().addAll(dirBadge, fileInfo, progressBox, statusBadge, actionBox);
        return row;
    }

    private void openFileSafely(Path path) {
        if (path == null) return;
        Thread.ofVirtual().start(() -> {
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop desktop = Desktop.getDesktop();
                    if (Files.exists(path)) {
                        desktop.open(path.toFile());
                    } else if (path.getParent() != null && Files.exists(path.getParent())) {
                        desktop.open(path.getParent().toFile());
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }
}
