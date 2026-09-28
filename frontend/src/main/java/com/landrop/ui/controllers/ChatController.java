package com.landrop.ui.controllers;

import com.landrop.transfer.FileTransfer;
import com.landrop.discovery.PeerInfo;
import com.landrop.transfer.FileTransferDirection;
import com.landrop.transfer.FileTransferStatus;
import com.landrop.transfer.FileTransferUtils;
import com.landrop.ui.cloud.model.Domain;
import com.landrop.ui.lan.LanTransferService;
import com.landrop.ui.models.NavigationTab;
import com.landrop.ui.models.RealtimeConnectionStatus;
import com.landrop.ui.models.UIMessage;
import com.landrop.ui.services.UIStateService;
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
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.DirectoryChooser;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enhanced Group Collaboration Chat Controller.
 * Provides real-time team messaging, inline large file sharing via LAN P2P with live progress HUD,
 * integrated Group Documents Vault tab, drag-and-drop file attachments, and active workspace members directory.
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

    // Group Documents Vault FXML controls
    @FXML private TabPane mainTabPane;
    @FXML private Tab chatTab, vaultTab;
    private Tab directTab;
    private ListView<PeerInfo> directPeerList;
    private VBox directMessagesContainer;
    private TextField directMessageInput;
    private Label directPeerTitle, directErrorLabel;
    @FXML private Label vaultBreadcrumbLabel, vaultDetailsLabel, vaultStatusLabel;
    @FXML private TextField vaultSearchField;
    @FXML private Button vaultRootButton, vaultNewFolderButton, vaultUploadButton, vaultRefreshButton;
    @FXML private VBox vaultFoldersContainer, vaultFilesContainer, vaultEmptyContainer;

    // Transfer Status HUD controls
    @FXML private VBox transferHudPanel, hudTransfersContainer;
    @FXML private Label totalBandwidthLabel, emptyHudLabel;

    private WorkspaceChatStateService chat;
    private WorkspaceStateService workspaces;
    private MainController mainController;
    private LanTransferService lanTransfer;
    private FileManagerStateService files;
    private UIStateService directState;

    private Domain.Message replyingTo, editing;
    private boolean initialHistory = true;
    private double olderContentHeight = -1, olderScrollValue;
    private long lastHudRenderMs = 0;

    // File attachment staging
    private File pendingAttachmentFile;

    // Track active inline cards for reactive real-time progress updates
    private record AttachmentCardBindings(ProgressBar progressBar, Label statusLabel, Button downloadBtn, Button openDirBtn) {}
    private final Map<String, AttachmentCardBindings> activeAttachmentCards = new ConcurrentHashMap<>();

    /** Shows direct LAN conversations beside Workspace channels. */
    public void initDirect(UIStateService state, MainController controller, boolean offline) {
        directState = Objects.requireNonNull(state);
        mainController = controller;
        buildDirectTab();
        directPeerList.setItems(state.getDiscoveredPeers());
        directPeerList.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(PeerInfo peer, boolean empty) {
                super.updateItem(peer, empty);
                setText(empty || peer == null ? null : peer.getDisplayName() + "  •  " + peer.getHost());
            }
        });
        directPeerList.getSelectionModel().selectedItemProperty().addListener((obs, old, peer) -> {
            if (peer == null) return;
            if (state.isP2pConnected() && peer.getDisplayName().equals(state.activeRemotePeerNameProperty().get())) {
                renderDirectMessages();
                return;
            }
            directPeerTitle.setText("Đang kết nối " + peer.getDisplayName() + "...");
            state.connectToPeer(peer).whenComplete((ok, error) -> Platform.runLater(() -> {
                directErrorLabel.setText(error == null ? "" : error.getMessage());
                if (error == null) renderDirectMessages();
            }));
        });
        state.getActiveChatMessages().addListener((ListChangeListener<UIMessage>) c -> renderDirectMessages());
        state.getTransfers().addListener((ListChangeListener<FileTransfer>) c -> renderDirectMessages());
        state.addTransferCompletionListener(transfer -> renderDirectMessages());
        state.activeRemotePeerNameProperty().addListener((obs, old, name) -> Platform.runLater(() -> {
            if (name != null && !name.isBlank()) {
                directPeerTitle.setText("Tin nhắn trực tiếp: " + name);
                state.getDiscoveredPeers().stream().filter(p -> name.equals(p.getDisplayName())).findFirst()
                        .ifPresent(p -> directPeerList.getSelectionModel().select(p));
                renderDirectMessages();
            }
        }));
        if (offline) {
            chatTab.setDisable(true);
            vaultTab.setDisable(true);
            createChannelBox.setVisible(false);
            createChannelBox.setManaged(false);
            workspaceNameLabel.setText("Quick LAN Share");
            mainTabPane.getSelectionModel().select(directTab);
        }
        renderDirectMessages();
    }

    public void showDirectChat() {
        if (mainTabPane != null && directTab != null) mainTabPane.getSelectionModel().select(directTab);
    }

    private void renderDirectMessages() {
        if (directState == null || directMessagesContainer == null) return;
        directMessagesContainer.getChildren().clear();
        for (UIMessage message : directState.getActiveChatMessages()) {
            VBox bubble = new VBox(3);
            bubble.getStyleClass().add(message.isLocal() ? "message-bubble-local" : "message-bubble-remote");
            Label sender = new Label(message.senderLabel() + "  " + message.formattedTime());
            sender.getStyleClass().add("message-sender");
            Label body = new Label(message.content());
            body.setWrapText(true);
            Button copy = new Button("Sao chép");
            copy.getStyleClass().add("message-action-button");
            copy.setOnAction(e -> {
                ClipboardContent content = new ClipboardContent();
                content.putString(message.content());
                Clipboard.getSystemClipboard().setContent(content);
            });
            bubble.getChildren().addAll(sender, body, copy);
            HBox row = new HBox(bubble);
            row.setAlignment(message.isLocal() ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
            directMessagesContainer.getChildren().add(row);
        }
        int imagePreviews = 0;
        String activePeer = directState.activeRemotePeerNameProperty().get();
        for (FileTransfer transfer : directState.getTransfers()) {
            if (transfer.getDirection() != FileTransferDirection.INCOMING
                    || transfer.getStatus() != FileTransferStatus.COMPLETED
                    || activePeer == null || !activePeer.equals(transfer.getRemotePeerName())
                    || transfer.getLocalPath() == null || !java.nio.file.Files.isRegularFile(transfer.getLocalPath())) {
                continue;
            }
            String fileName = transfer.getFileName().toLowerCase(Locale.ROOT);
            if (!fileName.endsWith(".png") && !fileName.endsWith(".jpg")
                    && !fileName.endsWith(".jpeg") && !fileName.endsWith(".webp")) continue;
            if (imagePreviews++ >= 10) break;
            VBox card = new VBox(5);
            card.getStyleClass().add("message-bubble-remote");
            card.getChildren().add(new Label("Ảnh đã nhận: " + transfer.getFileName()));
            Image image = new Image(transfer.getLocalPath().toUri().toString(), 360, 240, true, true, true);
            ImageView preview = new ImageView(image);
            preview.setFitWidth(360);
            preview.setFitHeight(240);
            preview.setPreserveRatio(true);
            card.getChildren().add(preview);
            image.errorProperty().addListener((obs, old, failed) -> {
                if (failed) card.getChildren().setAll(new Label("Không thể xem trước " + transfer.getFileName()));
            });
            directMessagesContainer.getChildren().add(card);
        }
    }

    @FXML private void onDirectSend() {
        if (directState == null || directMessageInput == null) return;
        String message = directMessageInput.getText();
        if (message == null || message.isBlank()) return;
        directState.sendDirectMessage(message.trim()).whenComplete((ok, error) -> Platform.runLater(() -> {
            if (error == null) directMessageInput.clear();
            directErrorLabel.setText(error == null ? "" : error.getMessage());
        }));
    }

    @FXML private void onDirectAttachFile() {
        if (!requireDirectConnection()) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Chọn file để gửi qua LAN");
        File file = chooser.showOpenDialog(directMessageInput.getScene().getWindow());
        if (file != null) directState.offerFile(file).exceptionally(error -> {
            Platform.runLater(() -> directErrorLabel.setText(error.getMessage()));
            return null;
        });
    }

    @FXML private void onDirectAttachFolder() {
        if (!requireDirectConnection()) return;
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Chọn thư mục để gửi qua LAN dưới dạng ZIP");
        File folder = chooser.showDialog(directMessageInput.getScene().getWindow());
        if (folder != null) directState.offerFolder(folder.toPath()).exceptionally(error -> {
            Platform.runLater(() -> directErrorLabel.setText(error.getMessage()));
            return null;
        });
    }

    private boolean requireDirectConnection() {
        if (directState != null && directState.isP2pConnected()) return true;
        directErrorLabel.setText("Hãy kết nối với một thiết bị trước khi gửi.");
        return false;
    }

    private void buildDirectTab() {
        directTab = new Tab("LAN Direct Messages");
        directPeerList = new ListView<>();
        directPeerList.setPrefWidth(200);
        VBox peers = new VBox(6, new Label("Thiết bị trong LAN"), directPeerList);
        peers.setPrefWidth(200);
        VBox.setVgrow(directPeerList, Priority.ALWAYS);

        directPeerTitle = new Label("Chọn một thiết bị để trò chuyện");
        directPeerTitle.getStyleClass().add("card-title");
        directErrorLabel = new Label();
        directErrorLabel.getStyleClass().add("chat-error-label");
        directErrorLabel.setWrapText(true);
        directMessagesContainer = new VBox(8);
        directMessagesContainer.setStyle("-fx-padding: 12px;");
        ScrollPane history = new ScrollPane(directMessagesContainer);
        history.setFitToWidth(true);
        VBox.setVgrow(history, Priority.ALWAYS);

        Button attach = new Button("Gửi file");
        attach.getStyleClass().add("secondary-button");
        attach.setOnAction(e -> onDirectAttachFile());
        Button folder = new Button("Gửi thư mục");
        folder.getStyleClass().add("secondary-button");
        folder.setOnAction(e -> onDirectAttachFolder());
        Button clipboard = new Button("Gửi clipboard");
        clipboard.getStyleClass().add("secondary-button");
        clipboard.setOnAction(e -> onDirectSendClipboard());
        directMessageInput = new TextField();
        directMessageInput.setPromptText("Nhập tin nhắn trực tiếp...");
        directMessageInput.setOnAction(e -> onDirectSend());
        HBox.setHgrow(directMessageInput, Priority.ALWAYS);
        Button send = new Button("Gửi");
        send.getStyleClass().add("primary-button");
        send.setOnAction(e -> onDirectSend());
        HBox composer = new HBox(8, attach, folder, clipboard, directMessageInput, send);
        VBox conversation = new VBox(8, directPeerTitle, directErrorLabel, history, composer);
        HBox.setHgrow(conversation, Priority.ALWAYS);
        HBox content = new HBox(10, peers, conversation);
        content.setStyle("-fx-padding: 8px;");
        directTab.setContent(content);
        mainTabPane.getTabs().add(directTab);
    }

    private void onDirectSendClipboard() {
        if (!requireDirectConnection()) return;
        String text = Clipboard.getSystemClipboard().getString();
        if (text == null || text.isBlank()) {
            directErrorLabel.setText("Clipboard không có văn bản để gửi.");
            return;
        }
        directState.sendDirectMessage(text).whenComplete((ok, error) -> Platform.runLater(() ->
                directErrorLabel.setText(error == null ? "" : error.getMessage())));
    }

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

        if (mainController != null && mainController.getMainApp() != null) {
            this.lanTransfer = mainController.getMainApp().getLanTransferService();
            this.files = mainController.getMainApp().getFileManagerStateService();
        }

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
            renderVaultFiles();
        }));
        workspaces.currentUserMemberProperty().addListener((o, old, now) -> Platform.runLater(() -> {
            updateCreateControls();
            renderMembers();
        }));

        // Vault listeners
        if (files != null) {
            files.folders().addListener((ListChangeListener<Domain.Folder>) c -> Platform.runLater(this::renderVaultFiles));
            files.files().addListener((ListChangeListener<Domain.File>) c -> Platform.runLater(this::renderVaultFiles));
            files.breadcrumbs().addListener((ListChangeListener<Domain.Folder>) c -> Platform.runLater(this::renderVaultBreadcrumbs));
            files.errorProperty().addListener((o, old, err) -> Platform.runLater(() -> {
                if (vaultStatusLabel != null && err != null) vaultStatusLabel.setText(err);
            }));
            files.successProperty().addListener((o, old, ok) -> Platform.runLater(() -> {
                if (vaultStatusLabel != null && ok != null) vaultStatusLabel.setText(ok);
            }));
        }
        if (vaultSearchField != null) {
            vaultSearchField.textProperty().addListener((o, old, text) -> renderVaultFiles());
        }

        // LAN Transfer & HUD listeners
        if (lanTransfer != null) {
            lanTransfer.transfers().addListener((ListChangeListener<FileTransfer>) c -> {
                scheduleHudRender();
                Platform.runLater(this::updateChatAttachmentTransfers);
            });
            lanTransfer.setOnTransferUpdated(t -> {
                scheduleHudRender();
                Platform.runLater(this::updateChatAttachmentTransfers);
            });
        }

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
        renderVaultBreadcrumbs();
        renderVaultFiles();
        renderTransferHud();
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
        if (mainTabPane != null && vaultTab != null) {
            mainTabPane.getSelectionModel().select(vaultTab);
            renderVaultFiles();
        } else if (mainController != null) {
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
                // Render beautiful large file attachment card with live progress controls
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
     * Builds an interactive Card for inline shared large files with live progress bar and open directory action.
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

            ProgressBar progressBar = new ProgressBar(0);
            progressBar.setPrefWidth(160);
            progressBar.setMaxHeight(8);
            progressBar.setVisible(false);
            progressBar.setManaged(false);

            Label statusLabel = new Label("");
            statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #2563eb;");

            Button openDirBtn = new Button("📁 Mở thư mục chứa");
            openDirBtn.getStyleClass().add("secondary-button");
            openDirBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3px 8px;");
            openDirBtn.setVisible(false);
            openDirBtn.setManaged(false);

            // Register card bindings for reactive transfer progress updates
            activeAttachmentCards.put(fileInfo.fileName().toLowerCase(),
                    new AttachmentCardBindings(progressBar, statusLabel, downloadBtn, openDirBtn));

            FileTransfer matched = findTransferForFile(fileInfo.fileName());
            if (matched != null) {
                if (matched.isCompleted() && matched.getLocalPath() != null) {
                    downloadBtn.setVisible(false);
                    downloadBtn.setManaged(false);
                    progressBar.setProgress(1.0);
                    progressBar.setVisible(true);
                    progressBar.setManaged(true);
                    statusLabel.setText("✅ Hoàn tất (Đã xác minh SHA-256)");
                    statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #059669; -fx-font-weight: bold;");
                    openDirBtn.setVisible(true);
                    openDirBtn.setManaged(true);
                    openDirBtn.setOnAction(e -> openFileLocation(matched.getLocalPath()));
                } else if (matched.getStatus() == FileTransferStatus.TRANSFERRING) {
                    downloadBtn.setDisable(true);
                    downloadBtn.setText("Đang tải...");
                    progressBar.setProgress(matched.getProgress());
                    progressBar.setVisible(true);
                    progressBar.setManaged(true);
                    statusLabel.setText(matched.getFormattedSpeed() + " • " + matched.getProgressPercent() + "%");
                }
            }

            downloadBtn.setOnAction(e -> {
                downloadBtn.setDisable(true);
                progressBar.setVisible(true);
                progressBar.setManaged(true);
                statusLabel.setText("Đang yêu cầu kết nối P2P...");
                initiateFileDownload(fileInfo, msg.senderId(), statusLabel, downloadBtn, progressBar, openDirBtn);
            });

            actionBox.getChildren().addAll(downloadBtn, progressBar, statusLabel, openDirBtn);
        }

        card.getChildren().add(actionBox);
        return card;
    }

    private FileTransfer findTransferForFile(String fileName) {
        if (lanTransfer == null || fileName == null) return null;
        for (FileTransfer t : lanTransfer.transfers()) {
            if (fileName.equalsIgnoreCase(t.getFileName())) {
                return t;
            }
        }
        return null;
    }

    private void updateChatAttachmentTransfers() {
        if (lanTransfer == null || activeAttachmentCards.isEmpty()) return;
        for (FileTransfer t : lanTransfer.transfers()) {
            AttachmentCardBindings b = activeAttachmentCards.get(t.getFileName().toLowerCase());
            if (b != null) {
                if (t.getStatus() == FileTransferStatus.TRANSFERRING) {
                    b.progressBar().setProgress(t.getProgress());
                    b.progressBar().setVisible(true);
                    b.progressBar().setManaged(true);
                    b.statusLabel().setText(t.getFormattedSpeed() + " • " + t.getProgressPercent() + "%");
                    b.statusLabel().setStyle("-fx-font-size: 11px; -fx-text-fill: #2563eb;");
                    b.downloadBtn().setDisable(true);
                    b.downloadBtn().setText("Đang tải...");
                } else if (t.isCompleted() && t.getLocalPath() != null) {
                    b.progressBar().setProgress(1.0);
                    b.progressBar().setVisible(true);
                    b.progressBar().setManaged(true);
                    b.statusLabel().setText("✅ Hoàn tất (Đã xác minh SHA-256)");
                    b.statusLabel().setStyle("-fx-font-size: 11px; -fx-text-fill: #059669; -fx-font-weight: bold;");
                    b.downloadBtn().setVisible(false);
                    b.downloadBtn().setManaged(false);
                    b.openDirBtn().setVisible(true);
                    b.openDirBtn().setManaged(true);
                    b.openDirBtn().setOnAction(e -> openFileLocation(t.getLocalPath()));
                } else if (t.getStatus() == FileTransferStatus.INTERRUPTED) {
                    b.statusLabel().setText("Đã tạm dừng (" + t.getProgressPercent() + "%)");
                    b.statusLabel().setStyle("-fx-font-size: 11px; -fx-text-fill: #d97706;");
                } else if (t.getStatus() == FileTransferStatus.FAILED) {
                    b.statusLabel().setText("Tải thất bại: " + t.getErrorMessage());
                    b.statusLabel().setStyle("-fx-font-size: 11px; -fx-text-fill: #dc2626;");
                    b.downloadBtn().setDisable(false);
                    b.downloadBtn().setText("⬇ Thử lại");
                }
            }
        }
    }

    private void initiateFileDownload(FileUtils.FileShareInfo fileInfo, String uploaderUserId, Label statusLabel, Button downloadBtn, ProgressBar progressBar, Button openDirBtn) {
        if (mainController == null || mainController.getMainApp() == null) {
            statusLabel.setText("Lỗi: Không tìm thấy dịch vụ truyền tệp");
            downloadBtn.setDisable(false);
            return;
        }

        var filesService = mainController.getMainApp().getFileManagerStateService();

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
                            statusLabel.setText("Đã bắt đầu truyền tải! Xem ở khay tiến trình.");
                            downloadBtn.setText("Đang tải...");
                        }
                    }));
        }));
    }

    private void openFileLocation(Path path) {
        try {
            if (path != null && java.nio.file.Files.exists(path)) {
                File file = path.toFile();
                if (Desktop.isDesktopSupported()) {
                    File parent = file.getParentFile();
                    Desktop.getDesktop().open(parent != null ? parent : file);
                }
            }
        } catch (Exception ignored) {}
    }

    // --------------------------------------------------------------------------
    // Transfer Status HUD / Drawer
    // --------------------------------------------------------------------------

    private void scheduleHudRender() {
        long now = System.currentTimeMillis();
        if (now - lastHudRenderMs < 100) return;
        lastHudRenderMs = now;
        Platform.runLater(this::renderTransferHud);
    }

    private void renderTransferHud() {
        if (hudTransfersContainer == null || lanTransfer == null) return;
        hudTransfersContainer.getChildren().clear();

        var transfers = lanTransfer.transfers();
        if (transfers.isEmpty()) {
            if (emptyHudLabel != null) {
                emptyHudLabel.setVisible(true);
                emptyHudLabel.setManaged(true);
                hudTransfersContainer.getChildren().add(emptyHudLabel);
            }
            if (totalBandwidthLabel != null) {
                totalBandwidthLabel.setText("0.0 MB/s");
            }
            return;
        }

        double totalSpeed = 0.0;
        for (FileTransfer t : transfers) {
            if (t.getStatus() == FileTransferStatus.TRANSFERRING) {
                totalSpeed += t.getSpeedBytesPerSec();
            }
            hudTransfersContainer.getChildren().add(createHudTransferRow(t));
        }

        if (totalBandwidthLabel != null) {
            totalBandwidthLabel.setText(FileTransferUtils.formatSpeed(totalSpeed));
        }
    }

    private HBox createHudTransferRow(FileTransfer t) {
        boolean outgoing = t.getDirection() == FileTransferDirection.OUTGOING;
        HBox row = new HBox(6);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setStyle("-fx-padding: 4px 6px; -fx-background-color: #ffffff; -fx-background-radius: 4px; -fx-border-color: #e2e8f0; -fx-border-radius: 4px;");

        Label dirIcon = new Label(outgoing ? "📤" : "📥");
        dirIcon.setStyle("-fx-font-size: 11px;");

        VBox info = new VBox(1);
        HBox.setHgrow(info, Priority.ALWAYS);
        String displayName = t.getFileName();
        if (displayName.length() > 16) {
            displayName = displayName.substring(0, 13) + "…";
        }
        Label name = new Label(displayName);
        name.setStyle("-fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: #1e293b;");

        ProgressBar bar = new ProgressBar(t.getStatus() == FileTransferStatus.TRANSFERRING ? t.getProgress() : (t.getStatus() == FileTransferStatus.COMPLETED ? 1.0 : 0.0));
        bar.setPrefWidth(90);
        bar.setMaxHeight(6);

        String statusStr;
        if (t.getStatus() == FileTransferStatus.TRANSFERRING) {
            statusStr = t.getProgressPercent() + "% • " + (t.getSpeedBytesPerSec() > 0 ? t.getFormattedSpeed() : "");
        } else if (t.getStatus() == FileTransferStatus.COMPLETED) {
            statusStr = "Hoàn tất";
        } else if (t.getStatus() == FileTransferStatus.INTERRUPTED) {
            statusStr = "Đã tạm dừng";
        } else {
            statusStr = t.getStatus().getLabel();
        }
        Label statusLabel = new Label(statusStr);
        statusLabel.setStyle("-fx-font-size: 9.5px; -fx-text-fill: #64748b;");

        info.getChildren().addAll(name, bar, statusLabel);

        HBox actions = new HBox(2);
        actions.setAlignment(Pos.CENTER_RIGHT);

        if (t.getStatus() == FileTransferStatus.PENDING && !outgoing) {
            Button acceptBtn = smallIcon("✓", "Chấp nhận", e -> lanTransfer.accept(t.getTransferId()));
            Button rejectBtn = smallIcon("✕", "Từ chối", e -> lanTransfer.reject(t.getTransferId(), "User declined"));
            actions.getChildren().addAll(acceptBtn, rejectBtn);
        } else if (t.getStatus() == FileTransferStatus.TRANSFERRING) {
            Button pauseBtn = smallIcon("⏸", "Tạm dừng", e -> lanTransfer.pause(t.getTransferId()));
            Button cancelBtn = smallIcon("✕", "Huỷ", e -> lanTransfer.cancel(t.getTransferId()));
            actions.getChildren().addAll(pauseBtn, cancelBtn);
        } else if (t.getStatus() == FileTransferStatus.INTERRUPTED) {
            Button resumeBtn = smallIcon("▶", "Tiếp tục tải", e -> lanTransfer.resume(t.getTransferId()));
            Button cancelBtn = smallIcon("✕", "Huỷ", e -> lanTransfer.cancel(t.getTransferId()));
            actions.getChildren().addAll(resumeBtn, cancelBtn);
        } else if (t.isCompleted() && t.getLocalPath() != null) {
            Button openBtn = smallIcon("📁", "Mở thư mục", e -> openFileLocation(t.getLocalPath()));
            actions.getChildren().add(openBtn);
        } else if (!t.getStatus().isTerminal() && t.getStatus() != FileTransferStatus.VERIFYING) {
            Button cancelBtn = smallIcon("✕", "Huỷ", e -> lanTransfer.cancel(t.getTransferId()));
            actions.getChildren().add(cancelBtn);
        }

        row.getChildren().addAll(dirIcon, info, actions);
        return row;
    }

    private Button smallIcon(String text, String tooltip, javafx.event.EventHandler<javafx.event.ActionEvent> h) {
        Button b = new Button(text);
        b.setTooltip(new Tooltip(tooltip));
        b.setStyle("-fx-font-size: 10px; -fx-padding: 2px 5px; -fx-background-radius: 3px;");
        b.setOnAction(h);
        return b;
    }

    // --------------------------------------------------------------------------
    // Group Documents Vault Tab
    // --------------------------------------------------------------------------

    private void renderVaultBreadcrumbs() {
        if (vaultBreadcrumbLabel == null || files == null) return;
        StringBuilder text = new StringBuilder("📁 Kho tài liệu");
        for (Domain.Folder f : files.breadcrumbs()) {
            text.append("  /  ").append(f.name());
        }
        vaultBreadcrumbLabel.setText(text.toString());
    }

    private void renderVaultFiles() {
        if (vaultFoldersContainer == null || vaultFilesContainer == null || files == null) return;
        vaultFoldersContainer.getChildren().clear();
        vaultFilesContainer.getChildren().clear();

        String query = vaultSearchField != null && vaultSearchField.getText() != null
                ? vaultSearchField.getText().trim().toLowerCase() : "";

        var folderList = files.folders().stream()
                .filter(f -> query.isEmpty() || f.name().toLowerCase().contains(query))
                .toList();

        var fileList = files.files().stream()
                .filter(f -> query.isEmpty() || f.name().toLowerCase().contains(query))
                .toList();

        boolean empty = folderList.isEmpty() && fileList.isEmpty();
        if (vaultEmptyContainer != null) {
            vaultEmptyContainer.setVisible(empty);
            vaultEmptyContainer.setManaged(empty);
        }

        for (Domain.Folder folder : folderList) {
            vaultFoldersContainer.getChildren().add(createVaultFolderRow(folder));
        }

        for (Domain.File file : fileList) {
            vaultFilesContainer.getChildren().add(createVaultFileRow(file));
        }

        if (vaultDetailsLabel != null) {
            vaultDetailsLabel.setText(folderList.size() + " thư mục, " + fileList.size() + " tệp tin");
        }
    }

    private HBox createVaultFolderRow(Domain.Folder folder) {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setStyle("-fx-padding: 6px 10px; -fx-background-color: #f8fafc; -fx-background-radius: 6px; -fx-border-color: #e2e8f0; -fx-border-radius: 6px;");

        Button open = new Button("📁 " + folder.name());
        open.setStyle("-fx-font-weight: bold; -fx-font-size: 12px; -fx-background-color: transparent; -fx-cursor: hand;");
        open.setOnAction(e -> files.openFolder(folder));

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);

        Button deleteBtn = small("Xoá", e -> {
            Alert a = new Alert(Alert.AlertType.CONFIRMATION, "Bạn có muốn xoá thư mục ‘" + folder.name() + "’ không?", ButtonType.CANCEL, ButtonType.OK);
            a.setHeaderText("Xoá thư mục");
            if (a.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
                files.deleteFolder(folder);
            }
        });

        row.getChildren().addAll(open, gap, deleteBtn);
        return row;
    }

    private HBox createVaultFileRow(Domain.File file) {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setStyle("-fx-padding: 6px 10px; -fx-background-color: #ffffff; -fx-background-radius: 6px; -fx-border-color: #e2e8f0; -fx-border-radius: 6px;");

        Label icon = new Label(FileUtils.getFileIcon(file.name(), file.mimeType()));
        icon.setStyle("-fx-font-size: 16px;");

        VBox meta = new VBox(2);
        HBox.setHgrow(meta, Priority.ALWAYS);

        Label name = new Label(file.name());
        name.setStyle("-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: #1e293b;");

        Label sub = new Label(FileUtils.formatSize(file.sizeBytes()) + " • " + senderName(file.uploadedById()) + " • " +
                DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault()).format(file.createdAt()));
        sub.setStyle("-fx-font-size: 10.5px; -fx-text-fill: #64748b;");

        meta.getChildren().addAll(name, sub);

        Button downloadBtn = new Button("⬇ Tải về (LAN)");
        downloadBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3px 8px;");
        downloadBtn.getStyleClass().add("primary-button-small");
        downloadBtn.setOnAction(e -> {
            downloadBtn.setDisable(true);
            downloadBtn.setText("Đang gửi yêu cầu...");
            requestVaultFileDownload(file, downloadBtn);
        });

        Button deleteBtn = small("Xoá", e -> {
            Alert a = new Alert(Alert.AlertType.CONFIRMATION, "Bạn có muốn xoá tệp ‘" + file.name() + "’ không?", ButtonType.CANCEL, ButtonType.OK);
            a.setHeaderText("Xoá tệp");
            if (a.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
                files.deleteFile(file);
            }
        });

        row.getChildren().addAll(icon, meta, downloadBtn, deleteBtn);
        return row;
    }

    private void requestVaultFileDownload(Domain.File file, Button downloadBtn) {
        if (files == null) {
            downloadBtn.setDisable(false);
            downloadBtn.setText("⬇ Tải về (LAN)");
            return;
        }

        // Locate uploader's device
        files.devicesFor(file.uploadedById()).whenComplete((devices, err) -> Platform.runLater(() -> {
            if (err != null || devices == null || devices.isEmpty()) {
                if (vaultStatusLabel != null) vaultStatusLabel.setText("Người tải lên hiện không có thiết bị online trên LAN.");
                downloadBtn.setDisable(false);
                downloadBtn.setText("⬇ Tải về (LAN)");
                return;
            }

            var reachable = devices.stream().filter(Domain.Device::lanAvailable).findFirst();
            if (reachable.isEmpty()) {
                if (vaultStatusLabel != null) vaultStatusLabel.setText("Thiết bị của người gửi đang bận hoặc offline.");
                downloadBtn.setDisable(false);
                downloadBtn.setText("⬇ Tải về (LAN)");
                return;
            }

            Domain.Device device = reachable.get();
            files.requestExistingTransfer(file, file.uploadedById(), device.id())
                    .whenComplete((res, transferErr) -> Platform.runLater(() -> {
                        if (transferErr != null) {
                            if (vaultStatusLabel != null) vaultStatusLabel.setText("Yêu cầu tải thất bại: " + transferErr.getMessage());
                            downloadBtn.setDisable(false);
                            downloadBtn.setText("⬇ Tải về (LAN)");
                        } else {
                            if (vaultStatusLabel != null) vaultStatusLabel.setText("Đã bắt đầu truyền tải " + file.name() + " qua LAN!");
                            downloadBtn.setText("Đã yêu cầu");
                        }
                    }));
        }));
    }

    @FXML
    private void onVaultRoot() {
        if (files != null) files.openRoot();
    }

    @FXML
    private void onVaultNewFolder() {
        if (files == null) return;
        TextInputDialog d = new TextInputDialog();
        d.setTitle("Thư mục mới");
        d.setHeaderText("Tạo thư mục mới trong Kho tài liệu");
        d.setContentText("Tên thư mục:");
        d.showAndWait().ifPresent(name -> {
            if (name != null && !name.trim().isEmpty()) {
                files.createFolder(name.trim());
            }
        });
    }

    @FXML
    private void onVaultUpload() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Chọn tệp để chia sẻ lên Kho tài liệu");
        File file = chooser.showOpenDialog(vaultUploadButton.getScene().getWindow());
        if (file == null || !file.exists()) return;

        if (lanTransfer != null) {
            lanTransfer.offerLocalFile(file);
        }

        if (files != null && workspaces != null && workspaces.getActiveWorkspace() != null) {
            var otherMembers = workspaces.getActiveMembers().stream()
                    .filter(m -> !m.userId().equals(workspaces.currentUserId()))
                    .toList();
            if (otherMembers.isEmpty()) {
                if (vaultStatusLabel != null) vaultStatusLabel.setText("Tệp đã sẵn sàng chia sẻ trên LAN: " + file.getName());
                return;
            }

            // Show recipient dialog
            ChoiceDialog<Domain.WorkspaceMember> dialog = new ChoiceDialog<>(otherMembers.get(0), otherMembers);
            dialog.setTitle("Chia sẻ tệp lên Kho tài liệu");
            dialog.setHeaderText("Chọn thành viên nhận thông báo tệp");
            dialog.setContentText("Thành viên:");
            dialog.showAndWait().ifPresent(recipient -> {
                files.devicesFor(recipient.userId()).whenComplete((devices, err) -> Platform.runLater(() -> {
                    if (err != null || devices == null || devices.isEmpty()) {
                        if (vaultStatusLabel != null) vaultStatusLabel.setText("Đã chia sẻ LAN: " + file.getName());
                        return;
                    }
                    var reachable = devices.stream().filter(Domain.Device::lanAvailable).findFirst();
                    String devId = reachable.map(Domain.Device::id).orElse(devices.get(0).id());
                    files.registerAndRequest(file.toPath(), recipient.userId(), devId);
                    if (vaultStatusLabel != null) vaultStatusLabel.setText("Đã tải lên Kho tài liệu: " + file.getName());
                }));
            });
        }
    }

    @FXML
    private void onVaultRefresh() {
        if (files != null) files.refresh();
    }

    // --------------------------------------------------------------------------
    // Chat Message Helpers
    // --------------------------------------------------------------------------

    private Button action(String text, javafx.event.EventHandler<javafx.event.ActionEvent> handler) {
        Button b = new Button(text);
        b.getStyleClass().add("message-action-button");
        b.setOnAction(handler);
        return b;
    }

    private Button small(String text, javafx.event.EventHandler<javafx.event.ActionEvent> h) {
        Button b = new Button(text);
        b.getStyleClass().add("message-action-button");
        b.setOnAction(h);
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
            LanTransferService localLanTransfer = mainController.getMainApp().getLanTransferService();
            if (localLanTransfer != null) {
                // Announce file to local LAN transfer service so peers can download it
                localLanTransfer.offerLocalFile(file);
            }

            FileManagerStateService fileManager = mainController.getMainApp().getFileManagerStateService();
            if (fileManager != null && workspaces.getActiveWorkspace() != null) {
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
