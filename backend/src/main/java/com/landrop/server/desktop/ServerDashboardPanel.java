package com.landrop.server.desktop;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Desktop view, independently renderable without starting servers or opening native windows. */
public final class ServerDashboardPanel extends JPanel {
    public interface Actions {
        void start(); void stop(); void restart(); void openClient(); void openAdmin();
        void copy(String text); void refreshNetwork(); void disconnect(String connectionId);
        void cancelTransfer(String transferId); void applySettings(DesktopServerSettings settings);
    }
    public record ClientRow(String id, String device, String user, String address, Instant connectedAt, boolean authenticated) { }
    public record TransferRow(String id, String fileName, long size, String sender, String receiver, String status, Instant expiresAt) { }
    public record Activity(Instant at, String level, String text) { }
    private static final Color NAVY = new Color(0x111C30);
    private static final Color BLUE = new Color(0x4779F3);
    private static final Color GREEN = new Color(0x169C72);
    private static final Color AMBER = new Color(0xD28A28);
    private static final Color RED = new Color(0xD95562);
    private static final Font FONT = new Font("Segoe UI", Font.PLAIN, 13);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private final Actions actions;
    private final List<Runnable> themeBindings = new ArrayList<>();
    private final CardLayout pages = new CardLayout();
    private final JPanel pageContainer = new JPanel(pages);
    private final List<JToggleButton> navigation = new ArrayList<>();
    private final JLabel pageTitle = new JLabel("Tổng quan máy chủ");
    private final JLabel pageDescription = new JLabel();
    private final JLabel statusBadge = new JLabel();
    private final JLabel sidebarStatus = new JLabel();
    private final JLabel notice = new JLabel();
    private final JLabel footer = new JLabel();
    private final JButton startButton;
    private final JButton stopButton;
    private final JButton restartButton;
    private final JButton themeButton;
    private final JButton clientButton;
    private final JButton adminButton;
    private final JLabel clientsMetric = new JLabel("0");
    private final JLabel transfersMetric = new JLabel("0");
    private final JLabel uptimeMetric = new JLabel("00:00:00");
    private final JLabel memoryMetric = new JLabel("—");
    private final JLabel apiHealth = new JLabel("Chưa chạy");
    private final JLabel wsHealth = new JLabel("Chưa chạy");
    private final JLabel databaseHealth = new JLabel("Chưa kiểm tra");
    private final JLabel accessHealth = new JLabel("Chưa chạy");
    private final JLabel clientLink = new JLabel();
    private final JLabel connectionHint = new JLabel();
    private final JLabel backendLink = new JLabel();
    private final JLabel qrLabel = new JLabel("QR", SwingConstants.CENTER);
    private final JComboBox<NetworkHelper.LanInterfaceInfo> interfaces = new JComboBox<>();
    private final JLabel recentActivity = new JLabel();
    private final JPanel recentLines = new JPanel();
    private final DefaultTableModel clientModel = model("Thiết bị / trình duyệt", "Người dùng", "Địa chỉ mạng", "Kết nối lúc", "Xác thực", "id");
    private final DefaultTableModel transferModel = model("Tệp", "Kích thước", "Người gửi", "Người nhận", "Trạng thái", "Còn lại", "id");
    private final JTable clientTable = table(clientModel, 5);
    private final JTable transferTable = table(transferModel, 6);
    private final JTextField clientSearch = new JTextField();
    private final JTextField transferSearch = new JTextField();
    private final JLabel clientCount = new JLabel("0 kết nối");
    private final JLabel transferCount = new JLabel("0 phiên đang hoạt động");
    private final JButton disconnectButton;
    private final JButton cancelButton;
    private final JLabel selectedDevice = new JLabel("Chọn một thiết bị");
    private final JTextArea selectedDetails = new JTextArea();
    private final JTextArea logText = new JTextArea();
    private final JComboBox<String> logFilter = new JComboBox<>(new String[]{"Tất cả mức", "INFO", "WARN", "ERROR"});
    private final JTextField logSearch = new JTextField();
    private final JCheckBox autoScroll = new JCheckBox("Theo dõi trực tiếp", true);
    private List<Activity> activity = List.of();
    private Instant logsClearedAt = Instant.MIN;
    private JScrollPane logScroll;
    private final JTextField hostField = new JTextField();
    private final JTextField httpField = new JTextField();
    private final JTextField wsField = new JTextField();
    private final JTextField clientPortField = new JTextField();
    private final JComboBox<String> clientMode = new JComboBox<>(new String[]{"Client đi kèm máy chủ", "Next.js client riêng"});
    private final JCheckBox minimizeTray = new JCheckBox("Đóng cửa sổ để thu nhỏ xuống khay hệ thống");
    private final JLabel settingsFeedback = new JLabel(" ");
    private DesktopServerSettings settings;
    private boolean dark;
    private DesktopServerController.State lifecycleState = DesktopServerController.State.STOPPED;
    private String lifecycleMessage = "";
    private boolean activeRuntime;
    private String selectedPage = "overview";
    private Consumer<String> interfaceChanged = ignored -> { };

    public ServerDashboardPanel(DesktopServerSettings settings, Actions actions, boolean dark) {
        this.settings = settings;
        this.actions = actions;
        this.dark = dark;
        setLayout(new BorderLayout());
        startButton = button("Khởi động", "play", BLUE, actions::start);
        stopButton = button("Dừng", "stop", null, actions::stop);
        restartButton = button("Khởi động lại", "restart", null, actions::restart);
        themeButton = button("Giao diện tối", "theme", null, this::toggleTheme);
        clientButton = button("Mở client", "link", BLUE, actions::openClient);
        adminButton = button("Quản trị web", "shield", null, actions::openAdmin);
        disconnectButton = button("Ngắt kết nối", "stop", RED, () -> actions.disconnect(selectedClientId()));
        cancelButton = button("Hủy phiên đã chọn", "stop", RED, () -> actions.cancelTransfer(selectedTransferId()));
        add(sidebar(), BorderLayout.WEST);
        JPanel main = transparent(new BorderLayout(0, 18));
        main.setBorder(new EmptyBorder(26, 28, 16, 28));
        main.add(header(), BorderLayout.NORTH);
        pageContainer.setOpaque(false);
        pageContainer.add(overview(), "overview");
        pageContainer.add(clientsPage(), "clients");
        pageContainer.add(transfersPage(), "transfers");
        pageContainer.add(logsPage(), "logs");
        pageContainer.add(settingsPage(), "settings");
        main.add(pageContainer, BorderLayout.CENTER);
        JPanel status = transparent(new BorderLayout());
        style(footer, 11, false, true);
        status.add(footer, BorderLayout.WEST);
        JLabel version = label("LANDrop  ·  Protocol v1", 11, false, true);
        status.add(version, BorderLayout.EAST);
        main.add(status, BorderLayout.SOUTH);
        add(main, BorderLayout.CENTER);
        themeBindings.add(() -> setBackground(this.dark ? new Color(0x0E1624) : new Color(0xF4F6FA)));
        bindSearch(clientTable, clientSearch, clientCount, "kết nối");
        bindSearch(transferTable, transferSearch, transferCount, "phiên đang hoạt động");
        clientTable.getSelectionModel().addListSelectionListener(e -> updateSelection());
        transferTable.getSelectionModel().addListSelectionListener(e -> updateSelection());
        interfaces.addActionListener(e -> {
            var item = (NetworkHelper.LanInterfaceInfo) interfaces.getSelectedItem();
            if (item != null) interfaceChanged.accept(item.ip());
        });
        listen(logSearch, this::renderLogs);
        logFilter.addActionListener(e -> renderLogs());
        clientMode.addActionListener(e -> clientPortField.setEnabled(clientMode.getSelectedIndex() == 1));
        setSettings(settings);
        showPage("overview");
        applyPalette();
        setLifecycle(DesktopServerController.State.STOPPED, "Sẵn sàng khởi động máy chủ.", false);
    }

    private JPanel sidebar() {
        JPanel side = new JPanel(new BorderLayout());
        side.setBackground(NAVY);
        side.setPreferredSize(new Dimension(210, 0));
        side.setBorder(new EmptyBorder(30, 18, 24, 18));
        JPanel upper = transparent(new BorderLayout(0, 38));
        JPanel brand = transparent(new BorderLayout(12, 0));
        JLabel mark = new JLabel("L", SwingConstants.CENTER);
        mark.setOpaque(true); mark.setBackground(BLUE); mark.setForeground(Color.WHITE);
        mark.setFont(FONT.deriveFont(Font.BOLD, 25)); mark.setPreferredSize(new Dimension(42, 42));
        JPanel brandText = transparent(new GridLayout(2, 1, 0, 3));
        JLabel name = new JLabel("LANDrop"); name.setForeground(Color.WHITE); name.setFont(FONT.deriveFont(Font.BOLD, 21));
        JLabel product = new JLabel("SERVER CONSOLE"); product.setFont(FONT.deriveFont(10f)); product.setForeground(new Color(0x91A4C2));
        brandText.add(name); brandText.add(product); brand.add(mark, BorderLayout.WEST); brand.add(brandText);
        upper.add(brand, BorderLayout.NORTH);
        JPanel nav = transparent(new GridLayout(6, 1, 0, 8));
        JLabel caption = new JLabel("ĐIỀU KHIỂN MÁY CHỦ"); caption.setForeground(new Color(0x7185A4)); caption.setFont(FONT.deriveFont(Font.BOLD, 10));
        nav.add(caption);
        String[][] entries = {{"overview", "Tổng quan", "home"}, {"clients", "Thiết bị kết nối", "users"},
                {"transfers", "Phiên truyền", "transfer"}, {"logs", "Nhật ký", "logs"}, {"settings", "Cấu hình", "settings"}};
        ButtonGroup group = new ButtonGroup();
        for (String[] entry : entries) {
            JToggleButton item = new JToggleButton(entry[1], new LineIcon(entry[2]));
            item.setActionCommand(entry[0]); item.setHorizontalAlignment(SwingConstants.LEFT);
            item.setIconTextGap(12); item.setFont(FONT.deriveFont(Font.BOLD, 13)); item.setPreferredSize(new Dimension(174, 44));
            item.setBorder(new EmptyBorder(12, 12, 12, 8)); item.setFocusPainted(false);
            item.putClientProperty("JButton.buttonType", "roundRect");
            item.addActionListener(e -> showPage(entry[0])); group.add(item); navigation.add(item); nav.add(item);
        }
        upper.add(nav, BorderLayout.CENTER); side.add(upper, BorderLayout.NORTH);
        JPanel lower = transparent(new GridLayout(4, 1, 0, 10));
        JLabel local = new JLabel("MÁY CHỦ CỤC BỘ"); local.setForeground(new Color(0x7185A4)); local.setFont(FONT.deriveFont(Font.BOLD, 10));
        sidebarStatus.setForeground(new Color(0xCFDAEC)); sidebarStatus.setFont(FONT.deriveFont(Font.BOLD, 13));
        themeButton.setHorizontalAlignment(SwingConstants.LEFT);
        JLabel hint = new JLabel("Cùng mạng. Cùng không gian."); hint.setForeground(new Color(0x7185A4)); hint.setFont(FONT.deriveFont(11f));
        lower.add(local); lower.add(sidebarStatus); lower.add(themeButton); lower.add(hint); side.add(lower, BorderLayout.SOUTH);
        return side;
    }

    private JPanel header() {
        JPanel header = transparent(new BorderLayout(12, 12));
        JPanel titles = transparent(new GridLayout(2, 1, 0, 5));
        style(pageTitle, 26, true, false); style(pageDescription, 12, false, true);
        titles.add(pageTitle); titles.add(pageDescription); header.add(titles, BorderLayout.CENTER);
        JPanel controls = transparent(new FlowLayout(FlowLayout.RIGHT, 8, 7));
        statusBadge.setFont(FONT.deriveFont(Font.BOLD, 11)); statusBadge.setOpaque(true);
        statusBadge.setBorder(new EmptyBorder(9, 12, 9, 12));
        controls.add(statusBadge); controls.add(startButton); controls.add(stopButton); controls.add(restartButton);
        header.add(controls, BorderLayout.EAST);
        style(notice, 12, false, true); notice.setBorder(new EmptyBorder(0, 0, 0, 0));
        header.add(notice, BorderLayout.SOUTH);
        return header;
    }

    private JComponent overview() {
        JPanel body = transparent(new BorderLayout(0, 18));
        JPanel metrics = transparent(new GridLayout(1, 4, 14, 0));
        metrics.add(metric("THIẾT BỊ KẾT NỐI", clientsMetric, "users", "Các kết nối đang online"));
        metrics.add(metric("PHIÊN ĐANG HOẠT ĐỘNG", transfersMetric, "transfer", "Điều phối kết nối P2P"));
        metrics.add(metric("THỜI GIAN HOẠT ĐỘNG", uptimeMetric, "clock", "Tính từ khi server sẵn sàng"));
        metrics.add(metric("BỘ NHỚ ĐANG SỬ DỤNG", memoryMetric, "server", "Bộ nhớ Java hiện tại"));
        body.add(metrics, BorderLayout.NORTH);
        JPanel stack = transparent(new BorderLayout(0, 18));
        stack.add(connectionCard(), BorderLayout.NORTH);
        JPanel lower = transparent(new GridLayout(1, 2, 18, 0));
        JPanel health = surface(new BorderLayout(0, 18));
        health.add(sectionTitle("Tình trạng dịch vụ", "server"), BorderLayout.NORTH);
        JPanel statuses = transparent(new GridLayout(4, 1, 0, 10));
        statuses.add(healthRow("HTTP API", apiHealth)); statuses.add(healthRow("WebSocket", wsHealth));
        statuses.add(healthRow("Xác thực & workspace", accessHealth)); statuses.add(healthRow("Cơ sở dữ liệu", databaseHealth));
        health.add(statuses); health.setPreferredSize(new Dimension(380, 218));
        JPanel recent = surface(new BorderLayout(0, 14));
        JPanel recentHeader = transparent(new BorderLayout()); recentHeader.add(sectionTitle("Hoạt động gần đây", "logs"));
        JButton seeLogs = button("Xem tất cả", "arrow", null, () -> showPage("logs")); recentHeader.add(seeLogs, BorderLayout.EAST);
        recent.add(recentHeader, BorderLayout.NORTH); recentLines.setLayout(new GridLayout(5, 1, 0, 8)); recentLines.setOpaque(false);
        style(recentActivity, 12, false, true); recentActivity.setText("Nhật ký sẽ xuất hiện khi máy chủ hoạt động.");
        recentLines.add(recentActivity); recent.add(recentLines); lower.add(health); lower.add(recent);
        stack.add(lower, BorderLayout.CENTER); body.add(stack, BorderLayout.CENTER);
        return pageScroll(body);
    }

    private JPanel metric(String title, JLabel value, String icon, String detail) {
        JPanel card = surface(new BorderLayout(0, 10));
        JPanel heading = transparent(new BorderLayout()); heading.add(label(title, 10, true, true));
        JLabel graphic = new JLabel(new LineIcon(icon)); graphic.setForeground(BLUE); heading.add(graphic, BorderLayout.EAST);
        card.add(heading, BorderLayout.NORTH); style(value, 25, true, false); card.add(value, BorderLayout.CENTER);
        card.add(label(detail, 11, false, true), BorderLayout.SOUTH); card.setPreferredSize(new Dimension(220, 116));
        return card;
    }

    private JPanel connectionCard() {
        JPanel card = surface(new BorderLayout(24, 16));
        card.add(sectionTitle("Cho người dùng kết nối", "link"), BorderLayout.NORTH);
        JPanel details = transparent(new BorderLayout(0, 12));
        JPanel network = transparent(new BorderLayout(12, 0));
        network.add(label("Mạng chia sẻ", 12, false, true), BorderLayout.WEST);
        interfaces.setFont(FONT); network.add(interfaces);
        network.add(button("Làm mới", "restart", null, actions::refreshNetwork), BorderLayout.EAST); details.add(network, BorderLayout.NORTH);
        JPanel linkBox = new JPanel(new BorderLayout(0, 6)); linkBox.setBorder(new EmptyBorder(12, 14, 12, 14));
        themeBindings.add(() -> linkBox.setBackground(dark ? new Color(0x182A45) : new Color(0xEFF4FF)));
        linkBox.add(label("URL DÀNH CHO CLIENT", 10, true, true), BorderLayout.NORTH);
        clientLink.setFont(new Font("Consolas", Font.BOLD, 14)); clientLink.setForeground(BLUE); clientLink.putClientProperty("html.disable", true);
        linkBox.add(clientLink); linkBox.add(button("Sao chép", "copy", null, () -> actions.copy(clientLink.getText())), BorderLayout.EAST);
        details.add(linkBox);
        JPanel tools = transparent(new BorderLayout(0, 8));
        JPanel links = transparent(new FlowLayout(FlowLayout.LEFT, 8, 0)); links.add(clientButton); links.add(adminButton);
        style(backendLink, 11, false, true); backendLink.setToolTipText("Địa chỉ backend API và gateway WebSocket.");
        tools.add(links, BorderLayout.NORTH); tools.add(backendLink, BorderLayout.CENTER); style(connectionHint, 11, false, true);
        tools.add(connectionHint, BorderLayout.SOUTH); details.add(tools, BorderLayout.SOUTH);
        card.add(details);
        JPanel qr = transparent(new BorderLayout(0, 9)); qrLabel.setPreferredSize(new Dimension(148, 148));
        qrLabel.setOpaque(true); qrLabel.setBackground(Color.WHITE); qrLabel.setForeground(new Color(0x16223A));
        qrLabel.setBorder(new EmptyBorder(5, 5, 5, 5)); qr.add(qrLabel);
        JLabel caption = label("Quét QR để vào client", 11, false, true); caption.setHorizontalAlignment(SwingConstants.CENTER);
        qr.add(caption, BorderLayout.SOUTH); card.add(qr, BorderLayout.EAST);
        return card;
    }

    private JPanel healthRow(String name, JLabel status) {
        JPanel row = transparent(new BorderLayout(8, 0)); row.add(label(name, 12, false, true));
        style(status, 12, true, false); row.add(status, BorderLayout.EAST); return row;
    }

    private JPanel clientsPage() {
        JPanel page = transparent(new BorderLayout(18, 16));
        page.add(searchBar(clientSearch, clientCount, "Tìm thiết bị, tài khoản hoặc địa chỉ IP…"), BorderLayout.NORTH);
        page.add(tableCard(clientTable, "Chưa có thiết bị kết nối", "Chia sẻ URL hoặc mã QR trên trang Tổng quan để người dùng đăng nhập."));
        JPanel detail = surface(new BorderLayout(0, 16)); detail.setPreferredSize(new Dimension(236, 0));
        style(selectedDevice, 16, true, false); detail.add(selectedDevice, BorderLayout.NORTH);
        selectedDetails.setEditable(false); selectedDetails.setLineWrap(true); selectedDetails.setWrapStyleWord(true);
        selectedDetails.setFont(FONT); selectedDetails.setOpaque(false); selectedDetails.setBorder(null);
        themeBindings.add(() -> selectedDetails.setForeground(mutedColor())); detail.add(selectedDetails);
        JPanel controls = transparent(new GridLayout(2, 1, 0, 8));
        controls.add(button("Sao chép mã kết nối", "copy", null, () -> {
            String id = selectedClientId(); if (id != null) actions.copy(id);
        })); controls.add(disconnectButton); detail.add(controls, BorderLayout.SOUTH); page.add(detail, BorderLayout.EAST);
        return page;
    }

    private JPanel transfersPage() {
        JPanel page = transparent(new BorderLayout(0, 16));
        page.add(searchBar(transferSearch, transferCount, "Tìm tệp, người gửi hoặc người nhận…"), BorderLayout.NORTH);
        page.add(tableCard(transferTable, "Chưa có phiên truyền đang hoạt động", "Các yêu cầu và phiên WebRTC xuất hiện tại đây khi người dùng bắt đầu kết nối."));
        JPanel tools = transparent(new BorderLayout(16, 0));
        tools.add(label("Máy chủ hiển thị phiên signaling; dữ liệu tệp được gửi trực tiếp giữa các client.", 11, false, true));
        tools.add(cancelButton, BorderLayout.EAST); page.add(tools, BorderLayout.SOUTH); return page;
    }

    private JPanel searchBar(JTextField field, JLabel count, String placeholder) {
        JPanel toolbar = transparent(new BorderLayout(18, 0)); style(count, 13, true, false); toolbar.add(count, BorderLayout.WEST);
        field.putClientProperty("JTextField.placeholderText", placeholder); field.setPreferredSize(new Dimension(340, 38));
        field.putClientProperty("JComponent.roundRect", true); field.setFont(FONT); toolbar.add(field, BorderLayout.EAST); return toolbar;
    }

    private JPanel tableCard(JTable table, String emptyTitle, String emptyDetail) {
        JPanel card = surface(new BorderLayout(0, 12));
        JPanel contents = transparent(new CardLayout());
        JPanel empty = transparent(new GridBagLayout());
        JPanel message = transparent(new GridLayout(3, 1, 0, 12));
        JLabel icon = new JLabel(new LineIcon("users"), SwingConstants.CENTER); icon.setForeground(BLUE);
        JLabel title = label(emptyTitle, 16, true, false); title.setHorizontalAlignment(SwingConstants.CENTER);
        JTextArea hint = new JTextArea(emptyDetail); hint.setEditable(false); hint.setOpaque(false); hint.setLineWrap(true); hint.setWrapStyleWord(true);
        hint.setFont(FONT.deriveFont(12f)); hint.setPreferredSize(new Dimension(340, 45));
        themeBindings.add(() -> hint.setForeground(mutedColor()));
        message.add(icon); message.add(title); message.add(hint); empty.add(message);
        contents.add(empty, "empty"); contents.add(scroll(table), "table");
        table.getModel().addTableModelListener(e -> ((CardLayout) contents.getLayout()).show(contents,
                table.getModel().getRowCount() == 0 ? "empty" : "table"));
        ((CardLayout) contents.getLayout()).show(contents, "empty"); card.add(contents);
        return card;
    }

    private JPanel logsPage() {
        JPanel page = transparent(new BorderLayout(0, 16));
        JPanel tools = transparent(new BorderLayout(10, 0));
        logSearch.putClientProperty("JTextField.placeholderText", "Tìm trong nhật ký…"); logSearch.setFont(FONT);
        tools.add(logSearch); tools.add(logFilter, BorderLayout.WEST);
        JPanel controls = transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0)); autoScroll.setOpaque(false); controls.add(autoScroll);
        controls.add(button("Sao chép", "copy", null, () -> actions.copy(logText.getText())));
        controls.add(button("Xóa màn hình", "logs", null, () -> {
            logsClearedAt = Instant.now(); renderLogs();
        })); tools.add(controls, BorderLayout.EAST); page.add(tools, BorderLayout.NORTH);
        logText.setEditable(false); logText.setFont(new Font("Consolas", Font.PLAIN, 12)); logText.setMargin(new Insets(18, 18, 18, 18));
        themeBindings.add(() -> { logText.setBackground(dark ? new Color(0x0B1220) : Color.WHITE); logText.setForeground(textColor()); });
        logScroll = scroll(logText); page.add(logScroll);
        page.add(label("Bộ đệm hiển thị tối đa 2.000 dòng. Xóa màn hình không xóa nhật ký trên máy chủ.", 11, false, true), BorderLayout.SOUTH);
        return page;
    }

    private JComponent settingsPage() {
        JPanel body = transparent(new BorderLayout(0, 16));
        JPanel form = surface(new GridBagLayout()); GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1; c.insets = new Insets(0, 0, 20, 0); c.gridx = 0; c.gridy = 0; c.gridwidth = 2;
        form.add(sectionTitle("Mạng & truy cập", "settings"), c);
        settingRow(form, c, "Địa chỉ lắng nghe", hostField, "0.0.0.0 cho thiết bị LAN; 127.0.0.1 chỉ cho máy này.");
        settingRow(form, c, "Cổng HTTP API", httpField, "Client đi kèm và trang quản trị web dùng cổng này.");
        settingRow(form, c, "Cổng WebSocket", wsField, "Dùng cho chat realtime và điều phối WebRTC.");
        settingRow(form, c, "Giao diện người dùng", clientMode, "Chọn Next.js nếu bạn chạy frontend riêng trên máy chủ.");
        settingRow(form, c, "Cổng Next.js client", clientPortField, "Frontend phải được chạy riêng; desktop không tự khởi động Next.js.");
        c.gridy++; c.gridwidth = 2; minimizeTray.setFont(FONT); minimizeTray.setOpaque(false); form.add(minimizeTray, c);
        c.gridy++; style(settingsFeedback, 12, false, true); form.add(settingsFeedback, c);
        c.gridy++; c.insets = new Insets(0, 0, 0, 0); form.add(button("Áp dụng cấu hình", "settings", BLUE, this::applySettings), c);
        body.add(form, BorderLayout.NORTH);
        JPanel note = surface(new BorderLayout(0, 10)); note.add(label("Cấu hình có hiệu lực thế nào?", 14, true, false), BorderLayout.NORTH);
        JTextArea explanation = new JTextArea("Cài đặt áp dụng cho lần chạy desktop hiện tại. Nếu thay đổi cổng hoặc địa chỉ lắng nghe khi server đang chạy, hãy khởi động lại để sử dụng cấu hình mới.\n\nURL/QR luôn dùng cổng thực tế của server đang chạy. Với Next.js trên máy khác, cần cho phép origin frontend trong WS_ALLOWED_ORIGINS.");
        explanation.setEditable(false); explanation.setOpaque(false); explanation.setLineWrap(true); explanation.setWrapStyleWord(true);
        explanation.setFont(FONT.deriveFont(12f)); explanation.setRows(5); themeBindings.add(() -> explanation.setForeground(mutedColor())); note.add(explanation);
        body.add(note); return pageScroll(body);
    }

    private void settingRow(JPanel form, GridBagConstraints c, String name, JComponent field, String hint) {
        c.gridy++; c.gridx = 0; c.gridwidth = 1; c.weightx = 0; c.insets = new Insets(0, 0, 20, 28);
        form.add(label(name, 13, true, false), c);
        c.gridx = 1; c.weightx = 1; c.insets = new Insets(0, 0, 20, 0);
        JPanel right = transparent(new BorderLayout(0, 6)); field.setFont(FONT); field.setPreferredSize(new Dimension(380, 36));
        right.add(field, BorderLayout.NORTH); right.add(label(hint, 11, false, true), BorderLayout.SOUTH); form.add(right, c);
        c.gridx = 0;
    }

    private void applySettings() {
        try {
            DesktopServerSettings draft = new DesktopServerSettings(hostField.getText(), Integer.parseInt(httpField.getText().trim()),
                    Integer.parseInt(wsField.getText().trim()), clientMode.getSelectedIndex() == 1,
                    Integer.parseInt(clientPortField.getText().trim()), minimizeTray.isSelected());
            actions.applySettings(draft);
        } catch (NumberFormatException invalid) { settingsFeedback("Các cổng phải là số nguyên từ 1024 đến 65535.", true); }
        catch (IllegalArgumentException invalid) { settingsFeedback(invalid.getMessage(), true); }
    }

    public void setSettings(DesktopServerSettings settings) {
        this.settings = settings; hostField.setText(settings.bindHost()); httpField.setText(String.valueOf(settings.httpPort()));
        wsField.setText(String.valueOf(settings.wsPort())); clientMode.setSelectedIndex(settings.externalClient() ? 1 : 0);
        clientPortField.setText(String.valueOf(settings.clientPort())); clientPortField.setEnabled(settings.externalClient());
        minimizeTray.setSelected(settings.minimizeToTray());
    }
    public void settingsFeedback(String text, boolean error) { settingsFeedback.setText(text); settingsFeedback.setForeground(error ? RED : GREEN); }

    public void setLifecycle(DesktopServerController.State state, String message, boolean activeRuntime) {
        this.lifecycleState = state; this.lifecycleMessage = message; this.activeRuntime = activeRuntime;
        boolean running = state == DesktopServerController.State.RUNNING;
        boolean busy = state == DesktopServerController.State.STARTING || state == DesktopServerController.State.STOPPING;
        String title = switch (state) { case RUNNING -> "ĐANG CHẠY"; case STARTING -> "ĐANG KHỞI ĐỘNG";
            case STOPPING -> "ĐANG DỪNG"; case FAILED -> "CẦN KIỂM TRA"; default -> "ĐÃ DỪNG"; };
        Color color = running ? GREEN : state == DesktopServerController.State.FAILED ? RED : busy ? AMBER : mutedColor();
        statusBadge.setText("●  " + title); statusBadge.setForeground(color);
        statusBadge.setBackground(new Color(color.getRed(), color.getGreen(), color.getBlue(), dark ? 40 : 18));
        sidebarStatus.setText("●  " + title); sidebarStatus.setForeground(color);
        notice.setText(message); notice.setForeground(state == DesktopServerController.State.FAILED ? RED : mutedColor());
        startButton.setEnabled(!busy && !activeRuntime); stopButton.setEnabled(!busy && activeRuntime);
        restartButton.setEnabled(running); clientButton.setEnabled(running); adminButton.setEnabled(running);
        if (!running && !activeRuntime) { uptimeMetric.setText("00:00:00"); apiHealth.setText("Chưa chạy"); wsHealth.setText("Chưa chạy"); }
        updateSelection();
    }

    public void updateMetrics(int clients, int transfers, long uptime, long memoryBytes, String api, String ws,
                               String database, boolean authenticatedServices) {
        clientsMetric.setText(String.valueOf(clients)); transfersMetric.setText(String.valueOf(transfers));
        uptimeMetric.setText(String.format("%02d:%02d:%02d", uptime / 3600, (uptime / 60) % 60, uptime % 60));
        memoryMetric.setText((memoryBytes / (1024 * 1024)) + " MB");
        apiHealth.setText(api); wsHealth.setText(ws); databaseHealth.setText(database);
        accessHealth.setText(authenticatedServices ? "Sẵn sàng" : "Chưa khả dụng");
        applyHealthColors();
        footer.setText("Cập nhật " + TIME.format(Instant.now()) + "  ·  " + clients + " kết nối  ·  " + transfers + " phiên signaling");
    }
    private void applyHealthColors() {
        apiHealth.setForeground(apiHealth.getText().startsWith("Chưa") ? mutedColor() : GREEN);
        wsHealth.setForeground(wsHealth.getText().startsWith("Chưa") ? mutedColor() : GREEN);
        boolean ready = "Sẵn sàng".equals(accessHealth.getText());
        databaseHealth.setForeground(ready ? GREEN : AMBER); accessHealth.setForeground(ready ? GREEN : AMBER);
    }

    public void setNetworkInterfaces(List<NetworkHelper.LanInterfaceInfo> choices, String selectedIp) {
        interfaces.removeAllItems();
        for (var item : choices) interfaces.addItem(item);
        if (interfaces.getItemCount() == 0) interfaces.addItem(new NetworkHelper.LanInterfaceInfo("127.0.0.1", "Chỉ máy này", "loopback"));
        for (int i = 0; i < interfaces.getItemCount(); i++) {
            if (interfaces.getItemAt(i).ip().equals(selectedIp)) { interfaces.setSelectedIndex(i); break; }
        }
    }
    public void onInterfaceChanged(Consumer<String> listener) { interfaceChanged = listener; }
    public void setConnectionUrls(String client, String api, String ws, boolean loopback) {
        clientLink.setText(client); clientLink.setToolTipText(client);
        backendLink.setText("API " + api + "    ·    WS " + ws); backendLink.setToolTipText(backendLink.getText());
        connectionHint.setText(loopback ? "Chỉ máy này truy cập được. Đổi địa chỉ bind để cho phép thiết bị LAN."
                : settings.externalClient() ? "Chạy Next.js riêng tại cổng " + settings.clientPort() + ". Thiết bị truy cập cần cùng mạng LAN."
                : "Client đi kèm dùng chung cổng HTTP. Thiết bị truy cập cần cùng mạng LAN.");
    }
    public void setQrImage(java.awt.image.BufferedImage image) { qrLabel.setText(""); qrLabel.setIcon(new ImageIcon(image)); }

    public void setClients(List<ClientRow> rows) {
        String selected = selectedClientId();
        clientModel.setRowCount(0);
        for (ClientRow row : rows) clientModel.addRow(new Object[]{row.device(), row.user(), row.address(),
                TIME.format(row.connectedAt()), row.authenticated() ? "Đã xác thực" : "Chờ xác thực", row.id()});
        restoreSelection(clientTable, clientModel, 5, selected); updateCount(clientTable, clientCount, "kết nối"); updateSelection();
    }
    public void setTransfers(List<TransferRow> rows) {
        String selected = selectedTransferId(); transferModel.setRowCount(0);
        for (TransferRow row : rows) transferModel.addRow(new Object[]{row.fileName(), fileSize(row.size()), row.sender(), row.receiver(),
                transferStatus(row.status()), Math.max(0, java.time.Duration.between(Instant.now(), row.expiresAt()).toSeconds()) + " giây", row.id()});
        restoreSelection(transferTable, transferModel, 6, selected); updateCount(transferTable, transferCount, "phiên đang hoạt động"); updateSelection();
    }
    static void restoreSelection(JTable table, DefaultTableModel model, int idColumn, String id) {
        if (id == null) return;
        for (int row = 0; row < model.getRowCount(); row++) {
            if (id.equals(model.getValueAt(row, idColumn))) {
                int visible = table.convertRowIndexToView(row);
                if (visible >= 0) table.setRowSelectionInterval(visible, visible);
                return;
            }
        }
    }
    public String selectedClientId() { return selectedId(clientTable, clientModel, 5); }
    public String selectedTransferId() { return selectedId(transferTable, transferModel, 6); }
    private static String selectedId(JTable table, DefaultTableModel model, int column) {
        int row = table.getSelectedRow();
        return row < 0 ? null : String.valueOf(model.getValueAt(table.convertRowIndexToModel(row), column));
    }
    private void updateSelection() {
        disconnectButton.setEnabled(stopButton.isEnabled() && selectedClientId() != null);
        cancelButton.setEnabled(stopButton.isEnabled() && selectedTransferId() != null);
        int selected = clientTable.getSelectedRow();
        if (selected < 0) { selectedDevice.setText("Chọn một thiết bị"); selectedDetails.setText("Thông tin kết nối và thao tác quản lý sẽ hiển thị tại đây.\n\nNgắt kết nối sẽ đóng WebSocket và kết thúc các phiên signaling liên quan."); return; }
        int row = clientTable.convertRowIndexToModel(selected);
        selectedDevice.setText(String.valueOf(clientModel.getValueAt(row, 0)));
        selectedDetails.setText("TÀI KHOẢN\n" + clientModel.getValueAt(row, 1) + "\n\nĐỊA CHỈ MẠNG\n" + clientModel.getValueAt(row, 2)
                + "\n\nKẾT NỐI LÚC\n" + clientModel.getValueAt(row, 3) + "\n\nXÁC THỰC\n" + clientModel.getValueAt(row, 4)
                + "\n\nMÃ KẾT NỐI\n" + clientModel.getValueAt(row, 5));
    }

    public void setActivity(List<Activity> entries) {
        activity = List.copyOf(entries); renderLogs();
        recentLines.removeAll();
        List<Activity> last = activity.subList(Math.max(0, activity.size() - 5), activity.size());
        if (last.isEmpty()) recentLines.add(recentActivity);
        for (int i = last.size() - 1; i >= 0; i--) {
            Activity entry = last.get(i); JPanel row = transparent(new BorderLayout(10, 0));
            JLabel text = new JLabel(entry.text()); text.putClientProperty("html.disable", true); text.setFont(FONT.deriveFont(11f));
            text.setForeground(textColor()); text.setToolTipText(entry.text()); row.add(text);
            JLabel time = new JLabel(TIME.format(entry.at())); time.setFont(new Font("Consolas", Font.PLAIN, 10)); time.setForeground(mutedColor());
            row.add(time, BorderLayout.EAST); recentLines.add(row);
        }
        recentLines.revalidate(); recentLines.repaint();
    }
    private void renderLogs() {
        String level = (String) logFilter.getSelectedItem(); String query = logSearch.getText().toLowerCase(java.util.Locale.ROOT);
        StringBuilder text = new StringBuilder();
        for (Activity entry : activity) {
            if (entry.at().isAfter(logsClearedAt) && ("Tất cả mức".equals(level) || entry.level().equals(level))
                    && entry.text().toLowerCase(java.util.Locale.ROOT).contains(query)) {
                text.append(TIME.format(entry.at())).append("  ").append(entry.level()).append("  ").append(entry.text()).append('\n');
            }
        }
        if (logText.getText().equals(text.toString())) return;
        int scrollPosition = logScroll == null ? 0 : logScroll.getVerticalScrollBar().getValue();
        int caret = logText.getCaretPosition(); logText.setText(text.toString());
        logText.setCaretPosition(autoScroll.isSelected() ? logText.getDocument().getLength() : Math.min(caret, logText.getDocument().getLength()));
        if (!autoScroll.isSelected() && logScroll != null) SwingUtilities.invokeLater(() -> logScroll.getVerticalScrollBar().setValue(scrollPosition));
    }

    public void showPage(String page) {
        selectedPage = page; pages.show(pageContainer, page);
        String[] text = switch (page) {
            case "clients" -> new String[]{"Thiết bị kết nối", "Theo dõi các kết nối online và kiểm soát từng thiết bị."};
            case "transfers" -> new String[]{"Phiên truyền", "Theo dõi yêu cầu và trạng thái kết nối giữa các client."};
            case "logs" -> new String[]{"Nhật ký máy chủ", "Tìm và lọc sự kiện để theo dõi hoạt động, xử lý lỗi."};
            case "settings" -> new String[]{"Cấu hình máy chủ", "Thiết lập mạng, cổng dịch vụ và cách người dùng truy cập."};
            default -> new String[]{"Tổng quan máy chủ", "Mọi thông tin và thao tác điều khiển trong một nơi."};
        };
        pageTitle.setText(text[0]); pageDescription.setText(text[1]);
        navigation.stream().filter(item -> item.getActionCommand().equals(page)).findFirst().ifPresent(item -> item.setSelected(true));
        for (JToggleButton item : navigation) {
            item.setBackground(item.isSelected() ? new Color(0x263E64) : NAVY);
            item.setForeground(item.isSelected() ? Color.WHITE : new Color(0x9BAFCB));
        }
    }

    private void toggleTheme() {
        dark = !dark; if (dark) FlatDarkLaf.setup(); else FlatLightLaf.setup();
        FlatLaf.updateUI(); SwingUtilities.updateComponentTreeUI(this); applyPalette();
        setLifecycle(lifecycleState, lifecycleMessage, activeRuntime); applyHealthColors();
        themeButton.setText(dark ? "Giao diện sáng" : "Giao diện tối");
        showPage(selectedPage); setActivity(activity); repaint();
    }
    private void applyPalette() { for (Runnable binding : themeBindings) binding.run(); }
    private Color textColor() { return dark ? new Color(0xE5ECF7) : new Color(0x20304A); }
    private Color mutedColor() { return dark ? new Color(0x8FA2BD) : new Color(0x76859B); }
    private JPanel surface(LayoutManager layout) {
        JPanel panel = new JPanel(layout) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(dark ? new Color(0x151F30) : Color.WHITE); g.fillRoundRect(0, 0, getWidth(), getHeight(), 18, 18);
                g.setColor(dark ? new Color(0x24334A) : new Color(0xE3E8F0)); g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18); g.dispose();
            }
        };
        panel.setOpaque(false); panel.setBorder(new EmptyBorder(16, 20, 16, 20)); return panel;
    }
    private JLabel sectionTitle(String title, String icon) {
        JLabel text = label(title, 15, true, false); text.setIcon(new LineIcon(icon)); text.setIconTextGap(10); return text;
    }
    private JLabel label(String text, int size, boolean bold, boolean muted) { JLabel value = new JLabel(text); style(value, size, bold, muted); return value; }
    private void style(JLabel label, int size, boolean bold, boolean muted) {
        label.setFont(FONT.deriveFont(bold ? Font.BOLD : Font.PLAIN, size)); label.putClientProperty("html.disable", true);
        themeBindings.add(() -> label.setForeground(muted ? mutedColor() : textColor()));
    }
    private JButton button(String name, String icon, Color fill, Runnable action) {
        JButton button = new JButton(name, new LineIcon(icon)); button.setFont(FONT.deriveFont(Font.BOLD, 12));
        button.setIconTextGap(7); button.setMargin(new Insets(9, 12, 9, 12)); button.setFocusPainted(true);
        button.putClientProperty("JButton.buttonType", "roundRect"); button.addActionListener(e -> action.run());
        themeBindings.add(() -> { button.setBackground(fill != null ? fill : dark ? new Color(0x202E43) : new Color(0xF3F6FB));
            button.setForeground(fill != null ? Color.WHITE : textColor()); }); return button;
    }
    private static JPanel transparent(LayoutManager layout) { JPanel panel = new JPanel(layout); panel.setOpaque(false); return panel; }
    private static JScrollPane scroll(JComponent contents) {
        JScrollPane pane = new JScrollPane(contents); pane.setBorder(null); pane.setOpaque(false); pane.getViewport().setOpaque(false);
        if (contents instanceof JTable table) pane.setColumnHeaderView(table.getTableHeader());
        pane.getVerticalScrollBar().setUnitIncrement(18); return pane;
    }
    private static JScrollPane pageScroll(JComponent contents) {
        class ViewportPanel extends JPanel implements Scrollable {
            ViewportPanel() { super(new BorderLayout()); setOpaque(false); add(contents); }
            @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
            @Override public int getScrollableUnitIncrement(Rectangle bounds, int orientation, int direction) { return 18; }
            @Override public int getScrollableBlockIncrement(Rectangle bounds, int orientation, int direction) { return bounds.height - 18; }
            @Override public boolean getScrollableTracksViewportWidth() { return true; }
            @Override public boolean getScrollableTracksViewportHeight() {
                return getParent() instanceof JViewport viewport && viewport.getHeight() > getPreferredSize().height;
            }
        }
        return scroll(new ViewportPanel());
    }
    private static DefaultTableModel model(String... columns) {
        return new DefaultTableModel(columns, 0) { @Override public boolean isCellEditable(int row, int column) { return false; } };
    }
    private JTable table(DefaultTableModel model, int hiddenIdColumn) {
        JTable table = new JTable(model); table.removeColumn(table.getColumnModel().getColumn(hiddenIdColumn));
        table.setRowHeight(48); table.setFont(FONT.deriveFont(12f)); table.setShowGrid(false); table.setIntercellSpacing(new Dimension(0, 0));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); table.setAutoCreateRowSorter(true); table.setFillsViewportHeight(true);
        table.getTableHeader().setFont(FONT.deriveFont(Font.BOLD, 11)); table.getTableHeader().setPreferredSize(new Dimension(0, 40));
        themeBindings.add(() -> {
            table.setBackground(dark ? new Color(0x151F30) : Color.WHITE);
            table.setSelectionBackground(dark ? new Color(0x263E64) : new Color(0xE8F0FF));
            table.setSelectionForeground(textColor());
            table.getTableHeader().setBackground(dark ? new Color(0x1C293D) : new Color(0xF3F6FB));
            table.getTableHeader().setForeground(mutedColor());
        });
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int column) {
                JLabel cell = (JLabel) super.getTableCellRendererComponent(t, value, selected, focus, row, column);
                cell.putClientProperty("html.disable", true); cell.setBorder(new EmptyBorder(0, 10, 0, 10)); cell.setToolTipText(String.valueOf(value));
                if (!selected) {
                    cell.setBackground(dark ? new Color(row % 2 == 0 ? 0x151F30 : 0x182337) : new Color(row % 2 == 0 ? 0xFFFFFF : 0xF8FAFD));
                    cell.setForeground("Đã xác thực".equals(value) || "P2P đã kết nối".equals(value) ? GREEN : textColor());
                }
                return cell;
            }
        }); return table;
    }
    @SuppressWarnings("unchecked")
    private static void bindSearch(JTable table, JTextField search, JLabel count, String noun) {
        listen(search, () -> {
            TableRowSorter<DefaultTableModel> sorter = (TableRowSorter<DefaultTableModel>) table.getRowSorter();
            sorter.setRowFilter(search.getText().isBlank() ? null : RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(search.getText().trim())));
            updateCount(table, count, noun);
        });
    }
    private static void updateCount(JTable table, JLabel count, String noun) {
        count.setText(table.getRowCount() + " " + noun + (table.getRowCount() != table.getModel().getRowCount() ? " / " + table.getModel().getRowCount() + " tổng" : ""));
    }
    private static void listen(JTextField field, Runnable changed) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { changed.run(); }
            @Override public void removeUpdate(DocumentEvent e) { changed.run(); }
            @Override public void changedUpdate(DocumentEvent e) { changed.run(); }
        });
    }
    private static String fileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
    private static String transferStatus(String status) {
        return switch (status) { case "REQUESTED" -> "Chờ chấp nhận"; case "ACCEPTED" -> "Đã chấp nhận";
            case "CONNECTING" -> "Đang kết nối"; case "P2P_CONNECTED" -> "P2P đã kết nối"; default -> status; };
    }

    private static final class LineIcon implements Icon {
        private final String kind;
        LineIcon(String kind) { this.kind = kind; }
        @Override public int getIconWidth() { return 18; }
        @Override public int getIconHeight() { return 18; }
        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create(); g.translate(x, y); g.setColor(component.getForeground());
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (kind) {
                case "play" -> { g.drawPolygon(new int[]{5, 5, 14}, new int[]{3, 15, 9}, 3); }
                case "stop" -> g.drawRoundRect(4, 4, 10, 10, 2, 2);
                case "home" -> { g.drawPolyline(new int[]{2, 9, 16}, new int[]{8, 2, 8}, 3); g.drawRect(4, 8, 10, 8); }
                case "users" -> { g.drawOval(5, 2, 6, 6); g.drawArc(2, 9, 13, 12, 0, 180); g.drawArc(10, 3, 6, 6, -80, 140); }
                case "transfer" -> { g.drawLine(3, 5, 15, 5); g.drawPolyline(new int[]{11, 15, 11}, new int[]{1, 5, 9}, 3);
                    g.drawLine(15, 13, 3, 13); g.drawPolyline(new int[]{7, 3, 7}, new int[]{9, 13, 17}, 3); }
                case "logs" -> { g.drawRoundRect(3, 2, 12, 14, 2, 2); for (int line = 6; line <= 12; line += 3) g.drawLine(6, line, 12, line); }
                case "copy" -> { g.drawRoundRect(6, 6, 10, 10, 2, 2); g.drawPolyline(new int[]{3, 3, 12}, new int[]{12, 2, 2}, 3); }
                case "clock" -> { g.drawOval(2, 2, 14, 14); g.drawPolyline(new int[]{9, 9, 13}, new int[]{5, 9, 10}, 3); }
                case "shield" -> { g.drawPolygon(new int[]{9, 15, 14, 9, 4, 3}, new int[]{2, 5, 12, 16, 12, 5}, 6);
                    g.drawPolyline(new int[]{6, 8, 12}, new int[]{9, 11, 7}, 3); }
                case "server" -> { g.drawRoundRect(2, 2, 14, 6, 2, 2); g.drawRoundRect(2, 10, 14, 6, 2, 2); g.fillOval(5, 4, 2, 2); g.fillOval(5, 12, 2, 2); }
                case "settings" -> { for (int line = 4; line <= 14; line += 5) g.drawLine(2, line, 16, line);
                    g.drawOval(5, 2, 4, 4); g.drawOval(10, 7, 4, 4); g.drawOval(4, 12, 4, 4); }
                case "link", "arrow" -> { g.drawLine(4, 14, 14, 4); g.drawPolyline(new int[]{6, 14, 14}, new int[]{4, 4, 12}, 3); }
                case "restart" -> { g.drawArc(3, 3, 12, 12, 30, 290); g.drawPolyline(new int[]{10, 15, 15}, new int[]{2, 3, 8}, 3); }
                default -> { g.drawOval(3, 3, 12, 12); g.fillArc(3, 3, 12, 12, 90, 180); }
            }
            g.dispose();
        }
    }
}
