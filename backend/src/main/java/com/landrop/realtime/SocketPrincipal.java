package com.landrop.realtime;

import java.time.Instant;
import java.util.UUID;

/** Access token jti identifies the credential, not a stable refresh session or LAN peer. */
public record SocketPrincipal(UUID userId, String accessTokenId, Instant expiresAt) {
    public boolean expired() { return !Instant.now().isBefore(expiresAt); }
}
