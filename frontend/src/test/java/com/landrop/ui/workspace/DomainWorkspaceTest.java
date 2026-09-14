package com.landrop.ui.workspace;

import com.landrop.ui.cloud.dto.Dto;
import com.landrop.ui.cloud.model.Domain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

public class DomainWorkspaceTest {

    private static final Instant NOW = Instant.now();

    @Test
    @DisplayName("16B.1: Workspace record validates required fields and owner check")
    void testWorkspaceDomain() {
        Domain.Workspace ws = new Domain.Workspace(
                "ws-101",
                "Engineering Core",
                "user-ada",
                "Core protocol and distributed systems",
                NOW,
                NOW
        );

        assertEquals("ws-101", ws.id());
        assertEquals("Engineering Core", ws.name());
        assertEquals("user-ada", ws.ownerId());
        assertEquals("Core protocol and distributed systems", ws.description());
        assertTrue(ws.isOwner("user-ada"));
        assertFalse(ws.isOwner("user-ken"));
        assertFalse(ws.isOwner(null));
    }

    @Test
    @DisplayName("16B.1: WorkspaceMember record initials and name fallback logic")
    void testWorkspaceMemberDisplayHelpers() {
        // With display name
        Domain.WorkspaceMember m1 = new Domain.WorkspaceMember(
                "m-1", "ws-1", "u-1", Domain.MemberRole.OWNER,
                "Ada Lovelace", "ada@example.com", null, NOW, NOW
        );
        assertEquals("Ada Lovelace", m1.nameOrFallback());
        assertEquals("AL", m1.initials());
        assertEquals("OWNER", m1.roleBadge());

        // With email only
        Domain.WorkspaceMember m2 = new Domain.WorkspaceMember(
                "m-2", "ws-1", "u-2", Domain.MemberRole.ADMIN,
                null, "ken@bell-labs.com", null, NOW, NOW
        );
        assertEquals("ken@bell-labs.com", m2.nameOrFallback());
        assertEquals("KE", m2.initials());
        assertEquals("ADMIN", m2.roleBadge());

        // Single word name
        Domain.WorkspaceMember m3 = new Domain.WorkspaceMember(
                "m-3", "ws-1", "u-3", Domain.MemberRole.MEMBER,
                "Louise", null, null, NOW, NOW
        );
        assertEquals("Louise", m3.nameOrFallback());
        assertEquals("LO", m3.initials());
        assertEquals("MEMBER", m3.roleBadge());

        // Backward compatibility constructor without display fields
        Domain.WorkspaceMember legacy = new Domain.WorkspaceMember(
                "m-4", "ws-1", "u-4", Domain.MemberRole.MEMBER, NOW, NOW
        );
        assertNull(legacy.displayName());
        assertEquals("u-4", legacy.nameOrFallback());
    }

    @Test
    @DisplayName("16B.1: MemberRole fromString parsing handles various casings and fallback")
    void testMemberRoleParsing() {
        assertEquals(Domain.MemberRole.OWNER, Domain.MemberRole.fromString("owner"));
        assertEquals(Domain.MemberRole.ADMIN, Domain.MemberRole.fromString("ADMIN"));
        assertEquals(Domain.MemberRole.MEMBER, Domain.MemberRole.fromString("member"));
        assertEquals(Domain.MemberRole.MEMBER, Domain.MemberRole.fromString("unknown"));
        assertEquals(Domain.MemberRole.MEMBER, Domain.MemberRole.fromString(null));
    }

    @Test
    @DisplayName("16B.1: Request DTOs construct properly")
    void testRequestDTOs() {
        Dto.CreateWorkspaceRequest createReq = new Dto.CreateWorkspaceRequest("Product Design", "Design sprints");
        assertEquals("Product Design", createReq.name());
        assertEquals("Design sprints", createReq.description());

        Dto.UpdateWorkspaceRequest updateReq = new Dto.UpdateWorkspaceRequest("Product Design v2", "Updated");
        assertEquals("Product Design v2", updateReq.name());

        Dto.AddWorkspaceMemberRequest addReq = new Dto.AddWorkspaceMemberRequest("bob@example.com", "ADMIN");
        assertEquals("bob@example.com", addReq.email());
        assertNull(addReq.userId());
        assertEquals("ADMIN", addReq.role());

        Dto.UpdateMemberRoleRequest roleReq = new Dto.UpdateMemberRoleRequest("ADMIN");
        assertEquals("ADMIN", roleReq.role());
    }
}
