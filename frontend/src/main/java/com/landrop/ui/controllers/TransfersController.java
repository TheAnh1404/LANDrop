package com.landrop.ui.controllers;

import com.landrop.transfer.FileTransfer;
import com.landrop.transfer.FileTransferDirection;
import com.landrop.transfer.FileTransferStatus;
import com.landrop.transfer.MultiPeerDownload;
import com.landrop.transfer.MultiPeerDownloadStatus;
import com.landrop.transfer.PeerDownloadState;
import com.landrop.ui.services.UIStateService;
import com.landrop.ui.utils.AlertUtils;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

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
    private Button startMultiPeerDownloadBtn;

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

        stateService.getMultiPeerDownloads().addListener((ListChangeListener<MultiPeerDownload>) c -> {
            Platform.runLater(this::renderTransfers);
        });

        stateService.setOnFileTransferUpdatedCallback(t -> {
            Platform.runLater(this::renderTransfers);
        });

        stateService.setOnMultiPeerDownloadUpdatedCallback(d -> {
            Platform.runLater(this::renderTransfers);
        });

        renderTransfers();
    }

    private void renderTransfers() {
        var singleList = stateService.getTransfers();
        var multiList = stateService.getMultiPeerDownloads();

        int total = singleList.size() + multiList.size();
        long activeSingle = singleList.stream().filter(t ->
                t.getStatus() == FileTransferStatus.TRANSFERRING ||
                t.getStatus() == FileTransferStatus.ACCEPTED ||
                t.getStatus() == FileTransferStatus.PENDING ||
                t.getStatus() == FileTransferStatus.RECONNECTING ||
                t.getStatus() == FileTransferStatus.RESUMING).count();
        long activeMulti = multiList.stream().filter(d ->
                d.getStatus() == MultiPeerDownloadStatus.DOWNLOADING ||
                d.getStatus() == MultiPeerDownloadStatus.DISCOVERING_PEERS ||
                d.getStatus() == MultiPeerDownloadStatus.PREPARING ||
                d.getStatus() == MultiPeerDownloadStatus.VERIFYING).count();
        long active = activeSingle + activeMulti;

        long completedSingle = singleList.stream().filter(t -> t.getStatus() == FileTransferStatus.COMPLETED).count();
        long completedMulti = multiList.stream().filter(d -> d.getStatus() == MultiPeerDownloadStatus.COMPLETED).count();
        long completed = completedSingle + completedMulti;

        if (totalTransfersLabel != null) totalTransfersLabel.setText(String.valueOf(total));
        if (activeTransfersLabel != null) activeTransfersLabel.setText(String.valueOf(active));
        if (completedTransfersLabel != null) completedTransfersLabel.setText(String.valueOf(completed));

        transfersContainer.getChildren().clear();

        boolean hasItems = !singleList.isEmpty() || !multiList.isEmpty();
        emptyTransfersContainer.setVisible(!hasItems);
        emptyTransfersContainer.setManaged(!hasItems);

        for (MultiPeerDownload download : multiList) {
            transfersContainer.getChildren().add(createMultiPeerRow(download));
        }

        for (FileTransfer transfer : singleList) {
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

    private HBox createMultiPeerRow(MultiPeerDownload download) {
        HBox row = new HBox(14.0);
        row.getStyleClass().add("card");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setStyle("-fx-padding: 12px 16px; -fx-background-color: #faf5ff; -fx-border-color: #e9d5ff;");

        // 1. Direction / Mode indicator icon
        Label dirBadge = new Label("⚡ Multi-Peer");
        dirBadge.setStyle("-fx-background-color: #f3e8ff; -fx-text-fill: #7c3aed; -fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 4px 8px; -fx-background-radius: 4px;");

        // 2. File info and remote peers
        VBox fileInfo = new VBox(2.0);
        fileInfo.setMinWidth(200.0);
        fileInfo.setPrefWidth(240.0);

        Label nameLabel = new Label(download.getFileName());
        nameLabel.getStyleClass().add("card-title");
        nameLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #581c87;");

        int activePeerCount = download.getConnectedSourcePeers().size();
        int totalPeerCount = download.getSourcePeers().size();
        String peerText = "Sources: " + activePeerCount + (totalPeerCount > activePeerCount ? "/" + totalPeerCount : "") +
                " peers  (" + download.getFormattedSize() + ")";
        Label peerLabel = new Label(peerText);
        peerLabel.getStyleClass().add("card-desc");
        peerLabel.setStyle("-fx-font-size: 11px;");

        fileInfo.getChildren().addAll(nameLabel, peerLabel);

        // 3. Progress area
        VBox progressBox = new VBox(4.0);
        HBox.setHgrow(progressBox, Priority.ALWAYS);
        progressBox.setAlignment(Pos.CENTER_LEFT);

        boolean isIndeterminate = download.getStatus() == MultiPeerDownloadStatus.VERIFYING ||
                download.getStatus() == MultiPeerDownloadStatus.DISCOVERING_PEERS ||
                download.getStatus() == MultiPeerDownloadStatus.PREPARING;
        ProgressBar bar = new ProgressBar(isIndeterminate ? -1.0 : download.getProgressPercentage() / 100.0);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.getStyleClass().add("transfer-progress-bar");
        bar.setStyle("-fx-accent: #7c3aed;");

        String progressText;
        if (download.getStatus() == MultiPeerDownloadStatus.DISCOVERING_PEERS) {
            progressText = "Discovering peers and querying file availability...";
        } else if (download.getStatus() == MultiPeerDownloadStatus.PREPARING) {
            progressText = "Preparing multi-peer partial storage...";
        } else if (download.getStatus() == MultiPeerDownloadStatus.VERIFYING) {
            progressText = "Verifying SHA-256 integrity across all downloaded chunks...";
        } else if (download.getStatus() == MultiPeerDownloadStatus.COMPLETED) {
            progressText = "✓ Multi-peer download completed • SHA-256 verified: " + download.getShortenedSha256();
        } else if (download.getStatus() == MultiPeerDownloadStatus.FAILED) {
            progressText = "✗ Download failed: " + (download.getErrorMessage() != null && !download.getErrorMessage().isBlank() ? download.getErrorMessage() : "Network error");
        } else if (download.getStatus() == MultiPeerDownloadStatus.CANCELLED) {
            progressText = "Download cancelled by user";
        } else {
            progressText = String.format(Locale.US, "%.1f%%  •  %s / %s  •  %d / %d chunks",
                    download.getProgressPercentage(),
                    download.getFormattedTransferred(),
                    download.getFormattedSize(),
                    download.getCompletedChunkCount(),
                    download.getTotalChunks());
            if (download.getStatus() == MultiPeerDownloadStatus.DOWNLOADING && download.getSpeedBytesPerSec() > 0) {
                progressText += "  •  " + download.getFormattedSpeed() + "  •  " + download.getEtaString();
            }
        }

        Label progressLabel = new Label(progressText);
        progressLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748b;");

        // Per-peer breakdown badges
        HBox peerBadgesBox = new HBox(6.0);
        peerBadgesBox.setAlignment(Pos.CENTER_LEFT);
        for (PeerDownloadState p : download.getSourcePeers()) {
            Label badge = new Label(p.getPeerDisplayName() + ": " + p.getCompletedChunks() + " chunks" + (p.isConnected() ? "" : " [Offline]"));
            badge.getStyleClass().add(p.isConnected() ? "peer-chunk-badge-active" : "peer-chunk-badge-offline");
            peerBadgesBox.getChildren().add(badge);
        }

        progressBox.getChildren().addAll(bar, progressLabel);
        if (!peerBadgesBox.getChildren().isEmpty()) {
            progressBox.getChildren().add(peerBadgesBox);
        }

        // 4. Status Badge
        Label statusBadge = new Label(download.getStatus().getLabel());
        statusBadge.getStyleClass().addAll("transfer-status-label", "transfer-status-" + download.getStatus().name().toLowerCase());
        statusBadge.setMinWidth(90.0);
        statusBadge.setAlignment(Pos.CENTER);

        // 5. Actions Box
        HBox actionBox = new HBox(6.0);
        actionBox.setAlignment(Pos.CENTER_RIGHT);
        actionBox.setMinWidth(160.0);

        if (download.getStatus() == MultiPeerDownloadStatus.COMPLETED) {
            if (download.getDestinationPath() != null) {
                Button openBtn = new Button("Open");
                openBtn.getStyleClass().add("secondary-button");
                openBtn.setStyle("-fx-font-size: 11px; -fx-padding: 4px 10px;");
                openBtn.setOnAction(e -> openFileSafely(download.getDestinationPath()));
                actionBox.getChildren().add(openBtn);
            }
        } else if (download.getStatus() == MultiPeerDownloadStatus.DOWNLOADING ||
                download.getStatus() == MultiPeerDownloadStatus.DISCOVERING_PEERS ||
                download.getStatus() == MultiPeerDownloadStatus.PREPARING) {
            Button cancelBtn = new Button("Cancel");
            cancelBtn.getStyleClass().add("secondary-button");
            cancelBtn.setStyle("-fx-font-size: 11px; -fx-padding: 4px 10px;");
            cancelBtn.setOnAction(e -> stateService.cancelMultiPeerDownload(download.getTransferId()));
            actionBox.getChildren().add(cancelBtn);
        }

        row.getChildren().addAll(dirBadge, fileInfo, progressBox, statusBadge, actionBox);
        return row;
    }

    @FXML
    private void handleStartMultiPeerDownload() {
        Dialog<MultiPeerDownloadRequest> dialog = new Dialog<>();
        dialog.setTitle("Start Multi-Peer Download");
        dialog.setHeaderText("Download a file concurrently from multiple peers");

        ButtonType downloadButtonType = new ButtonType("Download", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(downloadButtonType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 150, 10, 10));

        TextField fileNameField = new TextField();
        fileNameField.setPromptText("e.g. archive.zip");

        TextField fileSizeField = new TextField();
        fileSizeField.setPromptText("e.g. 10485760 (bytes)");

        TextField sha256Field = new TextField();
        sha256Field.setPromptText("64-character hex hash");

        grid.add(new Label("File Name:"), 0, 0);
        grid.add(fileNameField, 1, 0);
        grid.add(new Label("File Size (bytes):"), 0, 1);
        grid.add(fileSizeField, 1, 1);
        grid.add(new Label("SHA-256 Hash:"), 0, 2);
        grid.add(sha256Field, 1, 2);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == downloadButtonType) {
                String name = fileNameField.getText() != null ? fileNameField.getText().trim() : "";
                long size;
                try {
                    size = Long.parseLong(fileSizeField.getText() != null ? fileSizeField.getText().trim() : "");
                } catch (Exception ex) {
                    size = -1L;
                }
                String hash = sha256Field.getText() != null ? sha256Field.getText().trim() : "";
                return new MultiPeerDownloadRequest(name, size, hash);
            }
            return null;
        });

        var result = dialog.showAndWait();
        result.ifPresent(req -> {
            if (req.name().isBlank() || req.size() <= 0 || !com.landrop.transfer.FileTransferUtils.isValidSha256(req.hash())) {
                AlertUtils.showError("Invalid Input", "Validation Error", "Please provide a valid file name, positive size in bytes, and a 64-character hex SHA-256 hash.");
                return;
            }

            stateService.startMultiPeerDownload(req.name(), req.size(), req.hash().toLowerCase())
                    .thenAccept(dl -> Platform.runLater(this::renderTransfers))
                    .exceptionally(ex -> {
                        Platform.runLater(() -> AlertUtils.showError("Multi-Peer Error", "Could not start download", ex.getMessage()));
                        return null;
                    });
        });
    }

    private record MultiPeerDownloadRequest(String name, long size, String hash) {}

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
