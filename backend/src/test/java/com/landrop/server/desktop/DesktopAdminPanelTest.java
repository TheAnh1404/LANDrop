package com.landrop.server.desktop;

import com.landrop.admin.service.AdminControlCenterService;
import com.landrop.admin.logging.OperationalLogService;
import com.landrop.server.config.ServerConfig;
import com.landrop.server.config.ServerConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static com.landrop.server.desktop.ServerDashboardPanelTest.*;

class DesktopAdminPanelTest {
    @TempDir Path directory;
    private AdminControlCenterService service() {
        var config = new ServerConfig("127.0.0.1", 3100, 3101, 1, 1000, 3000, "test", "INFO");
        return new AdminControlCenterService(null, null, null, null,
                new ServerConfigManager(config, directory.resolve("server.json")), new OperationalLogService());
    }

    @Test void standaloneModeDisablesDatabaseActionsButKeepsAuditAndServerAccessible() throws Exception {
        var panel = new AtomicReference<DesktopAdminPanel>();
        onEdt(() -> {
            var view = new DesktopAdminPanel(ignored -> { }); panel.set(view);
            view.setRuntime(service(), null, false);
            assertFalse(button(view, "Sửa tài khoản").isEnabled());
            assertFalse(button(view, "Đọc dữ liệu").isEnabled());
            assertTrue(button(view, "Lọc nhật ký").isEnabled());
            assertTrue(button(view, "Đọc thông tin server").isEnabled());
            view.setRuntime(null, null, false);
            assertFalse(button(view, "Lọc nhật ký").isEnabled());
        });
        onEdt(() -> panel.get().close());
    }

    @Test void slowQueriesStayOffEdtAndDiscardResultsAfterStopOrRestart() throws Exception {
        var view = new AtomicReference<DesktopAdminPanel>();
        var result = new AtomicReference<CompletableFuture<Void>>();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var delivered = new AtomicBoolean(); var ranOnEdt = new AtomicBoolean();
        onEdt(() -> {
            var panel = new DesktopAdminPanel(ignored -> { }); view.set(panel); panel.setRuntime(service(), null, false);
            result.set(panel.submit(current -> {
                ranOnEdt.set(SwingUtilities.isEventDispatchThread()); entered.countDown();
                assertTrue(release.await(3, TimeUnit.SECONDS)); return "old runtime";
            }, value -> delivered.set(true)));
        });
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            onEdt(() -> { view.get().setRuntime(null, null, false); view.get().setRuntime(service(), null, false); });
            release.countDown(); result.get().get(3, TimeUnit.SECONDS);
            assertFalse(ranOnEdt.get()); assertFalse(delivered.get());
        } finally { release.countDown(); onEdt(() -> view.get().close()); }
    }

    @Test void failedQueryRestoresControlsAndMasksSqlDiagnostics() throws Exception {
        var view = new AtomicReference<DesktopAdminPanel>(); var result = new AtomicReference<CompletableFuture<Void>>();
        onEdt(() -> {
            var panel = new DesktopAdminPanel(ignored -> { }); view.set(panel); panel.setRuntime(service(), null, false);
            result.set(panel.submit(current -> { throw new SQLException("password=very-secret; jdbc:postgresql://private-host"); }, ignored -> fail("failed operation cannot render")));
        });
        try {
            assertThrows(ExecutionException.class, () -> result.get().get(3, TimeUnit.SECONDS));
            onEdt(() -> {
                assertTrue(button(view.get(), "Lọc nhật ký").isEnabled());
                var text = components(view.get(), JLabel.class).stream().map(JLabel::getText).reduce("", (a, b) -> a + b);
                assertFalse(text.contains("very-secret")); assertFalse(text.contains("private-host"));
                assertTrue(text.contains("Không thể đọc dữ liệu lưu trữ"));
            });
        } finally { onEdt(() -> view.get().close()); }
    }

    @Test void selectedDatabaseIdentityUsesModelRowAfterSortingAndFiltering() throws Exception {
        onEdt(() -> {
            var table = DesktopAdminPanel.table("Tài khoản", "ID");
            var model = (javax.swing.table.DefaultTableModel) table.getModel();
            UUID z = UUID.randomUUID(), a = UUID.randomUUID();
            model.addRow(new Object[]{"Z User", z}); model.addRow(new Object[]{"A User", a});
            table.getRowSorter().toggleSortOrder(0); table.setRowSelectionInterval(0, 0);
            assertEquals(a, DesktopAdminPanel.selectedUuid(table, 1));
            @SuppressWarnings("unchecked") var sorter = (javax.swing.table.TableRowSorter<javax.swing.table.DefaultTableModel>) table.getRowSorter();
            sorter.setRowFilter(RowFilter.regexFilter("Z User", 0)); table.setRowSelectionInterval(0, 0);
            assertEquals(z, DesktopAdminPanel.selectedUuid(table, 1));
        });
    }

    @Test void managementActionsRequireSelectionAndInternalIdsStayHidden() throws Exception {
        onEdt(() -> {
            var view = new DesktopAdminPanel(ignored -> { });
            // No repositories are used by this fixture; database controls stay unavailable.
            view.setRuntime(service(), null, false);
            var table = components(view, JTable.class).stream().filter(t -> t.getModel().getColumnCount() == 7).findFirst().orElseThrow();
            assertEquals(6, table.getColumnCount(), "Internal user identity must remain in the model, outside normal columns");
            assertFalse(button(view, "Sửa tài khoản").isEnabled());
            assertFalse(button(view, "Thêm tác vụ").isEnabled());
            assertTrue(components(view, JTextField.class).stream().noneMatch(field ->
                    String.valueOf(field.getClientProperty("JTextField.placeholderText")).contains("UUID")));
            view.close();
        });
    }
}
