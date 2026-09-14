package com.landrop.ui.config;

import java.util.prefs.Preferences;

/** Persists only the user-editable server endpoints; credentials remain in the auth session store. */
public final class ServerConnectionPreferences {
    private static final String API_URL = "landrop.server.api.url";
    private static final String WS_URL = "landrop.server.ws.url";
    private final Preferences preferences;

    public ServerConnectionPreferences() {
        this(Preferences.userNodeForPackage(ServerConnectionPreferences.class));
    }

    ServerConnectionPreferences(Preferences preferences) { this.preferences = preferences; }

    public String apiUrl() { return preferences.get(API_URL, ""); }
    public String wsUrl() { return preferences.get(WS_URL, ""); }

    public void save(String apiUrl, String wsUrl) {
        preferences.put(API_URL, apiUrl);
        preferences.put(WS_URL, wsUrl);
    }
}
