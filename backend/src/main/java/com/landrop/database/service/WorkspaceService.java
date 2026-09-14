package com.landrop.database.service;

import com.landrop.database.model.*;
import com.landrop.database.repository.WorkspaceRepository;
import com.landrop.workspace.WorkspaceAccess;
import com.landrop.workspace.WorkspaceException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

public class WorkspaceService {
    private final WorkspaceRepository repository;
    public record WorkspaceView(Workspace workspace, WorkspaceRole role) {}
    public record MemberView(WorkspaceMember member, String username, String displayName, String avatarUrl) {}
    public record Page<T>(List<T> items, int limit, int offset, boolean hasMore) {}
    public WorkspaceService(WorkspaceRepository repository) { this.repository=repository; }
    public WorkspaceService() { this(new WorkspaceRepository()); }

    /** Legacy Phase 14 read methods retained for database verification and Core compatibility. */
    public List<Workspace> listWorkspaces() throws SQLException { return repository.findAll(); }
    public Optional<Workspace> getWorkspaceBySlug(String slug) throws SQLException { return repository.findBySlug(slug); }
    public List<WorkspaceMember> getMembers(UUID workspaceId) throws SQLException { return repository.getMembers(workspaceId); }

    public WorkspaceView create(UUID caller,String name,String description) throws SQLException {
        String cleanName=name(name); String cleanDescription=description(description); UUID id=UUID.randomUUID();
        return repository.transaction(tx -> {
            if(!tx.userExists(caller)) throw new WorkspaceException(401,"UNAUTHENTICATED","User was not found.");
            Workspace w=tx.create(new Workspace(id,cleanName,"workspace-"+id,cleanDescription,caller,Instant.now(),Instant.now()));
            tx.addMember(new WorkspaceMember(w.id(),caller,WorkspaceRole.OWNER));
            return new WorkspaceView(w,WorkspaceRole.OWNER);
        });
    }
    public Page<WorkspaceView> list(UUID caller,int limit,int offset) throws SQLException {
        page(limit,offset); var rows=repository.findUserPage(caller,limit+1,offset);
        return new Page<>(rows.stream().limit(limit).map(x->new WorkspaceView(x.workspace(),x.role())).toList(),limit,offset,rows.size()>limit);
    }
    public WorkspaceView get(UUID caller,UUID id) throws SQLException {
        var m=WorkspaceAccess.requireMember(repository,id,caller); return new WorkspaceView(m.workspace(),m.member().role());
    }
    public WorkspaceView update(UUID caller,UUID id,String name,String description,boolean hasName,boolean hasDescription) throws SQLException {
        if(!hasName&&!hasDescription) throw invalid("At least one workspace field is required.");
        return repository.transaction(tx -> {
            var m=WorkspaceAccess.lockAdmin(tx,id,caller);
            Workspace w=tx.updateBasic(id,hasName?name(name):m.workspace().name(),hasDescription?description(description):m.workspace().description());
            return new WorkspaceView(w,m.member().role());
        });
    }
    public void delete(UUID caller,UUID id) throws SQLException { repository.transaction(tx->{WorkspaceAccess.lockOwner(tx,id,caller);if(!tx.deleteById(id))throw notFound();return null;}); }
    public Page<MemberView> members(UUID caller,UUID id,int limit,int offset) throws SQLException {
        page(limit,offset); WorkspaceAccess.requireMember(repository,id,caller); var rows=repository.memberPage(id,limit+1,offset);
        return new Page<>(rows.stream().limit(limit).map(x->new MemberView(x.member(),x.username(),x.displayName(),x.avatarUrl())).toList(),limit,offset,rows.size()>limit);
    }
    public MemberView addMember(UUID caller,UUID id,String email) throws SQLException {
        String normalized=email(email); return repository.transaction(tx->{WorkspaceAccess.lockAdmin(tx,id,caller);
            UUID uid=tx.userByEmail(normalized).orElseThrow(()->new WorkspaceException(404,"USER_NOT_FOUND","User was not found."));
            if(tx.findMember(id,uid).isPresent())throw new WorkspaceException(409,"MEMBER_EXISTS","User is already a workspace member.");
            return view(tx,tx.addMember(new WorkspaceMember(id,uid,WorkspaceRole.MEMBER)));
        });
    }
    public MemberView role(UUID caller,UUID id,UUID target,WorkspaceRole role) throws SQLException {
        if(role!=WorkspaceRole.ADMIN&&role!=WorkspaceRole.MEMBER)throw invalid("Role must be ADMIN or MEMBER.");
        return repository.transaction(tx->{WorkspaceAccess.lockOwner(tx,id,caller);
            var current=tx.findMember(id,target).orElseThrow(()->new WorkspaceException(404,"MEMBER_NOT_FOUND","Member was not found."));
            if(current.role()==WorkspaceRole.OWNER)throw new WorkspaceException(409,"OWNER_PROTECTED","The workspace owner cannot be demoted.");
            tx.updateRole(id,target,role); return view(tx,tx.findMember(id,target).orElseThrow());
        });
    }
    public void remove(UUID caller,UUID id,UUID target) throws SQLException { repository.transaction(tx->{var actor=WorkspaceAccess.lockMember(tx,id,caller).member();
        var targetMember=tx.findMember(id,target).orElseThrow(()->new WorkspaceException(404,"MEMBER_NOT_FOUND","Member was not found."));
        if(target.equals(caller)||targetMember.role()==WorkspaceRole.OWNER)throw new WorkspaceException(409,"OWNER_PROTECTED","The workspace owner cannot be removed.");
        if(actor.role()==WorkspaceRole.MEMBER||(actor.role()==WorkspaceRole.ADMIN&&targetMember.role()!=WorkspaceRole.MEMBER))
            throw new WorkspaceException(403,"FORBIDDEN","You cannot remove this member.");
        if(!tx.removeMember(id,target))throw new WorkspaceException(404,"MEMBER_NOT_FOUND","Member was not found."); return null;}); }
    public void leave(UUID caller,UUID id) throws SQLException { repository.transaction(tx->{var m=WorkspaceAccess.lockMember(tx,id,caller).member();
        if(m.role()==WorkspaceRole.OWNER)throw new WorkspaceException(409,"OWNER_PROTECTED","Transfer ownership or delete the workspace before leaving.");
        if(!tx.removeMember(id,caller))throw new WorkspaceException(404,"MEMBER_NOT_FOUND","Member was not found.");return null;}); }
    private MemberView view(WorkspaceRepository tx,WorkspaceMember m)throws SQLException{var v=tx.memberWithUser(m.workspaceId(),m.userId());return new MemberView(v.member(),v.username(),v.displayName(),v.avatarUrl());}
    private static String name(String v){if(v==null||v.trim().isEmpty()||v.trim().length()>100)throw invalid("Workspace name must be 1-100 characters.");return v.trim();}
    private static String description(String v){if(v==null)return null;if(v.length()>2000)throw invalid("Description must be at most 2000 characters.");return v.trim();}
    private static String email(String v){if(v==null||v.trim().length()>255||!v.trim().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))throw invalid("A valid email address is required.");return v.trim().toLowerCase(Locale.ROOT);}
    private static void page(int l,int o){if(l<1||l>100||o<0||o>100000)throw invalid("Invalid pagination values.");}
    private static WorkspaceException invalid(String m){return new WorkspaceException(400,"INVALID_REQUEST",m);}
    private static WorkspaceException notFound(){return new WorkspaceException(404,"WORKSPACE_NOT_FOUND","Workspace was not found.");}
}
