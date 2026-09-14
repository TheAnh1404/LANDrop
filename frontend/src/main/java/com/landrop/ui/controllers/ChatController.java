package com.landrop.ui.controllers;

import com.landrop.ui.cloud.model.Domain;
import com.landrop.ui.models.RealtimeConnectionStatus;
import com.landrop.ui.workspace.chat.services.WorkspaceChatStateService;
import com.landrop.ui.workspace.services.WorkspaceStateService;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/** Workspace channel chat; it deliberately contains no LAN peer/device identity. */
public final class ChatController {
    @FXML private Label workspaceNameLabel, channelTitleLabel, channelDescriptionLabel, connectionStatusLabel, errorLabel, replyLabel;
    @FXML private VBox channelListContainer, createChannelBox, messagesContainer, emptyMessagesContainer;
    @FXML private TextField channelNameInput, channelDescriptionInput, messageInput;
    @FXML private Button createChannelButton, loadOlderButton, sendButton;
    @FXML private Circle connectionDot;
    @FXML private ScrollPane chatScrollPane;
    @FXML private HBox replyBar;
    private WorkspaceChatStateService chat; private WorkspaceStateService workspaces;
    private Domain.Message replyingTo, editing; private boolean initialHistory = true;
    private double olderContentHeight = -1, olderScrollValue;

    /** Compatibility hook for the legacy FXML smoke test; production always uses workspace chat state. */
    @Deprecated
    public void init(com.landrop.ui.services.UIStateService ignored, MainController mainController) {
        workspaceNameLabel.setText("No workspace selected");
        renderActive(null);
        connectionStatusLabel.setText("Disconnected");
    }

    public void init(WorkspaceChatStateService chat, WorkspaceStateService workspaces, MainController ignored) {
        this.chat = chat; this.workspaces = workspaces;
        sendButton.disableProperty().bind(messageInput.textProperty().map(v -> v == null || v.trim().isEmpty()));
        chat.channels().addListener((ListChangeListener<Domain.Channel>) c -> Platform.runLater(this::renderChannels));
        chat.messages().addListener((ListChangeListener<Domain.Message>) c -> Platform.runLater(() -> {
            renderMessages(!initialHistory && nearBottom());
            if (olderContentHeight >= 0) preserveOlderPosition();
        }));
        chat.activeChannelProperty().addListener((o, old, next) -> Platform.runLater(() -> { initialHistory = true; renderActive(next); renderMessages(false); }));
        chat.connectionStatusProperty().addListener((o, old, next) -> Platform.runLater(this::renderConnection));
        chat.loadingHistoryProperty().addListener((o, old, now) -> Platform.runLater(() -> { if (!now && initialHistory) { renderMessages(true); initialHistory = false; } }));
        chat.errorMessageProperty().addListener((o, old, now) -> Platform.runLater(() -> errorLabel.setText(now == null ? "" : now)));
        workspaces.activeWorkspaceProperty().addListener((o, old, now) -> Platform.runLater(() -> { workspaceNameLabel.setText(now == null ? "No workspace selected" : now.name()); renderChannels(); updateCreateControls(); }));
        workspaces.currentUserMemberProperty().addListener((o, old, now) -> Platform.runLater(this::updateCreateControls));
        workspaceNameLabel.setText(workspaces.getActiveWorkspace() == null ? "No workspace selected" : workspaces.getActiveWorkspace().name());
        renderChannels(); renderActive(chat.activeChannel()); renderConnection(); updateCreateControls();
    }
    private void renderChannels() {
        channelListContainer.getChildren().clear();
        if (chat.channels().isEmpty()) { Label none = new Label("No channels yet"); none.getStyleClass().add("empty-label-subtle"); channelListContainer.getChildren().add(none); return; }
        for (Domain.Channel channel : chat.channels()) { Button item = new Button("# " + channel.name()); item.setMaxWidth(Double.MAX_VALUE); item.setAlignment(Pos.CENTER_LEFT); item.getStyleClass().add("channel-item-button"); if (chat.activeChannel() != null && chat.activeChannel().id().equals(channel.id())) item.getStyleClass().add("active"); item.setOnAction(e -> chat.selectChannel(channel)); channelListContainer.getChildren().add(item); }
    }
    private void renderActive(Domain.Channel channel) { channelTitleLabel.setText(channel == null ? "Select a channel" : "# " + channel.name()); channelDescriptionLabel.setText(channel == null ? "Choose a workspace channel to view its persisted conversation." : (channel.description() == null ? "" : channel.description())); messageInput.setDisable(channel == null); }
    private void updateCreateControls() { boolean allowed = chat.canManageChannels(); createChannelBox.setVisible(allowed); createChannelBox.setManaged(allowed); }
    private void renderConnection() { RealtimeConnectionStatus s = chat.connectionStatusProperty().get(); connectionDot.setFill(Color.web(s.getColorHex())); connectionStatusLabel.setText(s.getLabel()); loadOlderButton.setVisible(chat.hasOlderMessages()); loadOlderButton.setManaged(chat.hasOlderMessages()); }
    private void renderMessages(boolean toBottom) { messagesContainer.getChildren().clear(); boolean empty = chat.messages().isEmpty(); emptyMessagesContainer.setVisible(empty); emptyMessagesContainer.setManaged(empty); for (Domain.Message m : chat.messages()) messagesContainer.getChildren().add(row(m)); renderConnection(); if (toBottom) Platform.runLater(() -> chatScrollPane.setVvalue(1)); }
    private HBox row(Domain.Message m) {
        boolean mine = m.senderId().equals(workspaces.currentUserId()); HBox row = new HBox(8); row.setAlignment(mine ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT); row.getStyleClass().add(mine ? "message-row-local" : "message-row-remote"); VBox bubble = new VBox(4); bubble.getStyleClass().add(mine ? "message-bubble-local" : "message-bubble-remote");
        Label sender = new Label(senderName(m.senderId()) + (mine ? " (You)" : "")); sender.getStyleClass().add(mine ? "message-sender-mine" : "message-sender"); bubble.getChildren().add(sender);
        if (m.replyToId() != null) { Domain.Message parent = find(m.replyToId()); Label ref = new Label("↪ " + (parent == null ? "Reply to a message" : senderName(parent.senderId()) + ": " + preview(parent.content()))); ref.getStyleClass().add("message-reply-reference"); bubble.getChildren().add(ref); }
        if (m.isDeleted()) { Label d = new Label("Message deleted"); d.getStyleClass().add("message-deleted"); bubble.getChildren().add(d); }
        else if (editing != null && editing.id().equals(m.id())) { TextField input = new TextField(m.content()); input.getStyleClass().add("text-input-field"); HBox actions = new HBox(6); Button save = new Button("Save"), cancel = new Button("Cancel"); save.getStyleClass().add("primary-button"); cancel.getStyleClass().add("secondary-button"); save.setOnAction(e -> saveEdit(m, input.getText())); cancel.setOnAction(e -> { editing = null; renderMessages(false); }); actions.getChildren().addAll(save, cancel); bubble.getChildren().addAll(input, actions); }
        else { Label content = new Label(m.content()); content.setWrapText(true); content.getStyleClass().add("message-text"); bubble.getChildren().add(content); }
        HBox meta = new HBox(8); Label time = new Label(java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault()).format(m.createdAt())); time.getStyleClass().add("message-time"); meta.getChildren().add(time); if (m.isEdited() && !m.isDeleted()) { Label e = new Label("edited"); e.getStyleClass().add("message-time"); meta.getChildren().add(e); }
        if (!m.isDeleted()) { Button reply = action("Reply", e -> beginReply(m)); meta.getChildren().add(reply); if (mine) { meta.getChildren().addAll(action("Edit", e -> { editing = m; replyingTo = null; replyBar.setVisible(false); replyBar.setManaged(false); renderMessages(false); }), action("Delete", e -> delete(m))); } }
        bubble.getChildren().add(meta); row.getChildren().add(bubble); return row;
    }
    private Button action(String text, javafx.event.EventHandler<javafx.event.ActionEvent> handler) { Button b = new Button(text); b.getStyleClass().add("message-action-button"); b.setOnAction(handler); return b; }
    private void beginReply(Domain.Message m) { replyingTo = m; editing = null; replyLabel.setText("Replying to " + senderName(m.senderId()) + ": “" + preview(m.content()) + "”"); replyBar.setVisible(true); replyBar.setManaged(true); messageInput.requestFocus(); }
    private void saveEdit(Domain.Message m, String content) { if (content != null && !content.trim().isEmpty()) chat.edit(m, content).thenRun(() -> Platform.runLater(() -> { editing = null; renderMessages(false); })); }
    private void delete(Domain.Message m) { Alert a = new Alert(Alert.AlertType.CONFIRMATION, "Delete this message?", ButtonType.CANCEL, ButtonType.OK); a.setHeaderText("Delete message"); if (a.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) chat.delete(m); }
    private String senderName(String id) { if (id.equals(workspaces.currentUserId()) && workspaces.authStateService().user() != null) return workspaces.authStateService().user().displayName(); for (Domain.WorkspaceMember member : workspaces.getActiveMembers()) if (id.equals(member.userId())) return member.nameOrFallback(); return "User"; }
    private Domain.Message find(String id) { for (Domain.Message m : chat.messages()) if (m.id().equals(id)) return m; return null; }
    private static String preview(String text) { if (text == null || text.isBlank()) return "Message deleted"; return text.length() > 80 ? text.substring(0, 77) + "…" : text; }
    private boolean nearBottom() { return chatScrollPane.getVvalue() > .90; }
    private void preserveOlderPosition() {
        double oldHeight = olderContentHeight, oldValue = olderScrollValue; olderContentHeight = -1;
        Platform.runLater(() -> {
            double viewport = chatScrollPane.getViewportBounds().getHeight(), newHeight = messagesContainer.getHeight();
            double oldOffset = oldValue * Math.max(0, oldHeight - viewport);
            chatScrollPane.setVvalue(Math.min(1, (oldOffset + Math.max(0, newHeight - oldHeight)) / Math.max(1, newHeight - viewport)));
        });
    }
    @FXML private void onSendMessage() { String text = messageInput.getText(); if (text == null || text.trim().isEmpty()) return; String reply = replyingTo == null ? null : replyingTo.id(); messageInput.clear(); chat.send(text, reply).thenRun(() -> Platform.runLater(this::onCancelReply)); }
    @FXML private void onCancelReply() { replyingTo = null; replyBar.setVisible(false); replyBar.setManaged(false); }
    @FXML private void onCreateChannel() { String name = channelNameInput.getText(); if (name == null || name.trim().isEmpty()) return; createChannelButton.setDisable(true); chat.createChannel(name, channelDescriptionInput.getText()).thenAccept(c -> Platform.runLater(() -> { channelNameInput.clear(); channelDescriptionInput.clear(); createChannelButton.setDisable(false); chat.selectChannel(c); })).exceptionally(e -> { Platform.runLater(() -> createChannelButton.setDisable(false)); return null; }); }
    @FXML private void onLoadOlder() { olderContentHeight = messagesContainer.getHeight(); olderScrollValue = chatScrollPane.getVvalue(); chat.loadOlder(); }
}
