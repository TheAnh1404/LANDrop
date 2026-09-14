package com.landrop.database.repository;

import com.landrop.database.DatabaseManager;
import com.landrop.database.model.Folder;

import java.sql.*;
import java.time.Instant;
import java.util.*;

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
            INSERT INTO folders (id, workspace_id, name, parent_folder_id, created_by_id, created_at, updated_at, deleted_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id, workspace_id, name, parent_folder_id, created_by_id, created_at, updated_at, deleted_at
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
            if (folder.createdById() != null) {
                ps.setObject(5, folder.createdById());
            } else {
                ps.setNull(5, Types.OTHER);
            }
            ps.setTimestamp(6, Timestamp.from(folder.createdAt() != null ? folder.createdAt() : now));
            ps.setTimestamp(7, Timestamp.from(folder.updatedAt() != null ? folder.updatedAt() : now));
            if (folder.deletedAt() != null) {
                ps.setTimestamp(8, Timestamp.from(folder.deletedAt()));
            } else {
                ps.setNull(8, Types.TIMESTAMP_WITH_TIMEZONE);
            }

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
        return findByWorkspaceId(workspaceId, parentFolderId, false, false);
    }

    public List<Folder> findByWorkspaceId(UUID workspaceId, UUID parentFolderId, boolean isRootOnly, boolean includeDeleted) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM folders WHERE workspace_id = ?");
        if (isRootOnly) {
            sql.append(" AND parent_folder_id IS NULL");
        } else if (parentFolderId != null) {
            sql.append(" AND parent_folder_id = ?");
        }
        if (!includeDeleted) {
            sql.append(" AND deleted_at IS NULL");
        }
        sql.append(" ORDER BY name ASC, id ASC");

        List<Folder> list = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setObject(idx++, workspaceId);
            if (!isRootOnly && parentFolderId != null) {
                ps.setObject(idx++, parentFolderId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    public Folder update(UUID id, String name, UUID parentFolderId, boolean updateParent) throws SQLException {
        StringBuilder sql = new StringBuilder("UPDATE folders SET updated_at = ?");
        if (name != null && !name.isBlank()) {
            sql.append(", name = ?");
        }
        if (updateParent) {
            sql.append(", parent_folder_id = ?");
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
            if (updateParent) {
                if (parentFolderId != null) {
                    ps.setObject(idx++, parentFolderId);
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
        throw new SQLException("Folder not found for update: " + id);
    }

    public Folder softDelete(UUID id) throws SQLException {
        String sql = "UPDATE folders SET deleted_at = ?, updated_at = ? WHERE id = ? RETURNING *";
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
        throw new SQLException("Folder not found for soft delete: " + id);
    }

    /**
     * Checks whether potentialDescendantId is equal to ancestorId or is a descendant of ancestorId.
     * Prevents folder hierarchy cycles.
     */
    public boolean isDescendantOrSelf(UUID potentialDescendantId, UUID ancestorId) throws SQLException {
        if (potentialDescendantId == null || ancestorId == null) return false;
        if (potentialDescendantId.equals(ancestorId)) return true;

        UUID current = potentialDescendantId;
        Set<UUID> visited = new HashSet<>();
        while (current != null) {
            if (!visited.add(current)) break; // loop defense
            Optional<Folder> opt = findById(current);
            if (opt.isEmpty()) break;
            UUID parent = opt.get().parentFolderId();
            if (parent == null) break;
            if (parent.equals(ancestorId)) return true;
            current = parent;
        }
        return false;
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
        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();
        UUID createdById = null;
        Instant deletedAt = null;

        for (int i = 1; i <= cols; i++) {
            String col = md.getColumnName(i).toLowerCase();
            if ("created_by_id".equals(col)) {
                createdById = (UUID) rs.getObject(i);
            } else if ("deleted_at".equals(col)) {
                Timestamp ts = rs.getTimestamp(i);
                if (ts != null) deletedAt = ts.toInstant();
            }
        }

        return new Folder(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("workspace_id"),
            rs.getString("name"),
            (UUID) rs.getObject("parent_folder_id"),
            createdById,
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            deletedAt
        );
    }
}
