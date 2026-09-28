package com.landrop.ui.desktop;

import com.landrop.transfer.FileTransfer;
import com.landrop.transfer.FileTransferDirection;
import com.landrop.transfer.FileTransferUtils;
import com.landrop.ui.services.UIStateService;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.awt.CheckboxMenuItem;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;

/** Native tray integration; desktop notifications fall back to no-op when unsupported. */
public final class DesktopTrayService implements AutoCloseable {
    private final Stage stage;
    private final UIStateService state;
    private TrayIcon icon;
    private boolean quitting;

    public DesktopTrayService(Stage stage, UIStateService state) {
        this.stage = stage;
        this.state = state;
    }

    public boolean install() {
        if (!SystemTray.isSupported()) return false;
        PopupMenu menu = new PopupMenu();
        MenuItem open = new MenuItem("Mở LANDrop");
        open.addActionListener(e -> show());
        CheckboxMenuItem autoReceive = new CheckboxMenuItem("Tự nhận file", false);
        autoReceive.addItemListener(e -> state.setAutoAcceptTransfers(autoReceive.getState()));
        MenuItem downloads = new MenuItem("Mở thư mục tải về");
        downloads.addActionListener(e -> {
            try {
                if (Desktop.isDesktopSupported()) {
                    var directory = state.getConfiguredDownloadDirectory() != null
                            ? state.getConfiguredDownloadDirectory()
                            : FileTransferUtils.getDefaultDownloadDirectory();
                    java.nio.file.Files.createDirectories(directory);
                    Desktop.getDesktop().open(directory.toFile());
                }
            } catch (IOException | UnsupportedOperationException ignored) {}
        });
        MenuItem exit = new MenuItem("Thoát");
        exit.addActionListener(e -> Platform.runLater(() -> {
            quitting = true;
            stage.close();
            Platform.exit();
        }));
        menu.add(open);
        menu.add(autoReceive);
        menu.add(downloads);
        menu.addSeparator();
        menu.add(exit);

        icon = new TrayIcon(createIcon(), "LANDrop", menu);
        icon.setImageAutoSize(true);
        icon.addActionListener(e -> show());
        try {
            SystemTray.getSystemTray().add(icon);
        } catch (java.awt.AWTException | SecurityException unavailable) {
            icon = null;
            return false;
        }
        Platform.setImplicitExit(false);
        stage.setOnCloseRequest(e -> {
            if (!quitting) {
                e.consume();
                stage.hide();
            }
        });
        state.setTransferNotificationListener(this::notifyTransfer);
        state.setClipboardNotificationListener(this::notifyClipboard);
        return true;
    }

    private void show() {
        Platform.runLater(() -> {
            stage.show();
            stage.toFront();
            stage.requestFocus();
        });
    }

    private void notifyClipboard(String sender, String text) {
        if (icon == null) return;
        String preview = text != null ? (text.length() > 50 ? text.substring(0, 47) + "..." : text) : "";
        icon.displayMessage("Đã nhận Clipboard từ " + sender, preview + "\n(Đã tự động sao chép)", TrayIcon.MessageType.INFO);
        Toolkit.getDefaultToolkit().beep();
    }

    private void notifyTransfer(FileTransfer transfer, boolean completed) {
        if (icon == null) return;
        boolean incoming = transfer.getDirection() == FileTransferDirection.INCOMING;
        String title = completed ? "Truyền file hoàn tất" : "File mới từ mạng LAN";
        String detail = (incoming ? "Nhận: " : "Gửi: ") + transfer.getFileName();
        icon.displayMessage(title, detail, TrayIcon.MessageType.INFO);
        Toolkit.getDefaultToolkit().beep();
    }

    private static BufferedImage createIcon() {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(79, 70, 229));
        g.fillRoundRect(1, 1, 30, 30, 9, 9);
        g.setColor(Color.WHITE);
        g.fillOval(12, 7, 8, 8);
        g.fillRoundRect(10, 18, 12, 5, 3, 3);
        g.dispose();
        return image;
    }

    @Override public void close() {
        state.setTransferNotificationListener(null);
        if (icon != null) {
            SystemTray.getSystemTray().remove(icon);
            icon = null;
        }
    }
}
