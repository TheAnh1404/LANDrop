package com.landrop.ui;

import com.landrop.ui.controllers.*;
import com.landrop.ui.services.UIStateService;
import com.landrop.ui.auth.api.AuthApi;
import com.landrop.ui.auth.api.InMemorySessionStore;
import com.landrop.ui.auth.dto.AuthDto;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

public class FXMLLoaderTest {

    @BeforeAll
    static void initJavaFX() {
        try {
            Platform.startup(() -> {});
            Platform.setImplicitExit(false);
        } catch (IllegalStateException ignored) {
            Platform.setImplicitExit(false);
        }
    }

    @Test
    void testStylesCssExists() {
        URL cssUrl = getClass().getResource("/com/landrop/ui/styles.css");
        assertNotNull(cssUrl, "styles.css must be available on classpath");
    }

    @Test
    void testLoadSetupView() throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/landrop/ui/setup-view.fxml"));
        Parent root = loader.load();
        assertNotNull(root);

        SetupController controller = loader.getController();
        assertNotNull(controller);

        UIStateService stateService = new UIStateService();
        assertDoesNotThrow(() -> controller.init(new MainApp(), stateService, "TestPeer", 5001, "localhost", 8888, "127.0.0.1", false));
    }

    @Test
    void testLoadLoginView() throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/landrop/ui/auth-login-view.fxml"));
        Parent root = loader.load();
        assertNotNull(root);
        LoginController controller = loader.getController();
        assertNotNull(controller);
        MainApp app = new MainApp(new NoopAuthApi(), new InMemorySessionStore());
        assertDoesNotThrow(() -> controller.init(app, app.getAuthStateService()));
    }

    @Test
    void testLoadRegisterView() throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/landrop/ui/auth-register-view.fxml"));
        Parent root = loader.load();
        assertNotNull(root);
        RegisterController controller = loader.getController();
        assertNotNull(controller);
        MainApp app = new MainApp(new NoopAuthApi(), new InMemorySessionStore());
        assertDoesNotThrow(() -> controller.init(app, app.getAuthStateService()));
    }

    @Test
    void testLoadDashboardView() throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/landrop/ui/dashboard-view.fxml"));
        Node root = loader.load();
        assertNotNull(root);

        DashboardController controller = loader.getController();
        assertNotNull(controller);

        UIStateService stateService = new UIStateService();
        assertDoesNotThrow(() -> controller.init(stateService, new MainController()));
    }

    @Test
    void testLoadPeersView() throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/landrop/ui/peers-view.fxml"));
        Node root = loader.load();
        assertNotNull(root);

        PeersController controller = loader.getController();
        assertNotNull(controller);

        UIStateService stateService = new UIStateService();
        assertDoesNotThrow(() -> controller.init(stateService, new MainController()));
    }

    @Test
    void testLoadChatView() throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/landrop/ui/chat-view.fxml"));
        Node root = loader.load();
        assertNotNull(root);

        ChatController controller = loader.getController();
        assertNotNull(controller);

        UIStateService stateService = new UIStateService();
        assertDoesNotThrow(() -> controller.init(stateService, new MainController()));
    }

    @Test
    void testLoadTransfersView() throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/landrop/ui/transfers-view.fxml"));
        Node root = loader.load();
        assertNotNull(root);

        TransfersController controller = loader.getController();
        assertNotNull(controller);

        UIStateService stateService = new UIStateService();
        assertDoesNotThrow(() -> controller.init(stateService, new MainController()));
    }

    @Test
    void testLoadSettingsView() throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/landrop/ui/settings-view.fxml"));
        Node root = loader.load();
        assertNotNull(root);

        SettingsController controller = loader.getController();
        assertNotNull(controller);

        UIStateService stateService = new UIStateService();
        assertDoesNotThrow(() -> controller.init(stateService, new MainController()));
    }

    @Test
    void testLoadWorkspaceView() throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/landrop/ui/workspace-view.fxml"));
        Node root = loader.load();
        assertNotNull(root);

        WorkspaceController controller = loader.getController();
        assertNotNull(controller);
    }

    @Test
    void testLoadMainView() throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/landrop/ui/main-view.fxml"));
        Parent root = loader.load();
        assertNotNull(root);

        MainController controller = loader.getController();
        assertNotNull(controller);

        UIStateService stateService = new UIStateService();
        assertDoesNotThrow(() -> controller.init(stateService, new MainApp()));
    }

    private static final class NoopAuthApi implements AuthApi {
        @Override public CompletableFuture<AuthDto.AuthResponse> register(AuthDto.RegisterRequest request) { return CompletableFuture.failedFuture(new IllegalStateException()); }
        @Override public CompletableFuture<AuthDto.AuthResponse> login(AuthDto.LoginRequest request) { return CompletableFuture.failedFuture(new IllegalStateException()); }
        @Override public CompletableFuture<AuthDto.RefreshResponse> refresh(AuthDto.RefreshRequest request) { return CompletableFuture.failedFuture(new IllegalStateException()); }
        @Override public CompletableFuture<AuthDto.AuthUserResponse> me(String accessToken) { return CompletableFuture.failedFuture(new IllegalStateException()); }
        @Override public CompletableFuture<Void> logout(String accessToken, String refreshToken) { return CompletableFuture.completedFuture(null); }
    }
}
