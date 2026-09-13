package com.landrop.ui.auth.mapping;

import com.landrop.ui.auth.dto.AuthDto;
import com.landrop.ui.auth.model.AuthModels;
import com.landrop.ui.cloud.model.Domain;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

public final class AuthMapper {
    private AuthMapper() { }

    public static AuthModels.AuthUser user(AuthDto.AuthUserResponse dto) {
        if (dto == null) throw new IllegalArgumentException("user is required");
        return new AuthModels.AuthUser(dto.username(), new Domain.User(
                dto.id(), dto.displayName(), dto.email(), dto.avatarUrl(), instant(dto.createdAt()), instant(dto.updatedAt())));
    }

    public static AuthModels.AuthSession session(AuthDto.AuthResponse dto) {
        if (dto == null) throw new IllegalArgumentException("auth response is required");
        return new AuthModels.AuthSession(user(dto.user()), dto.accessToken(), dto.refreshToken(),
                dto.tokenType(), Instant.now().plusSeconds(dto.expiresIn()));
    }

    public static AuthModels.RefreshTokens refresh(AuthDto.RefreshResponse dto) {
        if (dto == null) throw new IllegalArgumentException("refresh response is required");
        return new AuthModels.RefreshTokens(dto.accessToken(), dto.refreshToken(), dto.tokenType(),
                Instant.now().plusSeconds(dto.expiresIn()));
    }

    private static Instant instant(String value) {
        if (value == null) throw new IllegalArgumentException("timestamp is required");
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("timestamp must include an offset", e);
        }
    }
}
