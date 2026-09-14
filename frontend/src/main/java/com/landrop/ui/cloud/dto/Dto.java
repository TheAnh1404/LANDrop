package com.landrop.ui.cloud.dto;

import java.util.List;

/**
 * Phase 14 JSON response shapes. Nullable fields remain explicit JSON keys. String
 * timestamps, enums and decimal file sizes are validated at the mapping boundary.
 */
public final class Dto {
    private Dto() {}

    public record UserResponse(String id, String displayName, String email, String avatarUrl,
                               String createdAt, String updatedAt) {}

    public record WorkspaceResponse(String id, String name, String ownerId, String description,
                                    String createdAt, String updatedAt) {}

    public record WorkspaceMemberResponse(String id, String workspaceId, String userId, String role,
                                          String createdAt, String updatedAt) {}

    public record CreateWorkspaceRequest(String name, String description) {}

    public record UpdateWorkspaceRequest(String name, String description) {}

    public record AddWorkspaceMemberRequest(String email, String userId, String role) {
        public AddWorkspaceMemberRequest(String email, String role) {
            this(email, null, role);
        }
    }

    public record UpdateMemberRoleRequest(String role) {}

    public record ChannelResponse(String id, String workspaceId, String name, String description,
                                  String createdAt, String updatedAt) {}

    public record MessageResponse(String id, String channelId, String senderId, String content,
                                  String type, String replyToId, String fileId,
                                  String createdAt, String updatedAt) {}

    public record FolderResponse(String id, String workspaceId, String parentId, String name,
                                 String createdById, String createdAt, String updatedAt) {}

    public record FileResponse(String id, String workspaceId, String folderId, String uploadedById,
                               String name, String mimeType, String sizeBytes, String sha256,
                               String createdAt, String updatedAt) {}

    public record ItemResponse<T>(T data) {
        public ItemResponse {
            if (data == null) {
                throw new IllegalArgumentException("data is required");
            }
        }
    }

    public record ListResponse<T>(List<T> data, PageInfo page) {
        public ListResponse {
            if (data == null || page == null || data.stream().anyMatch(item -> item == null)) {
                throw new IllegalArgumentException("data and page are required; data cannot contain null");
            }
            data = List.copyOf(data);
        }
    }

    public record PageInfo(String nextCursor) {
        public PageInfo {
            if (nextCursor != null && nextCursor.isBlank()) {
                throw new IllegalArgumentException("nextCursor must be null or a nonblank string");
            }
        }
    }
}
