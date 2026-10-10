package com.landrop.server.desktop;

import com.landrop.server.bootstrap.ServerBootstrap;
import com.landrop.server.clients.ClientConnection;
import com.landrop.util.AppLogger;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Native desktop shell: all controls operate the integrated backend, all slow work stays off the EDT. */
public class ServerMainFrame extends JFrame {
    private volatile DesktopServerSettings settings = DesktopServerSettings.load();
    private final ServerDashboardPanel dashboard;
    private final DesktopServerController controller;
    private final ExecutorService background = Executors.newFixedThreadPool(2, task -> {
        Thread worker = new Thread(task, "desktop-services"); worker.setDaemon(true); return worker;
    });
    private final ArrayBlockingQueue<ServerDashboardPanel.Activity> pendingLogs = new ArrayBlockingQueue<>(2000);
    private final ArrayDeque<ServerDashboardPanel.Activity> history = new ArrayDeque<>();
    private final AtomicLong qrRevision = new AtomicLong();
    private final Consumer<String> logListener = line -> pendingLogs.offer(new ServerDashboardPanel.Activity(Instant.now(),
            line.contains("[ERROR]") ? "ERROR" : line.contains("[WARN]") ? "WARN" : line.contains("[DEBUG]") ? "DEBUG" : "INFO", line));
    private final Timer refreshTimer;
    private final DesktopTrayManager tray;
    private String lanIp = "127.0.0.1";
    private String lastQrUrl = "";
    private Instant startedAt;
    private boolean closing;
    private volatile boolean disposed;
    private volatile boolean databaseConnected;
    private volatile boolean databaseCheckRunning;
    private String databaseMessage = "Chưa chạy";
    private long lastDatabaseCheck;

    public ServerMainFrame() {
        super("LANDrop Server — Bảng điều khiển máy chủ");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setSize(1280, 900); setMinimumSize(new Dimension(1080, 760)); setLocationRelativeTo(null);
        controller = new DesktopServerController(() -> settings, this::lifecycleChanged);
        dashboard = new ServerDashboardPanel(settings, new ServerDashboardPanel.Actions() {
            @Override public void start() { startServer(); }
            @Override public void stop() { stopServer(); }
            @Override public void restart() { restartServer(); }
            @Override public void openClient() { openBrowser(clientUrl()); }
            @Override public void applyServiceConfig(com.landrop.server.config.ServerConfig config) {
                controller.setServiceConfig(config);
            }
            @Override public void copy(String text) {
                if (text != null && !text.isBlank()) Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
            }
            @Override public void refreshNetwork() { refreshNetworkInterfaces(); }
            @Override public void disconnect(String id) { disconnectClient(id); }
            @Override public void cancelTransfer(String id) { cancelSignalingTransfer(id); }
            @Override public void applySettings(DesktopServerSettings next) { applyConfiguration(next); }
        }, false);
        setContentPane(dashboard);
        dashboard.onInterfaceChanged(ip -> { lanIp = ip; refreshUrls(); });
        AppLogger.addLogListener(logListener);
        refreshTimer = new Timer(1000, event -> refreshDashboard()); refreshTimer.start();
        tray = new DesktopTrayManager(this, this::clientUrl, () -> {
            if (controller.state() == DesktopServerController.State.RUNNING) stopServer(); else startServer();
        }, this::quit);
        tray.setupTray();
        addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent event) { closeWindow(); } });
        refreshNetworkInterfaces();
        lifecycleChanged(DesktopServerController.State.STOPPED, "Khởi động máy chủ để bắt đầu nhận kết nối.");
    }

    public void startServer() { controller.start(); }
    public void stopServer() {
        if (controller.bootstrap() != null && controller.bootstrap().getClientRegistry().size() > 0
                && JOptionPane.showConfirmDialog(this, "Dừng máy chủ sẽ ngắt các kết nối và phiên signaling đang hoạt động.",
                "Dừng máy chủ?", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        controller.stop();
    }
    public void restartServer() {
        if (controller.state() != DesktopServerController.State.RUNNING) return;
        if (JOptionPane.showConfirmDialog(this, "Các client sẽ bị ngắt và cần kết nối lại sau khi máy chủ khởi động.\nTiếp tục khởi động lại?",
                "Khởi động lại máy chủ", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION) controller.restart();
    }

    private void lifecycleChanged(DesktopServerController.State state, String message) {
        SwingUtilities.invokeLater(() -> {
            if (disposed) return;
            if (state == DesktopServerController.State.RUNNING) {
                startedAt = Instant.now(); databaseMessage = "Đang kiểm tra…"; databaseConnected = false; lastDatabaseCheck = 0;
            } else if (state == DesktopServerController.State.STOPPED || state == DesktopServerController.State.FAILED) {
                startedAt = null;
            }
            dashboard.setLifecycle(state, message, controller.bootstrap() != null);
            tray.updateTooltip("LANDrop Server · " + state.name());
            refreshDashboard();
        });
    }

    private int actualHttpPort() {
        ServerBootstrap server = controller.bootstrap();
        return server != null && server.getHttpServer() != null ? server.getHttpServer().getPort() : settings.httpPort();
    }
    private int actualWsPort() {
        ServerBootstrap server = controller.bootstrap();
        return server != null && server.getRealtimeServer() != null ? server.getRealtimeServer().getPort() : settings.wsPort();
    }
    private DesktopServerSettings advertisedSettings() {
        ServerBootstrap server = controller.bootstrap();
        return server == null ? settings : new DesktopServerSettings(server.getConfig().getHost(), server.getConfig().getHttpPort(),
                server.getConfig().getWsPort(), false, settings.clientPort(), settings.minimizeToTray());
    }
    private String advertisedHost() { return advertisedSettings().advertisedHost(lanIp); }
    private String apiUrl() { return "http://" + advertisedHost() + ":" + actualHttpPort(); }
    private String clientUrl() {
        if (settings.externalClient()) return "http://" + advertisedHost() + ":" + settings.clientPort()
                + "/connect?server=" + java.net.URLEncoder.encode(apiUrl(), java.nio.charset.StandardCharsets.UTF_8);
        return apiUrl() + "/client";
    }
    private void refreshUrls() {
        if (disposed) return;
        String url = clientUrl();
        dashboard.setConnectionUrls(url, apiUrl(), "ws://" + advertisedHost() + ":" + actualWsPort(),
                advertisedHost().equals("127.0.0.1") || advertisedHost().equals("[::1]"));
        if (url.equals(lastQrUrl)) return;
        lastQrUrl = url; long revision = qrRevision.incrementAndGet();
        background.execute(() -> {
            var qr = QrCodeGenerator.generateQrCode(url, 136);
            SwingUtilities.invokeLater(() -> { if (!disposed && revision == qrRevision.get()) dashboard.setQrImage(qr); });
        });
    }
    private void refreshNetworkInterfaces() {
        String selected = lanIp;
        background.execute(() -> {
            var interfaces = NetworkHelper.getAvailableLanInterfaces();
            SwingUtilities.invokeLater(() -> { if (!disposed) { dashboard.setNetworkInterfaces(interfaces, selected); refreshUrls(); } });
        });
    }

    private void refreshDashboard() {
        if (disposed) return;
        boolean newLogs = false;
        for (ServerDashboardPanel.Activity line; (line = pendingLogs.poll()) != null;) {
            history.addLast(line); if (history.size() > 2000) history.removeFirst(); newLogs = true;
        }
        if (newLogs) dashboard.setActivity(List.copyOf(history));
        ServerBootstrap server = controller.bootstrap();
        boolean running = server != null && server.isRunning();
        dashboard.administration().setRuntime(running && !closing && controller.state() == DesktopServerController.State.RUNNING
                ? server.getAdminControlCenterService() : null, running ? server.getAdminDataRepository() : null,
                running && server.getUserRepository() != null && server.getRefreshTokenRepository() != null);
        List<ClientConnection> connections = running ? server.getClientRegistry().activeConnections().stream()
                .sorted(Comparator.comparing(ClientConnection::deviceName).thenComparing(ClientConnection::connectionId)).toList() : List.of();
        dashboard.setClients(connections.stream().map(c -> new ServerDashboardPanel.ClientRow(c.connectionId(), c.deviceName(),
                c.username() == null ? "Chưa đăng nhập" : c.username(), c.remoteAddress(), c.connectedAt(), c.isAuthenticated())).toList());
        var transfers = running ? server.getTransferCoordinator().activeTransfers() : List.<com.landrop.server.transfers.TransferRecord>of();
        dashboard.setTransfers(transfers.stream().sorted(Comparator.comparing(com.landrop.server.transfers.TransferRecord::createdAt))
                .map(t -> new ServerDashboardPanel.TransferRow(t.transferId(), t.fileName(), t.fileSize(),
                        peerName(server, t.senderConnectionId()), peerName(server, t.receiverConnectionId()), t.status().name(), t.expiresAt())).toList());
        long uptime = running && startedAt != null ? Duration.between(startedAt, Instant.now()).toSeconds() : 0;
        Runtime memory = Runtime.getRuntime();
        if (running) checkDatabase(server);
        dashboard.updateMetrics(connections.size(), transfers.size(), uptime, memory.totalMemory() - memory.freeMemory(),
                running ? "Online · " + actualHttpPort() : "Chưa chạy", running ? "Online · " + actualWsPort() : "Chưa chạy",
                running ? databaseMessage : "Chưa chạy", running && databaseConnected);
        refreshUrls();
    }
    private static String peerName(ServerBootstrap server, String connectionId) {
        return server.getClientRegistry().get(connectionId).map(c -> c.username() == null ? c.deviceName() : c.username())
                .orElse(connectionId.substring(0, Math.min(8, connectionId.length())));
    }
    private void checkDatabase(ServerBootstrap server) {
        if (server.getUserRepository() == null || server.getRefreshTokenRepository() == null) {
            databaseConnected = false; databaseMessage = "Chưa cấu hình"; return;
        }
        if (databaseCheckRunning || System.currentTimeMillis() - lastDatabaseCheck < 15000) return;
        databaseCheckRunning = true; lastDatabaseCheck = System.currentTimeMillis();
        background.execute(() -> {
            boolean connected;
            try { server.getUserRepository().findById(new java.util.UUID(0, 0)); connected = true; }
            catch (Exception unavailable) { connected = false; }
            boolean result = connected;
            SwingUtilities.invokeLater(() -> {
                databaseCheckRunning = false;
                if (!disposed && controller.bootstrap() == server && server.isRunning()) {
                    databaseConnected = result; databaseMessage = result ? "PostgreSQL online" : "Không kết nối";
                }
            });
        });
    }

    private void disconnectClient(String id) {
        ServerBootstrap server = controller.bootstrap();
        if (id == null || server == null || server.getRealtimeServer() == null) return;
        if (JOptionPane.showConfirmDialog(this, "Đóng WebSocket của thiết bị này và kết thúc các phiên signaling liên quan?",
                "Ngắt kết nối thiết bị", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        background.execute(() -> {
            // Gateway close also triggers subscription/transfer cleanup; removing the registry alone is insufficient.
            if (server.getRealtimeServer().disconnectConnection(id, "ADMIN_DISCONNECT", "Disconnected from LANDrop Server Desktop")) {
                server.getOperationalLogService().warn("ADMIN_CLIENT_DISCONNECT", DesktopAdminPanel.ACTOR, id,
                        "Disconnected client from LANDrop Server Desktop.");
            }
            SwingUtilities.invokeLater(this::refreshDashboard);
        });
    }
    private void cancelSignalingTransfer(String id) {
        ServerBootstrap server = controller.bootstrap();
        if (id == null || server == null || server.getRealtimeServer() == null) return;
        if (JOptionPane.showConfirmDialog(this, "Hủy phiên signaling này? Hai phía sẽ nhận thông báo kết thúc phiên.",
                "Hủy phiên truyền", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        background.execute(() -> {
            if (server.getRealtimeServer().cancelTransferFromAdmin(id)) {
                server.getOperationalLogService().warn("ADMIN_TRANSFER_CANCEL", DesktopAdminPanel.ACTOR, id,
                        "Cancelled signaling transfer from LANDrop Server Desktop.");
            }
            SwingUtilities.invokeLater(this::refreshDashboard);
        });
    }
    private void applyConfiguration(DesktopServerSettings next) {
        ServerBootstrap current = controller.bootstrap();
        if (current != null && next.externalClient() && (next.clientPort() == actualHttpPort() || next.clientPort() == actualWsPort())) {
            throw new IllegalArgumentException("Cổng Next.js đang trùng dịch vụ đang chạy. Dừng máy chủ trước khi áp dụng cấu hình này.");
        }
        settings = next; dashboard.setSettings(next); refreshUrls();
        ServerBootstrap running = controller.bootstrap();
        boolean needsRestart = running != null && (!next.bindHost().equals(running.getConfig().getHost())
                || next.httpPort() != running.getConfig().getHttpPort() || next.wsPort() != running.getConfig().getWsPort());
        dashboard.settingsFeedback(needsRestart ? "Đã áp dụng. Khởi động lại máy chủ để sử dụng cấu hình mạng mới."
                : "Đã áp dụng cho lần chạy desktop hiện tại.", false);
    }
    private void openBrowser(String url) {
        background.execute(() -> {
            try {
                if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) throw new IllegalStateException();
                Desktop.getDesktop().browse(URI.create(url));
            } catch (Exception unavailable) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Không thể mở trình duyệt. Sao chép URL và mở thủ công.",
                        "Mở giao diện web", JOptionPane.INFORMATION_MESSAGE));
            }
        });
    }
    private void closeWindow() {
        if (settings.minimizeToTray() && tray.isAvailable()) {
            setVisible(false); tray.showNotification("LANDrop Server", "Máy chủ vẫn chạy. Mở lại từ biểu tượng khay hệ thống.", TrayIcon.MessageType.INFO);
        } else quit();
    }
    private void quit() {
        if (closing) return;
        if (controller.bootstrap() != null && JOptionPane.showConfirmDialog(this, "Dừng máy chủ và thoát LANDrop Server?",
                "Thoát ứng dụng", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        closing = true;
        dashboard.administration().setRuntime(null, null, false);
        dashboard.setLifecycle(DesktopServerController.State.STOPPING, "Đang dừng dịch vụ trước khi thoát…", false);
        controller.shutdown().whenComplete((ignored, failure) -> SwingUtilities.invokeLater(() -> {
            if (failure != null) {
                closing = false; dashboard.setLifecycle(DesktopServerController.State.FAILED, "Dừng dịch vụ chưa hoàn tất. Kiểm tra nhật ký.", true);
                return;
            }
            disposed = true; refreshTimer.stop(); dashboard.administration().close();
            AppLogger.removeLogListener(logListener); tray.dispose(); background.shutdownNow();
            super.dispose();
        }));
    }
}
