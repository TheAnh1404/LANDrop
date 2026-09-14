package com.landrop.ui.lan;

import com.landrop.ui.lan.model.DiscoveredDevice;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.collections.ObservableList;
import java.util.concurrent.CompletableFuture;

/** UI-safe LAN discovery boundary; it exposes devices/endpoints, not fake users. */
public interface LanDiscoveryService {
    ObservableList<DiscoveredDevice> discoveredDevices();
    ReadOnlyIntegerProperty onlineDeviceCountProperty();
    CompletableFuture<Void> connect(DiscoveredDevice device);
    CompletableFuture<java.util.List<DiscoveredDevice>> refresh();
}
