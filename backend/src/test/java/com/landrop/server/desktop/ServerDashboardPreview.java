package com.landrop.server.desktop;

import com.formdev.flatlaf.FlatLightLaf;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;

/** Exports the production panel with clearly synthetic sample data; never starts a backend. */
public final class ServerDashboardPreview {
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Path output = Path.of(args.length == 0 ? "output/desktop-server" : args[0]);
        Files.createDirectories(output);
        SwingUtilities.invokeAndWait(() -> {
            FlatLightLaf.setup();
            var panel = ServerDashboardPanelTest.view(new ServerDashboardPanelTest.Actions());
            panel.setLifecycle(DesktopServerController.State.RUNNING, "Máy chủ sẵn sàng nhận kết nối.", true);
            panel.updateMetrics(3, 2, 4962, 184L * 1024 * 1024, "Online · 3100", "Online · 3001", "PostgreSQL online", true);
            String url = "http://192.168.1.10:3100/client";
            panel.setNetworkInterfaces(List.of(new NetworkHelper.LanInterfaceInfo("192.168.1.10", "Wi-Fi", "wlan0")), "192.168.1.10");
            panel.setConnectionUrls(url, "http://192.168.1.10:3100", "ws://192.168.1.10:3001", false);
            panel.setQrImage(QrCodeGenerator.generateQrCode(url, 136));
            Instant now = Instant.now();
            panel.setClients(List.of(new ServerDashboardPanel.ClientRow("demo-connection-01", "Laptop · Chrome", "theanh", "192.168.1.12:52138", now.minusSeconds(960), true),
                    new ServerDashboardPanel.ClientRow("demo-connection-02", "iPhone · Safari", "linh", "192.168.1.15:49218", now.minusSeconds(600), true),
                    new ServerDashboardPanel.ClientRow("demo-connection-03", "Desktop · Edge", "minh", "192.168.1.18:55302", now.minusSeconds(300), true)));
            panel.setTransfers(List.of(new ServerDashboardPanel.TransferRow("demo-transfer-01", "Project brief.pdf", 3407872, "theanh", "linh", "CONNECTING", now.plusSeconds(85)),
                    new ServerDashboardPanel.TransferRow("demo-transfer-02", "Presentation.pptx", 13631488, "minh", "theanh", "REQUESTED", now.plusSeconds(112))));
            panel.setActivity(List.of(new ServerDashboardPanel.Activity(now.minusSeconds(120), "INFO", "Máy chủ HTTP sẵn sàng tại cổng 3100"),
                    new ServerDashboardPanel.Activity(now.minusSeconds(90), "INFO", "Client Laptop · Chrome đã xác thực"),
                    new ServerDashboardPanel.Activity(now.minusSeconds(70), "INFO", "Client iPhone · Safari đã kết nối"),
                    new ServerDashboardPanel.Activity(now.minusSeconds(30), "INFO", "Yêu cầu transfer đã được chấp nhận"),
                    new ServerDashboardPanel.Activity(now.minusSeconds(10), "INFO", "WebRTC signaling: trạng thái CONNECTING")));
            try {
                var userTable = ServerDashboardPanelTest.components(panel.administration(), JTable.class).stream()
                        .filter(t -> t.getModel().getColumnCount() == 7).findFirst().orElseThrow();
                var userModel = (javax.swing.table.DefaultTableModel) userTable.getModel();
                userModel.addRow(new Object[]{"theanh", "The Anh", "theanh@example.test", "ACTIVE", "SYSTEM_ADMIN", 2, "11111111-1111-1111-1111-111111111111"});
                userModel.addRow(new Object[]{"linh", "Linh", "linh@example.test", "ACTIVE", "USER", 1, "22222222-2222-2222-2222-222222222222"});
                userModel.addRow(new Object[]{"minh", "Minh", "minh@example.test", "DISABLED", "USER", 0, "33333333-3333-3333-3333-333333333333"});
                ServerDashboardPanelTest.components(panel.administration(), JLabel.class).stream()
                        .filter(label -> "Khởi động máy chủ để quản trị dữ liệu.".equals(label.getText()))
                        .forEach(label -> label.setText("DỮ LIỆU MẪU · Xem trước giao diện quản trị desktop"));
                for (String page : List.of("overview", "clients", "transfers", "administration", "logs", "settings")) {
                    panel.showPage(page);
                    if (page.equals("clients")) ServerDashboardPanelTest.components(panel, JTable.class).stream()
                            .filter(t -> t.getModel().getColumnCount() == 6).findFirst().orElseThrow().setRowSelectionInterval(0, 0);
                    render(panel, output.resolve("landrop-server-" + page + ".png"), 1280, 850);
                }
                panel.showPage("overview"); render(panel, output.resolve("landrop-server-compact.png"), 1080, 710);
                ServerDashboardPanelTest.button(panel, "Giao diện tối").doClick();
                render(panel, output.resolve("landrop-server-dark.png"), 1280, 850);
                panel.showPage("administration");
                render(panel, output.resolve("landrop-server-administration-dark.png"), 1280, 850);
                render(panel, output.resolve("landrop-server-administration-compact.png"), 1080, 760);
            } catch (Exception error) { throw new IllegalStateException(error); }
            finally { panel.administration().close(); }
        });
        System.out.println("Dashboard previews written to " + output.toAbsolutePath());
    }
    private static void render(JPanel panel, Path path, int width, int height) throws Exception {
        panel.setSize(width, height);
        for (int pass = 0; pass < 3; pass++) layout(panel);
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); panel.paint(graphics); graphics.dispose();
        ImageIO.write(image, "png", path.toFile());
    }
    private static void layout(Container component) {
        component.doLayout();
        for (Component child : component.getComponents()) if (child instanceof Container nested) layout(nested);
    }
}
