package com.landrop.database.repository;

import com.landrop.database.DatabaseManager;
import com.landrop.database.model.Folder;

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

public class FolderRepository {

    private final DatabaseManager databaseManager;

    public FolderRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public FolderRepository() {
        this(DatabaseManager.getInstance());
    }

    public Folder create(Folder folder) throws SQLException {
        String sql = """
            INSERT INTO folders (id, workspace_id, name, parent_folder_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            RETURNING id, workspace_id, name, parent_folder_id, created_at, updated_at
        """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            UUID id = folder.id() != null ? folder.id() : UUID.randomUUID();
            Instant now = Instant.now();

            ps.setObject(1, id);
            ps.setObject(2, folder.workspaceId());
            ps.setString(3, folder.name());
            if (folder.parentFolderId() != null) {
                ps.setObject(4, folder.parentFolderId());
            } else {
                ps.setNull(4, Types.OTHER);
            }
            ps.setTimestamp(5, Timestamp.from(folder.createdAt() != null ? folder.createdAt() : now));
            ps.setTimestamp(6, Timestamp.from(folder.updatedAt() != null ? folder.updatedAt() : now));

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
            }
        }
        throw new SQLException("Failed to create folder record.");
    }

    public Optional<Folder> findById(UUID id) throws SQLException {
        String sql = "SELECT * FROM folders WHERE id = ?";
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

    public List<Folder> findByWorkspaceId(UUID workspaceId, UUID parentFolderId) throws SQLException {
        String sql = parentFolderId == null
            ? "SELECT * FROM folders WHERE workspace_id = ? AND parent_folder_id IS NULL ORDER BY name ASC"
            : "SELECT * FROM folders WHERE workspace_id = ? AND parent_folder_id = ? ORDER BY name ASC";

        List<Folder> list = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, workspaceId);
            if (parentFolderId != null) {
                ps.setObject(2, parentFolderId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    public boolean deleteById(UUID id) throws SQLException {
        String sql = "DELETE FROM folders WHERE id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    public void deleteAll() throws SQLException {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM folders")) {
            ps.executeUpdate();
        }
    }

    private Folder mapRow(ResultSet rs) throws SQLException {
        return new Folder(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("workspace_id"),
            rs.getString("name"),
            (UUID) rs.getObject("parent_folder_id"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        );
    }
}
