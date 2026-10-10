package com.landrop.database.repository;

import com.landrop.database.DatabaseManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Read-only, explicitly allowlisted projections for the local server console. */
public class AdminDataRepository {
    public enum Dataset {
        DEVICES("Thiết bị đã đăng ký", "SELECT id, user_id, device_name, platform, status, last_seen_at, created_at FROM devices ORDER BY created_at DESC, id"),
        CHANNELS("Kênh", "SELECT id, workspace_id, name, topic, is_private, created_at FROM channels ORDER BY created_at DESC, id"),
        MESSAGES("Tin nhắn", "SELECT id, channel_id, user_id, content, created_at, updated_at FROM messages ORDER BY created_at DESC, id"),
        FOLDERS("Thư mục", "SELECT id, workspace_id, name, parent_folder_id, deleted_at, created_at FROM folders ORDER BY created_at DESC, id"),
        FILES("Thông tin tệp", "SELECT id, workspace_id, user_id, folder_id, name, size, mime_type, storage_type, deleted_at, created_at FROM files ORDER BY created_at DESC, id"),
        TRANSFERS("Lịch sử truyền", "SELECT id, file_id, sender_user_id, receiver_user_id, status, created_at, started_at, completed_at, failed_at FROM transfers ORDER BY created_at DESC, id");

        private final String label;
        private final String sql;
        Dataset(String label, String sql) { this.label = label; this.sql = sql; }
        @Override public String toString() { return label; }
    }

    public record DataPage(List<String> columns, List<List<String>> rows, long total) { }
    private final DatabaseManager database;
    public AdminDataRepository(DatabaseManager database) { this.database = database; }

    public DataPage read(Dataset dataset, int offset, int limit) throws SQLException {
        if (dataset == null || offset < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid data page");
        List<String> columns = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();
        // Both statements use one snapshot so the count matches the page during concurrent writes.
        try (var connection = database.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            long total;
            try (var count = connection.prepareStatement("SELECT COUNT(*) FROM (" + dataset.sql + ") AS records")) {
                count.setQueryTimeout(10);
                try (var result = count.executeQuery()) { result.next(); total = result.getLong(1); }
            }
            try (var query = connection.prepareStatement(dataset.sql + " LIMIT ? OFFSET ?")) {
                query.setInt(1, limit); query.setInt(2, offset); query.setQueryTimeout(10);
                try (var result = query.executeQuery()) {
                    var metadata = result.getMetaData();
                    for (int col = 1; col <= metadata.getColumnCount(); col++) columns.add(metadata.getColumnLabel(col));
                    while (result.next()) {
                        List<String> row = new ArrayList<>();
                        for (int col = 1; col <= columns.size(); col++) {
                            String value = result.getString(col);
                            row.add(value == null ? "" : value);
                        }
                        rows.add(List.copyOf(row));
                    }
                }
            }
            connection.commit();
            return new DataPage(List.copyOf(columns), List.copyOf(rows), total);
        }
    }
}
