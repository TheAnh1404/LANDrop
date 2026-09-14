package com.landrop.ui.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.landrop.ui.auth.services.AuthStateService;
import com.landrop.ui.cloud.api.ApiClient;
import com.landrop.ui.cloud.model.Domain;
import com.landrop.ui.services.UIStateService;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.prefs.Preferences;

/** Maintains this installation's device identity; it never becomes the product-user identity. */
public final class CurrentDeviceService {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final AuthStateService auth; private final ApiClient api; private final String deviceId;
    private final ObjectProperty<Domain.Device> currentDevice=new SimpleObjectProperty<>();
    public CurrentDeviceService(URI apiBase,AuthStateService auth){this.auth=auth;this.api=new ApiClient(apiBase,auth);Preferences p=Preferences.userNodeForPackage(CurrentDeviceService.class);deviceId=p.get("landrop.device.id",null)==null?store(p):p.get("landrop.device.id",null);}
    private static String store(Preferences p){String id=UUID.randomUUID().toString();p.put("landrop.device.id",id);return id;}
    public ObjectProperty<Domain.Device> currentDeviceProperty(){return currentDevice;}
    public Domain.Device currentDevice(){return currentDevice.get();}
    /** Registers current endpoint after Discovery is connected. Server remains the authority for mapping device to user. */
    public CompletableFuture<Domain.Device> register(UIStateService lan){
        String name=lan.getIdentity().getDisplayName();String peerId=lan.getIdentity().getPeerId();
        try {String body=JSON.writeValueAsString(Map.of("deviceId",deviceId,"deviceName",name,"peerId",peerId,"ipAddress",lan.getAdvertisedHost(),"port",lan.getLocalP2pPort()));
            return api.request("POST","devices",body).thenApply(this::map);
        }catch(Exception error){return CompletableFuture.failedFuture(error);}
    }
    private Domain.Device map(String body){try{JsonNode n=JSON.readTree(body).path("data");String id=n.path("deviceId").asText(deviceId);String label=n.path("deviceName").asText("Device");Domain.Device device=new Domain.Device(id,n.path("userId").asText(auth.user().id()),label,n.path("isOnline").asBoolean(false));currentDevice.set(device);return device;}catch(Exception e){throw new IllegalArgumentException("Invalid device registration response",e);}}
}
