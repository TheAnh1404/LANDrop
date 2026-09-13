package com.landrop.database.repository;

import com.landrop.database.DatabaseManager;
import com.landrop.database.model.Workspace;
import com.landrop.database.model.WorkspaceMember;
import com.landrop.database.model.WorkspaceRole;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class WorkspaceRepository {

    private final DatabaseManager databaseManager;

    public WorkspaceRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public WorkspaceRepository() {
        this(DatabaseManager.getInstance());
    }

    public Workspace create(Workspace workspace) throws SQLException {
        String sql = """
            INSERT INTO workspaces (id, name, slug, description, owner_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            RETURNING id, name, slug, description, owner_id, created_at, updated_at
        """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            UUID id = workspace.id() != null ? workspace.id() : UUID.randomUUID();
            Instant now = Instant.now();

            ps.setObject(1, id);
            ps.setString(2, workspace.name());
            ps.setString(3, workspace.slug());
            ps.setString(4, workspace.description());
            ps.setObject(5, workspace.ownerId());
            ps.setTimestamp(6, Timestamp.from(workspace.createdAt() != null ? workspace.createdAt() : now));
            ps.setTimestamp(7, Timestamp.from(workspace.updatedAt() != null ? workspace.updatedAt() : now));

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
            }
        }
        throw new SQLException("Failed to create workspace record.");
    }

    public Optional<Workspace> findById(UUID id) throws SQLException {
        String sql = "SELECT * FROM workspaces WHERE id = ?";
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

    public Optional<Workspace> findBySlug(String slug) throws SQLException {
        String sql = "SELECT * FROM workspaces WHERE slug = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, slug);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<Workspace> findAll() throws SQLException {
        String sql = "SELECT * FROM workspaces ORDER BY created_at ASC";
        List<Workspace> list = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        }
        return list;
    }

    public List<Workspace> findByUserId(UUID userId) throws SQLException {
        String sql = """
            SELECT w.* FROM workspaces w
            INNER JOIN workspace_members wm ON w.id = wm.workspace_id
            WHERE wm.user_id = ?
            ORDER BY w.created_at ASC
        """;
        List<Workspace> list = new ArrayList<>();
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

    public WorkspaceMember addMember(WorkspaceMember member) throws SQLException {
        String sql = """
            INSERT INTO workspace_members (id, workspace_id, user_id, role, joined_at, updated_at)
            VALUES (?, ?, ?, ?::"WorkspaceRole", ?, ?)
            RETURNING id, workspace_id, user_id, role::text, joined_at, updated_at
        """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            UUID id = member.id() != null ? member.id() : UUID.randomUUID();
            Instant now = Instant.now();

            ps.setObject(1, id);
            ps.setObject(2, member.workspaceId());
            ps.setObject(3, member.userId());
            ps.setString(4, member.role().name());
            ps.setTimestamp(5, Timestamp.from(member.joinedAt() != null ? member.joinedAt() : now));
            ps.setTimestamp(6, Timestamp.from(member.updatedAt() != null ? member.updatedAt() : now));

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapMemberRow(rs);
                }
            }
        }
        throw new SQLException("Failed to add workspace member.");
    }

    public List<WorkspaceMember> getMembers(UUID workspaceId) throws SQLException {
        String sql = "SELECT id, workspace_id, user_id, role::text, joined_at, updated_at FROM workspace_members WHERE workspace_id = ? ORDER BY joined_at ASC";
        List<WorkspaceMember> list = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, workspaceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapMemberRow(rs));
                }
            }
        }
        return list;
    }

    public boolean removeMember(UUID workspaceId, UUID userId) throws SQLException {
        String sql = "DELETE FROM workspace_members WHERE workspace_id = ? AND user_id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, workspaceId);
            ps.setObject(2, userId);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean deleteById(UUID id) throws SQLException {
        String sql = "DELETE FROM workspaces WHERE id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    public void deleteAllMembers() throws SQLException {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM workspace_members")) {
            ps.executeUpdate();
        }
    }

    public void deleteAll() throws SQLException {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM workspaces")) {
            ps.executeUpdate();
        }
    }

    private Workspace mapRow(ResultSet rs) throws SQLException {
        return new Workspace(
            (UUID) rs.getObject("id"),
            rs.getString("name"),
            rs.getString("slug"),
            rs.getString("description"),
            (UUID) rs.getObject("owner_id"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        );
    }

    private WorkspaceMember mapMemberRow(ResultSet rs) throws SQLException {
        return new WorkspaceMember(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("workspace_id"),
            (UUID) rs.getObject("user_id"),
            WorkspaceRole.fromString(rs.getString("role")),
            rs.getTimestamp("joined_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        );
    }
}
