package com.landrop.ui.controllers;

import com.landrop.ui.cloud.model.Domain;
import com.landrop.ui.workspace.services.WorkspaceStateService;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.Optional;

/**
 * Controller for the Workspace management view (Phase 16B).
 * Coordinates workspace listing, switching, creation, member administration, and settings.
 */
public class WorkspaceController {

    // Status Banners
    @FXML private HBox errorBanner;
    @FXML private Label errorMessageLabel;
    @FXML private HBox successBanner;
    @FXML private Label successMessageLabel;

    // Left Sidebar (Workspace List)
    @FXML private VBox workspaceListContainer;
    @FXML private Button openCreateDialogBtn;

    // Right Details View
    @FXML private ScrollPane activeWorkspaceDetailScroll;
    @FXML private Label workspaceTitleLabel;
    @FXML private Label currentUserRoleBadge;
    @FXML private Label memberCountBadge;
    @FXML private Label workspaceDescriptionLabel;
    @FXML private Button leaveWorkspaceBtn;
    @FXML private Button deleteWorkspaceBtn;

    // Members Section
    @FXML private VBox inviteMemberContainer;
    @FXML private TextField inviteIdentifierField;
    @FXML private ComboBox<Domain.MemberRole> inviteRoleComboBox;
    @FXML private Button inviteSubmitBtn;
    @FXML private VBox memberListContainer;

    // Settings Section
    @FXML private VBox settingsContainer;
    @FXML private TextField settingsNameField;
    @FXML private TextArea settingsDescriptionField;
    @FXML private Label settingsRoleNoteLabel;
    @FXML private Button saveSettingsBtn;

    // Empty State
    @FXML private VBox emptyStateContainer;

    // Create Modal
    @FXML private StackPane createModalOverlay;
    @FXML private TextField createNameField;
    @FXML private TextArea createDescriptionField;
    @FXML private Label createModalErrorLabel;
    @FXML private Button createSubmitBtn;

    // Loading Overlay
    @FXML private StackPane loadingOverlay;

    private WorkspaceStateService stateService;
    private MainController mainController;

    public void initOffline(MainController mainController) {
        this.mainController = mainController;
        if (workspaceTitleLabel != null) workspaceTitleLabel.setText("Chế độ Ngoại tuyến (Offline P2P)");
        if (workspaceDescriptionLabel != null) {
            workspaceDescriptionLabel.setText("Tính năng Workspace và Chat nhóm yêu cầu máy chủ LANDrop Server. Bạn có thể sử dụng các tab Peers và Transfers để gửi nhận file trực tiếp trong mạng LAN.");
        }
        if (emptyStateContainer != null) {
            emptyStateContainer.setVisible(false);
            emptyStateContainer.setManaged(false);
        }
        if (inviteMemberContainer != null) {
            inviteMemberContainer.setVisible(false);
            inviteMemberContainer.setManaged(false);
        }
        if (settingsContainer != null) {
            settingsContainer.setVisible(false);
            settingsContainer.setManaged(false);
        }
        if (leaveWorkspaceBtn != null) {
            leaveWorkspaceBtn.setVisible(false);
            leaveWorkspaceBtn.setManaged(false);
        }
        if (deleteWorkspaceBtn != null) {
            deleteWorkspaceBtn.setVisible(false);
            deleteWorkspaceBtn.setManaged(false);
        }
    }

    public void init(WorkspaceStateService stateService, MainController mainController) {
        this.stateService = stateService;
        this.mainController = mainController;

        // Initialize Role ComboBox
        inviteRoleComboBox.getItems().setAll(Domain.MemberRole.MEMBER, Domain.MemberRole.ADMIN);
        inviteRoleComboBox.setValue(Domain.MemberRole.MEMBER);

        // Bind State Listeners
        bindStateService();

        // Initial Load
        stateService.loadWorkspaces();
    }

    private void bindStateService() {
        // 1. Workspaces List
        stateService.getWorkspaces().addListener((ListChangeListener<Domain.Workspace>) change -> {
            Platform.runLater(this::renderWorkspaceList);
        });

        // 2. Active Workspace
        stateService.activeWorkspaceProperty().addListener((obs, oldVal, newVal) -> {
            Platform.runLater(() -> renderActiveWorkspace(newVal));
        });

        // 3. Current User Role in Active Workspace
        stateService.currentUserMemberProperty().addListener((obs, oldVal, newVal) -> {
            Platform.runLater(this::updateRoleAwareControls);
        });

        // 4. Members List
        stateService.getActiveMembers().addListener((ListChangeListener<Domain.WorkspaceMember>) change -> {
            Platform.runLater(this::renderMemberList);
        });

        // 5. Loading State
        stateService.loadingProperty().addListener((obs, oldVal, newVal) -> {
            Platform.runLater(() -> {
                loadingOverlay.setVisible(newVal);
                loadingOverlay.setManaged(newVal);
            });
        });

        // 6. Error and Success Messages
        stateService.errorMessageProperty().addListener((obs, oldVal, newVal) -> {
            Platform.runLater(() -> {
                boolean hasError = newVal != null && !newVal.isBlank();
                errorBanner.setVisible(hasError);
                errorBanner.setManaged(hasError);
                errorMessageLabel.setText(newVal != null ? newVal : "");
            });
        });

        stateService.successMessageProperty().addListener((obs, oldVal, newVal) -> {
            Platform.runLater(() -> {
                boolean hasSuccess = newVal != null && !newVal.isBlank();
                successBanner.setVisible(hasSuccess);
                successBanner.setManaged(hasSuccess);
                successMessageLabel.setText(newVal != null ? newVal : "");
            });
        });

        // Render initial view
        renderWorkspaceList();
        renderActiveWorkspace(stateService.getActiveWorkspace());
    }

    // --- Render Left Workspace List (16B.3) ---

    private void renderWorkspaceList() {
        workspaceListContainer.getChildren().clear();

        var workspaces = stateService.getWorkspaces();
        Domain.Workspace active = stateService.getActiveWorkspace();

        if (workspaces.isEmpty()) {
            Label noWorkspacesLabel = new Label("No workspaces yet");
            noWorkspacesLabel.getStyleClass().add("empty-label-subtle");
            workspaceListContainer.getChildren().add(noWorkspacesLabel);
            return;
        }

        for (Domain.Workspace ws : workspaces) {
            boolean isActive = active != null && active.id().equals(ws.id());

            Button wsButton = new Button();
            wsButton.setMaxWidth(Double.MAX_VALUE);
            wsButton.setAlignment(Pos.CENTER_LEFT);

            HBox row = new HBox(10);
            row.setAlignment(Pos.CENTER_LEFT);

            Label iconLabel = new Label("🏢");
            iconLabel.getStyleClass().add("workspace-item-icon");

            VBox textCol = new VBox(2);
            textCol.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(textCol, Priority.ALWAYS);

            Label nameLabel = new Label(ws.name());
            nameLabel.getStyleClass().add("workspace-item-name");

            String desc = ws.description();
            if (desc == null || desc.isBlank()) desc = "Personal / Team Workspace";
            Label descLabel = new Label(desc);
            descLabel.getStyleClass().add("workspace-item-desc");

            textCol.getChildren().addAll(nameLabel, descLabel);

            row.getChildren().addAll(iconLabel, textCol);

            if (isActive) {
                Label activeIndicator = new Label("ACTIVE");
                activeIndicator.getStyleClass().add("workspace-active-tag");
                row.getChildren().add(activeIndicator);
                wsButton.getStyleClass().addAll("workspace-item-btn", "active");
            } else {
                wsButton.getStyleClass().add("workspace-item-btn");
            }

            wsButton.setGraphic(row);
            wsButton.setOnAction(e -> stateService.selectWorkspace(ws));

            workspaceListContainer.getChildren().add(wsButton);
        }
    }

    // --- Render Active Workspace (16B.6) ---

    private void renderActiveWorkspace(Domain.Workspace workspace) {
        if (workspace == null) {
            activeWorkspaceDetailScroll.setVisible(false);
            activeWorkspaceDetailScroll.setManaged(false);
            emptyStateContainer.setVisible(true);
            emptyStateContainer.setManaged(true);
            return;
        }

        activeWorkspaceDetailScroll.setVisible(true);
        activeWorkspaceDetailScroll.setManaged(true);
        emptyStateContainer.setVisible(false);
        emptyStateContainer.setManaged(false);

        workspaceTitleLabel.setText(workspace.name());
        workspaceDescriptionLabel.setText(
                (workspace.description() != null && !workspace.description().isBlank())
                        ? workspace.description()
                        : "No description provided for this workspace."
        );

        settingsNameField.setText(workspace.name());
        settingsDescriptionField.setText(workspace.description() != null ? workspace.description() : "");

        renderWorkspaceList(); // Refresh active indicator
        renderMemberList();
        updateRoleAwareControls();
    }

    // --- Role-Aware Permissions UI (16B.9, 16B.11, 16B.12) ---

    private void updateRoleAwareControls() {
        Domain.MemberRole role = stateService.getCurrentRole();
        Domain.Workspace ws = stateService.getActiveWorkspace();

        if (role != null) {
            currentUserRoleBadge.setText(role.name());
            currentUserRoleBadge.getStyleClass().setAll("role-badge-" + role.name().toLowerCase());
        } else {
            currentUserRoleBadge.setText("MEMBER");
            currentUserRoleBadge.getStyleClass().setAll("role-badge-member");
        }

        boolean isOwner = stateService.isOwner();
        boolean isAdmin = stateService.isAdmin();
        boolean canManage = stateService.canManageMembers();

        // 1. Delete Workspace (Owner only)
        deleteWorkspaceBtn.setVisible(isOwner);
        deleteWorkspaceBtn.setManaged(isOwner);

        // 2. Leave Workspace: restricted for Owner
        if (isOwner) {
            leaveWorkspaceBtn.setText("Owner Cannot Leave");
            leaveWorkspaceBtn.setDisable(false);
        } else {
            leaveWorkspaceBtn.setText("Leave Workspace");
            leaveWorkspaceBtn.setDisable(false);
        }

        // 3. Member Invite Section (Admin/Owner only)
        inviteMemberContainer.setVisible(canManage);
        inviteMemberContainer.setManaged(canManage);

        // 4. Basic Settings Editability (Admin/Owner only)
        settingsNameField.setEditable(canManage);
        settingsDescriptionField.setEditable(canManage);
        saveSettingsBtn.setVisible(canManage);
        saveSettingsBtn.setManaged(canManage);
        settingsRoleNoteLabel.setVisible(!canManage);
        settingsRoleNoteLabel.setManaged(!canManage);
    }

    // --- Render Member List (16B.7, 16B.9, 16B.10) ---

    private void renderMemberList() {
        memberListContainer.getChildren().clear();

        var members = stateService.getActiveMembers();
        memberCountBadge.setText(members.size() + (members.size() == 1 ? " member" : " members"));

        if (members.isEmpty()) {
            Label noMembersLabel = new Label("No members in this workspace.");
            noMembersLabel.getStyleClass().add("empty-label-subtle");
            memberListContainer.getChildren().add(noMembersLabel);
            return;
        }

        String currentUserId = stateService.currentUserId();
        boolean canManage = stateService.canManageMembers();
        boolean isOwner = stateService.isOwner();

        for (Domain.WorkspaceMember member : members) {
            HBox row = new HBox(12);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("member-row-card");

            // Avatar badge
            StackPane avatar = new StackPane();
            avatar.getStyleClass().add("member-avatar");
            Label initialsLabel = new Label(member.initials());
            initialsLabel.getStyleClass().add("member-avatar-text");
            avatar.getChildren().add(initialsLabel);

            // Details
            VBox details = new VBox(2);
            details.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(details, Priority.ALWAYS);

            HBox nameBox = new HBox(6);
            nameBox.setAlignment(Pos.CENTER_LEFT);

            Label nameLabel = new Label(member.nameOrFallback());
            nameLabel.getStyleClass().add("member-name");
            nameBox.getChildren().add(nameLabel);

            if (member.userId().equals(currentUserId)) {
                Label youBadge = new Label("(You)");
                youBadge.getStyleClass().add("you-badge");
                nameBox.getChildren().add(youBadge);
            }

            Label emailLabel = new Label(member.email() != null ? member.email() : "ID: " + member.userId());
            emailLabel.getStyleClass().add("member-email");

            details.getChildren().addAll(nameBox, emailLabel);

            // Role Badge
            Label roleBadge = new Label(member.roleBadge());
            roleBadge.getStyleClass().add("role-badge-" + member.roleBadge().toLowerCase());

            row.getChildren().addAll(avatar, details, roleBadge);

            // Role Management Actions (16B.9, 16B.10)
            boolean isTargetSelf = member.userId().equals(currentUserId);
            boolean isTargetOwner = member.role() == Domain.MemberRole.OWNER;

            if (canManage && !isTargetSelf && !isTargetOwner) {
                // Owner can manage anyone; Admin can manage regular members
                boolean allowedToModify = isOwner || member.role() == Domain.MemberRole.MEMBER;

                if (allowedToModify) {
                    // Role changer combo
                    ComboBox<Domain.MemberRole> roleCombo = new ComboBox<>();
                    roleCombo.getItems().setAll(Domain.MemberRole.MEMBER, Domain.MemberRole.ADMIN);
                    roleCombo.setValue(member.role());
                    roleCombo.getStyleClass().add("role-combo-small");
                    roleCombo.setOnAction(e -> {
                        Domain.MemberRole newRole = roleCombo.getValue();
                        if (newRole != null && newRole != member.role()) {
                            stateService.updateMemberRole(member.userId(), newRole);
                        }
                    });

                    // Remove button (16B.10)
                    Button removeBtn = new Button("Remove");
                    removeBtn.getStyleClass().add("danger-button-small");
                    removeBtn.setOnAction(e -> confirmRemoveMember(member));

                    row.getChildren().addAll(roleCombo, removeBtn);
                }
            }

            memberListContainer.getChildren().add(row);
        }
    }

    // --- Action Handlers ---

    @FXML
    private void onOpenCreateWorkspaceClicked() {
        createNameField.setText("");
        createDescriptionField.setText("");
        createModalErrorLabel.setVisible(false);
        createModalErrorLabel.setManaged(false);
        createModalOverlay.setVisible(true);
        createModalOverlay.setManaged(true);
        createNameField.requestFocus();
    }

    @FXML
    private void onCloseCreateDialogClicked() {
        createModalOverlay.setVisible(false);
        createModalOverlay.setManaged(false);
    }

    @FXML
    private void onSubmitCreateWorkspaceClicked() {
        String name = createNameField.getText();
        String description = createDescriptionField.getText();

        if (name == null || name.trim().isBlank()) {
            createModalErrorLabel.setText("Workspace name is required.");
            createModalErrorLabel.setVisible(true);
            createModalErrorLabel.setManaged(true);
            return;
        }

        createSubmitBtn.setDisable(true);
        createSubmitBtn.setText("Creating...");

        stateService.createWorkspace(name, description)
                .whenComplete((ws, ex) -> Platform.runLater(() -> {
                    createSubmitBtn.setDisable(false);
                    createSubmitBtn.setText("Create Workspace");
                    if (ex == null) {
                        onCloseCreateDialogClicked();
                    } else {
                        createModalErrorLabel.setText(ex.getMessage());
                        createModalErrorLabel.setVisible(true);
                        createModalErrorLabel.setManaged(true);
                    }
                }));
    }

    @FXML
    private void onAddMemberClicked() {
        String identifier = inviteIdentifierField.getText();
        Domain.MemberRole role = inviteRoleComboBox.getValue();

        if (identifier == null || identifier.trim().isBlank()) {
            stateService.clearMessages();
            errorMessageLabel.setText("Please enter an email or username to invite.");
            errorBanner.setVisible(true);
            errorBanner.setManaged(true);
            return;
        }

        inviteSubmitBtn.setDisable(true);
        inviteSubmitBtn.setText("Adding...");

        stateService.addMember(identifier, role)
                .whenComplete((member, ex) -> Platform.runLater(() -> {
                    inviteSubmitBtn.setDisable(false);
                    inviteSubmitBtn.setText("Add Member");
                    if (ex == null) {
                        inviteIdentifierField.setText("");
                    }
                }));
    }

    private void confirmRemoveMember(Domain.WorkspaceMember member) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Remove Member");
        alert.setHeaderText("Remove " + member.nameOrFallback() + "?");
        alert.setContentText("Are you sure you want to remove this member from the workspace? They will lose access immediately.");

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            stateService.removeMember(member.userId());
        }
    }

    @FXML
    private void onSaveSettingsClicked() {
        Domain.Workspace ws = stateService.getActiveWorkspace();
        if (ws == null) return;

        String newName = settingsNameField.getText();
        String newDesc = settingsDescriptionField.getText();

        if (newName == null || newName.trim().isBlank()) {
            stateService.clearMessages();
            errorMessageLabel.setText("Workspace name cannot be empty.");
            errorBanner.setVisible(true);
            errorBanner.setManaged(true);
            return;
        }

        saveSettingsBtn.setDisable(true);
        saveSettingsBtn.setText("Saving...");

        stateService.updateWorkspace(ws.id(), newName, newDesc)
                .whenComplete((res, ex) -> Platform.runLater(() -> {
                    saveSettingsBtn.setDisable(false);
                    saveSettingsBtn.setText("Save Changes");
                }));
    }

    @FXML
    private void onLeaveWorkspaceClicked() {
        Domain.Workspace ws = stateService.getActiveWorkspace();
        if (ws == null) return;

        if (stateService.isOwner()) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Owner Restriction");
            alert.setHeaderText("Cannot Leave Workspace");
            alert.setContentText(WorkspaceStateService.OWNER_LEAVE_RESTRICTION);
            alert.showAndWait();
            return;
        }

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Leave Workspace");
        alert.setHeaderText("Leave " + ws.name() + "?");
        alert.setContentText("Are you sure you want to leave this workspace? You will need an invitation to rejoin.");

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            stateService.leaveWorkspace();
        }
    }

    @FXML
    private void onDeleteWorkspaceClicked() {
        Domain.Workspace ws = stateService.getActiveWorkspace();
        if (ws == null) return;

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Delete Workspace");
        alert.setHeaderText("Delete " + ws.name() + "?");
        alert.setContentText("This action cannot be undone. All workspace members, channels, and records will be deleted.");

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            stateService.deleteWorkspace(ws.id());
        }
    }

    @FXML
    private void onDismissAlertClicked() {
        stateService.clearMessages();
    }
}
