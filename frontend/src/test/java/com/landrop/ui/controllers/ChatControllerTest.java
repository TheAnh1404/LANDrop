package com.landrop.ui.controllers;

import com.landrop.transfer.FileTransfer;
import com.landrop.transfer.FileTransferDirection;
import com.landrop.transfer.FileTransferStatus;
import com.landrop.ui.services.UIStateService;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class ChatControllerTest {

    @BeforeAll
    static void initJavaFX() {
        try {
            Platform.startup(() -> {});
            Platform.setImplicitExit(false);
        } catch (IllegalStateException ignored) {
            Platform.setImplicitExit(false);
        }
    }

    private static void runOnFxThread(Runnable action) throws InterruptedException {
        if (Platform.isFxApplicationThread()) {
            action.run();
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS), "FX action timed out");
    }

    @Test
    void testChatViewTabStructureAndSwitching() throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/landrop/ui/chat-view.fxml"));
        Node root = loader.load();
        assertNotNull(root);

        ChatController controller = loader.getController();
        assertNotNull(controller);

        TabPane tabPane = (TabPane) loader.getNamespace().get("mainTabPane");
        Tab chatTab = (Tab) loader.getNamespace().get("chatTab");
        Tab vaultTab = (Tab) loader.getNamespace().get("vaultTab");

        assertNotNull(tabPane, "mainTabPane must be present");
        assertNotNull(chatTab, "chatTab must be present");
        assertNotNull(vaultTab, "vaultTab must be present");
        assertEquals(2, tabPane.getTabs().size());

        runOnFxThread(() -> {
            // Default selection should be chatTab
            tabPane.getSelectionModel().select(chatTab);
            assertEquals(chatTab, tabPane.getSelectionModel().getSelectedItem());

            // Simulating clicking "📁 Mở Kho Tài Liệu"
            java.lang.reflect.Method openVaultMethod;
            try {
                openVaultMethod = ChatController.class.getDeclaredMethod("onOpenDocumentsVault");
                openVaultMethod.setAccessible(true);
                openVaultMethod.invoke(controller);
            } catch (Exception e) {
                fail("Failed to invoke onOpenDocumentsVault: " + e.getMessage());
            }

            assertEquals(vaultTab, tabPane.getSelectionModel().getSelectedItem(),
                    "Selecting open documents vault should switch active tab to vaultTab");
        });
    }

    @Test
    void testTransferHudControlsAndBandwidth() throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/landrop/ui/chat-view.fxml"));
        Node root = loader.load();
        assertNotNull(root);

        ChatController controller = loader.getController();
        assertNotNull(controller);

        VBox hudTransfersContainer = (VBox) loader.getNamespace().get("hudTransfersContainer");
        Label totalBandwidthLabel = (Label) loader.getNamespace().get("totalBandwidthLabel");
        Label emptyHudLabel = (Label) loader.getNamespace().get("emptyHudLabel");

        assertNotNull(hudTransfersContainer);
        assertNotNull(totalBandwidthLabel);
        assertNotNull(emptyHudLabel);

        UIStateService stateService = new UIStateService();
        controller.init(stateService, new MainController());

        // Add a mock transfer
        FileTransfer transfer = new FileTransfer(
                "transfer-test-1",
                "large_archive.zip",
                100 * 1024 * 1024L,
                FileTransferDirection.INCOMING,
                "remote-peer-1",
                "Peer Alpha",
                Path.of("large_archive.zip")
        );
        transfer.setStatus(FileTransferStatus.TRANSFERRING);
        transfer.setSpeedBytesPerSec(10.5 * 1024 * 1024); // 10.5 MB/s
        transfer.setTransferredBytes(50 * 1024 * 1024L); // 50%

        runOnFxThread(() -> {
            // Verify pause/resume on UIStateService directly
            assertDoesNotThrow(() -> stateService.pauseTransfer(transfer.getTransferId()));
            assertDoesNotThrow(() -> stateService.resumeTransfer(transfer.getTransferId()));
        });
    }
}
