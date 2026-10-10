package com.landrop.server.desktop;

import com.formdev.flatlaf.FlatLightLaf;
import com.landrop.admin.dto.*;
import com.landrop.admin.logging.*;
import com.landrop.admin.service.AdminControlCenterService;
import com.landrop.database.repository.AdminDataRepository;
import com.landrop.server.config.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeListener;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Renders production panels with isolated sample services; never opens sockets or a database. */
public final class ServerDashboardPreview {
    private static final Instant NOW = Instant.parse("2026-10-10T08:30:00Z");
    private static final UUID ANH = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID LINH = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID MINH = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID GROUP = UUID.fromString("44444444-4444-4444-8444-444444444444");

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Path output = Path.of(args.length == 0 ? "output/desktop-server" : args[0]); Files.createDirectories(output);
        var view = new AtomicReference<ServerDashboardPanel>();
        SwingUtilities.invokeAndWait(() -> {
            FlatLightLaf.setup();
            var panel = ServerDashboardPanelTest.view(new ServerDashboardPanelTest.Actions()); view.set(panel);
            panel.setLifecycle(DesktopServerController.State.RUNNING, "Máy chủ đã bật. Bạn có thể chia sẻ đường dẫn kết nối.", true);
            panel.updateMetrics(3, 2, 4962, 184L * 1024 * 1024, "Online · 3100", "Online · 3001", "PostgreSQL online", true);
            String url = "http://192.168.1.10:3100/client";
            panel.setNetworkInterfaces(List.of(new NetworkHelper.LanInterfaceInfo("192.168.1.10", "Wi-Fi", "wlan0")), "192.168.1.10");
            panel.setConnectionUrls(url, "http://192.168.1.10:3100", "ws://192.168.1.10:3001", false);
            panel.setQrImage(QrCodeGenerator.generateQrCode(url, 136));
            Instant now = Instant.now();
            panel.setClients(List.of(new ServerDashboardPanel.ClientRow("demo-connection-01", "Laptop · Chrome", "theanh", "192.168.1.12", now.minusSeconds(960), true),
                    new ServerDashboardPanel.ClientRow("demo-connection-02", "iPhone · Safari", "linh", "192.168.1.15", now.minusSeconds(600), true),
                    new ServerDashboardPanel.ClientRow("demo-connection-03", "Desktop · Edge", "minh", "192.168.1.18", now.minusSeconds(300), true)));
            panel.setTransfers(List.of(new ServerDashboardPanel.TransferRow("demo-transfer-01", "Báo cáo dự án.pdf", 3407872, "theanh", "linh", "CONNECTING", now.plusSeconds(85)),
                    new ServerDashboardPanel.TransferRow("demo-transfer-02", "Bài thuyết trình.pptx", 13631488, "minh", "theanh", "REQUESTED", now.plusSeconds(112))));
            panel.setActivity(List.of(new ServerDashboardPanel.Activity(now.minusSeconds(120), "INFO", "Máy chủ đã sẵn sàng nhận kết nối"),
                    new ServerDashboardPanel.Activity(now.minusSeconds(90), "INFO", "Laptop của The Anh đã đăng nhập"),
                    new ServerDashboardPanel.Activity(now.minusSeconds(70), "INFO", "iPhone của Linh đã kết nối"),
                    new ServerDashboardPanel.Activity(now.minusSeconds(30), "INFO", "Linh đã chấp nhận yêu cầu chia sẻ"),
                    new ServerDashboardPanel.Activity(now.minusSeconds(10), "INFO", "Hai thiết bị đang thiết lập kết nối")));
            panel.administration().setRuntime(new SampleService(output), new AdminDataRepository(null) {
                @Override public DataPage read(Dataset dataset, int offset, int limit) {
                    return new DataPage(List.of("Tên tệp", "Người sở hữu", "Kích thước", "Loại lưu trữ", "Ngày tạo"),
                            List.of(List.of("Báo cáo dự án.pdf", "theanh", "3,2 MB", "Trên thiết bị", "10/10/2026 · 15:30"),
                                    List.of("Bài thuyết trình.pptx", "minh", "13 MB", "Trên thiết bị", "10/10/2026 · 15:25")), 2);
                }
            }, true);
        });
        var panel = view.get();
        try {
            for (String page : List.of("overview", "users", "sessions", "clients", "workspaces", "transfers", "data", "audit", "logs", "settings", "help", "server")) {
                renderPage(panel, page, output.resolve("landrop-server-" + page + ".png"), 1280, 850);
            }
            renderPage(panel, "users", output.resolve("landrop-server-administration.png"), 1280, 850);
            renderPage(panel, "users", output.resolve("landrop-server-administration-compact.png"), 1080, 760);
            renderPage(panel, "overview", output.resolve("landrop-server-compact.png"), 1080, 760);
            SwingUtilities.invokeAndWait(() -> {
                panel.showPage("settings"); ServerDashboardPanelTest.components(panel, JToggleButton.class).stream()
                        .filter(b -> b.getText().startsWith("›  Cài đặt mạng")).findFirst().orElseThrow().doClick();
            });
            renderPage(panel, "settings", output.resolve("landrop-server-settings-advanced.png"), 1280, 850);
            SwingUtilities.invokeAndWait(() -> ServerDashboardPanelTest.button(panel, "Giao diện tối").doClick());
            renderPage(panel, "overview", output.resolve("landrop-server-dark.png"), 1280, 850);
            renderPage(panel, "users", output.resolve("landrop-server-administration-dark.png"), 1280, 850);
            SwingUtilities.invokeAndWait(() -> {
                ServerDashboardPanelTest.button(panel, "Giao diện sáng").doClick();
                panel.administration().setRuntime(null, null, false);
                panel.setLifecycle(DesktopServerController.State.STOPPED, "Bật máy chủ để bắt đầu chia sẻ với các thiết bị khác.", false);
                panel.updateMetrics(0, 0, 0, 184L * 1024 * 1024, "Chưa chạy", "Chưa chạy", "Chưa kiểm tra", false);
            });
            renderPage(panel, "overview", output.resolve("landrop-server-welcome.png"), 1280, 850);
        } finally { SwingUtilities.invokeAndWait(() -> panel.administration().close()); }
        System.out.println("Production UI previews with SAMPLE DATA written to " + output.toAbsolutePath());
    }

    private static void renderPage(ServerDashboardPanel panel, String page, Path path, int width, int height) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<JLabel> feedback = new AtomicReference<>();
        PropertyChangeListener listener = event -> { if (String.valueOf(event.getNewValue()).startsWith("Đã cập nhật")) ready.countDown(); };
        SwingUtilities.invokeAndWait(() -> {
            JLabel label = ServerDashboardPanelTest.components(panel.administration(), JLabel.class).stream()
                    .filter(l -> "administrationFeedback".equals(l.getName())).findFirst().orElseThrow();
            feedback.set(label); label.addPropertyChangeListener("text", listener); panel.showPage(page);
            if (!List.of("users", "sessions", "workspaces", "data", "audit", "server").contains(page)) ready.countDown();
        });
        if (!ready.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Sample view did not finish loading: " + page);
        SwingUtilities.invokeAndWait(() -> {
            feedback.get().removePropertyChangeListener("text", listener);
            feedback.get().setText("DỮ LIỆU MẪU · Xem trước giao diện, không kết nối database.");
            ServerDashboardPanelTest.components(panel, JLabel.class).stream().filter(l -> "dashboardFooter".equals(l.getName()))
                    .forEach(l -> l.setText("DỮ LIỆU MẪU · Ảnh xem trước giao diện desktop"));
            for (JTable table : ServerDashboardPanelTest.components(panel, JTable.class)) {
                if (table.getRowCount() > 0) table.setRowSelectionInterval(0, 0);
            }
            try { render(panel, path, width, height); } catch (Exception failure) { throw new IllegalStateException(failure); }
        });
    }

    private static void render(JPanel panel, Path path, int width, int height) throws Exception {
        panel.setSize(width, height); for (int pass = 0; pass < 3; pass++) layout(panel);
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); panel.paint(graphics); graphics.dispose(); ImageIO.write(image, "png", path.toFile());
    }
    private static void layout(Container component) {
        component.doLayout(); for (Component child : component.getComponents()) if (child instanceof Container nested) layout(nested);
    }

    private static final class SampleService extends AdminControlCenterService {
        SampleService(Path output) {
            super(null, null, null, null, new ServerConfigManager(new ServerConfig("0.0.0.0", 3100, 3001, 1, 10000, 30000, "development", "INFO"),
                    output.resolve("sample-only-config.json")), new OperationalLogService());
        }
        @Override public List<AdminUserDto> listUsers(int offset, int limit, String search, String status) {
            return List.of(new AdminUserDto(ANH, "theanh@example.test", "theanh", "The Anh", null, "ACTIVE", "SYSTEM_ADMIN", NOW, NOW, 2),
                    new AdminUserDto(LINH, "linh@example.test", "linh", "Nguyễn Linh", null, "ACTIVE", "USER", NOW, NOW, 1),
                    new AdminUserDto(MINH, "minh@example.test", "minh", "Trần Minh", null, "DISABLED", "USER", NOW, NOW, 0));
        }
        @Override public AdminDashboardDto getDashboard() { return new AdminDashboardDto("RUNNING", 4962, "01:22:42", 3, 3, 2, 1, 3, 4, 1, 3, 2, 2, false, NOW); }
        @Override public List<AdminWorkspaceDto> listWorkspaces(String search) {
            return List.of(new AdminWorkspaceDto(GROUP, "Nhóm thiết kế", "nhom-thiet-ke", "Trao đổi tài liệu và ý tưởng", ANH, "theanh", 3, NOW, NOW),
                    new AdminWorkspaceDto(UUID.randomUUID(), "Dự án LANDrop", "du-an-landrop", "Tài liệu dự án", LINH, "linh", 2, NOW, NOW));
        }
        @Override public List<AdminSessionDto> listSessions(boolean active, UUID user, int limit, int offset) {
            return List.of(new AdminSessionDto(UUID.randomUUID(), ANH, "theanh", "sample-device-1", "Laptop · Chrome", NOW, NOW.plusSeconds(86400), NOW, null, false, false, true, true),
                    new AdminSessionDto(UUID.randomUUID(), LINH, "linh", "sample-device-2", "iPhone · Safari", NOW, NOW.plusSeconds(86400), NOW, null, false, false, true, true));
        }
        @Override public List<OperationalLogEntry> getLogs(int limit, int offset, String level, String type, String search) {
            return List.of(new OperationalLogEntry(1, NOW, "USER_UPDATED", "INFO", "DESKTOP_OPERATOR", "linh", "Cập nhật tên hiển thị", null),
                    new OperationalLogEntry(2, NOW.minusSeconds(300), "WORKSPACE_MEMBER_SAVED", "INFO", "DESKTOP_OPERATOR", "Nhóm thiết kế", "Thêm thành viên vào nhóm", null));
        }
    }
}
