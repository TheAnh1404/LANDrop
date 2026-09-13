package com.landrop.ui.auth.api;

import java.util.Optional;

/** Stores only the refresh token needed for a session restore; access tokens stay in memory. */
public interface SessionStore {
    Optional<String> loadRefreshToken();
    void saveRefreshToken(String refreshToken);
    void clear();
}
