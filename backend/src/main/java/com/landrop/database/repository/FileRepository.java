package com.landrop.database.repository;

import com.landrop.database.DatabaseManager;
import com.landrop.database.model.FileRecord;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class FileRepository {

    private final DatabaseManager databaseManager;

    public FileRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public FileRepository() {
        this(DatabaseManager.getInstance());
    }

    public FileRecord create(FileRecord file) throws SQLException {
        String sql = """
            INSERT INTO files (id, workspace_id, user_id, folder_id, name, original_name, size, mime_type, sha256, storage_type, storage_path, storage_key, created_at, updated_at, deleted_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id, workspace_id, user_id, folder_id, name, original_name, size, mime_type, sha256, storage_type, storage_path, storage_key, created_at, updated_at, deleted_at
        """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            UUID id = file.id() != null ? file.id() : UUID.randomUUID();
            Instant now = Instant.now();
            String originalName = file.originalName() != null ? file.originalName() : file.name();
            String storageType = file.storageType() != null ? file.storageType() : "LAN_ONLY";
            String storagePath = file.storageKey() != null ? file.storageKey() : file.storagePath();

            ps.setObject(1, id);
            ps.setObject(2, file.workspaceId());
            ps.setObject(3, file.userId());
            if (file.folderId() != null) {
                ps.setObject(4, file.folderId());
            } else {
                ps.setNull(4, Types.OTHER);
            }
            ps.setString(5, file.name());
            ps.setString(6, originalName);
            ps.setLong(7, file.size());
            ps.setString(8, file.mimeType());
            ps.setString(9, file.sha256());
            ps.setString(10, storageType);
            ps.setString(11, storagePath);
            ps.setString(12, storagePath);
            ps.setTimestamp(13, Timestamp.from(file.createdAt() != null ? file.createdAt() : now));
            ps.setTimestamp(14, Timestamp.from(file.updatedAt() != null ? file.updatedAt() : now));
            if (file.deletedAt() != null) {
                ps.setTimestamp(15, Timestamp.from(file.deletedAt()));
            } else {
                ps.setNull(15, Types.TIMESTAMP_WITH_TIMEZONE);
            }

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
            }
        }
        throw new SQLException("Failed to create file record.");
    }

    public Optional<FileRecord> findById(UUID id) throws SQLException {
        String sql = "SELECT * FROM files WHERE id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<FileRecord> findByWorkspaceId(UUID workspaceId, UUID folderId) throws SQLException {
        return findByWorkspaceId(workspaceId, folderId, false, false);
    }

    public List<FileRecord> findByWorkspaceId(UUID workspaceId, UUID folderId, boolean isRootOnly, boolean includeDeleted) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM files WHERE workspace_id = ?");
        if (isRootOnly) {
            sql.append(" AND folder_id IS NULL");
        } else if (folderId != null) {
            sql.append(" AND folder_id = ?");
        }
        if (!includeDeleted) {
            sql.append(" AND deleted_at IS NULL");
        }
        sql.append(" ORDER BY created_at DESC, id DESC");

        List<FileRecord> list = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setObject(idx++, workspaceId);
            if (!isRootOnly && folderId != null) {
                ps.setObject(idx++, folderId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    public List<FileRecord> findByUserId(UUID userId) throws SQLException {
        String sql = "SELECT * FROM files WHERE user_id = ? AND deleted_at IS NULL ORDER BY created_at DESC";
        List<FileRecord> list = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    public FileRecord update(UUID id, String name, UUID folderId, boolean updateFolder) throws SQLException {
        StringBuilder sql = new StringBuilder("UPDATE files SET updated_at = ?");
        if (name != null && !name.isBlank()) {
            sql.append(", name = ?");
        }
        if (updateFolder) {
            sql.append(", folder_id = ?");
        }
        sql.append(" WHERE id = ? RETURNING *");

        Instant now = Instant.now();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setTimestamp(idx++, Timestamp.from(now));
            if (name != null && !name.isBlank()) {
                ps.setString(idx++, name);
            }
            if (updateFolder) {
                if (folderId != null) {
                    ps.setObject(idx++, folderId);
                } else {
                    ps.setNull(idx++, Types.OTHER);
                }
            }
            ps.setObject(idx, id);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
            }
        }
        throw new SQLException("File not found for update: " + id);
    }

    public FileRecord softDelete(UUID id) throws SQLException {
        String sql = "UPDATE files SET deleted_at = ?, updated_at = ? WHERE id = ? RETURNING *";
        Instant now = Instant.now();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(now));
            ps.setTimestamp(2, Timestamp.from(now));
            ps.setObject(3, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
            }
        }
        throw new SQLException("File not found for soft delete: " + id);
    }

    public boolean deleteById(UUID id) throws SQLException {
        String sql = "DELETE FROM files WHERE id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    public void deleteAll() throws SQLException {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM files")) {
            ps.executeUpdate();
        }
    }

    private FileRecord mapRow(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();
        String origName = rs.getString("name");
        String storageType = "LAN_ONLY";
        String storagePath = rs.getString("storage_key");
        Instant deletedAt = null;

        for (int i = 1; i <= cols; i++) {
            String col = md.getColumnName(i).toLowerCase();
            if ("original_name".equals(col)) {
                String s = rs.getString(i);
                if (s != null) origName = s;
            } else if ("storage_type".equals(col)) {
                String s = rs.getString(i);
                if (s != null) storageType = s;
            } else if ("storage_path".equals(col)) {
                String s = rs.getString(i);
                if (s != null) storagePath = s;
            } else if ("deleted_at".equals(col)) {
                Timestamp ts = rs.getTimestamp(i);
                if (ts != null) deletedAt = ts.toInstant();
            }
        }

        return new FileRecord(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("workspace_id"),
            (UUID) rs.getObject("user_id"),
            (UUID) rs.getObject("folder_id"),
            rs.getString("name"),
            origName,
            rs.getLong("size"),
            rs.getString("mime_type"),
            rs.getString("sha256"),
            storageType,
            storagePath,
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            deletedAt
        );
    }
}
