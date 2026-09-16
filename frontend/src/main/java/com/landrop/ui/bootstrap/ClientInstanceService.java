package com.landrop.ui.bootstrap;

import java.util.Locale;
import java.util.UUID;
import java.util.prefs.Preferences;
import java.net.InetAddress;

/** Stable installation identity. It is intentionally independent of the signed-in user and LAN peer ID. */
public final class ClientInstanceService {
    private static final String KEY = "landrop.client.instance.id";
    private static final String DEVICE_NAME_KEY = "landrop.client.device.name";
    private final String clientInstanceId;
    private final Preferences preferences;

    public ClientInstanceService() {
        this("default");
    }
    /** Isolated profile is useful for independent local installations and integration tests. */
    public ClientInstanceService(String profile) {
        this(Preferences.userNodeForPackage(ClientInstanceService.class).node(profile == null || profile.isBlank() ? "default" : profile));
    }
    private ClientInstanceService(Preferences preferences) {
        this.preferences = preferences;
        String stored = preferences.get(KEY, "");
        if (stored.isBlank()) {
            stored = UUID.randomUUID().toString();
            preferences.put(KEY, stored);
        }
        this.clientInstanceId = stored;
    }

    public String clientInstanceId() { return clientInstanceId; }
    public String platform() { return System.getProperty("os.name", "unknown").toLowerCase(Locale.ROOT); }
    public String osVersion() { return System.getProperty("os.version", "unknown"); }
    public String architecture() { return System.getProperty("os.arch", "unknown"); }
    public String deviceName() {
        String configured = preferences.get(DEVICE_NAME_KEY, "");
        return configured.isBlank() ? defaultDeviceName() : configured.trim();
    }
    public void setDeviceName(String deviceName) {
        if (deviceName == null || deviceName.isBlank()) preferences.remove(DEVICE_NAME_KEY);
        else preferences.put(DEVICE_NAME_KEY, deviceName.trim());
    }
    public String defaultDeviceName() {
        String host = System.getenv("COMPUTERNAME");
        if (host == null || host.isBlank()) host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) {
            try { host = InetAddress.getLocalHost().getHostName(); } catch (Exception ignored) { }
        }
        return host == null || host.isBlank() ? "LANDrop device" : host.trim();
    }
}
