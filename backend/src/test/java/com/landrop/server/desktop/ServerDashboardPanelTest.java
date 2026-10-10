package com.landrop.server.desktop;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ServerDashboardPanelTest {
    static class Actions implements ServerDashboardPanel.Actions {
        String disconnected, cancelled, copied;
        DesktopServerSettings applied;
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void restart() { }
        @Override public void openClient() { }
        @Override public void copy(String text) { copied = text; }
        @Override public void refreshNetwork() { }
        @Override public void disconnect(String id) { disconnected = id; }
        @Override public void cancelTransfer(String id) { cancelled = id; }
        @Override public void applySettings(DesktopServerSettings next) { applied = next; }
    }
    static <T> List<T> components(Container parent, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child)) result.add(type.cast(child));
            if (child instanceof Container nested) result.addAll(components(nested, type));
        }
        return result;
    }
    static JButton button(Container view, String text) {
        return components(view, JButton.class).stream().filter(b -> text.equals(b.getText())).findFirst().orElseThrow();
    }
    static JTextField search(Container view, String placeholder) {
        return components(view, JTextField.class).stream().filter(f -> placeholder.equals(f.getClientProperty("JTextField.placeholderText"))).findFirst().orElseThrow();
    }
    static void onEdt(Runnable assertions) throws Exception {
        var failure = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> { try { assertions.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    static ServerDashboardPanel view(Actions actions) {
        return new ServerDashboardPanel(new DesktopServerSettings("0.0.0.0", 3100, 3001, false, 3000, true), actions, false);
    }
    static ServerDashboardPanel.ClientRow client(String id, String device) {
        return new ServerDashboardPanel.ClientRow(id, device, "theanh", "192.168.1.12", Instant.now(), true);
    }

    @Test void sortedAndFilteredDisconnectUsesSelectedConnectionIdAndSurvivesRefresh() throws Exception {
        onEdt(() -> {
            var actions = new Actions(); var panel = view(actions);
            panel.setLifecycle(DesktopServerController.State.RUNNING, "Online", true);
            panel.setClients(List.of(client("id-z", "Z Laptop"), client("id-a", "A Phone")));
            var table = components(panel, JTable.class).stream().filter(t -> t.getModel().getColumnCount() == 6).findFirst().orElseThrow();
            table.getRowSorter().toggleSortOrder(0); table.setRowSelectionInterval(0, 0);
            assertEquals("id-a", panel.selectedClientId());
            panel.setClients(List.of(client("id-a", "A Phone"), client("id-z", "Z Laptop")));
            assertEquals("id-a", panel.selectedClientId());
            button(panel, "Ngắt kết nối").doClick(); assertEquals("id-a", actions.disconnected);
            search(panel, "Tìm thiết bị, tài khoản hoặc địa chỉ IP…").setText("Z Laptop");
            table.setRowSelectionInterval(0, 0); button(panel, "Ngắt kết nối").doClick();
            assertEquals("id-z", actions.disconnected);
            panel.setClients(List.of(client("id-a", "A Phone")));
            assertNull(panel.selectedClientId()); assertFalse(button(panel, "Ngắt kết nối").isEnabled());
        });
    }

    @Test void filteredTransferCancelUsesItsIdAndIsDisabledWhileStopping() throws Exception {
        onEdt(() -> {
            var actions = new Actions(); var panel = view(actions);
            panel.setLifecycle(DesktopServerController.State.RUNNING, "Online", true);
            panel.setTransfers(List.of(new ServerDashboardPanel.TransferRow("id-1", "one.pdf", 1024, "a", "b", "REQUESTED", Instant.now().plusSeconds(60)),
                    new ServerDashboardPanel.TransferRow("id-2", "two.pdf", 2048, "b", "a", "CONNECTING", Instant.now().plusSeconds(60))));
            search(panel, "Tìm tệp, người gửi hoặc người nhận…").setText("two.pdf");
            var table = components(panel, JTable.class).stream().filter(t -> t.getModel().getColumnCount() == 7).findFirst().orElseThrow();
            table.setRowSelectionInterval(0, 0); button(panel, "Hủy yêu cầu đã chọn").doClick();
            assertEquals("id-2", actions.cancelled);
            panel.setLifecycle(DesktopServerController.State.STOPPING, "Stopping", true);
            assertFalse(button(panel, "Hủy yêu cầu đã chọn").isEnabled());
            assertFalse(button(panel, "Bật máy chủ").isEnabled());
            assertFalse(button(panel, "Mở trang kết nối").isEnabled());
        });
    }

    @Test void clearDisplayAndSearchDoNotEraseRecentActivityOrNewLogs() throws Exception {
        onEdt(() -> {
            var panel = view(new Actions());
            var old = new ServerDashboardPanel.Activity(Instant.now().minusSeconds(1), "INFO", "server ready");
            panel.setActivity(List.of(old));
            var text = components(panel, JTextArea.class).stream().filter(t -> t.getFont().getName().equals("Consolas")).findFirst().orElseThrow();
            assertTrue(text.getText().contains("server ready"));
            button(panel, "Ẩn nhật ký đã xem").doClick(); assertEquals("", text.getText());
            panel.setActivity(List.of(old, new ServerDashboardPanel.Activity(Instant.now().plusSeconds(1), "ERROR", "bind failed")));
            assertFalse(text.getText().contains("server ready")); assertTrue(text.getText().contains("bind failed"));
            search(panel, "Tìm trong nhật ký…").setText("absent"); assertEquals("", text.getText());
        });
    }

    @Test void themeSwitchKeepsReadableBackgroundAndLifecycleControls() throws Exception {
        onEdt(() -> {
            var panel = view(new Actions());
            panel.setLifecycle(DesktopServerController.State.RUNNING, "Online", true);
            button(panel, "Giao diện tối").doClick();
            assertTrue(panel.getBackground().getRed() < 50, "Dark view needs a dark background behind light text");
            assertFalse(button(panel, "Bật máy chủ").isEnabled());
            assertTrue(button(panel, "Dừng máy chủ").isEnabled());
            button(panel, "Giao diện sáng").doClick();
            assertTrue(panel.getBackground().getRed() > 200);
            assertTrue(button(panel, "Mở trang kết nối").isEnabled());
        });
    }

    @Test void invalidSettingsDoNotReachController() throws Exception {
        onEdt(() -> {
            var actions = new Actions(); var panel = view(actions);
            var port = components(panel, JTextField.class).stream().filter(f -> "3100".equals(f.getText())).findFirst().orElseThrow();
            port.setText("3001"); button(panel, "Áp dụng cấu hình").doClick(); assertNull(actions.applied);
            port.setText("3200"); button(panel, "Áp dụng cấu hình").doClick(); assertEquals(3200, actions.applied.httpPort());
        });
    }

    @Test void navigationOpensTheRightManagementSectionAndThemePreservesSessions() throws Exception {
        onEdt(() -> {
            var panel = view(new Actions());
            for (var route : java.util.Map.of("users", 0, "workspaces", 2, "data", 3).entrySet()) {
                components(panel, JToggleButton.class).stream().filter(b -> route.getKey().equals(b.getActionCommand()))
                        .findFirst().orElseThrow().doClick();
                assertEquals(route.getValue(), panel.administration().selectedSection());
            }
            panel.showPage("users");
            components(panel.administration(), JToggleButton.class).stream().filter(b -> "Phiên đăng nhập".equals(b.getText()))
                    .findFirst().orElseThrow().doClick();
            assertEquals(1, panel.administration().selectedSection());
            button(panel, "Giao diện tối").doClick();
            assertEquals(1, panel.administration().selectedSection(), "Changing appearance must preserve the operator's task");
            panel.administration().close();
        });
    }

    @Test void sharingWaitsForRunningServerAndCopiesTheAdvertisedLink() throws Exception {
        onEdt(() -> {
            var actions = new Actions(); var panel = view(actions);
            String link = "http://192.168.1.10:3100/client";
            panel.setConnectionUrls(link, "http://192.168.1.10:3100", "ws://192.168.1.10:3001", false);
            button(panel, "Sao chép đường dẫn").doClick(); assertNull(actions.copied);
            panel.setLifecycle(DesktopServerController.State.RUNNING, "Ready", true);
            button(panel, "Sao chép đường dẫn").doClick(); assertEquals(link, actions.copied);
            panel.setLifecycle(DesktopServerController.State.STOPPING, "Stopping", true);
            assertFalse(button(panel, "Sao chép đường dẫn").isEnabled());
            panel.administration().close();
        });
    }

    @Test void simpleAccessSettingsChangeBindHostAndPreserveTheConfiguredPorts() throws Exception {
        onEdt(() -> {
            var actions = new Actions(); var panel = view(actions);
            panel.setSettings(new DesktopServerSettings("0.0.0.0", 3100, 3101, true, 3200, true));
            var access = components(panel, JComboBox.class).stream()
                    .filter(box -> "Các thiết bị trong mạng nội bộ".equals(box.getItemAt(0))).findFirst().orElseThrow();
            access.setSelectedIndex(1);
            button(panel, "Lưu cài đặt").doClick();
            assertEquals("127.0.0.1", actions.applied.bindHost());
            assertEquals(3100, actions.applied.httpPort()); assertEquals(3101, actions.applied.wsPort());
            assertTrue(actions.applied.externalClient()); assertEquals(3200, actions.applied.clientPort());
            panel.setSettings(actions.applied);
            access.setSelectedIndex(0);
            button(panel, "Lưu cài đặt").doClick();
            assertEquals("0.0.0.0", actions.applied.bindHost());
            panel.administration().close();
        });
    }
}
