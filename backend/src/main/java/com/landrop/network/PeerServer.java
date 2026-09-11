package com.landrop.network;

import com.landrop.protocol.MessageType;
import com.landrop.protocol.Packet;
import com.landrop.protocol.PacketDecoder;
import com.landrop.protocol.PacketEncoder;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * PeerServer represents the TCP server component of a peer in the LANDrop network.
 * It listens on a specified port, accepts incoming peer connections, and exchanges
 * binary packets using the LANDrop Phase 2 custom TCP protocol.
 */
public class PeerServer implements AutoCloseable {

    private final int configuredPort;
    private final Function<Packet, Packet> responseHandler;
    private final CountDownLatch readyLatch = new CountDownLatch(1);
    private final ExecutorService workerExecutor = Executors.newCachedThreadPool();
    private final PacketEncoder packetEncoder = new PacketEncoder();
    private final PacketDecoder packetDecoder = new PacketDecoder();

    private volatile ServerSocket serverSocket;
    private volatile int actualPort;
    private volatile boolean running;

    /**
     * Constructs a PeerServer on the specified port using the default Phase 2 protocol response strategy.
     *
     * @param port the TCP port to listen on (or 0 for ephemeral/system-assigned port)
     */
    public PeerServer(int port) {
        this(port, PeerServer::defaultResponse);
    }

    /**
     * Constructs a PeerServer on the specified port with a custom packet response handler.
     *
     * @param port the TCP port to listen on
     * @param responseHandler function transforming an incoming request Packet into a response Packet
     */
    public PeerServer(int port, Function<Packet, Packet> responseHandler) {
        this.configuredPort = port;
        this.responseHandler = responseHandler != null ? responseHandler : PeerServer::defaultResponse;
    }

    /**
     * Starts the server synchronously, binding the ServerSocket and listening for connections.
     * This method blocks until the server is stopped or closed.
     */
    public void start() {
        System.out.println("=================================");
        System.out.println("LANDrop - Peer Server");
        System.out.println("=================================");
        System.out.println();
        System.out.println("Starting server...");
        System.out.println("Port: " + (configuredPort == 0 ? "(assigning dynamic port...)" : configuredPort));
        System.out.println();

        try (ServerSocket server = new ServerSocket(configuredPort)) {
            this.serverSocket = server;
            this.actualPort = server.getLocalPort();
            this.running = true;
            readyLatch.countDown();

            System.out.println("Server started successfully.");
            System.out.println("Waiting for peer connection...");
            System.out.println();

            while (running && !server.isClosed()) {
                try {
                    Socket clientSocket = server.accept();
                    workerExecutor.submit(() -> handleClient(clientSocket));
                } catch (SocketException e) {
                    if (!running || server.isClosed()) {
                        break;
                    }
                    System.err.println("Server socket exception: " + e.getMessage());
                }
            }
        } catch (BindException e) {
            System.err.println("Unable to start server.");
            System.err.println("Port " + configuredPort + " is already in use.");
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        } finally {
            this.running = false;
            readyLatch.countDown();
            workerExecutor.shutdown();
        }
    }

    /**
     * Starts the server asynchronously on a dedicated background thread.
     */
    public void startAsync() {
        Thread serverThread = new Thread(this::start, "peer-server-listener");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    /**
     * Waits until the server socket has successfully bound and is ready to accept connections.
     *
     * @param timeout maximum time to wait
     * @param unit the time unit
     * @return true if the server became ready before the timeout, false otherwise
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean awaitReady(long timeout, TimeUnit unit) throws InterruptedException {
        return readyLatch.await(timeout, unit);
    }

    /**
     * Handles an incoming connection from a peer using the custom binary protocol.
     *
     * @param clientSocket the accepted client socket
     */
    private void handleClient(Socket clientSocket) {
        try (clientSocket) {
            System.out.println("Peer connected!");
            System.out.println();

            DataInputStream in = new DataInputStream(new BufferedInputStream(clientSocket.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(clientSocket.getOutputStream()));

            Packet request;
            while (running && (request = packetDecoder.decode(in)) != null) {
                logReceivedPacket(request);

                Packet response = responseHandler.apply(request);
                if (response != null) {
                    logSendingPacket(response);
                    packetEncoder.encode(response, out);
                }
            }

            System.out.println("Peer disconnected.");
            System.out.println();
        } catch (IOException e) {
            if (running) {
                System.err.println("Peer connection error: " + e.getMessage());
            }
        }
    }

    private void logReceivedPacket(Packet packet) {
        System.out.println("Received packet:");
        System.out.println("  Version: " + packet.getVersion());
        System.out.println("  Type: " + packet.getType());
        System.out.println("  Request ID: " + packet.getRequestId());
        System.out.println("  Payload Length: " + packet.getPayloadLength());
        System.out.println("  Payload: " + (packet.getPayloadLength() == 0 ? "(empty)" : packet.payloadAsString()));
        System.out.println();
    }

    private void logSendingPacket(Packet packet) {
        System.out.println("Sending packet:");
        System.out.println("  Type: " + packet.getType());
        System.out.println("  Request ID: " + packet.getRequestId());
        System.out.println("  Payload: " + (packet.getPayloadLength() == 0 ? "(empty)" : packet.payloadAsString()));
        System.out.println();
    }

    /**
     * Stops the server and releases all networking and thread resources.
     */
    public void stop() {
        this.running = false;
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        workerExecutor.shutdownNow();
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * Returns the port the server is currently listening on.
     *
     * @return the bound port, or the configured port if not yet bound
     */
    public int getPort() {
        try {
            readyLatch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return actualPort > 0 ? actualPort : configuredPort;
    }

    /**
     * Checks if the server is currently running.
     *
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Default response handler implementing Phase 2 protocol specifications:
     * <ul>
     *   <li>HELLO -> HELLO response (payload: "LANDrop Peer B")</li>
     *   <li>CHAT_MESSAGE -> CHAT_MESSAGE response (payload: "Hello from Peer B")</li>
     *   <li>PING -> PONG response (payload: empty)</li>
     * </ul>
     *
     * @param request the incoming request Packet
     * @return the corresponding response Packet
     */
    public static Packet defaultResponse(Packet request) {
        long reqId = request.getRequestId();
        return switch (request.getType()) {
            case HELLO -> Packet.text(MessageType.HELLO, reqId, "LANDrop Peer B");
            case CHAT_MESSAGE -> Packet.text(MessageType.CHAT_MESSAGE, reqId, "Hello from Peer B");
            case PING -> Packet.empty(MessageType.PONG, reqId);
            default -> Packet.text(request.getType(), reqId, "ACK");
        };
    }
}
