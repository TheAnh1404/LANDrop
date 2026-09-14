package com.landrop.ui.cloud.model;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

/**
 * Immutable persistent data for the JavaFX frontend. These identities and timestamps
 * belong to the cloud contract, independently of LAN peers and transfer sessions.
 */
public final class Domain {
    private static final BigInteger MAX_FILE_SIZE = BigInteger.valueOf(Long.MAX_VALUE);

    private Domain() {}

    public enum MemberRole {
        OWNER, ADMIN, MEMBER;

        public static MemberRole fromString(String role) {
            if (role == null) return MEMBER;
            try {
                return MemberRole.valueOf(role.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return MEMBER;
            }
        }
    }

    public enum MessageType { TEXT, FILE }

    public record User(String id, String displayName, String email, String avatarUrl,
                       Instant createdAt, Instant updatedAt) {
        public User {
            required(id, "id");
            required(displayName, "displayName");
            optional(email, "email");
            optional(avatarUrl, "avatarUrl");
            timestamps(createdAt, updatedAt);
        }
    }

    public record Workspace(String id, String name, String ownerId, String description,
                            Instant createdAt, Instant updatedAt) {
        public Workspace {
            required(id, "id");
            required(name, "name");
            required(ownerId, "ownerId");
            timestamps(createdAt, updatedAt);
        }

        public boolean isOwner(String userId) {
            return userId != null && userId.equals(ownerId);
        }
    }

    public record WorkspaceMember(String id, String workspaceId, String userId, MemberRole role,
                                  String displayName, String email, String avatarUrl,
                                  Instant createdAt, Instant updatedAt) {
        public WorkspaceMember {
            required(id, "id");
            required(workspaceId, "workspaceId");
            required(userId, "userId");
            present(role, "role");
            optional(displayName, "displayName");
            optional(email, "email");
            optional(avatarUrl, "avatarUrl");
            timestamps(createdAt, updatedAt);
        }

        public WorkspaceMember(String id, String workspaceId, String userId, MemberRole role,
                               Instant createdAt, Instant updatedAt) {
            this(id, workspaceId, userId, role, null, null, null, createdAt, updatedAt);
        }

        public String nameOrFallback() {
            if (displayName != null && !displayName.isBlank()) return displayName;
            if (email != null && !email.isBlank()) return email;
            return userId;
        }

        public String roleBadge() {
            return role != null ? role.name() : "MEMBER";
        }

        public String initials() {
            String name = nameOrFallback();
            if (name == null || name.isBlank()) return "??";
            String[] parts = name.trim().split("\\s+");
            if (parts.length == 1) {
                return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase();
            }
            return ("" + parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase();
        }
    }

    public record Channel(String id, String workspaceId, String name, String description,
                          Instant createdAt, Instant updatedAt) {
        public Channel {
            required(id, "id");
            required(workspaceId, "workspaceId");
            required(name, "name");
            timestamps(createdAt, updatedAt);
        }
    }

    public record Message(String id, String channelId, String senderId, String content,
                          MessageType type, String replyToId, String fileId,
                          Instant createdAt, Instant updatedAt, Instant editedAt, Instant deletedAt) {
        public Message {
            required(id, "id");
            required(channelId, "channelId");
            required(senderId, "senderId");
            present(content, "content");
            present(type, "type");
            optional(replyToId, "replyToId");
            optional(fileId, "fileId");
            if (id.equals(replyToId)) {
                throw new IllegalArgumentException("A message cannot reply to itself");
            }
            if (type == MessageType.TEXT) {
                // Soft-deleted messages intentionally retain their position while content may be blank.
                if (deletedAt == null) required(content, "content");
                if (fileId != null) {
                    throw new IllegalArgumentException("TEXT messages cannot reference fileId");
                }
            } else {
                required(fileId, "fileId");
            }
            timestamps(createdAt, updatedAt);
        }

        /** Phase 14 compatibility constructor; Phase 17 adds edit/delete timestamps. */
        public Message(String id, String channelId, String senderId, String content,
                       MessageType type, String replyToId, String fileId,
                       Instant createdAt, Instant updatedAt) {
            this(id, channelId, senderId, content, type, replyToId, fileId, createdAt, updatedAt, null, null);
        }

        /** Sender identity is a user ID; presentation resolves it from authenticated workspace members. */
        public String sender() { return senderId; }
        public boolean isDeleted() { return deletedAt != null; }
        public boolean isEdited() { return editedAt != null; }
    }

    public record Folder(String id, String workspaceId, String parentId, String name,
                         String createdById, Instant createdAt, Instant updatedAt) {
        public Folder {
            required(id, "id");
            required(workspaceId, "workspaceId");
            optional(parentId, "parentId");
            required(name, "name");
            required(createdById, "createdById");
            if (id.equals(parentId)) {
                throw new IllegalArgumentException("A folder cannot be its own parent");
            }
            timestamps(createdAt, updatedAt);
        }
    }

    /** File metadata only; this is neither a local filesystem handle nor a transfer session. */
    public record File(String id, String workspaceId, String folderId, String uploadedById,
                       String name, String mimeType, BigInteger sizeBytes, String sha256,
                       Instant createdAt, Instant updatedAt) {
        public File {
            required(id, "id");
            required(workspaceId, "workspaceId");
            optional(folderId, "folderId");
            required(uploadedById, "uploadedById");
            required(name, "name");
            required(mimeType, "mimeType");
            present(sizeBytes, "sizeBytes");
            if (sizeBytes.signum() < 0 || sizeBytes.compareTo(MAX_FILE_SIZE) > 0) {
                throw new IllegalArgumentException("sizeBytes must be between 0 and 9223372036854775807");
            }
            if (sha256 != null && !sha256.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("sha256 must be null or 64 hexadecimal characters");
            }
            timestamps(createdAt, updatedAt);
        }
    }

    public record Page<T>(List<T> items, String nextCursor) {
        public Page {
            present(items, "items");
            if (items.stream().anyMatch(item -> item == null)) {
                throw new IllegalArgumentException("items cannot contain null");
            }
            items = List.copyOf(items);
            optional(nextCursor, "nextCursor");
        }
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be a nonblank string");
        }
    }

    private static void optional(String value, String field) {
        if (value != null) {
            required(value, field);
        }
    }

    private static void present(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static void timestamps(Instant createdAt, Instant updatedAt) {
        present(createdAt, "createdAt");
        present(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt cannot precede createdAt");
        }
    }
}
