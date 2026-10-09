package com.landrop.server.desktop;

import com.landrop.util.AppLogger;

import javax.swing.JFrame;
import java.awt.AWTException;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.function.Supplier;

/**
 * Manages Windows and desktop System Tray integration for background server operation.
 */
public class DesktopTrayManager {

    private static final String TAG = "DesktopTrayManager";
    private final JFrame mainFrame;
    private final Supplier<String> clientUrlSupplier;
    private final Runnable toggleServerAction;
    private final Runnable exitAction;
    private TrayIcon trayIcon;

    public DesktopTrayManager(JFrame mainFrame, Supplier<String> clientUrlSupplier, Runnable toggleServerAction) {
        this(mainFrame, clientUrlSupplier, toggleServerAction, () -> mainFrame.dispatchEvent(
                new java.awt.event.WindowEvent(mainFrame, java.awt.event.WindowEvent.WINDOW_CLOSING)));
    }

    public DesktopTrayManager(JFrame mainFrame, Supplier<String> clientUrlSupplier, Runnable toggleServerAction, Runnable exitAction) {
        this.mainFrame = mainFrame;
        this.clientUrlSupplier = clientUrlSupplier;
        this.toggleServerAction = toggleServerAction;
        this.exitAction = exitAction;
    }

    public boolean setupTray() {
        if (!SystemTray.isSupported()) {
            AppLogger.info(TAG, "SystemTray is not supported on this operating environment.");
            return false;
        }

        try {
            SystemTray tray = SystemTray.getSystemTray();
            BufferedImage iconImage = createTrayIconImage();

            PopupMenu popup = new PopupMenu();

            MenuItem openItem = new MenuItem("Mở LANDrop Server");
            openItem.setFont(new Font("SansSerif", Font.BOLD, 12));
            openItem.addActionListener(e -> restoreWindow());
            popup.add(openItem);

            MenuItem copyUrlItem = new MenuItem("Sao chép URL Client");
            copyUrlItem.addActionListener(e -> {
                String url = clientUrlSupplier.get();
                if (url != null && !url.isBlank()) {
                    Toolkit.getDefaultToolkit().getSystemClipboard()
                            .setContents(new StringSelection(url), null);
                    showNotification("LANDrop", "Đã sao chép URL client: " + url, TrayIcon.MessageType.INFO);
                }
            });
            popup.add(copyUrlItem);

            popup.addSeparator();

            MenuItem toggleItem = new MenuItem("Bật / dừng máy chủ");
            toggleItem.addActionListener(e -> javax.swing.SwingUtilities.invokeLater(toggleServerAction));
            popup.add(toggleItem);

            MenuItem exitItem = new MenuItem("Thoát hoàn toàn");
            exitItem.addActionListener(e -> {
                javax.swing.SwingUtilities.invokeLater(exitAction);
            });
            popup.add(exitItem);

            trayIcon = new TrayIcon(iconImage, "LANDrop Server", popup);
            trayIcon.setImageAutoSize(true);
            trayIcon.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1) {
                        restoreWindow();
                    }
                }
            });

            tray.add(trayIcon);
            AppLogger.info(TAG, "SystemTray icon registered successfully.");
            return true;
        } catch (AWTException e) {
            trayIcon = null;
            AppLogger.warn(TAG, "Failed to register SystemTray icon: " + e.getMessage());
            return false;
        }
    }

    public void restoreWindow() {
        if (!javax.swing.SwingUtilities.isEventDispatchThread()) {
            javax.swing.SwingUtilities.invokeLater(this::restoreWindow);
            return;
        }
        mainFrame.setVisible(true);
        mainFrame.setExtendedState(JFrame.NORMAL);
        mainFrame.toFront();
        mainFrame.requestFocus();
    }

    public void showNotification(String title, String message, TrayIcon.MessageType type) {
        if (trayIcon != null) {
            trayIcon.displayMessage(title, message, type);
        }
    }

    public void updateTooltip(String tooltip) {
        if (trayIcon != null) {
            trayIcon.setToolTip(tooltip);
        }
    }

    public boolean isAvailable() { return trayIcon != null; }

    public void dispose() {
        if (trayIcon != null && SystemTray.isSupported()) SystemTray.getSystemTray().remove(trayIcon);
        trayIcon = null;
    }

    private BufferedImage createTrayIconImage() {
        int size = 16;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Circular background (LANDrop blue)
        g.setColor(new Color(0x25, 0x63, 0xeb));
        g.fillRoundRect(0, 0, size, size, 4, 4);

        // Letter 'L' in white
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 10));
        g.drawString("L", 4, 12);
        g.dispose();
        return img;
    }
}
