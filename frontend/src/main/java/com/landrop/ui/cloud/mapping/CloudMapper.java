package com.landrop.ui.cloud.mapping;

import com.landrop.ui.cloud.dto.Dto;
import com.landrop.ui.cloud.model.Domain;

import java.math.BigInteger;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.function.Function;

/** Converts wire values to validated, immutable models before data reaches JavaFX. */
public final class CloudMapper {
    private CloudMapper() {}

    public static Domain.User user(Dto.UserResponse dto) {
        required(dto);
        return new Domain.User(dto.id(), dto.displayName(), dto.email(), dto.avatarUrl(),
                timestamp(dto.createdAt()), timestamp(dto.updatedAt()));
    }

    public static Domain.Workspace workspace(Dto.WorkspaceResponse dto) {
        required(dto);
        return new Domain.Workspace(dto.id(), dto.name(), dto.ownerId(), dto.description(),
                timestamp(dto.createdAt()), timestamp(dto.updatedAt()));
    }

    public static Domain.WorkspaceMember member(Dto.WorkspaceMemberResponse dto) {
        required(dto);
        return new Domain.WorkspaceMember(dto.id(), dto.workspaceId(), dto.userId(),
                enumValue(Domain.MemberRole.class, dto.role()),
                timestamp(dto.createdAt()), timestamp(dto.updatedAt()));
    }

    public static Domain.Channel channel(Dto.ChannelResponse dto) {
        required(dto);
        return new Domain.Channel(dto.id(), dto.workspaceId(), dto.name(), dto.description(),
                timestamp(dto.createdAt()), timestamp(dto.updatedAt()));
    }

    public static Domain.Message message(Dto.MessageResponse dto) {
        required(dto);
        return new Domain.Message(dto.id(), dto.channelId(), dto.senderId(), dto.content(),
                enumValue(Domain.MessageType.class, dto.type()), dto.replyToId(), dto.fileId(),
                timestamp(dto.createdAt()), timestamp(dto.updatedAt()), null, null);
    }

    public static Domain.Folder folder(Dto.FolderResponse dto) {
        required(dto);
        return new Domain.Folder(dto.id(), dto.workspaceId(), dto.parentId(), dto.name(), dto.createdById(),
                timestamp(dto.createdAt()), timestamp(dto.updatedAt()));
    }

    public static Domain.File file(Dto.FileResponse dto) {
        required(dto);
        return new Domain.File(dto.id(), dto.workspaceId(), dto.folderId(), dto.uploadedById(),
                dto.name(), dto.mimeType(), sizeBytes(dto.sizeBytes()), dto.sha256(),
                timestamp(dto.createdAt()), timestamp(dto.updatedAt()));
    }

    public static <S, T> Domain.Page<T> page(Dto.ListResponse<S> dto, Function<S, T> mapper) {
        required(dto);
        required(mapper);
        return new Domain.Page<>(dto.data().stream().map(mapper).toList(), dto.page().nextCursor());
    }

    private static Instant timestamp(String value) {
        if (value == null) {
            throw new IllegalArgumentException("timestamp is required");
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("timestamp must be ISO 8601 with an explicit UTC offset", e);
        }
    }

    private static BigInteger sizeBytes(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException("sizeBytes must be a canonical nonnegative decimal string");
        }
        // Reject oversized values before constructing an arbitrarily large integer.
        if (value.length() > 19) {
            throw new IllegalArgumentException("sizeBytes exceeds signed 64-bit storage");
        }
        return new BigInteger(value);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        if (value == null) {
            throw new IllegalArgumentException(type.getSimpleName() + " is required");
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported " + type.getSimpleName() + ": " + value, e);
        }
    }

    private static void required(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("mapping input is required");
        }
    }
}
