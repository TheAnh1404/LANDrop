package com.landrop.discovery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.protocol.MessageType;
import com.landrop.protocol.Packet;
import com.landrop.protocol.PacketDecoder;
import com.landrop.protocol.PacketEncoder;
import com.landrop.protocol.RequestIdGenerator;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Client for interacting with the centralized Discovery Server.
 * Handles registration, periodic presence heartbeats, peer list queries, and unregistration.
 */
public class DiscoveryClient implements AutoCloseable {

    public static final int DEFAULT_HEARTBEAT_INTERVAL_SEC = 5;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String discoveryHost;
    private final int discoveryPort;
    private final RequestIdGenerator requestIdGenerator;
    private final PacketEncoder packetEncoder = new PacketEncoder();
    private final PacketDecoder packetDecoder = new PacketDecoder();
    private final Object exchangeLock = new Object();

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private PeerInfo myPeerInfo;
    private ScheduledExecutorService heartbeatScheduler;
    private volatile boolean connected = false;

    /**
     * Constructs a DiscoveryClient targeting the specified Discovery Server host and port.
     *
     * @param discoveryHost hostname or IP address of the discovery server
     * @param discoveryPort port of the discovery server
     */
    public DiscoveryClient(String discoveryHost, int discoveryPort) {
        this(discoveryHost, discoveryPort, new RequestIdGenerator(100L));
    }

    /**
     * Constructs a DiscoveryClient with a custom RequestIdGenerator.
     *
     * @param discoveryHost hostname or IP address of the discovery server
     * @param discoveryPort port of the discovery server
     * @param requestIdGenerator custom request ID generator
     */
    public DiscoveryClient(String discoveryHost, int discoveryPort, RequestIdGenerator requestIdGenerator) {
        this.discoveryHost = Objects.requireNonNull(discoveryHost, "discoveryHost cannot be null");
        this.discoveryPort = discoveryPort;
        this.requestIdGenerator = Objects.requireNonNull(requestIdGenerator, "requestIdGenerator cannot be null");
    }

    /**
     * Connects to the discovery server with a 5000ms timeout.
     *
     * @throws IOException if connection fails
     */
    public void connect() throws IOException {
        connect(5000);
    }

    /**
     * Connects to the discovery server with a specified timeout.
     *
     * @param timeoutMs connection timeout in milliseconds
     * @throws IOException if connection fails
     */
    public void connect(int timeoutMs) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(discoveryHost, discoveryPort), timeoutMs);
        s.setSoTimeout(com.landrop.config.AppConfig.getSocketTimeoutMs());
        this.socket = s;
        this.in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
        this.connected = true;
    }

    /**
     * Registers this peer with the discovery server.
     *
     * @param peerInfo peer details to register
     * @return true if registration succeeded, false otherwise
     * @throws IOException if communication fails
     */
    public boolean register(PeerInfo peerInfo) throws IOException {
        Objects.requireNonNull(peerInfo, "peerInfo cannot be null");
        this.myPeerInfo = peerInfo;

        String json = OBJECT_MAPPER.writeValueAsString(peerInfo);
        long reqId = requestIdGenerator.nextId();
        Packet reqPacket = Packet.text(MessageType.REGISTER, reqId, json);

        Packet respPacket = exchange(reqPacket);
        if (respPacket == null || respPacket.getType() != MessageType.REGISTER_ACK) {
            return false;
        }

        JsonNode node = OBJECT_MAPPER.readTree(respPacket.payloadAsString());
        boolean success = node.has("success") && node.get("success").asBoolean();
        String message = node.has("message") ? node.get("message").asText() : "";

        if (success) {
            System.out.println("[Discovery] Registered successfully");
            System.out.println("Peer ID: " + peerInfo.getPeerId());
            System.out.println("Address: " + peerInfo.getHost() + ":" + peerInfo.getPort());
        } else {
            System.err.println("[Discovery] Registration failed: " + message);
        }

        return success;
    }

    /**
     * Requests the current list of online peers from the discovery server (excluding this peer).
     *
     * @return list of active online peers
     * @throws IOException if communication fails
     */
    public List<PeerInfo> requestPeerList() throws IOException {
        Map<String, String> reqData = new HashMap<>();
        if (myPeerInfo != null) {
            reqData.put("requesterId", myPeerInfo.getPeerId());
        }

        String json = OBJECT_MAPPER.writeValueAsString(reqData);
        long reqId = requestIdGenerator.nextId();
        Packet reqPacket = Packet.text(MessageType.PEER_LIST_REQUEST, reqId, json);

        Packet respPacket = exchange(reqPacket);
        if (respPacket == null || respPacket.getType() != MessageType.PEER_LIST) {
            return Collections.emptyList();
        }

        List<PeerInfo> list = OBJECT_MAPPER.readValue(respPacket.payloadAsString(), new TypeReference<List<PeerInfo>>() {});
        System.out.println("[Discovery] Received peer list (" + list.size() + " peers)");
        return list;
    }

    /**
     * Sends a presence heartbeat to the discovery server.
     *
     * @return true if heartbeat acknowledged, false otherwise
     * @throws IOException if communication fails
     */
    public boolean heartbeat() throws IOException {
        if (myPeerInfo == null) {
            return false;
        }

        Map<String, String> reqData = new HashMap<>();
        reqData.put("peerId", myPeerInfo.getPeerId());

        String json = OBJECT_MAPPER.writeValueAsString(reqData);
        long reqId = requestIdGenerator.nextId();
        Packet reqPacket = Packet.text(MessageType.HEARTBEAT, reqId, json);

        Packet respPacket = exchange(reqPacket);
        if (respPacket == null || respPacket.getType() != MessageType.HEARTBEAT_ACK) {
            return false;
        }

        JsonNode node = OBJECT_MAPPER.readTree(respPacket.payloadAsString());
        return node.has("success") && node.get("success").asBoolean();
    }

    /**
     * Starts background periodic heartbeat transmission.
     *
     * @param intervalSeconds interval in seconds between heartbeats
     */
    public synchronized void startHeartbeat(int intervalSeconds) {
        if (heartbeatScheduler != null && !heartbeatScheduler.isShutdown()) {
            return;
        }

        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                if (isConnected()) {
                    boolean ack = heartbeat();
                    if (ack) {
                        System.out.println("[Discovery] Heartbeat sent");
                    }
                }
            } catch (Exception e) {
                System.err.println("[Discovery] Heartbeat error: " + e.getMessage());
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    /**
     * Unregisters this peer from the discovery server.
     *
     * @throws IOException if communication fails
     */
    public void unregister() throws IOException {
        if (!isConnected() || myPeerInfo == null) {
            return;
        }

        Map<String, String> reqData = new HashMap<>();
        reqData.put("peerId", myPeerInfo.getPeerId());

        String json = OBJECT_MAPPER.writeValueAsString(reqData);
        long reqId = requestIdGenerator.nextId();
        Packet reqPacket = Packet.text(MessageType.UNREGISTER, reqId, json);

        try {
            exchange(reqPacket);
        } catch (IOException ignored) {
        }
    }

    private Packet exchange(Packet request) throws IOException {
        if (!isConnected()) {
            throw new IOException("DiscoveryClient is not connected");
        }

        synchronized (exchangeLock) {
            packetEncoder.encode(request, out);
            out.flush();
            return packetDecoder.decode(in);
        }
    }

    public boolean isConnected() {
        return connected && socket != null && socket.isConnected() && !socket.isClosed();
    }

    public PeerInfo getMyPeerInfo() {
        return myPeerInfo;
    }

    public String getDiscoveryHost() {
        return discoveryHost;
    }

    public int getDiscoveryPort() {
        return discoveryPort;
    }

    @Override
    public void close() {
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
        }

        try {
            unregister();
        } catch (Exception ignored) {
        }

        this.connected = false;

        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
