package com.landrop.network;

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
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * PeerClient represents the TCP client component of a peer in the LANDrop network.
 * It connects to a target peer, encodes and sends binary Packets, and decodes responses.
 */
public class PeerClient {

    private final String host;
    private final int port;
    private final RequestIdGenerator requestIdGenerator;
    private final PacketEncoder packetEncoder = new PacketEncoder();
    private final PacketDecoder packetDecoder = new PacketDecoder();

    private volatile Packet lastReceivedPacket;

    /**
     * Constructs a PeerClient with the specified target peer host and port.
     *
     * @param host hostname or IP address of the target peer
     * @param port port of the target peer
     */
    public PeerClient(String host, int port) {
        this(host, port, new RequestIdGenerator(1L));
    }

    /**
     * Constructs a PeerClient with a custom RequestIdGenerator.
     *
     * @param host hostname or IP address of the target peer
     * @param port port of the target peer
     * @param requestIdGenerator custom request ID generator
     */
    public PeerClient(String host, int port, RequestIdGenerator requestIdGenerator) {
        this.host = host;
        this.port = port;
        this.requestIdGenerator = requestIdGenerator != null ? requestIdGenerator : new RequestIdGenerator(1L);
    }

    /**
     * Connects to the peer server and executes the Phase 2 demonstration flow:
     * <ol>
     *   <li>Sends HELLO (Request ID 1) -> awaits HELLO response</li>
     *   <li>Sends CHAT_MESSAGE (Request ID 2) -> awaits CHAT_MESSAGE response</li>
     *   <li>Sends PING (Request ID 3) -> awaits PONG response</li>
     * </ol>
     *
     * @return the list of response packets received from the server
     * @throws IOException if network or protocol error occurs
     */
    public List<Packet> executePhase2Flow() throws IOException {
        return executePhase2Flow("Hello from Peer A");
    }

    /**
     * Connects to the peer server and executes the Phase 2 demonstration flow with a custom chat message.
     *
     * @param chatMessage the chat message to send in the CHAT_MESSAGE packet
     * @return the list of response packets received from the server
     * @throws IOException if network or protocol error occurs
     */
    public List<Packet> executePhase2Flow(String chatMessage) throws IOException {
        System.out.println("=================================");
        System.out.println("LANDrop - Peer Client");
        System.out.println("=================================");
        System.out.println();
        System.out.println("Connecting to " + host + ":" + port + "...");
        System.out.println();

        List<Packet> responses = new ArrayList<>();

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(5000);

            System.out.println("Connected to Peer B");
            System.out.println();

            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            // Step 1: HELLO
            Packet helloRequest = Packet.text(MessageType.HELLO, requestIdGenerator.nextId(), "Hello from Peer A");
            logSendingPacket(helloRequest);
            packetEncoder.encode(helloRequest, out);

            Packet helloResponse = packetDecoder.decode(in);
            if (helloResponse != null) {
                logReceivedPacket(helloResponse);
                this.lastReceivedPacket = helloResponse;
                responses.add(helloResponse);
            }

            // Step 2: CHAT_MESSAGE
            String messageToSend = (chatMessage != null && !chatMessage.isBlank()) ? chatMessage : "Hello from Peer A";
            Packet chatRequest = Packet.text(MessageType.CHAT_MESSAGE, requestIdGenerator.nextId(), messageToSend);
            logSendingPacket(chatRequest);
            packetEncoder.encode(chatRequest, out);

            Packet chatResponse = packetDecoder.decode(in);
            if (chatResponse != null) {
                logReceivedPacket(chatResponse);
                this.lastReceivedPacket = chatResponse;
                responses.add(chatResponse);
            }

            // Step 3: PING
            Packet pingRequest = Packet.empty(MessageType.PING, requestIdGenerator.nextId());
            logSendingPacket(pingRequest);
            packetEncoder.encode(pingRequest, out);

            Packet pongResponse = packetDecoder.decode(in);
            if (pongResponse != null) {
                logReceivedPacket(pongResponse);
                this.lastReceivedPacket = pongResponse;
                responses.add(pongResponse);
            }

            return responses;
        } catch (ConnectException e) {
            System.err.println("Unable to connect to " + host + ":" + port + ".");
            System.err.println("Make sure the peer server is running.");
            throw e;
        }
    }

    /**
     * Sends a single packet to the server on a dedicated connection and awaits the response packet.
     *
     * @param request the Packet to transmit
     * @return the response Packet received, or null if connection was terminated
     * @throws IOException if network or protocol error occurs
     */
    public Packet sendPacket(Packet request) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(5000);

            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            packetEncoder.encode(request, out);
            Packet response = packetDecoder.decode(in);
            this.lastReceivedPacket = response;
            return response;
        } catch (ConnectException e) {
            System.err.println("Unable to connect to " + host + ":" + port + ".");
            System.err.println("Make sure the peer server is running.");
            throw e;
        }
    }

    private void logSendingPacket(Packet packet) {
        System.out.println("Sending packet:");
        System.out.println("  Type: " + packet.getType());
        System.out.println("  Request ID: " + packet.getRequestId());
        System.out.println("  Payload: " + (packet.getPayloadLength() == 0 ? "(empty)" : packet.payloadAsString()));
        System.out.println();
    }

    private void logReceivedPacket(Packet packet) {
        System.out.println("Received packet:");
        System.out.println("  Type: " + packet.getType());
        System.out.println("  Request ID: " + packet.getRequestId());
        System.out.println("  Payload: " + (packet.getPayloadLength() == 0 ? "(empty)" : packet.payloadAsString()));
        System.out.println();
    }

    public Packet getLastReceivedPacket() {
        return lastReceivedPacket;
    }

    public String getLastResponse() {
        return lastReceivedPacket != null ? lastReceivedPacket.payloadAsString() : null;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }
}
