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

/** Registration page controller. Client checks improve feedback; the backend remains authoritative. */
public final class RegisterController {
    @FXML private TextField usernameField;
    @FXML private TextField displayNameField;
    @FXML private TextField emailField;
    @FXML private PasswordField passwordField;
    @FXML private PasswordField confirmationField;
    @FXML private Button submitButton;
    @FXML private Hyperlink loginLink;
    @FXML private Label errorLabel;

    private MainApp mainApp;
    private AuthStateService authState;

    public void init(MainApp mainApp, AuthStateService authState) {
        this.mainApp = mainApp;
        this.authState = authState;
        errorLabel.setText(authState.authError());
        authState.authErrorProperty().addListener((obs, oldValue, newValue) ->
                Platform.runLater(() -> errorLabel.setText(newValue == null ? "" : newValue)));
    }

    @FXML private void onSubmit() {
        if (!passwordField.getText().equals(confirmationField.getText())) {
            errorLabel.setText("Passwords do not match.");
            return;
        }
        submitButton.setDisable(true);
        submitButton.setText("Creating account...");
        authState.register(new AuthModels.RegisterRequest(usernameField.getText(), displayNameField.getText(),
                        emailField.getText(), passwordField.getText()))
                .whenComplete((user, failure) -> Platform.runLater(() -> {
                    submitButton.setDisable(false);
                    submitButton.setText("Create account");
                    if (failure == null) mainApp.onAuthenticated();
                }));
    }

    @FXML private void onLogin() { mainApp.showLoginView(); }
}
