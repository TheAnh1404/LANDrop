package com.landrop.ui.controllers;

import com.landrop.ui.cloud.model.Domain;
import com.landrop.ui.lan.LanTransferService;
import com.landrop.ui.models.NavigationTab;
import com.landrop.ui.models.RealtimeConnectionStatus;
import com.landrop.ui.util.FileUtils;
import com.landrop.ui.workspace.chat.services.WorkspaceChatStateService;
import com.landrop.ui.workspace.files.services.FileManagerStateService;
import com.landrop.ui.workspace.services.WorkspaceStateService;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;

/**
 * Enhanced Group Collaboration Chat Controller.
 * Provides real-time team messaging, inline large file sharing via LAN P2P,
 * drag-and-drop file attachments, and active workspace members directory.
 */
public final class ChatController {

    @FXML private Label workspaceNameLabel, channelTitleLabel, channelDescriptionLabel, connectionStatusLabel, errorLabel, replyLabel;
    @FXML private Label membersHeaderLabel, attachmentIconLabel, attachmentNameLabel, attachmentSizeLabel;
    @FXML private VBox channelListContainer, createChannelBox, messagesContainer, emptyMessagesContainer, membersListContainer;
    @FXML private TextField channelNameInput, channelDescriptionInput, messageInput;
    @FXML private Button createChannelButton, loadOlderButton, sendButton, attachFileButton, openFolderButton;
    @FXML private Circle connectionDot;
    @FXML private ScrollPane chatScrollPane;
    @FXML private HBox replyBar, attachmentBar;

    private WorkspaceChatStateService chat;
    private WorkspaceStateService workspaces;
    private MainController mainController;

    private Domain.Message replyingTo, editing;
    private boolean initialHistory = true;
    private double olderContentHeight = -1, olderScrollValue;

    // File attachment staging
    private File pendingAttachmentFile;

    /** Compatibility hook for legacy tests; production uses workspace chat state. */
    @Deprecated
    public void init(com.landrop.ui.services.UIStateService ignored, MainController mainController) {
        this.mainController = mainController;
        if (workspaceNameLabel != null) workspaceNameLabel.setText("No workspace selected");
        renderActive(null);
        if (connectionStatusLabel != null) connectionStatusLabel.setText("Disconnected");
    }

    public void init(WorkspaceChatStateService chat, WorkspaceStateService workspaces, MainController mainController) {
        this.chat = Objects.requireNonNull(chat);
        this.workspaces = Objects.requireNonNull(workspaces);
        this.mainController = mainController;

        // Enable send button if text or attachment is present
        updateSendButtonState();
        messageInput.textProperty().addListener((obs, old, val) -> updateSendButtonState());

        // Chat real-time listeners
        chat.channels().addListener((ListChangeListener<Domain.Channel>) c -> Platform.runLater(this::renderChannels));
        chat.messages().addListener((ListChangeListener<Domain.Message>) c -> Platform.runLater(() -> {
            renderMessages(!initialHistory && nearBottom());
            if (olderContentHeight >= 0) preserveOlderPosition();
        }));
        chat.activeChannelProperty().addListener((o, old, next) -> Platform.runLater(() -> {
            initialHistory = true;
            renderActive(next);
            renderMessages(false);
        }));
        chat.connectionStatusProperty().addListener((o, old, next) -> Platform.runLater(this::renderConnection));
        chat.loadingHistoryProperty().addListener((o, old, now) -> Platform.runLater(() -> {
            if (!now && initialHistory) {
                renderMessages(true);
                initialHistory = false;
            }
        }));
        chat.errorMessageProperty().addListener((o, old, now) -> Platform.runLater(() -> {
            if (errorLabel != null) errorLabel.setText(now == null ? "" : now);
        }));

        // Workspace and members listeners
        workspaces.activeWorkspaceProperty().addListener((o, old, now) -> Platform.runLater(() -> {
            if (workspaceNameLabel != null) workspaceNameLabel.setText(now == null ? "No workspace selected" : now.name());
            renderChannels();
            updateCreateControls();
            renderMembers();
        }));
        workspaces.currentUserMemberProperty().addListener((o, old, now) -> Platform.runLater(() -> {
            updateCreateControls();
            renderMembers();
        }));

        // Setup drag and drop for files on the chat area
        setupDragAndDrop();

        if (workspaceNameLabel != null) {
            workspaceNameLabel.setText(workspaces.getActiveWorkspace() == null ? "No workspace selected" : workspaces.getActiveWorkspace().name());
        }

        renderChannels();
        renderActive(chat.activeChannel());
        renderConnection();
        updateCreateControls();
        renderMembers();
    }

    private void updateSendButtonState() {
        boolean hasText = messageInput.getText() != null && !messageInput.getText().trim().isEmpty();
        boolean hasFile = pendingAttachmentFile != null;
        sendButton.setDisable(!hasText && !hasFile);
    }

    private void setupDragAndDrop() {
        if (chatScrollPane == null) return;

        chatScrollPane.setOnDragOver(event -> {
            if (event.getGestureSource() != chatScrollPane && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY_OR_MOVE);
            }
            event.consume();
        });

        chatScrollPane.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;
            if (db.hasFiles() && !db.getFiles().isEmpty()) {
                File file = db.getFiles().get(0);
                setPendingAttachment(file);
                success = true;
            }
            event.setDropCompleted(success);
            event.consume();
        });
    }

    private void setPendingAttachment(File file) {
        if (file == null || !file.exists()) return;
        this.pendingAttachmentFile = file;

        if (attachmentBar != null) {
            if (attachmentIconLabel != null) attachmentIconLabel.setText(FileUtils.getFileIcon(file.getName(), null));
            if (attachmentNameLabel != null) attachmentNameLabel.setText(file.getName());
            if (attachmentSizeLabel != null) attachmentSizeLabel.setText("Dung lượng: " + FileUtils.formatSize(file.length()));
            attachmentBar.setVisible(true);
            attachmentBar.setManaged(true);
        }
        updateSendButtonState();
        messageInput.requestFocus();
    }

    @FXML
    private void onAttachFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Chọn tệp để chia sẻ trong nhóm");
        File file = chooser.showOpenDialog(attachFileButton.getScene().getWindow());
        if (file != null) {
            setPendingAttachment(file);
        }
    }

    @FXML
    private void onCancelAttachment() {
        this.pendingAttachmentFile = null;
        if (attachmentBar != null) {
            attachmentBar.setVisible(false);
            attachmentBar.setManaged(false);
        }
        updateSendButtonState();
    }

    @FXML
    private void onOpenDocumentsVault() {
        if (mainController != null) {
            mainController.navigateTo(NavigationTab.TRANSFERS);
        }
    }

    private void renderChannels() {
        if (channelListContainer == null) return;
        channelListContainer.getChildren().clear();
        if (chat.channels().isEmpty()) {
            Label none = new Label("Chưa có kênh nào");
            none.getStyleClass().add("empty-label-subtle");
            channelListContainer.getChildren().add(none);
            return;
        }
        for (Domain.Channel channel : chat.channels()) {
            Button item = new Button("# " + channel.name());
            item.setMaxWidth(Double.MAX_VALUE);
            item.setAlignment(Pos.CENTER_LEFT);
            item.getStyleClass().add("channel-item-button");
            if (chat.activeChannel() != null && chat.activeChannel().id().equals(channel.id())) {
                item.getStyleClass().add("active");
            }
            item.setOnAction(e -> chat.selectChannel(channel));
            channelListContainer.getChildren().add(item);
        }
    }

    private void renderActive(Domain.Channel channel) {
        if (channelTitleLabel != null) channelTitleLabel.setText(channel == null ? "Chọn một kênh" : "# " + channel.name());
        if (channelDescriptionLabel != null) {
            channelDescriptionLabel.setText(channel == null
                    ? "Chọn một kênh của nhóm để xem trao đổi và tệp đính kèm."
                    : (channel.description() == null ? "" : channel.description()));
        }
        messageInput.setDisable(channel == null);
        if (attachFileButton != null) attachFileButton.setDisable(channel == null);
    }

    private void updateCreateControls() {
        if (createChannelBox == null) return;
        boolean allowed = chat != null && chat.canManageChannels();
        createChannelBox.setVisible(allowed);
        createChannelBox.setManaged(allowed);
    }

    private void renderConnection() {
        if (connectionStatusLabel == null || connectionDot == null) return;
        RealtimeConnectionStatus s = chat.connectionStatusProperty().get();
        connectionDot.setFill(Color.web(s.getColorHex()));
        connectionStatusLabel.setText(s.getLabel());
        if (loadOlderButton != null) {
            loadOlderButton.setVisible(chat.hasOlderMessages());
            loadOlderButton.setManaged(chat.hasOlderMessages());
        }
    }

    private void renderMembers() {
        if (membersListContainer == null || workspaces == null) return;
        membersListContainer.getChildren().clear();

        var members = workspaces.getActiveMembers();
        if (membersHeaderLabel != null) {
            membersHeaderLabel.setText("THÀNH VIÊN (" + members.size() + ")");
        }

        if (members.isEmpty()) {
            Label none = new Label("Chưa có thành viên");
            none.getStyleClass().add("empty-label-subtle");
            membersListContainer.getChildren().add(none);
            return;
        }

        String myUserId = workspaces.currentUserId();

        for (Domain.WorkspaceMember m : members) {
            HBox row = new HBox(8);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setStyle("-fx-padding: 4px 6px; -fx-background-radius: 6px;");

            boolean isMe = m.userId().equals(myUserId);

            // Online status indicator
            Circle dot = new Circle(4);
            dot.setFill(isMe ? Color.web("#10b981") : Color.web("#94a3b8"));

            VBox info = new VBox(2);
            HBox nameRow = new HBox(4);
            nameRow.setAlignment(Pos.CENTER_LEFT);

            Label name = new Label(m.nameOrFallback() + (isMe ? " (Bạn)" : ""));
            name.setStyle("-fx-font-size: 12px; -fx-font-weight: " + (isMe ? "bold" : "normal") + "; -fx-text-fill: #1e293b;");

            Label role = new Label(m.roleBadge());
            boolean isOwner = m.role() == Domain.MemberRole.OWNER;
            boolean isAdmin = m.role() == Domain.MemberRole.ADMIN;
            role.setStyle("-fx-font-size: 9px; -fx-padding: 1px 4px; -fx-background-radius: 4px; " +
                    (isOwner ? "-fx-background-color: #fef3c7; -fx-text-fill: #b45309;" :
                     isAdmin ? "-fx-background-color: #e0e7ff; -fx-text-fill: #4338ca;" :
                               "-fx-background-color: #f1f5f9; -fx-text-fill: #64748b;"));

            nameRow.getChildren().addAll(name, role);

            Label statusText = new Label(isMe ? "● Đang trực tuyến" : "Thiết bị mạng LAN");
            statusText.setStyle("-fx-font-size: 10px; -fx-text-fill: " + (isMe ? "#059669" : "#64748b") + ";");

            info.getChildren().addAll(nameRow, statusText);
            row.getChildren().addAll(dot, info);
            membersListContainer.getChildren().add(row);
        }
    }

    private void renderMessages(boolean toBottom) {
        messagesContainer.getChildren().clear();
        boolean empty = chat.messages().isEmpty();
        emptyMessagesContainer.setVisible(empty);
        emptyMessagesContainer.setManaged(empty);

        for (Domain.Message m : chat.messages()) {
            messagesContainer.getChildren().add(row(m));
        }

        renderConnection();
        if (toBottom) {
            Platform.runLater(() -> chatScrollPane.setVvalue(1));
        }
    }

    private HBox row(Domain.Message m) {
        boolean mine = m.senderId().equals(workspaces.currentUserId());
        HBox row = new HBox(8);
        row.setAlignment(mine ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        row.getStyleClass().add(mine ? "message-row-local" : "message-row-remote");

        VBox bubble = new VBox(5);
        bubble.getStyleClass().add(mine ? "message-bubble-local" : "message-bubble-remote");
        bubble.setMaxWidth(560);

        Label sender = new Label(senderName(m.senderId()) + (mine ? " (Bạn)" : ""));
        sender.getStyleClass().add(mine ? "message-sender-mine" : "message-sender");
        bubble.getChildren().add(sender);

        if (m.replyToId() != null) {
            Domain.Message parent = find(m.replyToId());
            Label ref = new Label("↪ " + (parent == null ? "Trả lời tin nhắn" : senderName(parent.senderId()) + ": " + preview(parent.content())));
            ref.getStyleClass().add("message-reply-reference");
            bubble.getChildren().add(ref);
        }

        if (m.isDeleted()) {
            Label d = new Label("Tin nhắn đã bị xóa");
            d.getStyleClass().add("message-deleted");
            bubble.getChildren().add(d);
        } else if (editing != null && editing.id().equals(m.id())) {
            TextField input = new TextField(m.content());
            input.getStyleClass().add("text-input-field");
            HBox actions = new HBox(6);
            Button save = new Button("Lưu"), cancel = new Button("Huỷ");
            save.getStyleClass().add("primary-button");
            cancel.getStyleClass().add("secondary-button");
            save.setOnAction(e -> saveEdit(m, input.getText()));
            cancel.setOnAction(e -> {
                editing = null;
                renderMessages(false);
            });
            actions.getChildren().addAll(save, cancel);
            bubble.getChildren().addAll(input, actions);
        } else {
            // Check for large file share tag
            FileUtils.FileShareInfo fileInfo = FileUtils.parseFileShareInfo(m.content());
            if (fileInfo != null) {
                // If there is accompanying text, display it first
                if (fileInfo.accompanyingText() != null && !fileInfo.accompanyingText().isBlank()) {
                    Label accompanying = new Label(fileInfo.accompanyingText());
                    accompanying.setWrapText(true);
                    accompanying.getStyleClass().add("message-text");
                    bubble.getChildren().add(accompanying);
                }
                // Render beautiful large file attachment card
                bubble.getChildren().add(createFileAttachmentCard(fileInfo, mine, m));
            } else {
                Label content = new Label(m.content());
                content.setWrapText(true);
                content.getStyleClass().add("message-text");
                bubble.getChildren().add(content);
            }
        }

        HBox meta = new HBox(8);
        meta.setAlignment(Pos.CENTER_LEFT);
        Label time = new Label(DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(m.createdAt()));
        time.getStyleClass().add("message-time");
        meta.getChildren().add(time);

        if (m.isEdited() && !m.isDeleted()) {
            Label e = new Label("đã chỉnh sửa");
            e.getStyleClass().add("message-time");
            meta.getChildren().add(e);
        }

        if (!m.isDeleted()) {
            Button reply = action("Trả lời", e -> beginReply(m));
            meta.getChildren().add(reply);
            if (mine) {
                meta.getChildren().addAll(
                        action("Sửa", e -> {
                            editing = m;
                            replyingTo = null;
                            if (replyBar != null) {
                                replyBar.setVisible(false);
                                replyBar.setManaged(false);
                            }
                            renderMessages(false);
                        }),
                        action("Xóa", e -> delete(m))
                );
            }
        }

        bubble.getChildren().add(meta);
        row.getChildren().add(bubble);
        return row;
    }

    /**
     * Builds an interactive Card for inline shared large files.
     */
    private VBox createFileAttachmentCard(FileUtils.FileShareInfo fileInfo, boolean mine, Domain.Message msg) {
        VBox card = new VBox(6);
        card.setStyle("-fx-background-color: " + (mine ? "#f0fdf4" : "#ffffff") + ";" +
                "-fx-padding: 10px 14px; -fx-background-radius: 8px; " +
                "-fx-border-color: " + (mine ? "#bbf7d0" : "#cbd5e1") + "; -fx-border-radius: 8px;");

        HBox top = new HBox(10);
        top.setAlignment(Pos.CENTER_LEFT);

        Label icon = new Label(fileInfo.icon());
        icon.setStyle("-fx-font-size: 26px;");

        VBox fileDetails = new VBox(2);
        Label fileName = new Label(fileInfo.fileName());
        fileName.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: #0f172a;");
        fileName.setWrapText(true);

        HBox metaLine = new HBox(8);
        metaLine.setAlignment(Pos.CENTER_LEFT);
        Label size = new Label(fileInfo.formattedSize());
        size.setStyle("-fx-font-size: 11.5px; -fx-font-weight: bold; -fx-text-fill: #059669;");
        Label hash = new Label("SHA-256: " + fileInfo.shortHash());
        hash.setStyle("-fx-font-size: 10px; -fx-text-fill: #64748b;");
        metaLine.getChildren().addAll(size, hash);

        fileDetails.getChildren().addAll(fileName, metaLine);
        HBox.setHgrow(fileDetails, Priority.ALWAYS);
        top.getChildren().addAll(icon, fileDetails);

        card.getChildren().add(top);

        // Action controls
        HBox actionBox = new HBox(8);
        actionBox.setAlignment(Pos.CENTER_LEFT);

        if (mine) {
            Label senderBadge = new Label("📤 Đã chia sẻ từ thiết bị này (Sẵn sàng truyền P2P)");
            senderBadge.setStyle("-fx-font-size: 11px; -fx-text-fill: #047857; -fx-font-style: italic;");
            actionBox.getChildren().add(senderBadge);
        } else {
            Button downloadBtn = new Button("⬇ Tải về (P2P LAN)");
            downloadBtn.getStyleClass().add("primary-button-small");
            downloadBtn.setStyle("-fx-font-size: 11px; -fx-padding: 4px 10px;");

            Label statusLabel = new Label("");
            statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #2563eb;");

            downloadBtn.setOnAction(e -> {
                downloadBtn.setDisable(true);
                statusLabel.setText("Đang yêu cầu kết nối P2P...");
                initiateFileDownload(fileInfo, msg.senderId(), statusLabel, downloadBtn);
            });

            actionBox.getChildren().addAll(downloadBtn, statusLabel);
        }

        card.getChildren().add(actionBox);
        return card;
    }

    private void initiateFileDownload(FileUtils.FileShareInfo fileInfo, String uploaderUserId, Label statusLabel, Button downloadBtn) {
        if (mainController == null || mainController.getMainApp() == null) {
            statusLabel.setText("Lỗi: Không tìm thấy dịch vụ truyền tệp");
            downloadBtn.setDisable(false);
            return;
        }

        var filesService = mainController.getMainApp().getFileManagerStateService();
        var lanTransfer = mainController.getMainApp().getLanTransferService();

        if (filesService == null) {
            statusLabel.setText("Đang kết nối máy chủ tệp...");
            downloadBtn.setDisable(false);
            return;
        }

        // Check uploader's available devices
        filesService.devicesFor(uploaderUserId).whenComplete((devices, err) -> Platform.runLater(() -> {
            if (err != null || devices == null || devices.isEmpty()) {
                statusLabel.setText("Người gửi hiện không có thiết bị online trên LAN.");
                downloadBtn.setDisable(false);
                return;
            }

            var reachable = devices.stream().filter(Domain.Device::lanAvailable).findFirst();
            if (reachable.isEmpty()) {
                statusLabel.setText("Thiết bị của người gửi đang bận hoặc offline.");
                downloadBtn.setDisable(false);
                return;
            }

            Domain.Device device = reachable.get();
            statusLabel.setText("Đã gửi yêu cầu tới " + device.label() + ". Đang truyền...");

            // Create a domain file instance for transfer coordination
            String hash = fileInfo.sha256();
            String validHash = (hash != null && hash.matches("[0-9a-fA-F]{64}")) ? hash : null;
            Domain.File fileRecord = new Domain.File(
                    fileInfo.fileId(),
                    workspaces.getActiveWorkspace().id(),
                    null,
                    uploaderUserId,
                    fileInfo.fileName(),
                    "application/octet-stream",
                    java.math.BigInteger.valueOf(fileInfo.sizeBytes()),
                    validHash,
                    java.time.Instant.now(),
                    java.time.Instant.now()
            );

            filesService.requestExistingTransfer(fileRecord, uploaderUserId, device.id())
                    .whenComplete((res, transferErr) -> Platform.runLater(() -> {
                        if (transferErr != null) {
                            statusLabel.setText("Yêu cầu tải thất bại: " + transferErr.getMessage());
                            downloadBtn.setDisable(false);
                        } else {
                            statusLabel.setText("Đã bắt đầu truyền tải! Xem ở mục Transfers.");
                            downloadBtn.setText("Đang tải...");
                        }
                    }));
        }));
    }

    private Button action(String text, javafx.event.EventHandler<javafx.event.ActionEvent> handler) {
        Button b = new Button(text);
        b.getStyleClass().add("message-action-button");
        b.setOnAction(handler);
        return b;
    }

    private void beginReply(Domain.Message m) {
        replyingTo = m;
        editing = null;
        if (replyLabel != null) {
            replyLabel.setText("Trả lời " + senderName(m.senderId()) + ": “" + preview(m.content()) + "”");
        }
        if (replyBar != null) {
            replyBar.setVisible(true);
            replyBar.setManaged(true);
        }
        messageInput.requestFocus();
    }

    private void saveEdit(Domain.Message m, String content) {
        if (content != null && !content.trim().isEmpty()) {
            chat.edit(m, content).thenRun(() -> Platform.runLater(() -> {
                editing = null;
                renderMessages(false);
            }));
        }
    }

    private void delete(Domain.Message m) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "Bạn có chắc chắn muốn xóa tin nhắn này?", ButtonType.CANCEL, ButtonType.OK);
        a.setHeaderText("Xóa tin nhắn");
        if (a.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            chat.delete(m);
        }
    }

    private String senderName(String id) {
        if (id.equals(workspaces.currentUserId()) && workspaces.authStateService().user() != null) {
            return workspaces.authStateService().user().displayName();
        }
        for (Domain.WorkspaceMember member : workspaces.getActiveMembers()) {
            if (id.equals(member.userId())) {
                return member.nameOrFallback();
            }
        }
        return "Thành viên";
    }

    private Domain.Message find(String id) {
        for (Domain.Message m : chat.messages()) {
            if (m.id().equals(id)) return m;
        }
        return null;
    }

    private static String preview(String text) {
        if (text == null || text.isBlank()) return "Tin nhắn đã bị xóa";
        FileUtils.FileShareInfo info = FileUtils.parseFileShareInfo(text);
        if (info != null) return "[Tệp: " + info.fileName() + " (" + info.formattedSize() + ")]";
        return text.length() > 80 ? text.substring(0, 77) + "…" : text;
    }

    private boolean nearBottom() {
        return chatScrollPane.getVvalue() > .90;
    }

    private void preserveOlderPosition() {
        double oldHeight = olderContentHeight, oldValue = olderScrollValue;
        olderContentHeight = -1;
        Platform.runLater(() -> {
            double viewport = chatScrollPane.getViewportBounds().getHeight();
            double newHeight = messagesContainer.getHeight();
            double oldOffset = oldValue * Math.max(0, oldHeight - viewport);
            chatScrollPane.setVvalue(Math.min(1, (oldOffset + Math.max(0, newHeight - oldHeight)) / Math.max(1, newHeight - viewport)));
        });
    }

    @FXML
    private void onSendMessage() {
        String text = messageInput.getText();
        boolean hasText = text != null && !text.trim().isEmpty();
        boolean hasFile = pendingAttachmentFile != null;

        if (!hasText && !hasFile) return;

        String reply = replyingTo == null ? null : replyingTo.id();
        messageInput.clear();

        if (hasFile) {
            File fileToShare = pendingAttachmentFile;
            onCancelAttachment();

            // Register file with local LAN engine and workspace file service
            offerAndSendFileMessage(fileToShare, hasText ? text.trim() : "", reply);
        } else {
            chat.send(text.trim(), reply).thenRun(() -> Platform.runLater(this::onCancelReply));
        }
    }

    private void offerAndSendFileMessage(File file, String accompanyingText, String replyToId) {
        if (mainController != null && mainController.getMainApp() != null) {
            LanTransferService lanTransfer = mainController.getMainApp().getLanTransferService();
            if (lanTransfer != null) {
                // Announce file to local LAN transfer service so peers can download it
                lanTransfer.offerLocalFile(file);
            }

            FileManagerStateService fileManager = mainController.getMainApp().getFileManagerStateService();
            if (fileManager != null && workspaces.getActiveWorkspace() != null) {
                // Register in workspace file vault in background
                Path filePath = file.toPath();
                var ws = workspaces.getActiveWorkspace();
                var currentFolder = fileManager.currentFolder();
                String folderId = currentFolder != null ? currentFolder.id() : null;

                // Build inline file share tag
                String fileId = UUID.randomUUID().toString();
                String fileTag = FileUtils.buildFileShareTag(fileId, file.getName(), file.length(), "");
                String fullMessage = accompanyingText.isEmpty() ? fileTag : fileTag + " " + accompanyingText;

                chat.send(fullMessage, replyToId).thenRun(() -> Platform.runLater(this::onCancelReply));
                return;
            }
        }

        // Fallback file tag if fileManager not initialized
        String fileTag = FileUtils.buildFileShareTag(UUID.randomUUID().toString(), file.getName(), file.length(), "");
        String fullMessage = accompanyingText.isEmpty() ? fileTag : fileTag + " " + accompanyingText;
        chat.send(fullMessage, replyToId).thenRun(() -> Platform.runLater(this::onCancelReply));
    }

    @FXML
    private void onCancelReply() {
        replyingTo = null;
        if (replyBar != null) {
            replyBar.setVisible(false);
            replyBar.setManaged(false);
        }
    }

    @FXML
    private void onCreateChannel() {
        String name = channelNameInput.getText();
        if (name == null || name.trim().isEmpty()) return;
        createChannelButton.setDisable(true);
        chat.createChannel(name, channelDescriptionInput.getText()).thenAccept(c -> Platform.runLater(() -> {
            channelNameInput.clear();
            channelDescriptionInput.clear();
            createChannelButton.setDisable(false);
            chat.selectChannel(c);
        })).exceptionally(e -> {
            Platform.runLater(() -> createChannelButton.setDisable(false));
            return null;
        });
    }

    @FXML
    private void onLoadOlder() {
        olderContentHeight = messagesContainer.getHeight();
        olderScrollValue = chatScrollPane.getVvalue();
        chat.loadOlder();
    }
}
