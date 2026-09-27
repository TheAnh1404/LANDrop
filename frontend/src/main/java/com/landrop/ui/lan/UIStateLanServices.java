package com.landrop.ui.lan;

import com.landrop.discovery.PeerInfo;
import com.landrop.transfer.FileTransfer;
import com.landrop.ui.lan.model.DiscoveredDevice;
import com.landrop.ui.services.UIStateService;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Adapter isolating legacy Core P2P classes behind the client LAN contracts. */
public final class UIStateLanServices implements LanDiscoveryService, LanTransferService {
    private final UIStateService engine;
    private final ObservableList<DiscoveredDevice> devices = FXCollections.observableArrayList();
    public UIStateLanServices(UIStateService engine) {
        this.engine = engine;
        engine.getDiscoveredPeers().addListener((ListChangeListener<PeerInfo>) c -> refreshMapped());
        refreshMapped();
    }
    @Override public ObservableList<DiscoveredDevice> discoveredDevices() { return devices; }
    @Override public ReadOnlyIntegerProperty onlineDeviceCountProperty() { return engine.onlinePeersCountProperty(); }
    @Override public CompletableFuture<Void> connect(DiscoveredDevice device) {
        PeerInfo peer = engine.getDiscoveredPeers().stream().filter(p -> p.getPeerId().equals(device.peerId())).findFirst()
                .orElseThrow(() -> new IllegalStateException("The selected LAN device is no longer available."));
        return engine.connectToPeer(peer);
    }
    @Override public CompletableFuture<List<DiscoveredDevice>> refresh() { return engine.refreshPeerList().thenApply(peers -> { refreshMapped(); return List.copyOf(devices); }); }
    @Override public ObservableList<FileTransfer> transfers() { return engine.getTransfers(); }
    @Override public CompletableFuture<FileTransfer> offerLocalFile(File file) { return engine.offerFile(file); }
    @Override public CompletableFuture<Void> accept(String transferId) { return engine.acceptTransfer(transferId); }
    @Override public CompletableFuture<Void> reject(String transferId, String reason) { return engine.rejectTransfer(transferId, reason); }
    @Override public CompletableFuture<Void> cancel(String transferId) { return engine.cancelTransfer(transferId); }
    @Override public CompletableFuture<Void> pause(String transferId) { return engine.pauseTransfer(transferId); }
    @Override public CompletableFuture<Void> resume(String transferId) { return engine.resumeTransfer(transferId); }
    @Override public void setOnTransferUpdated(java.util.function.Consumer<FileTransfer> listener) { engine.setOnFileTransferUpdatedCallback(listener); }
    private void refreshMapped() { devices.setAll(engine.getDiscoveredPeers().stream().map(p -> new DiscoveredDevice(p.getPeerId(), p.getDisplayName(), p.getPeerId(), p.getHost(), p.getPort())).toList()); }
}
