package com.landrop.database.repository;

import com.landrop.database.DatabaseManager;
import com.landrop.database.model.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Existing Phase 14 repository; scoped instances share one transaction for Phase 16. */
public class WorkspaceRepository {
    private final DatabaseManager manager;
    private final Connection scoped;
    public WorkspaceRepository(DatabaseManager manager) { this(manager,null); }
    private WorkspaceRepository(DatabaseManager manager,Connection scoped) { this.manager=manager; this.scoped=scoped; }
    public WorkspaceRepository() { this(DatabaseManager.getInstance()); }
    @FunctionalInterface public interface Work<T> { T run(WorkspaceRepository repository) throws SQLException; }
    @FunctionalInterface private interface SqlWork<T> { T run(Connection c) throws SQLException; }
    @FunctionalInterface private interface Row<T> { T map(ResultSet rs) throws SQLException; }
    public record WorkspaceWithRole(Workspace workspace, WorkspaceRole role) { }
    public record MemberWithUser(WorkspaceMember member,String username,String displayName,String avatarUrl) { }
    public <T> T transaction(Work<T> work) throws SQLException {
        if (scoped!=null) return work.run(this);
        try (var c=manager.getConnection()) {
            c.setAutoCommit(false);
            try { T result=work.run(new WorkspaceRepository(manager,c)); c.commit(); return result; }
            catch (SQLException|RuntimeException e) { c.rollback(); throw e; }
        }
    }
    private <T> T connection(SqlWork<T> work) throws SQLException {
        if(scoped!=null) return work.run(scoped);
        try(var c=manager.getConnection()) { return work.run(c); }
    }
    private <T> List<T> query(String sql,Row<T> row,Object... args) throws SQLException {
        return connection(c -> {
            try(var ps=c.prepareStatement(sql)) {
                bind(ps,args);
                try(var rs=ps.executeQuery()) {
                    List<T> list=new ArrayList<>();
                    while(rs.next()) list.add(row.map(rs));
                    return list;
                }
            }
        });
    }
    private int execute(String sql,Object... args) throws SQLException {
        return connection(c -> { try(var ps=c.prepareStatement(sql)) { bind(ps,args); return ps.executeUpdate(); } });
    }
    private static void bind(PreparedStatement ps,Object[] args) throws SQLException {
        for(int i=0;i<args.length;i++) ps.setObject(i+1,args[i]);
    }
    private static Timestamp time(Instant t) { return Timestamp.from(t==null?Instant.now():t); }
    private static <T> T one(List<T> rows) throws SQLException {
        if(rows.isEmpty()) throw new SQLException("Expected persisted row");
        return rows.getFirst();
    }
    public Workspace create(Workspace w) throws SQLException {
        return one(query("""
            INSERT INTO workspaces(id,name,slug,description,owner_id,created_at,updated_at)
            VALUES (?,?,?,?,?,?,?) RETURNING *
            """,this::mapRow,w.id()==null?UUID.randomUUID():w.id(),w.name(),w.slug(),w.description(),w.ownerId(),
            time(w.createdAt()),time(w.updatedAt())));
    }
    public Optional<Workspace> findById(UUID id) throws SQLException {
        return query("SELECT * FROM workspaces WHERE id=?",this::mapRow,id).stream().findFirst();
    }
    public Optional<Workspace> lockById(UUID id) throws SQLException {
        if(scoped==null) throw new IllegalStateException("Lock requires a transaction");
        return query("SELECT * FROM workspaces WHERE id=? FOR UPDATE",this::mapRow,id).stream().findFirst();
    }
    public Optional<Workspace> findBySlug(String slug) throws SQLException {
        return query("SELECT * FROM workspaces WHERE slug=?",this::mapRow,slug).stream().findFirst();
    }
    public List<Workspace> findAll() throws SQLException {
        return query("SELECT * FROM workspaces ORDER BY created_at,id",this::mapRow);
    }
    public List<Workspace> findByUserId(UUID user) throws SQLException {
        return query("SELECT w.* FROM workspaces w JOIN workspace_members m ON w.id=m.workspace_id WHERE m.user_id=? ORDER BY w.created_at,w.id",this::mapRow,user);
    }
    public List<WorkspaceWithRole> findUserPage(UUID user,int limit,int offset) throws SQLException {
        return query("""
            SELECT w.*,m.role::text AS caller_role FROM workspaces w JOIN workspace_members m ON w.id=m.workspace_id
            WHERE m.user_id=? AND m.role::text IN ('OWNER','ADMIN','MEMBER')
            ORDER BY w.created_at,w.id LIMIT ? OFFSET ?
            """,rs -> new WorkspaceWithRole(mapRow(rs),WorkspaceRole.valueOf(rs.getString("caller_role"))),user,limit,offset);
    }
    public Workspace updateBasic(UUID id,String name,String description) throws SQLException {
        return one(query("UPDATE workspaces SET name=?,description=?,updated_at=CURRENT_TIMESTAMP WHERE id=? RETURNING *",this::mapRow,name,description,id));
    }
    public boolean userExists(UUID id) throws SQLException {
        return !query("SELECT id FROM users WHERE id=?",rs -> rs.getObject(1,UUID.class),id).isEmpty();
    }
    public Optional<UUID> userByEmail(String email) throws SQLException {
        var ids=query("SELECT id FROM users WHERE lower(email)=? ORDER BY id LIMIT 2",rs -> rs.getObject(1,UUID.class),email);
        if(ids.size()>1) throw new SQLException("Ambiguous email identity");
        return ids.stream().findFirst();
    }
    public WorkspaceMember addMember(WorkspaceMember m) throws SQLException {
        return one(query("""
            INSERT INTO workspace_members(id,workspace_id,user_id,role,joined_at,updated_at)
            VALUES (?,?,?,?::"WorkspaceRole",?,?) RETURNING *
            """,this::mapMemberRow,m.id()==null?UUID.randomUUID():m.id(),m.workspaceId(),m.userId(),m.role().name(),time(m.joinedAt()),time(m.updatedAt())));
    }
    public Optional<WorkspaceMember> findMember(UUID workspace,UUID user) throws SQLException {
        return query("SELECT * FROM workspace_members WHERE workspace_id=? AND user_id=?",this::mapMemberRow,workspace,user).stream().findFirst();
    }
    public List<WorkspaceMember> getMembers(UUID workspace) throws SQLException {
        return query("SELECT * FROM workspace_members WHERE workspace_id=? ORDER BY joined_at,id",this::mapMemberRow,workspace);
    }
    public List<MemberWithUser> memberPage(UUID workspace,int limit,int offset) throws SQLException {
        return query("""
            SELECT m.*,u.username,u.display_name,u.avatar_url FROM workspace_members m JOIN users u ON u.id=m.user_id
            WHERE m.workspace_id=? AND m.role::text IN ('OWNER','ADMIN','MEMBER')
            ORDER BY m.joined_at,m.id LIMIT ? OFFSET ?
            """,this::mapMemberWithUser,workspace,limit,offset);
    }
    public MemberWithUser memberWithUser(UUID workspace,UUID user) throws SQLException {
        return one(query("""
            SELECT m.*,u.username,u.display_name,u.avatar_url FROM workspace_members m JOIN users u ON u.id=m.user_id
            WHERE m.workspace_id=? AND m.user_id=?
            """,this::mapMemberWithUser,workspace,user));
    }
    public void updateRole(UUID workspace,UUID user,WorkspaceRole role) throws SQLException {
        execute("UPDATE workspace_members SET role=?::\"WorkspaceRole\",updated_at=CURRENT_TIMESTAMP WHERE workspace_id=? AND user_id=?",role.name(),workspace,user);
    }
    public boolean removeMember(UUID workspace,UUID user) throws SQLException {
        return execute("DELETE FROM workspace_members WHERE workspace_id=? AND user_id=?",workspace,user)>0;
    }
    public boolean deleteById(UUID id) throws SQLException { return execute("DELETE FROM workspaces WHERE id=?",id)>0; }
    public void deleteAllMembers() throws SQLException { execute("DELETE FROM workspace_members"); }
    public void deleteAll() throws SQLException { execute("DELETE FROM workspaces"); }
    private Workspace mapRow(ResultSet rs) throws SQLException {
        return new Workspace(rs.getObject("id",UUID.class),rs.getString("name"),rs.getString("slug"),rs.getString("description"),
            rs.getObject("owner_id",UUID.class),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant());
    }
    private WorkspaceMember mapMemberRow(ResultSet rs) throws SQLException {
        return new WorkspaceMember(rs.getObject("id",UUID.class),rs.getObject("workspace_id",UUID.class),rs.getObject("user_id",UUID.class),
            WorkspaceRole.valueOf(rs.getString("role")),rs.getTimestamp("joined_at").toInstant(),rs.getTimestamp("updated_at").toInstant());
    }
    private MemberWithUser mapMemberWithUser(ResultSet rs) throws SQLException {
        return new MemberWithUser(mapMemberRow(rs),rs.getString("username"),rs.getString("display_name"),rs.getString("avatar_url"));
    }
}
