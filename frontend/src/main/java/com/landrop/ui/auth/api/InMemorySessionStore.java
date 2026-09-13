package com.landrop.ui.auth.api;

import java.util.Optional;

public final class InMemorySessionStore implements SessionStore {
    private volatile String refreshToken;

    @Override public Optional<String> loadRefreshToken() { return Optional.ofNullable(refreshToken); }
    @Override public void saveRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
    @Override public void clear() { this.refreshToken = null; }
}
