package com.landrop.ui.controllers;

import com.landrop.ui.MainApp;
import com.landrop.ui.auth.model.AuthModels;
import com.landrop.ui.auth.services.AuthStateService;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

/** Login page controller. Supports normal server sign in and zero-login LAN P2P mode. */
public final class LoginController {
    @FXML private TextField emailField;
    @FXML private PasswordField passwordField;
    @FXML private Button submitButton;
    @FXML private Button offlineModeButton;
    @FXML private Hyperlink registerLink;
    @FXML private Label errorLabel;
    @FXML private Label serverAddressLabel;

    private MainApp mainApp;
    private AuthStateService authState;

    public void init(MainApp mainApp, AuthStateService authState) {
        this.mainApp = mainApp;
        this.authState = authState;
        errorLabel.setText(authState.authError());
        if (mainApp.getBootstrap() != null && !mainApp.getBootstrap().errorCode().isBlank()
                && !"ERROR".equals(mainApp.getBootstrap().errorCode())) {
            errorLabel.setText(com.landrop.ui.bootstrap.BootstrapErrors.message(mainApp.getBootstrap().errorCode()));
        }
        if (serverAddressLabel != null && mainApp.getResolvedApiUri() != null) {
            String host = mainApp.getResolvedApiUri().getHost();
            int port = mainApp.getResolvedApiUri().getPort() > 0 ? mainApp.getResolvedApiUri().getPort() : 3000;
            serverAddressLabel.setText("🟢 Máy chủ: " + host + ":" + port);
        }
    }

    @FXML private void onSubmit() {
        submitButton.setDisable(true);
        submitButton.setText("Signing in...");
        registerLink.setDisable(true);
        var request = new AuthModels.LoginRequest(emailField.getText(), passwordField.getText());
        passwordField.clear();
        var operation = mainApp.getBootstrap() == null ? authState.login(request) : mainApp.getBootstrap().login(request);
        operation
                .whenComplete((user, failure) -> Platform.runLater(() -> {
                    submitButton.setDisable(false);
                    submitButton.setText("Sign in");
                    registerLink.setDisable(false);
                    errorLabel.setText(authState.authError());
                }));
    }

    @FXML private void onRegister() { mainApp.showRegisterView(); }

    @FXML private void onAutoDetectServer() { mainApp.startServerDiscovery(); }

    @FXML private void onContinueOffline() {
        if (mainApp != null) {
            mainApp.startOfflineMode();
        }
    }
}
