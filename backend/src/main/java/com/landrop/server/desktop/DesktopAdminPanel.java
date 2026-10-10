package com.landrop.server.desktop;

import com.landrop.admin.dto.*;
import com.landrop.admin.service.AdminControlCenterService;
import com.landrop.database.model.User;
import com.landrop.database.repository.AdminDataRepository;
import com.landrop.server.config.ServerConfig;
import com.landrop.util.AppLogger;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Local administration, authorized by access to the server's OS desktop session. */
public final class DesktopAdminPanel extends JPanel implements AutoCloseable {
    static final String ACTOR = "DESKTOP_OPERATOR";
    private static final int PAGE_SIZE = 100;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "desktop-administration"); thread.setDaemon(true); return thread;
    });
    private final JTabbedPane tabs = new JTabbedPane();
    private final JLabel feedback = new JLabel("Khởi động máy chủ để quản trị dữ liệu.");
    private final JLabel metrics = new JLabel(" ");
    private final JPanel controls = new JPanel(new BorderLayout(0, 12));
    private final JTextField userSearch = new JTextField(18);
    private final JComboBox<String> userStatus = new JComboBox<>(new String[]{"Tất cả", "ACTIVE", "DISABLED"});
    private final JCheckBox activeSessions = new JCheckBox("Chỉ phiên còn hiệu lực", true);
    private final JTextField sessionUser = new JTextField(22);
    private final JTextField workspaceSearch = new JTextField(20);
    private final JComboBox<AdminDataRepository.Dataset> dataset = new JComboBox<>(AdminDataRepository.Dataset.values());
    private final JTextField auditSearch = new JTextField(18);
    private final JComboBox<String> auditLevel = new JComboBox<>(new String[]{"Tất cả", "INFO", "WARN", "ERROR"});
    private final JTable users = table("Tài khoản", "Tên hiển thị", "Email", "Trạng thái", "Quyền", "Phiên", "ID");
    private final JTable sessions = table("Tài khoản", "Thiết bị", "Tạo lúc", "Hết hạn", "Trạng thái", "Online", "ID", "User ID");
    private final JTable workspaces = table("Tên", "Slug", "Chủ sở hữu", "Thành viên", "Tạo lúc", "ID");
    private final JTable data = table("Dữ liệu");
    private final JTable audit = table("Thời điểm", "Mức", "Sự kiện", "Người thao tác", "Đối tượng", "Chi tiết");
    private final JTextArea information = new JTextArea();
    private final JTextField heartbeatInterval = new JTextField(12);
    private final JTextField heartbeatTimeout = new JTextField(12);
    private final JComboBox<String> environment = new JComboBox<>(new String[]{"development", "production", "test"});
    private final JComboBox<String> logLevel = new JComboBox<>(new String[]{"DEBUG", "INFO", "WARN", "ERROR"});
    private final Consumer<ServerConfig> configurationChanged;
    private final JLabel userPage = new JLabel();
    private final JLabel sessionPage = new JLabel();
    private final JLabel dataPage = new JLabel();
    private List<AdminUserDto> userRows = List.of();
    private List<AdminWorkspaceDto> workspaceRows = List.of();
    private volatile AdminControlCenterService service;
    private AdminDataRepository dataRepository;
    private int userOffset, sessionOffset, dataOffset;
    private long revision;
    private boolean busy, closed, databaseAvailable;

    public DesktopAdminPanel(Consumer<ServerConfig> configurationChanged) {
        this.configurationChanged = configurationChanged;
        setLayout(new BorderLayout(0, 12)); setOpaque(false);
        JPanel header = new JPanel(new BorderLayout(0, 8)); header.setOpaque(false);
        feedback.putClientProperty("html.disable", true);
        header.add(metrics, BorderLayout.NORTH); header.add(feedback); add(header, BorderLayout.NORTH);
        tabs.addTab("Người dùng", usersPage());
        tabs.addTab("Phiên đăng nhập", sessionsPage());
        tabs.addTab("Workspace", workspacesPage());
        tabs.addTab("Dữ liệu lưu trữ", dataPage());
        tabs.addTab("Nhật ký quản trị", auditPage());
        tabs.addTab("Thông tin server", serverPage());
        controls.add(tabs); add(controls);
        tabs.addChangeListener(event -> reload());
        applyFont(this);
        enableControls();
    }

    /** EDT only. Invalidates in-flight callbacks when the runtime changes or stops. */
    public void setRuntime(AdminControlCenterService next, AdminDataRepository repository, boolean databaseAvailable) {
        if (closed || (service == next && this.databaseAvailable == databaseAvailable)) return;
        service = next; dataRepository = repository; this.databaseAvailable = databaseAvailable;
        revision++; busy = false; userOffset = sessionOffset = dataOffset = 0;
        userRows = List.of(); workspaceRows = List.of();
        for (JTable table : List.of(users, sessions, workspaces, data, audit)) model(table).setRowCount(0);
        information.setText(""); metrics.setText(" ");
        feedback.setText(next == null ? "Khởi động máy chủ để quản trị dữ liệu."
                : databaseAvailable ? "Đang tải dữ liệu…" : "Chưa cấu hình database. Nhật ký và thông tin server vẫn khả dụng.");
        enableControls(); if (next != null) reload();
    }

    private JPanel usersPage() {
        userSearch.putClientProperty("JTextField.placeholderText", "Tìm tài khoản, tên hoặc email");
        JPanel page = page(users);
        page.add(bar(userSearch, userStatus, button("Tìm", () -> { userOffset = 0; reload(); }),
                button("Làm mới", this::reload)), BorderLayout.NORTH);
        page.add(bar(button("Chi tiết", this::userDetails), button("Sửa tài khoản", this::editUser),
                button("Khóa / mở khóa", this::toggleUser), button("Thu hồi mọi phiên", this::revokeUser),
                button("Xóa tài khoản", this::deleteUser), button("‹", () -> { userOffset = Math.max(0, userOffset - PAGE_SIZE); reload(); }),
                userPage, button("›", () -> { if (userRows.size() == PAGE_SIZE) { userOffset += PAGE_SIZE; reload(); } })), BorderLayout.SOUTH);
        userSearch.addActionListener(event -> { userOffset = 0; reload(); });
        return page;
    }

    private JPanel sessionsPage() {
        sessionUser.putClientProperty("JTextField.placeholderText", "User UUID (để trống: tất cả)");
        JPanel page = page(sessions);
        page.add(bar(activeSessions, sessionUser, button("Tìm phiên", () -> { sessionOffset = 0; reload(); })), BorderLayout.NORTH);
        page.add(bar(button("Thu hồi phiên", () -> {
            UUID id = selectedUuid(sessions, 6); if (id == null) return;
            mutate("Thu hồi phiên đăng nhập và ngắt kết nối liên quan?", current -> requireChanged(current.revokeSession(id, ACTOR)));
        }), button("‹", () -> { sessionOffset = Math.max(0, sessionOffset - PAGE_SIZE); reload(); }), sessionPage,
                button("›", () -> { if (model(sessions).getRowCount() == PAGE_SIZE) { sessionOffset += PAGE_SIZE; reload(); } })), BorderLayout.SOUTH);
        return page;
    }

    private JPanel workspacesPage() {
        workspaceSearch.putClientProperty("JTextField.placeholderText", "Tìm tên hoặc slug workspace");
        JPanel page = page(workspaces);
        page.add(bar(workspaceSearch, button("Tìm workspace", this::reload)), BorderLayout.NORTH);
        page.add(bar(button("Tạo workspace", () -> editWorkspace(true)), button("Sửa workspace", () -> editWorkspace(false)),
                button("Thành viên", this::members), button("Xóa workspace", () -> {
                    UUID id = selectedUuid(workspaces, 5); if (id == null) return;
                    mutate("Xóa workspace và toàn bộ kênh, tin nhắn, thư mục, thông tin tệp liên quan?", current -> requireChanged(current.deleteWorkspace(id, ACTOR)));
                })), BorderLayout.SOUTH);
        return page;
    }

    private JPanel dataPage() {
        JPanel page = page(data);
        var heading = new JPanel(new BorderLayout(0, 6)); heading.setOpaque(false);
        heading.add(bar(dataset, button("Đọc dữ liệu", () -> { dataOffset = 0; reload(); })), BorderLayout.NORTH);
        heading.add(new JLabel("Thông tin trong PostgreSQL; tệp LAN có thể nằm trên thiết bị người dùng."));
        page.add(heading, BorderLayout.NORTH);
        page.add(bar(button("Xem bản ghi", () -> {
            int selected = data.getSelectedRow(); if (selected < 0) return;
            int row = data.convertRowIndexToModel(selected); var text = new StringBuilder();
            for (int col = 0; col < model(data).getColumnCount(); col++) text.append(model(data).getColumnName(col)).append(": ").append(model(data).getValueAt(row, col)).append('\n');
            showText("Bản ghi database", text.toString());
        }), button("‹", () -> { dataOffset = Math.max(0, dataOffset - PAGE_SIZE); reload(); }), dataPage,
                button("›", () -> { if (model(data).getRowCount() == PAGE_SIZE) { dataOffset += PAGE_SIZE; reload(); } })), BorderLayout.SOUTH);
        dataset.addActionListener(event -> { dataOffset = 0; reload(); });
        return page;
    }

    private JPanel auditPage() {
        JPanel page = page(audit);
        page.add(bar(auditLevel, auditSearch, button("Lọc nhật ký", this::reload), new JLabel("Tối đa 1.000 sự kiện gần nhất")), BorderLayout.NORTH);
        return page;
    }

    private JPanel serverPage() {
        JPanel page = new JPanel(new BorderLayout(0, 12)); page.setOpaque(false);
        information.setEditable(false); information.setFont(new Font("Consolas", Font.PLAIN, 12));
        page.add(new JScrollPane(information));
        JPanel form = new JPanel(new GridLayout(0, 2, 12, 8));
        form.add(new JLabel("Heartbeat interval (ms)")); form.add(heartbeatInterval);
        form.add(new JLabel("Heartbeat timeout (ms)")); form.add(heartbeatTimeout);
        form.add(new JLabel("Môi trường")); form.add(environment);
        form.add(new JLabel("Mức nhật ký")); form.add(logLevel);
        form.add(button("Lưu cấu hình dịch vụ", () -> {
            try {
                Map<String, Object> values = Map.of("heartbeatIntervalMs", Long.parseLong(heartbeatInterval.getText().trim()),
                        "heartbeatTimeoutMs", Long.parseLong(heartbeatTimeout.getText().trim()),
                        "environment", environment.getSelectedItem(), "logLevel", logLevel.getSelectedItem());
                submit(current -> current.updateConfig(values, ACTOR), config -> {
                    configurationChanged.accept(config); feedback.setText("Đã lưu. Khởi động lại máy chủ để áp dụng cấu hình dịch vụ.");
                });
            } catch (NumberFormatException invalid) { feedback.setText("Heartbeat phải là số nguyên."); }
        })); form.add(button("Đọc thông tin server", this::reload));
        page.add(form, BorderLayout.SOUTH); return page;
    }

    public void reload() {
        if (service == null || busy || closed) return;
        int tab = tabs.getSelectedIndex();
        if (tab < 4 && !databaseAvailable) return;
        String query = userSearch.getText().trim(); String status = userStatus.getSelectedIndex() == 0 ? null : (String) userStatus.getSelectedItem();
        String groupQuery = workspaceSearch.getText().trim(); boolean active = activeSessions.isSelected();
        String sessionFilter = sessionUser.getText().trim(); String search = auditSearch.getText().trim();
        String level = auditLevel.getSelectedIndex() == 0 ? null : (String) auditLevel.getSelectedItem();
        var selectedDataset = (AdminDataRepository.Dataset) dataset.getSelectedItem();
        int uOffset = userOffset, sOffset = sessionOffset, dOffset = dataOffset;
        var repository = dataRepository;
        submit(current -> {
            Object result = switch (tab) {
                case 0 -> current.listUsers(uOffset, PAGE_SIZE, query, status);
                case 1 -> current.listSessions(active, sessionFilter.isBlank() ? null : UUID.fromString(sessionFilter), PAGE_SIZE, sOffset);
                case 2 -> current.listWorkspaces(groupQuery);
                case 3 -> { if (repository == null) throw new SQLException("Database chưa khả dụng"); yield repository.read(selectedDataset, dOffset, PAGE_SIZE); }
                case 4 -> current.getLogs(1000, 0, level, null, search);
                default -> {
                    var config = current.getServerConfigManager().getCurrentConfig();
                    var info = current.getServerInfo(null, config.getHttpPort(), config.getWsPort());
                    info.put("networkInterfaces", current.getNetworkInterfaces(config.getHost()));
                    yield info;
                }
            };
            // Audit and server views remain usable when the configured database is temporarily offline.
            return new Object[]{result, tab < 4 ? current.getDashboard() : null, current.getConfig()};
        }, result -> {
            // A user may switch tabs while a query is running. Render the captured page, then load the selected one.
            render(tab, result[0]);
            if (result[1] instanceof AdminDashboardDto summary) metrics.setText("Database: " + summary.registeredUsers() + " tài khoản · "
                    + summary.registeredDevices() + " thiết bị · " + summary.activeSessions() + " phiên còn hiệu lực · " + summary.workspaceCount() + " workspace");
            if (tab == 5) renderConfig(result[2]);
            feedback.setText("Đã cập nhật · " + java.time.LocalTime.now().withNano(0));
            if (tabs.getSelectedIndex() != tab) reload();
        });
    }

    @SuppressWarnings("unchecked")
    private void render(int tab, Object result) {
        switch (tab) {
            case 0 -> {
                userRows = (List<AdminUserDto>) result;
                replace(users, userRows.stream().map(u -> new Object[]{u.username(), u.displayName(), u.email(), u.status(), u.systemRole(), u.activeSessionsCount(), u.id()}).toList(), 6);
                userPage.setText("Trang " + (userOffset / PAGE_SIZE + 1) + " · " + userRows.size() + " tài khoản");
            }
            case 1 -> {
                var rows = (List<AdminSessionDto>) result;
                replace(sessions, rows.stream().map(s -> new Object[]{s.username(), s.deviceName(), s.createdAt(), s.expiresAt(), s.revoked() ? "Đã thu hồi" : s.isExpired() ? "Hết hạn" : "Còn hiệu lực", s.isOnline(), s.id(), s.userId()}).toList(), 6);
                sessionPage.setText("Trang " + (sessionOffset / PAGE_SIZE + 1) + " · " + rows.size() + " phiên");
            }
            case 2 -> {
                workspaceRows = (List<AdminWorkspaceDto>) result;
                replace(workspaces, workspaceRows.stream().map(w -> new Object[]{w.name(), w.slug(), w.ownerUsername(), w.memberCount(), w.createdAt(), w.id()}).toList(), 5);
            }
            case 3 -> {
                var page = (AdminDataRepository.DataPage) result;
                data.setRowSorter(null); model(data).setDataVector(page.rows().stream().map(List::toArray).toArray(Object[][]::new), page.columns().toArray());
                data.setAutoCreateRowSorter(true);
                for (int col = 0; col < page.columns().size(); col++) {
                    String name = page.columns().get(col);
                    data.getColumnModel().getColumn(col).setPreferredWidth(name.equals("id") || name.endsWith("_id") || name.equals("content") ? 280 : 160);
                }
                dataPage.setText("Trang " + (dataOffset / PAGE_SIZE + 1) + " · " + page.total() + " bản ghi");
            }
            case 4 -> {
                var rows = (List<com.landrop.admin.logging.OperationalLogEntry>) result;
                replace(audit, rows.stream().map(e -> new Object[]{e.timestamp(), e.level(), e.eventType(), e.actor(), e.target(), e.details()}).toList(), -1);
            }
            default -> {
                var info = (Map<String, Object>) result; var text = new StringBuilder();
                info.forEach((key, value) -> text.append(key).append(": ").append(value).append('\n'));
                information.setText(text.toString()); information.setCaretPosition(0);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void renderConfig(Object config) {
        var configuration = (Map<String, Object>) config;
        for (String key : List.of("current", "pending")) {
            information.append("\n" + (key.equals("current") ? "CẤU HÌNH ĐANG CHẠY" : "CẤU HÌNH ĐÃ LƯU") + "\n");
            if (configuration.get(key) instanceof Map<?, ?> fields) {
                fields.forEach((name, value) -> information.append(name + ": " + value + "\n"));
            }
        }
        var values = (Map<String, Object>) configuration.get("pending");
        if (values == null) return;
        heartbeatInterval.setText(String.valueOf(values.get("heartbeatIntervalMs")));
        heartbeatTimeout.setText(String.valueOf(values.get("heartbeatTimeoutMs")));
        environment.setSelectedItem(values.get("environment")); logLevel.setSelectedItem(values.get("logLevel"));
    }

    private AdminUserDto selectedUser() {
        UUID id = selectedUuid(users, 6);
        return userRows.stream().filter(user -> user.id().equals(id)).findFirst().orElse(null);
    }
    private void userDetails() {
        var user = selectedUser(); if (user == null) return;
        submit(current -> current.getUserIdentity(user.id()).orElseThrow(), identity -> {
            var text = new StringBuilder("User ID: " + user.id() + "\nTài khoản: " + user.username() + "\nEmail: " + user.email()
                    + "\nQuyền: " + user.systemRole() + "\nTrạng thái: " + user.status() + "\nTạo lúc: " + user.createdAt() + "\n\nTHIẾT BỊ\n");
            identity.devices().forEach(d -> text.append(d.id()).append(" · ").append(d.deviceName()).append(" · ").append(d.platform()).append(" · ").append(d.isOnline() ? "Online" : "Offline").append("\n"));
            text.append("\nPHIÊN ĐĂNG NHẬP (tối đa 500)\n");
            identity.sessions().forEach(s -> text.append(s.id()).append(" · ").append(s.isActive() ? "Còn hiệu lực" : "Hết hạn / thu hồi").append(" · ").append(s.deviceName()).append("\n"));
            text.append("\nKẾT NỐI ONLINE\n");
            identity.connections().forEach(c -> text.append(c.connectionId()).append(" · ").append(c.deviceName()).append(" · ").append(c.remoteAddress()).append("\n"));
            showText("Danh tính người dùng", text.toString());
        });
    }
    private void editUser() {
        var user = selectedUser(); if (user == null) return;
        var name = new JTextField(user.displayName()); var role = new JComboBox<>(new String[]{User.ROLE_USER, User.ROLE_SYSTEM_ADMIN}); role.setSelectedItem(user.systemRole());
        if (!form("Sửa " + user.username(), "Tên hiển thị", name, "Quyền hệ thống", role)) return;
        var values = Map.<String, Object>of("displayName", name.getText(), "systemRole", role.getSelectedItem());
        mutate("Lưu tên hiển thị và quyền của " + user.username() + "?", current -> current.updateUser(user.id(), null, values, ACTOR));
    }
    private void toggleUser() {
        var user = selectedUser(); if (user == null) return;
        boolean disable = User.STATUS_ACTIVE.equals(user.status());
        mutate((disable ? "Khóa tài khoản, thu hồi mọi phiên và ngắt kết nối: " : "Mở khóa tài khoản: ") + user.username() + "?",
                current -> requireChanged(disable ? current.disableUser(user.id(), null, ACTOR) : current.enableUser(user.id(), ACTOR)));
    }
    private void revokeUser() {
        var user = selectedUser(); if (user != null) mutate("Thu hồi mọi phiên và ngắt kết nối của " + user.username() + "?", current -> current.revokeUserSessions(user.id(), ACTOR));
    }
    private void deleteUser() {
        var user = selectedUser(); if (user != null) mutate("Xóa vĩnh viễn tài khoản " + user.username() + "?", current -> requireChanged(current.deleteUser(user.id(), null, ACTOR)));
    }
    private void editWorkspace(boolean create) {
        UUID id = create ? null : selectedUuid(workspaces, 5);
        var group = workspaceRows.stream().filter(w -> w.id().equals(id)).findFirst().orElse(null);
        if (!create && group == null) return;
        var name = new JTextField(create ? "" : group.name()); var description = new JTextField(create ? "" : group.description());
        var owner = new JTextField(create ? "" : group.ownerId().toString()); owner.setEditable(create);
        if (!form(create ? "Tạo workspace" : "Sửa workspace", "Tên", name, "Mô tả", description, "Owner UUID", owner)) return;
        var values = Map.<String, Object>of("name", name.getText(), "description", description.getText(), "ownerId", owner.getText());
        mutate("Lưu workspace " + name.getText() + "?", current -> create ? current.createWorkspace(values, ACTOR) : current.updateWorkspace(id, values, ACTOR));
    }

    private void members() {
        UUID id = selectedUuid(workspaces, 5); if (id == null) return;
        var origin = service;
        submit(current -> current.listWorkspaceMembers(id), rows -> {
            JTable members = table("Tài khoản", "Tên hiển thị", "Quyền", "Tham gia lúc", "User ID");
            replace(members, rows.stream().map(m -> new Object[]{m.username(), m.displayName(), m.role(), m.joinedAt(), m.userId()}).toList(), 4);
            JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this), "Thành viên workspace", Dialog.ModalityType.APPLICATION_MODAL);
            JPanel panel = page(members);
            panel.add(bar(button("Thêm / đổi quyền", () -> {
                if (service != origin || busy) { dialog.dispose(); return; }
                UUID selected = selectedUuid(members, 4);
                var user = new JTextField(selected == null ? "" : selected.toString());
                var role = new JComboBox<>(new String[]{"MEMBER", "ADMIN", "OWNER"});
                if (!form("Thành viên", "User UUID", user, "Quyền", role)) return;
                var values = Map.<String, Object>of("userId", user.getText(), "role", role.getSelectedItem());
                mutate("Lưu thành viên workspace?", current -> current.upsertWorkspaceMember(id, values, ACTOR)); dialog.dispose();
            }), button("Xóa thành viên", () -> {
                if (service != origin || busy) { dialog.dispose(); return; }
                UUID userId = selectedUuid(members, 4); if (userId == null) return;
                mutate("Xóa thành viên khỏi workspace?", current -> requireChanged(current.removeWorkspaceMember(id, userId, ACTOR))); dialog.dispose();
            })), BorderLayout.SOUTH);
            dialog.setContentPane(panel); dialog.setSize(780, 420); dialog.setLocationRelativeTo(this); dialog.setVisible(true);
        });
    }

    @FunctionalInterface interface Query<T> { T run(AdminControlCenterService current) throws Exception; }
    private void mutate(String question, Query<?> operation) {
        var origin = service;
        if (origin == null || busy) return;
        if (JOptionPane.showConfirmDialog(this, question, "Xác nhận quản trị", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        if (service != origin) { feedback.setText("Máy chủ đã thay đổi. Hãy tải lại dữ liệu."); return; }
        submit(operation, ignored -> { feedback.setText("Thao tác hoàn tất."); reload(); });
    }

    /** Captures one runtime; all JDBC and mutations run off the EDT, stale callbacks are discarded. */
    <T> java.util.concurrent.CompletableFuture<Void> submit(Query<T> operation, Consumer<T> result) {
        var completion = new java.util.concurrent.CompletableFuture<Void>();
        if (service == null || busy || closed) { completion.complete(null); return completion; }
        var origin = service; long version = revision; busy = true; feedback.setText("Đang xử lý…"); enableControls();
        worker.execute(() -> {
            try {
                if (service != origin) { completion.complete(null); return; }
                T value = operation.run(origin);
                SwingUtilities.invokeLater(() -> {
                    try {
                        if (closed || revision != version || service != origin) return;
                        busy = false; enableControls(); result.accept(value);
                    } finally { completion.complete(null); }
                });
            } catch (Exception failure) {
                SwingUtilities.invokeLater(() -> {
                    if (closed || revision != version || service != origin) { completion.complete(null); return; }
                    busy = false; enableControls();
                    // SQL diagnostics can contain connection details. Keep them out of the UI and logs.
                    feedback.setText(failure instanceof SQLException ? "Không thể truy xuất database. Kiểm tra kết nối và migrations, rồi làm mới."
                            : failure instanceof IllegalArgumentException ? "Dữ liệu không hợp lệ. Kiểm tra UUID và giá trị nhập."
                            : failure instanceof com.landrop.auth.exception.ValidationException ? failure.getMessage() : "Không thể hoàn tất thao tác. Hãy tải lại dữ liệu.");
                    AppLogger.warn("DesktopAdmin", "Administration operation failed: " + failure.getClass().getSimpleName());
                    completion.completeExceptionally(failure);
                });
            }
        });
        return completion;
    }

    private void enableControls() {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            boolean enabled = service != null && !busy && (i >= 4 || databaseAvailable);
            enableTree((Container) tabs.getComponentAt(i), enabled);
        }
    }
    private static void enableTree(Container parent, boolean enabled) {
        for (Component child : parent.getComponents()) {
            child.setEnabled(enabled); if (child instanceof Container nested) enableTree(nested, enabled);
        }
    }
    private static Object requireChanged(boolean changed) {
        if (!changed) throw new com.landrop.auth.exception.ValidationException("Bản ghi không còn tồn tại hoặc không thể thay đổi. Hãy tải lại dữ liệu.");
        return changed;
    }
    static JTable table(String... columns) {
        var model = new DefaultTableModel(columns, 0) { @Override public boolean isCellEditable(int row, int col) { return false; } };
        var table = new JTable(model); table.setAutoCreateRowSorter(true); table.setRowHeight(32);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, value, selected, focus, row, col);
                putClientProperty("html.disable", true); setToolTipText(value == null ? null : value.toString()); return this;
            }
        });
        for (int col = 0; col < columns.length; col++) table.getColumnModel().getColumn(col).setPreferredWidth(col == columns.length - 1 ? 280 : 160);
        return table;
    }
    private static DefaultTableModel model(JTable table) { return (DefaultTableModel) table.getModel(); }
    static UUID selectedUuid(JTable table, int column) {
        int row = table.getSelectedRow(); return row < 0 ? null : UUID.fromString(String.valueOf(model(table).getValueAt(table.convertRowIndexToModel(row), column)));
    }
    private static void replace(JTable table, List<Object[]> rows, int idColumn) {
        String selected = null;
        if (idColumn >= 0 && table.getSelectedRow() >= 0) selected = String.valueOf(model(table).getValueAt(table.convertRowIndexToModel(table.getSelectedRow()), idColumn));
        model(table).setRowCount(0); rows.forEach(model(table)::addRow);
        if (idColumn >= 0) ServerDashboardPanel.restoreSelection(table, model(table), idColumn, selected);
    }
    private static JPanel page(JTable table) {
        var panel = new JPanel(new BorderLayout(0, 10)); panel.setOpaque(false);
        var scroll = new JScrollPane(table); scroll.setColumnHeaderView(table.getTableHeader());
        table.setFillsViewportHeight(true); panel.add(scroll); return panel;
    }
    private static void applyFont(Container parent) {
        for (Component child : parent.getComponents()) {
            child.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            if (child instanceof Container nested) applyFont(nested);
        }
    }
    private static JPanel bar(Component... components) {
        var panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6)); panel.setOpaque(false);
        for (var component : components) panel.add(component); return panel;
    }
    private static JButton button(String label, Runnable action) { var button = new JButton(label); button.addActionListener(event -> action.run()); return button; }
    private boolean form(String title, Object... fields) {
        var panel = new JPanel(new GridLayout(fields.length / 2, 2, 12, 10));
        for (int i = 0; i < fields.length; i += 2) { panel.add(new JLabel((String) fields[i])); panel.add((Component) fields[i + 1]); }
        var origin = service;
        return JOptionPane.showConfirmDialog(this, panel, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION && service == origin;
    }
    private void showText(String title, String text) {
        var area = new JTextArea(text); area.setEditable(false); area.setLineWrap(true); area.setWrapStyleWord(true); area.setCaretPosition(0);
        var scroll = new JScrollPane(area); scroll.setPreferredSize(new Dimension(760, 430));
        JOptionPane.showMessageDialog(this, scroll, title, JOptionPane.PLAIN_MESSAGE);
    }
    @Override public void close() { closed = true; service = null; revision++; worker.shutdownNow(); }
}
