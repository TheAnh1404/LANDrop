package com.landrop.discovery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.protocol.MessageType;
import com.landrop.protocol.Packet;
import com.landrop.protocol.PacketDecoder;
import com.landrop.protocol.PacketEncoder;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Handles incoming discovery protocol requests from an individual peer client connection.
 */
public class DiscoveryClientHandler implements Runnable, AutoCloseable {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Socket socket;
    private final PeerRegistry registry;
    private final PacketDecoder packetDecoder;
    private final PacketEncoder packetEncoder;
    private final Object writeLock = new Object();

    private volatile String registeredPeerId;
    private volatile boolean running = true;

    /**
     * Constructs a DiscoveryClientHandler for the accepted peer client socket.
     *
     * @param socket the connected TCP socket
     * @param registry the shared PeerRegistry
     */
    public DiscoveryClientHandler(Socket socket, PeerRegistry registry) {
        this.socket = Objects.requireNonNull(socket, "socket cannot be null");
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");
        this.packetDecoder = new PacketDecoder();
        this.packetEncoder = new PacketEncoder();
    }

    @Override
    public void run() {
        try (socket;
             DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {

            while (running && !socket.isClosed()) {
                Packet request;
                try {
                    request = packetDecoder.decode(in);
                } catch (EOFException | SocketException e) {
                    break;
                }

                if (request == null) {
                    break;
                }

                handlePacket(request, out);
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("[Discovery Error] Client connection error: " + e.getMessage());
            }
        } finally {
            close();
        }
    }

    private void handlePacket(Packet request, DataOutputStream out) throws IOException {
        long reqId = request.getRequestId();
        String payloadStr = request.payloadAsString();

        switch (request.getType()) {
            case REGISTER -> handleRegister(reqId, payloadStr, out);
            case PEER_LIST_REQUEST -> handlePeerListRequest(reqId, payloadStr, out);
            case HEARTBEAT -> handleHeartbeat(reqId, payloadStr, out);
            case UNREGISTER -> handleUnregister(reqId, payloadStr, out);
            default -> {
                // Ignore or respond with empty ACK for unhandled messages
            }
        }
    }

    private void handleRegister(long reqId, String payloadStr, DataOutputStream out) throws IOException {
        try {
            PeerInfo peerInfo = OBJECT_MAPPER.readValue(payloadStr, PeerInfo.class);
            PeerRegistry.RegistrationResult result = registry.register(peerInfo);

            if (result.success()) {
                this.registeredPeerId = peerInfo.getPeerId();
                System.out.println("[REGISTER] " + peerInfo.getDisplayName() + " registered");
            } else {
                System.out.println("[REGISTER Rejected] " + peerInfo.getDisplayName() + ": " + result.message());
            }

            Map<String, Object> ackPayload = new HashMap<>();
            ackPayload.put("success", result.success());
            ackPayload.put("message", result.message());

            String jsonAck = OBJECT_MAPPER.writeValueAsString(ackPayload);
            Packet response = Packet.text(MessageType.REGISTER_ACK, reqId, jsonAck);
            sendPacket(response, out);

        } catch (Exception e) {
            Map<String, Object> failPayload = new HashMap<>();
            failPayload.put("success", false);
            failPayload.put("message", "Malformed registration payload: " + e.getMessage());

            String jsonAck = OBJECT_MAPPER.writeValueAsString(failPayload);
            Packet response = Packet.text(MessageType.REGISTER_ACK, reqId, jsonAck);
            sendPacket(response, out);
        }
    }

    private void handlePeerListRequest(long reqId, String payloadStr, DataOutputStream out) throws IOException {
        String requesterId = registeredPeerId;
        if (payloadStr != null && !payloadStr.isBlank()) {
            try {
                JsonNode node = OBJECT_MAPPER.readTree(payloadStr);
                if (node.has("requesterId")) {
                    requesterId = node.get("requesterId").asText();
                } else if (node.isTextual()) {
                    requesterId = node.asText();
                }
            } catch (Exception ignored) {
                // Fallback to registeredPeerId
            }
        }

        List<PeerInfo> peerList = registry.getPeerList(requesterId);
        String jsonList = OBJECT_MAPPER.writeValueAsString(peerList);

        System.out.println("[PEER_LIST] Sent " + peerList.size() + " peers");

        Packet response = Packet.text(MessageType.PEER_LIST, reqId, jsonList);
        sendPacket(response, out);
    }

    private void handleHeartbeat(long reqId, String payloadStr, DataOutputStream out) throws IOException {
        String peerId = registeredPeerId;
        if (payloadStr != null && !payloadStr.isBlank()) {
            try {
                JsonNode node = OBJECT_MAPPER.readTree(payloadStr);
                if (node.has("peerId")) {
                    peerId = node.get("peerId").asText();
                }
            } catch (Exception ignored) {
            }
        }

        boolean updated = registry.updateHeartbeat(peerId);
        PeerInfo peer = registry.getPeer(peerId);
        String name = (peer != null) ? peer.getDisplayName() : (peerId != null ? peerId : "Unknown");
        System.out.println("[HEARTBEAT] " + name);

        Map<String, Object> ackPayload = new HashMap<>();
        ackPayload.put("success", updated);

        String jsonAck = OBJECT_MAPPER.writeValueAsString(ackPayload);
        Packet response = Packet.text(MessageType.HEARTBEAT_ACK, reqId, jsonAck);
        sendPacket(response, out);
    }

    private void handleUnregister(long reqId, String payloadStr, DataOutputStream out) throws IOException {
        String peerId = registeredPeerId;
        if (payloadStr != null && !payloadStr.isBlank()) {
            try {
                JsonNode node = OBJECT_MAPPER.readTree(payloadStr);
                if (node.has("peerId")) {
                    peerId = node.get("peerId").asText();
                }
            } catch (Exception ignored) {
            }
        }

        PeerInfo removed = registry.unregister(peerId);
        String name = (removed != null) ? removed.getDisplayName() : (peerId != null ? peerId : "Unknown");
        System.out.println("[UNREGISTER] " + name);

        Map<String, Object> ackPayload = new HashMap<>();
        ackPayload.put("success", removed != null);
        ackPayload.put("message", "Unregistered successfully");

        String jsonAck = OBJECT_MAPPER.writeValueAsString(ackPayload);
        Packet response = Packet.text(MessageType.REGISTER_ACK, reqId, jsonAck);
        sendPacket(response, out);
    }

    private void sendPacket(Packet packet, DataOutputStream out) throws IOException {
        synchronized (writeLock) {
            packetEncoder.encode(packet, out);
            out.flush();
        }
    }

    @Override
    public void close() {
        this.running = false;
        if (!socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    public String getRegisteredPeerId() {
        return registeredPeerId;
    }
}
