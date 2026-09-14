package com.landrop.ui.auth.api;

import java.util.Optional;
import java.util.prefs.Preferences;

/** Desktop fallback store. A production app can replace it with an OS keychain implementation. */
public final class PreferencesSessionStore implements SessionStore {
    private static final String KEY = "refreshToken";
    private final Preferences preferences;

    public PreferencesSessionStore() {
        this(null);
    }

    public PreferencesSessionStore(String profile) {
        Preferences base = Preferences.userNodeForPackage(PreferencesSessionStore.class);
        if (profile != null && !profile.isBlank()) {
            this.preferences = base.node("profiles").node(profile.trim());
        } else {
            this.preferences = base;
        }
    }

    @Override public Optional<String> loadRefreshToken() {
        return Optional.ofNullable(preferences.get(KEY, null)).filter(value -> !value.isBlank());
    }

    @Override public void saveRefreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) throw new IllegalArgumentException("refreshToken required");
        preferences.put(KEY, refreshToken);
    }

    @Override public void clear() { preferences.remove(KEY); }
}
