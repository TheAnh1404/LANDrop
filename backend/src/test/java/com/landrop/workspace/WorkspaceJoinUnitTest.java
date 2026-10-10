package com.landrop.workspace;

import com.landrop.auth.security.PasswordHasher;
import com.landrop.database.model.Workspace;
import com.landrop.database.model.WorkspaceRole;
import com.landrop.database.service.WorkspaceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceJoinUnitTest {

    @Test
    @DisplayName("Workspace model retains join policy and password hash")
    void testWorkspaceModelJoinFields() {
        UUID id = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Instant now = Instant.now();
        String passHash = PasswordHasher.hash("Secr3tPass!");

        Workspace ws = new Workspace(id, "Engineering", "engineering", "Core team", ownerId, now, now, "PASSWORD_PROTECTED", passHash);

        assertEquals("PASSWORD_PROTECTED", ws.joinPolicy());
        assertEquals(passHash, ws.joinPasswordHash());
        assertTrue(PasswordHasher.verify("Secr3tPass!", ws.joinPasswordHash()));
        assertFalse(PasswordHasher.verify("WrongPass", ws.joinPasswordHash()));
    }

    @Test
    @DisplayName("Workspace default constructor assigns INVITE_ONLY and null hash")
    void testWorkspaceDefaultConstructor() {
        UUID ownerId = UUID.randomUUID();
        Workspace ws = new Workspace("Alpha", "alpha", "Alpha project", ownerId);

        assertEquals("INVITE_ONLY", ws.joinPolicy());
        assertNull(ws.joinPasswordHash());
    }

    @Test
    @DisplayName("WorkspacePreview provides public metadata without exposing password hash")
    void testWorkspacePreviewMetadata() {
        UUID id = UUID.randomUUID();
        WorkspaceService.WorkspacePreview preview = new WorkspaceService.WorkspacePreview(
            id, "Marketing", "marketing", "Growth & Design", "Alice", 12L,
            "PASSWORD_PROTECTED", true, false, null
        );

        assertEquals("Marketing", preview.name());
        assertEquals("Alice", preview.ownerName());
        assertEquals(12L, preview.membersCount());
        assertEquals("PASSWORD_PROTECTED", preview.joinPolicy());
        assertTrue(preview.hasPassword());
        assertFalse(preview.isMember());
        assertNull(preview.role());
    }
}
