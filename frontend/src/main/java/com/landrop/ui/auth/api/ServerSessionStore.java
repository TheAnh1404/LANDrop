package com.landrop.ui.auth.api;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

/** Server-scoped refresh credentials. Windows uses current-user DPAPI; other platforms stay in memory. */
public final class ServerSessionStore implements SessionStore {
    private final Preferences preferences;
    private final boolean windows = System.getProperty("os.name", "").startsWith("Windows");
    private String memory;

    public ServerSessionStore(URI server, String profile) {
        try {
            String scope = server.normalize().toASCIIString().replaceAll("/+$", "") + "|" + (profile == null ? "" : profile);
            String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(scope.getBytes(StandardCharsets.UTF_8)));
            preferences = Preferences.userNodeForPackage(ServerSessionStore.class).node("servers").node(key);
        } catch (Exception error) { throw new IllegalStateException("Cannot initialize credential storage."); }
    }
    @Override public synchronized Optional<String> loadRefreshToken() {
        if (!windows) return Optional.ofNullable(memory);
        String encrypted = preferences.get("protectedRefresh", null);
        if (encrypted == null) return Optional.ofNullable(memory);
        try { return Optional.of(protect(encrypted, false)); }
        catch (Exception error) { clear(); return Optional.empty(); }
    }
    @Override public synchronized void saveRefreshToken(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Refresh credential required.");
        memory = token;
        if (windows) {
            try { preferences.put("protectedRefresh", protect(token, true)); preferences.flush(); }
            catch (Exception error) { preferences.remove("protectedRefresh"); }
        }
    }
    @Override public synchronized void clear() {
        memory = null;
        preferences.remove("protectedRefresh");
        try { preferences.flush(); } catch (Exception ignored) { }
    }
    private static String protect(String value, boolean encrypt) throws Exception {
        String script = "Add-Type -AssemblyName System.Security; $v=[Console]::In.ReadToEnd(); "
                + (encrypt
                ? "$b=[Text.Encoding]::UTF8.GetBytes($v); [Console]::Out.Write([Convert]::ToBase64String([Security.Cryptography.ProtectedData]::Protect($b,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)))"
                : "$b=[Convert]::FromBase64String($v); [Console]::Out.Write([Text.Encoding]::UTF8.GetString([Security.Cryptography.ProtectedData]::Unprotect($b,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)))");
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            try (var input = process.getOutputStream()) { input.write(value.getBytes(StandardCharsets.UTF_8)); }
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) throw new IllegalStateException("Credential protection unavailable.");
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } finally { process.destroyForcibly(); }
    }
}
