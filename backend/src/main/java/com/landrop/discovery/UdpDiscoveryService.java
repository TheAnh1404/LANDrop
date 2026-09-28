package com.landrop.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.util.AppLogger;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Zero-configuration LAN Peer Discovery using UDP Multicast.
 * <p>
 * Periodically broadcasts presence beacons containing peer ID, display name,
 * and TCP listening port across multicast group 239.255.88.88 on port 8889.
 * Listens for beacons from other LAN peers and maintains presence state with automatic
 * stale peer eviction.
 */
public class UdpDiscoveryService implements AutoCloseable {

    private static final String TAG = "UdpDiscoveryService";
    public static final String MULTICAST_GROUP_IP = "239.255.88.88";
    public static final int DEFAULT_UDP_PORT = 8889;
    public static final int BEACON_INTERVAL_SEC = 1;
    public static final long PEER_TIMEOUT_MS = 10_000L;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String selfPeerId;
    private final String selfDisplayName;
    private final int selfTcpPort;
    private final int udpPort;
    private final String multicastGroupIp;

    private final Map<String, PeerInfo> discoveredPeers = new ConcurrentHashMap<>();
    private final List<Consumer<PeerInfo>> peerDiscoveredListeners = new ArrayList<>();
    private final List<Consumer<String>> peerLostListeners = new ArrayList<>();

    private MulticastSocket socket;
    private InetAddress groupAddress;
    private ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public UdpDiscoveryService(String selfPeerId, String selfDisplayName, int selfTcpPort) {
        this(selfPeerId, selfDisplayName, selfTcpPort, DEFAULT_UDP_PORT, MULTICAST_GROUP_IP);
    }

    public UdpDiscoveryService(String selfPeerId, String selfDisplayName, int selfTcpPort, int udpPort) {
        this(selfPeerId, selfDisplayName, selfTcpPort, udpPort, MULTICAST_GROUP_IP);
    }

    public UdpDiscoveryService(String selfPeerId, String selfDisplayName, int selfTcpPort, int udpPort, String multicastGroupIp) {
        this.selfPeerId = Objects.requireNonNull(selfPeerId, "selfPeerId cannot be null");
        this.selfDisplayName = Objects.requireNonNull(selfDisplayName, "selfDisplayName cannot be null");
        this.selfTcpPort = selfTcpPort;
        this.udpPort = udpPort;
        this.multicastGroupIp = Objects.requireNonNull(multicastGroupIp, "multicastGroupIp cannot be null");
    }

    public synchronized void addPeerDiscoveredListener(Consumer<PeerInfo> listener) {
        if (listener != null) peerDiscoveredListeners.add(listener);
    }

    public synchronized void addPeerLostListener(Consumer<String> listener) {
        if (listener != null) peerLostListeners.add(listener);
    }

    public Map<String, PeerInfo> getDiscoveredPeers() {
        return Collections.unmodifiableMap(discoveredPeers);
    }

    /**
     * Starts listening for UDP beacons and broadcasts self presence periodically.
     */
    public synchronized void start() throws IOException {
        if (running.get()) return;

        this.groupAddress = InetAddress.getByName(multicastGroupIp);
        this.socket = new MulticastSocket(udpPort);
        this.socket.setReuseAddress(true);

        try {
            this.socket.setTimeToLive(4);
        } catch (Exception ignored) {
        }

        // Join multicast group on all active non-loopback network interfaces and loopback
        joinGroupAcrossInterfaces();

        running.set(true);

        // 1. Start listener thread
        Thread.ofVirtual().name("udp-discovery-listener-" + selfTcpPort).start(this::listenLoop);

        // 2. Schedule periodic presence broadcast and stale peer eviction
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "udp-discovery-scheduler-" + selfTcpPort);
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(this::broadcastPresence, 0, BEACON_INTERVAL_SEC, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::evictStalePeers, BEACON_INTERVAL_SEC, BEACON_INTERVAL_SEC, TimeUnit.SECONDS);

        AppLogger.info(TAG, "UDP Multicast Discovery Service started on " + multicastGroupIp + ":" + udpPort + " for " + selfDisplayName);
    }

    private void joinGroupAcrossInterfaces() {
        SocketAddress mcastAddr = new InetSocketAddress(groupAddress, udpPort);
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                try {
                    if (iface.isUp() && iface.supportsMulticast()) {
                        socket.joinGroup(mcastAddr, iface);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void leaveGroupAcrossInterfaces() {
        if (socket == null || groupAddress == null) return;
        SocketAddress mcastAddr = new InetSocketAddress(groupAddress, udpPort);
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                try {
                    if (iface.isUp() && iface.supportsMulticast()) {
                        socket.leaveGroup(mcastAddr, iface);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void listenLoop() {
        byte[] buffer = new byte[4096];
        while (running.get() && socket != null && !socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String json = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                PeerInfo peer = OBJECT_MAPPER.readValue(json, PeerInfo.class);

                if (peer.getPeerId() == null || peer.getPeerId().isBlank()
                        || peer.getPeerId().length() > 128 || peer.getDisplayName() == null
                        || peer.getDisplayName().isBlank() || peer.getDisplayName().length() > 128
                        || peer.getPort() < 1 || peer.getPort() > 65535) {
                    continue;
                }

                // Ignore packets from self
                if (selfPeerId.equals(peer.getPeerId())) {
                    continue;
                }

                // If host in beacon is 0.0.0.0, use packet's sender IP address
                String senderIp = packet.getAddress().getHostAddress();
                peer.setHost(senderIp);

                if (peer.getStatus() == PeerStatus.OFFLINE) {
                    PeerInfo removed = discoveredPeers.remove(peer.getPeerId());
                    if (removed != null) {
                        notifyPeerLost(peer.getPeerId());
                    }
                } else {
                    peer.setLastSeen(System.currentTimeMillis());
                    boolean isNew = !discoveredPeers.containsKey(peer.getPeerId());
                    discoveredPeers.put(peer.getPeerId(), peer);
                    if (isNew) {
                        AppLogger.info(TAG, "Discovered LAN peer via UDP: " + peer.getDisplayName() + " at " + peer.getHost() + ":" + peer.getPort());
                        notifyPeerDiscovered(peer);
                    }
                }
            } catch (SocketException e) {
                if (!running.get() || socket == null || socket.isClosed()) {
                    break;
                }
            } catch (Exception e) {
                if (running.get()) {
                    AppLogger.warn(TAG, "Error processing incoming UDP discovery packet: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Broadcasts presence beacon to the multicast group.
     */
    public void broadcastPresence() {
        if (!running.get() || socket == null || socket.isClosed()) return;

        try {
            PeerInfo self = new PeerInfo(selfPeerId, selfDisplayName, "0.0.0.0", selfTcpPort, System.currentTimeMillis(), PeerStatus.ONLINE);
            byte[] data = OBJECT_MAPPER.writeValueAsString(self).getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(data, data.length, groupAddress, udpPort);
            socket.send(packet);
        } catch (Exception e) {
            AppLogger.warn(TAG, "Failed sending UDP multicast presence: " + e.getMessage());
        }
    }

    /**
     * Broadcasts an OFFLINE beacon so peers immediately remove this instance.
     */
    public void broadcastOffline() {
        if (socket == null || socket.isClosed() || groupAddress == null) return;
        try {
            PeerInfo self = new PeerInfo(selfPeerId, selfDisplayName, "0.0.0.0", selfTcpPort, System.currentTimeMillis(), PeerStatus.OFFLINE);
            byte[] data = OBJECT_MAPPER.writeValueAsString(self).getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(data, data.length, groupAddress, udpPort);
            socket.send(packet);
        } catch (Exception ignored) {
        }
    }

    private void evictStalePeers() {
        long now = System.currentTimeMillis();
        List<String> staleIds = new ArrayList<>();

        for (Map.Entry<String, PeerInfo> entry : discoveredPeers.entrySet()) {
            if (now - entry.getValue().getLastSeen() > PEER_TIMEOUT_MS) {
                staleIds.add(entry.getKey());
            }
        }

        for (String id : staleIds) {
            PeerInfo removed = discoveredPeers.remove(id);
            if (removed != null) {
                AppLogger.info(TAG, "Evicted stale LAN peer via UDP timeout: " + removed.getDisplayName() + " (" + id + ")");
                notifyPeerLost(id);
            }
        }
    }

    private synchronized void notifyPeerDiscovered(PeerInfo peer) {
        for (Consumer<PeerInfo> listener : peerDiscoveredListeners) {
            try {
                listener.accept(peer);
            } catch (Exception e) {
                AppLogger.warn(TAG, "Error in peer discovered listener: " + e.getMessage());
            }
        }
    }

    private synchronized void notifyPeerLost(String peerId) {
        for (Consumer<String> listener : peerLostListeners) {
            try {
                listener.accept(peerId);
            } catch (Exception e) {
                AppLogger.warn(TAG, "Error in peer lost listener: " + e.getMessage());
            }
        }
    }

    @Override
    public synchronized void close() {
        if (!running.compareAndSet(true, false)) return;

        broadcastOffline();

        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }

        leaveGroupAcrossInterfaces();

        if (socket != null && !socket.isClosed()) {
            socket.close();
            socket = null;
        }

        discoveredPeers.clear();
        AppLogger.info(TAG, "UDP Multicast Discovery Service closed");
    }
}
