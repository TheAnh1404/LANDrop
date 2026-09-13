package com.landrop.database.repository;

import com.landrop.database.DatabaseManager;
import com.landrop.database.model.FileRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
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
            INSERT INTO files (id, workspace_id, user_id, folder_id, name, size, mime_type, sha256, storage_key, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id, workspace_id, user_id, folder_id, name, size, mime_type, sha256, storage_key, created_at, updated_at
        """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            UUID id = file.id() != null ? file.id() : UUID.randomUUID();
            Instant now = Instant.now();

            ps.setObject(1, id);
            ps.setObject(2, file.workspaceId());
            ps.setObject(3, file.userId());
            if (file.folderId() != null) {
                ps.setObject(4, file.folderId());
            } else {
                ps.setNull(4, Types.OTHER);
            }
            ps.setString(5, file.name());
            ps.setLong(6, file.size());
            ps.setString(7, file.mimeType());
            ps.setString(8, file.sha256());
            ps.setString(9, file.storageKey());
            ps.setTimestamp(10, Timestamp.from(file.createdAt() != null ? file.createdAt() : now));
            ps.setTimestamp(11, Timestamp.from(file.updatedAt() != null ? file.updatedAt() : now));

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
        String sql = folderId == null
            ? "SELECT * FROM files WHERE workspace_id = ? AND folder_id IS NULL ORDER BY created_at DESC"
            : "SELECT * FROM files WHERE workspace_id = ? AND folder_id = ? ORDER BY created_at DESC";

        List<FileRecord> list = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, workspaceId);
            if (folderId != null) {
                ps.setObject(2, folderId);
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
        String sql = "SELECT * FROM files WHERE user_id = ? ORDER BY created_at DESC";
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
        return new FileRecord(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("workspace_id"),
            (UUID) rs.getObject("user_id"),
            (UUID) rs.getObject("folder_id"),
            rs.getString("name"),
            rs.getLong("size"),
            rs.getString("mime_type"),
            rs.getString("sha256"),
            rs.getString("storage_key"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        );
    }
}
