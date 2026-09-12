package com.landrop.ui.services;

import com.landrop.chat.ChatListener;
import com.landrop.chat.ChatMessage;
import com.landrop.chat.ChatService;
import com.landrop.discovery.DiscoveryClient;
import com.landrop.discovery.PeerInfo;
import com.landrop.peer.ConnectionListener;
import com.landrop.peer.PeerConnection;
import com.landrop.peer.PeerIdentity;
import com.landrop.peer.PeerListener;
import com.landrop.transfer.FileTransfer;
import com.landrop.transfer.FileTransferListener;
import com.landrop.transfer.FileTransferService;
import com.landrop.ui.models.DiscoveryStatus;
import com.landrop.ui.models.P2PStatus;
import com.landrop.ui.models.UIMessage;
import javafx.application.Platform;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Application-level state management service bridging JavaFX presentation
 * with the underlying Discovery and P2P transport network services.
 * <p>
 * Ensures thread isolation between background network I/O and the JavaFX Application Thread.
 */
public class UIStateService {

    // Identity and configuration
    private volatile PeerIdentity identity;
    private volatile int localP2pPort = 5001;
    private volatile String advertisedHost = "127.0.0.1";
    private volatile String discoveryHost = "localhost";
    private volatile int discoveryPort = 8888;
    private final int protocolVersion = 1;

    // Observable properties bound to JavaFX views
    private final ObjectProperty<DiscoveryStatus> discoveryStatus =
            new SimpleObjectProperty<>(DiscoveryStatus.DISCONNECTED);
    private final ObjectProperty<P2PStatus> p2pStatus =
            new SimpleObjectProperty<>(P2PStatus.DISCONNECTED);

    private final StringProperty activeRemotePeerName = new SimpleStringProperty("");
    private final StringProperty activeRemotePeerAddress = new SimpleStringProperty("");

    private final IntegerProperty onlinePeersCount = new SimpleIntegerProperty(0);
    private final IntegerProperty p2pConnectionsCount = new SimpleIntegerProperty(0);
    private final IntegerProperty totalMessagesCount = new SimpleIntegerProperty(0);

    private final ObservableList<PeerInfo> discoveredPeers = FXCollections.observableArrayList();
    private final ObservableList<UIMessage> activeChatMessages = FXCollections.observableArrayList();

    // In-memory chat history storage keyed by remote peer display name
    private final Map<String, List<UIMessage>> chatHistoriesByPeer = new ConcurrentHashMap<>();

    // Underlying network handles
    private volatile PeerListener peerListener;
    private volatile DiscoveryClient discoveryClient;
    private volatile PeerConnection activeConnection;
    private volatile ChatService activeChatService;
    private volatile FileTransferService fileTransferService;

    private final ObservableList<FileTransfer> transfers = FXCollections.observableArrayList();
    private Consumer<FileTransfer> onFileTransferOfferedCallback;
    private Consumer<FileTransfer> onFileTransferUpdatedCallback;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Consumer<String> onIncomingConnectionCallback;

    public UIStateService() {
        this.identity = new PeerIdentity("Peer A");
    }

    /**
     * Initializes network listeners and connects to Discovery Server.
     */
    public CompletableFuture<Void> connectAndRegister(
            String displayName,
            int localPort,
            String discHost,
            int discPort,
            String advHost) {

        CompletableFuture<Void> future = new CompletableFuture<>();

        CompletableFuture.runAsync(() -> {
            try {
                Platform.runLater(() -> discoveryStatus.set(DiscoveryStatus.CONNECTING));

                // 1. Set configuration and identity
                this.identity = new PeerIdentity(displayName);
                this.localP2pPort = localPort;
                this.discoveryHost = discHost;
                this.discoveryPort = discPort;
                this.advertisedHost = (advHost != null && !advHost.isBlank()) ? advHost : "127.0.0.1";

                this.running.set(true);

                // 2. Start local P2P listener socket
                if (peerListener != null && !peerListener.isClosed()) {
                    try {
                        peerListener.close();
                    } catch (Exception ignored) {
                    }
                }
                try {
                    this.peerListener = new PeerListener(this.localP2pPort);
                } catch (IOException e) {
                    throw new RuntimeException("Local P2P Port " + this.localP2pPort + " is already in use by another application. Please choose another port (e.g. 5002, 5003).", e);
                }
                startP2pAcceptorLoop();

                // 3. Connect to Discovery Server
                if (discoveryClient != null) {
                    try {
                        discoveryClient.close();
                    } catch (Exception ignored) {
                    }
                }
                try {
                    this.discoveryClient = new DiscoveryClient(this.discoveryHost, this.discoveryPort);
                    this.discoveryClient.connect(5000);
                } catch (IOException e) {
                    throw new RuntimeException("Cannot connect to Discovery Server at " + this.discoveryHost + ":" + this.discoveryPort + ". Please ensure DiscoveryServer is running on port " + this.discoveryPort + ".", e);
                }

                // 4. Register peer
                PeerInfo myInfo = new PeerInfo(
                        identity.getPeerId(),
                        identity.getDisplayName(),
                        this.advertisedHost,
                        this.peerListener.getPort()
                );
                boolean registered = discoveryClient.register(myInfo);
                if (!registered) {
                    throw new IOException("Discovery Server rejected peer registration");
                }

                // 5. Start heartbeat loop
                discoveryClient.startHeartbeat(DiscoveryClient.DEFAULT_HEARTBEAT_INTERVAL_SEC);

                // 6. Fetch initial peer list
                List<PeerInfo> peers = discoveryClient.requestPeerList();
                Platform.runLater(() -> {
                    discoveryStatus.set(DiscoveryStatus.CONNECTED);
                    updateDiscoveredPeersList(peers);
                    future.complete(null);
                });

            } catch (Exception e) {
                Platform.runLater(() -> {
                    discoveryStatus.set(DiscoveryStatus.ERROR);
                    String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                    future.completeExceptionally(new RuntimeException(msg, e));
                });
            }
        });

        return future;
    }

    /**
     * Background loop accepting incoming direct P2P connections.
     */
    private void startP2pAcceptorLoop() {
        Thread.ofVirtual().name("ui-p2p-acceptor-" + localP2pPort).start(() -> {
            while (running.get() && peerListener != null && !peerListener.isClosed()) {
                try {
                    PeerConnection incoming = peerListener.acceptConnection();
                    incoming.setLocalPeerName(identity.getDisplayName());
                    incoming.start();

                    boolean ready = incoming.awaitReady(10, TimeUnit.SECONDS);
                    if (ready) {
                        String remoteName = incoming.getRemotePeerName();
                        String remoteAddress = (incoming.getSocket() != null && incoming.getSocket().getInetAddress() != null)
                                ? incoming.getSocket().getInetAddress().getHostAddress() + ":" + incoming.getSocket().getPort()
                                : "Unknown";
                        setActiveChatConnection(incoming, remoteName, remoteAddress, true, null);
                    }
                } catch (Exception e) {
                    if (!running.get() || (peerListener != null && peerListener.isClosed())) {
                        break;
                    }
                }
            }
        });
    }

    /**
     * Connects directly to a remote peer using P2P TCP socket and completes HELLO handshake.
     */
    public CompletableFuture<Void> connectToPeer(PeerInfo peerInfo) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        CompletableFuture.runAsync(() -> {
            try {
                Platform.runLater(() -> p2pStatus.set(P2PStatus.CONNECTING));

                PeerConnection conn = PeerConnection.connectTo(peerInfo.getHost(), peerInfo.getPort(), 5000);
                conn.setLocalPeerName(identity.getDisplayName());
                conn.start();

                conn.initiateHandshake(identity.getDisplayName());
                boolean ready = conn.awaitReady(10, TimeUnit.SECONDS);

                if (!ready) {
                    conn.close();
                    throw new IOException("Handshake with " + peerInfo.getDisplayName() + " timed out");
                }

                String address = peerInfo.getHost() + ":" + peerInfo.getPort();
                setActiveChatConnection(conn, peerInfo.getDisplayName(), address, false, future);

            } catch (Exception e) {
                Platform.runLater(() -> {
                    p2pStatus.set(P2PStatus.ERROR);
                    future.completeExceptionally(new RuntimeException("Unable to connect to " + peerInfo.getDisplayName() + " at " +
                            peerInfo.getHost() + ":" + peerInfo.getPort() + " (" + e.getMessage() + ")", e));
                });
            }
        });

        return future;
    }

    /**
     * Establishes the active chat connection and binds ChatService event listeners.
     */
    public synchronized void setActiveChatConnection(
            PeerConnection connection,
            String remotePeerName,
            String remoteAddress,
            boolean isIncoming,
            CompletableFuture<Void> completionFuture) {

        if (this.activeConnection != null && this.activeConnection.isConnected()) {
            try {
                this.activeConnection.close();
            } catch (Exception ignored) {
            }
        }

        if (this.fileTransferService != null) {
            try {
                this.fileTransferService.close();
            } catch (Exception ignored) {
            }
            this.fileTransferService = null;
        }

        this.activeConnection = connection;
        this.activeChatService = new ChatService(connection, identity.getDisplayName());

        try {
            this.fileTransferService = new FileTransferService(
                    connection,
                    identity != null ? identity.getPeerId() : java.util.UUID.randomUUID().toString(),
                    identity != null ? identity.getDisplayName() : "Peer"
            );

            this.fileTransferService.addListener(new FileTransferListener() {
                @Override
                public void onTransferOffered(FileTransfer transfer) {
                    Platform.runLater(() -> {
                        if (!transfers.contains(transfer)) {
                            transfers.add(0, transfer);
                        }
                        if (onFileTransferOfferedCallback != null) {
                            onFileTransferOfferedCallback.accept(transfer);
                        }
                        if (onFileTransferUpdatedCallback != null) {
                            onFileTransferUpdatedCallback.accept(transfer);
                        }
                    });
                }

                @Override
                public void onTransferStarted(FileTransfer transfer) {
                    Platform.runLater(() -> {
                        if (!transfers.contains(transfer)) {
                            transfers.add(0, transfer);
                        }
                        if (onFileTransferUpdatedCallback != null) {
                            onFileTransferUpdatedCallback.accept(transfer);
                        }
                    });
                }

                @Override
                public void onTransferProgress(FileTransfer transfer, long transferredBytes, long totalBytes, double speedBytesPerSec) {
                    Platform.runLater(() -> {
                        if (onFileTransferUpdatedCallback != null) {
                            onFileTransferUpdatedCallback.accept(transfer);
                        }
                    });
                }

                @Override
                public void onTransferCompleted(FileTransfer transfer) {
                    Platform.runLater(() -> {
                        if (onFileTransferUpdatedCallback != null) {
                            onFileTransferUpdatedCallback.accept(transfer);
                        }
                    });
                }

                @Override
                public void onTransferRejected(FileTransfer transfer, String reason) {
                    Platform.runLater(() -> {
                        if (onFileTransferUpdatedCallback != null) {
                            onFileTransferUpdatedCallback.accept(transfer);
                        }
                    });
                }

                @Override
                public void onTransferFailed(FileTransfer transfer, String reason) {
                    Platform.runLater(() -> {
                        if (onFileTransferUpdatedCallback != null) {
                            onFileTransferUpdatedCallback.accept(transfer);
                        }
                    });
                }
            });
        } catch (Exception e) {
            System.err.println("[FileTransfer] Failed to initialize FileTransferService: " + e.getMessage());
        }

        // Wire chat events
        activeChatService.addListener(new ChatListener() {
            @Override
            public void onMessageReceived(ChatMessage message) {
                UIMessage uiMsg = UIMessage.from(message, false);
                recordMessage(remotePeerName, uiMsg);
                Platform.runLater(() -> {
                    if (remotePeerName.equals(activeRemotePeerName.get())) {
                        activeChatMessages.add(uiMsg);
                    }
                    totalMessagesCount.set(totalMessagesCount.get() + 1);
                });
            }

            @Override
            public void onMessageSent(ChatMessage message) {
                UIMessage uiMsg = UIMessage.from(message, true);
                recordMessage(remotePeerName, uiMsg);
                Platform.runLater(() -> {
                    if (remotePeerName.equals(activeRemotePeerName.get())) {
                        activeChatMessages.add(uiMsg);
                    }
                    totalMessagesCount.set(totalMessagesCount.get() + 1);
                });
            }

            @Override
            public void onChatDisconnected() {
                Platform.runLater(() -> {
                    p2pStatus.set(P2PStatus.DISCONNECTED);
                    p2pConnectionsCount.set(0);
                });
            }

            @Override
            public void onChatError(Exception error) {
                Platform.runLater(() -> {
                    p2pStatus.set(P2PStatus.ERROR);
                    p2pConnectionsCount.set(0);
                });
            }
        });

        // Connection lifecycle listener
        connection.addListener(new ConnectionListener() {
            @Override
            public void onDisconnected() {
                Platform.runLater(() -> {
                    p2pStatus.set(P2PStatus.DISCONNECTED);
                    p2pConnectionsCount.set(0);
                });
            }
        });

        Platform.runLater(() -> {
            this.p2pStatus.set(P2PStatus.CONNECTED);
            this.activeRemotePeerName.set(remotePeerName);
            this.activeRemotePeerAddress.set(remoteAddress);
            this.p2pConnectionsCount.set(1);

            // Load existing chat history for this peer
            List<UIMessage> history = chatHistoriesByPeer.getOrDefault(remotePeerName, Collections.emptyList());
            this.activeChatMessages.setAll(history);

            if (completionFuture != null) {
                completionFuture.complete(null);
            }

            if (isIncoming && onIncomingConnectionCallback != null) {
                onIncomingConnectionCallback.accept(remotePeerName);
            }
        });
    }

    private void recordMessage(String peerName, UIMessage msg) {
        chatHistoriesByPeer.computeIfAbsent(peerName, k -> new CopyOnWriteArrayList<>()).add(msg);
    }

    /**
     * Refreshes the peer list from the Discovery Server asynchronously.
     */
    public CompletableFuture<List<PeerInfo>> refreshPeerList() {
        CompletableFuture<List<PeerInfo>> future = new CompletableFuture<>();

        CompletableFuture.runAsync(() -> {
            if (discoveryClient == null || !discoveryClient.isConnected()) {
                Platform.runLater(() -> {
                    discoveryStatus.set(DiscoveryStatus.DISCONNECTED);
                    discoveredPeers.clear();
                    onlinePeersCount.set(0);
                    future.complete(Collections.emptyList());
                });
                return;
            }

            try {
                List<PeerInfo> peers = discoveryClient.requestPeerList();
                Platform.runLater(() -> {
                    updateDiscoveredPeersList(peers);
                    future.complete(peers);
                });
            } catch (IOException e) {
                Platform.runLater(() -> {
                    discoveryStatus.set(DiscoveryStatus.ERROR);
                    future.completeExceptionally(new RuntimeException("Failed to refresh peers: " + e.getMessage(), e));
                });
            }
        });

        return future;
    }

    private void updateDiscoveredPeersList(List<PeerInfo> peers) {
        String selfId = identity != null ? identity.getPeerId() : "";
        List<PeerInfo> filtered = peers.stream()
                .filter(p -> !p.getPeerId().equals(selfId))
                .toList();

        discoveredPeers.setAll(filtered);
        onlinePeersCount.set(filtered.size());
    }

    /**
     * Sends a chat message over the active P2P connection.
     */
    public CompletableFuture<Void> sendMessage(String text) {
        return CompletableFuture.runAsync(() -> {
            if (activeChatService == null || !activeChatService.isConnected()) {
                throw new IllegalStateException("No active P2P chat connection");
            }
            try {
                activeChatService.sendMessage(text);
            } catch (IOException e) {
                throw new RuntimeException("Failed to send message: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Measures round-trip latency to the connected peer via PING probe.
     */
    public CompletableFuture<Double> pingPeer() {
        if (activeChatService == null || !activeChatService.isConnected()) {
            return CompletableFuture.failedFuture(new IllegalStateException("No active P2P connection to ping"));
        }
        try {
            return activeChatService.ping();
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Disconnects the active P2P direct session without closing the Discovery connection.
     */
    public void disconnectP2P() {
        if (activeChatService != null) {
            try {
                activeChatService.close();
            } catch (Exception ignored) {
            }
        }

        if (activeConnection != null) {
            try {
                activeConnection.close();
            } catch (Exception ignored) {
            }
        }

        if (fileTransferService != null) {
            try {
                fileTransferService.close();
            } catch (Exception ignored) {
            }
            fileTransferService = null;
        }

        Platform.runLater(() -> {
            p2pStatus.set(P2PStatus.DISCONNECTED);
            p2pConnectionsCount.set(0);
            activeRemotePeerName.set("");
            activeRemotePeerAddress.set("");
        });
    }

    /**
     * Graceful application shutdown: closes P2P connections, unregisters from Discovery, and stops listeners.
     */
    public void shutdown() {
        running.set(false);

        disconnectP2P();

        if (discoveryClient != null) {
            try {
                discoveryClient.close();
            } catch (Exception ignored) {
            }
        }

        if (peerListener != null) {
            try {
                peerListener.close();
            } catch (Exception ignored) {
            }
        }

        Platform.runLater(() -> {
            discoveryStatus.set(DiscoveryStatus.DISCONNECTED);
            p2pStatus.set(P2PStatus.DISCONNECTED);
        });
    }

    // Getters and Properties
    public PeerIdentity getIdentity() {
        return identity;
    }

    public void setIdentity(PeerIdentity identity) {
        this.identity = identity;
    }

    public int getLocalP2pPort() {
        return peerListener != null ? peerListener.getPort() : localP2pPort;
    }

    public void setLocalP2pPort(int localP2pPort) {
        this.localP2pPort = localP2pPort;
    }

    public String getAdvertisedHost() {
        return advertisedHost;
    }

    public void setAdvertisedHost(String advertisedHost) {
        this.advertisedHost = advertisedHost;
    }

    public String getDiscoveryHost() {
        return discoveryHost;
    }

    public void setDiscoveryHost(String discoveryHost) {
        this.discoveryHost = discoveryHost;
    }

    public int getDiscoveryPort() {
        return discoveryPort;
    }

    public void setDiscoveryPort(int discoveryPort) {
        this.discoveryPort = discoveryPort;
    }

    public int getProtocolVersion() {
        return protocolVersion;
    }

    public ObjectProperty<DiscoveryStatus> discoveryStatusProperty() {
        return discoveryStatus;
    }

    public ObjectProperty<P2PStatus> p2pStatusProperty() {
        return p2pStatus;
    }

    public StringProperty activeRemotePeerNameProperty() {
        return activeRemotePeerName;
    }

    public StringProperty activeRemotePeerAddressProperty() {
        return activeRemotePeerAddress;
    }

    public IntegerProperty onlinePeersCountProperty() {
        return onlinePeersCount;
    }

    public IntegerProperty p2pConnectionsCountProperty() {
        return p2pConnectionsCount;
    }

    public IntegerProperty totalMessagesCountProperty() {
        return totalMessagesCount;
    }

    public ObservableList<PeerInfo> getDiscoveredPeers() {
        return discoveredPeers;
    }

    public ObservableList<UIMessage> getActiveChatMessages() {
        return activeChatMessages;
    }

    public void setOnIncomingConnectionCallback(Consumer<String> callback) {
        this.onIncomingConnectionCallback = callback;
    }

    public boolean isP2pConnected() {
        return p2pStatus.get() == P2PStatus.CONNECTED;
    }

    public CompletableFuture<FileTransfer> offerFile(java.io.File file) {
        CompletableFuture<FileTransfer> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            if (fileTransferService == null || !isP2pConnected()) {
                future.completeExceptionally(new IllegalStateException("No active P2P connection to transfer files"));
                return;
            }
            try {
                FileTransfer transfer = fileTransferService.offerFile(file);
                Platform.runLater(() -> {
                    if (!transfers.contains(transfer)) {
                        transfers.add(0, transfer);
                    }
                    if (onFileTransferUpdatedCallback != null) {
                        onFileTransferUpdatedCallback.accept(transfer);
                    }
                    future.complete(transfer);
                });
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public CompletableFuture<Void> acceptTransfer(String transferId) {
        return CompletableFuture.runAsync(() -> {
            if (fileTransferService == null) {
                throw new IllegalStateException("File transfer service is not active");
            }
            try {
                fileTransferService.acceptTransfer(transferId);
            } catch (Exception e) {
                throw new RuntimeException("Failed to accept transfer: " + e.getMessage(), e);
            }
        });
    }

    public CompletableFuture<Void> rejectTransfer(String transferId, String reason) {
        return CompletableFuture.runAsync(() -> {
            if (fileTransferService == null) {
                throw new IllegalStateException("File transfer service is not active");
            }
            try {
                fileTransferService.rejectTransfer(transferId, reason);
            } catch (Exception e) {
                throw new RuntimeException("Failed to reject transfer: " + e.getMessage(), e);
            }
        });
    }

    public CompletableFuture<Void> cancelTransfer(String transferId) {
        return CompletableFuture.runAsync(() -> {
            if (fileTransferService != null) {
                fileTransferService.cancelTransfer(transferId);
            }
        });
    }

    public ObservableList<FileTransfer> getTransfers() {
        return transfers;
    }

    public FileTransferService getFileTransferService() {
        return fileTransferService;
    }

    public void setOnFileTransferOfferedCallback(Consumer<FileTransfer> callback) {
        this.onFileTransferOfferedCallback = callback;
    }

    public void setOnFileTransferUpdatedCallback(Consumer<FileTransfer> callback) {
        this.onFileTransferUpdatedCallback = callback;
    }
}
