package com.landrop.ui.bootstrap;

import java.util.Locale;
import java.util.UUID;
import java.util.prefs.Preferences;

/** Stable installation identity. It is intentionally independent of the signed-in user and LAN peer ID. */
public final class ClientInstanceService {
    private static final String KEY = "landrop.client.instance.id";
    private final String clientInstanceId;

    public ClientInstanceService() {
        Preferences preferences = Preferences.userNodeForPackage(ClientInstanceService.class);
        String stored = preferences.get(KEY, "");
        if (stored.isBlank()) {
            stored = UUID.randomUUID().toString();
            preferences.put(KEY, stored);
        }
        this.clientInstanceId = stored;
    }

    public String clientInstanceId() { return clientInstanceId; }
    public String platform() { return System.getProperty("os.name", "unknown").toLowerCase(Locale.ROOT); }
    public String defaultDeviceName() {
        String host = System.getenv("COMPUTERNAME");
        if (host == null || host.isBlank()) host = System.getenv("HOSTNAME");
        return host == null || host.isBlank() ? "LANDrop device" : host.trim();
    }
}
