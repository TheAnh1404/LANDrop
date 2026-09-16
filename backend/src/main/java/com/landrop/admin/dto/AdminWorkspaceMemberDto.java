package com.landrop.admin.dto;

import com.landrop.database.repository.WorkspaceRepository.MemberWithUser;

import java.time.Instant;
import java.util.UUID;

/** Member projection used by the system-admin workspace editor. */
public record AdminWorkspaceMemberDto(
    UUID userId,
    String username,
    String displayName,
    String role,
    Instant joinedAt
) {
    public static AdminWorkspaceMemberDto from(MemberWithUser value) {
        return new AdminWorkspaceMemberDto(value.member().userId(), value.username(), value.displayName(),
                value.member().role().name(), value.member().joinedAt());
    }
}
