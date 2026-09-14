package com.landrop.chat.dto;

import java.util.UUID;

/**
 * Public user projection for message sender relations.
 * Never includes passwords, hash secrets, or token credentials.
 */
public record SenderDto(
    UUID id,
    String username,
    String displayName,
    String avatarUrl
) {}
