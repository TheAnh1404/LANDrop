package com.landrop.ui.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.landrop.ui.auth.services.AuthStateService;
import com.landrop.ui.bootstrap.ClientInstanceService;
import com.landrop.ui.cloud.api.ApiClient;
import com.landrop.ui.cloud.api.ApiException;
import com.landrop.ui.cloud.model.Domain;
import com.landrop.ui.config.FrontendConfig;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Server-owned device identity, resolved independently of LAN discovery. */
public final class CurrentDeviceService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final AuthStateService auth;
    private final ApiClient api;
    private final ClientInstanceService instance;
    private final ReadOnlyObjectWrapper<Domain.Device> currentDevice = new ReadOnlyObjectWrapper<>();
    private long generation;

    public CurrentDeviceService(URI base, AuthStateService auth) { this(base, auth, new ClientInstanceService()); }
    public CurrentDeviceService(URI base, AuthStateService auth, ClientInstanceService instance) {
        this.auth = auth; this.api = new ApiClient(base, auth); this.instance = instance;
    }
    public ReadOnlyObjectProperty<Domain.Device> currentDeviceProperty() { return currentDevice.getReadOnlyProperty(); }
    public Domain.Device currentDevice() { return currentDevice.get(); }
    public synchronized void clear() { generation++; currentDevice.set(null); }
    public synchronized CompletableFuture<Domain.Device> enroll() {
        return send("POST", "devices/enroll", Map.of("clientInstanceId", instance.clientInstanceId(),
                "deviceName", instance.deviceName(), "platform", instance.platform(), "clientVersion", FrontendConfig.CLIENT_VERSION));
    }
    public synchronized CompletableFuture<Domain.Device> rename(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 100 || name.chars().anyMatch(Character::isISOControl)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Enter a device name of 1 to 100 characters."));
        }
        Domain.Device device = currentDevice();
        if (device == null) return CompletableFuture.failedFuture(new IllegalStateException("Enroll this device first."));
        return send("PATCH", "devices/" + java.net.URLEncoder.encode(device.id(), java.nio.charset.StandardCharsets.UTF_8),
                Map.of("deviceName", name.trim()));
    }
    private CompletableFuture<Domain.Device> send(String method, String path, Map<String, String> payload) {
        long expected = generation;
        String userId = auth.user() == null ? null : auth.user().id();
        try {
            return api.request(method, path, JSON.writeValueAsString(payload)).thenApply(body -> {
                synchronized (this) {
                    if (expected != generation || auth.user() == null || !auth.user().id().equals(userId)) throw new java.util.concurrent.CancellationException();
                    try {
                        JsonNode root = JSON.readTree(body);
                        JsonNode data = root.has("data") ? root.path("data") : root;
                        String id = data.path("id").asText(data.path("deviceId").asText(""));
                        if (id.isBlank() || !userId.equals(data.path("userId").asText())
                                || !instance.clientInstanceId().equals(data.path("clientInstanceId").asText())) {
                            throw new ApiException(0, "PROTOCOL_INCOMPATIBLE", "Device enrollment response does not establish identity.");
                        }
                        Domain.Device device = new Domain.Device(id, userId, data.path("name").asText(data.path("deviceName").asText()), data.path("isOnline").asBoolean());
                        currentDevice.set(device);
                        instance.setDeviceName(device.label());
                        return device;
                    } catch (ApiException error) { throw error; }
                    catch (Exception error) { throw new ApiException(0, "PROTOCOL_INCOMPATIBLE", "Invalid device enrollment response."); }
                }
            });
        } catch (Exception error) { return CompletableFuture.failedFuture(error); }
    }
}
