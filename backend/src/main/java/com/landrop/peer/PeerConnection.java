package com.landrop.peer;

import com.landrop.protocol.MessageType;
import com.landrop.protocol.Packet;
import com.landrop.protocol.PacketDecoder;
import com.landrop.protocol.PacketEncoder;
import com.landrop.protocol.PacketHandler;
import com.landrop.protocol.ProtocolException;
import com.landrop.protocol.RequestIdGenerator;
import com.landrop.security.P2pCryptoSession;
import com.landrop.util.AppLogger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Transport and protocol connection layer for a LANDrop peer.
 * <p>
 * Manages the TCP socket, asynchronous background packet decoding,
 * thread-safe serialized writes, handshake progression, and PING/PONG latency tracking.
 */
public class PeerConnection implements AutoCloseable {

    private static final AtomicLong ID_GENERATOR = new AtomicLong(1);

    private final long connectionId = ID_GENERATOR.getAndIncrement();
    private final PacketEncoder packetEncoder;
    private final PacketDecoder packetDecoder;
    private final RequestIdGenerator requestIdGenerator;

    private final AtomicReference<ConnectionState> state = new AtomicReference<>(ConnectionState.CONNECTING);
    private final List<ConnectionListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<MessageType, PacketHandler> customHandlers = new ConcurrentHashMap<>();
    private final Map<Long, PingTracker> pendingPings = new ConcurrentHashMap<>();

    private final Object writeLock = new Object();
    private final CountDownLatch readyLatch = new CountDownLatch(1);
    private final AtomicBoolean helloSent = new AtomicBoolean(false);

    // Cryptographic E2E Encryption Subsystem
    private final P2pCryptoSession cryptoSession = new P2pCryptoSession();
    private final AtomicBoolean keyExchangeSent = new AtomicBoolean(false);
    private final CountDownLatch secureLatch = new CountDownLatch(1);
    private volatile boolean enforceEncryption = com.landrop.config.AppConfig.isE2eEncryptionEnforced();

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private Thread readerThread;

    private volatile String localPeerName = "Peer";
    private volatile String remotePeerName = "Unknown";
    private volatile ConnectionPurpose purpose = ConnectionPurpose.CHAT;
    private volatile ConnectionPurpose remotePurpose = ConnectionPurpose.CHAT;
    private volatile boolean running = false;

    private record PingTracker(long startNano, CompletableFuture<Double> future) {}

    /**
     * Constructs a PeerConnection with default encoder, decoder, and request ID generator.
     */
    public PeerConnection() {
        this(new PacketEncoder(), new PacketDecoder(), new RequestIdGenerator());
    }

    /**
     * Constructs a PeerConnection wrapping an already established Socket.
     *
     * @param socket the connected TCP socket
     * @throws IOException if obtaining socket input or output streams fails
     */
    public PeerConnection(Socket socket) throws IOException {
        this(new PacketEncoder(), new PacketDecoder(), new RequestIdGenerator());
        initSocket(socket);
    }

    /**
     * Constructs a PeerConnection with custom encoder, decoder, and request ID generator.
     *
     * @param packetEncoder the packet encoder
     * @param packetDecoder the packet decoder
     * @param requestIdGenerator the request ID generator
     */
    public PeerConnection(PacketEncoder packetEncoder, PacketDecoder packetDecoder, RequestIdGenerator requestIdGenerator) {
        this.packetEncoder = Objects.requireNonNull(packetEncoder, "packetEncoder cannot be null");
        this.packetDecoder = Objects.requireNonNull(packetDecoder, "packetDecoder cannot be null");
        this.requestIdGenerator = Objects.requireNonNull(requestIdGenerator, "requestIdGenerator cannot be null");
    }

    /**
     * Connects to a remote peer at the given host and port.
     *
     * @param host remote host address or name
     * @param port remote TCP port
     * @param timeoutMs connection timeout in milliseconds
     * @throws IOException if connection fails
     */
    public void connect(String host, int port, int timeoutMs) throws IOException {
        transitionState(ConnectionState.CONNECTING);
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            initSocket(s);
        } catch (IOException e) {
            transitionState(ConnectionState.ERROR);
            throw e;
        }
    }

    /**
     * Factory method creating and connecting a PeerConnection to the target host and port.
     *
     * @param host remote host address or name
     * @param port remote TCP port
     * @return an established, connected PeerConnection
     * @throws IOException if connection fails
     */
    public static PeerConnection connectTo(String host, int port) throws IOException {
        return connectTo(host, port, com.landrop.config.AppConfig.getConnectTimeoutMs());
    }

    /**
     * Factory method creating and connecting a PeerConnection with a custom timeout.
     *
     * @param host remote host address or name
     * @param port remote TCP port
     * @param timeoutMs connection timeout in milliseconds
     * @return an established, connected PeerConnection
     * @throws IOException if connection fails
     */
    public static PeerConnection connectTo(String host, int port, int timeoutMs) throws IOException {
        PeerConnection connection = new PeerConnection();
        connection.connect(host, port, timeoutMs);
        return connection;
    }

    private void initSocket(Socket s) throws IOException {
        this.socket = s;
        this.in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
        transitionState(ConnectionState.CONNECTED);
    }

    /**
     * Starts the dedicated asynchronous reader thread.
     */
    public synchronized void start() {
        if (running) {
            return;
        }
        if (socket == null || !socket.isConnected() || socket.isClosed()) {
            throw new IllegalStateException("Cannot start reader: Socket is not connected");
        }
        this.running = true;
        this.readerThread = Thread.ofVirtual()
                .name("peer-reader-" + connectionId)
                .start(this::runReaderLoop);
    }

    /**
     * The continuous asynchronous reader loop. Decodes packets from the network stream
     * and dispatches them to appropriate handlers.
     */
    private void runReaderLoop() {
        try {
            while (running && isConnected()) {
                Packet packet;
                try {
                    packet = packetDecoder.decode(in);
                } catch (EOFException e) {
                    // Remote peer closed stream cleanly or abruptly
                    break;
                } catch (SocketException e) {
                    // Socket closed locally or connection reset by peer
                    break;
                }

                if (packet == null) {
                    // Clean stream EOF
                    break;
                }

                dispatch(packet);
            }
        } catch (IOException e) {
            if (running) {
                notifyError(e);
            }
        } catch (Exception e) {
            if (running) {
                notifyError(e);
            }
        } finally {
            close();
        }
    }

    /**
     * Dispatches an incoming packet according to its MessageType.
     *
     * @param packet the received packet
     */
    private void dispatch(Packet packet) {
        notifyPacketReceived(packet);

        try {
            // Transparently decrypt encrypted payloads
            if (packet.getType() == MessageType.ENCRYPTED_PAYLOAD) {
                try {
                    byte[] decryptedBytes = cryptoSession.decrypt(packet.getPayload());
                    Packet innerPacket = packetDecoder.decodeFromBytes(decryptedBytes);
                    dispatch(innerPacket);
                    return;
                } catch (GeneralSecurityException e) {
                    notifyError(new ProtocolException("Failed to decrypt incoming ENCRYPTED_PAYLOAD: " + e.getMessage(), e));
                    return;
                }
            }

            switch (packet.getType()) {
                case HELLO -> handleIncomingHello(packet);
                case KEY_EXCHANGE -> handleIncomingKeyExchange(packet);
                case KEY_EXCHANGE_ACK -> handleIncomingKeyExchangeAck(packet);
                case PING -> handleIncomingPing(packet);
                case PONG -> handleIncomingPong(packet);
                default -> {
                    // Handled by custom registered handler if present
                }
            }

            PacketHandler customHandler = customHandlers.get(packet.getType());
            if (customHandler != null) {
                customHandler.handle(packet);
            }
        } catch (Exception e) {
            notifyError(e);
        }
    }

    /**
     * Handles incoming HELLO packet.
     */
    private void handleIncomingHello(Packet packet) {
        String raw = packet.payloadAsString();
        ConnectionPurpose detectedPurpose = ConnectionPurpose.CHAT;
        String peerName = raw;
        if (raw != null && raw.startsWith("FILE_TRANSFER|")) {
            detectedPurpose = ConnectionPurpose.FILE_TRANSFER;
            peerName = raw.substring("FILE_TRANSFER|".length());
        } else if (raw != null && raw.startsWith("PURPOSE:FILE_TRANSFER|")) {
            detectedPurpose = ConnectionPurpose.FILE_TRANSFER;
            peerName = raw.substring("PURPOSE:FILE_TRANSFER|".length());
        }
        this.remotePurpose = detectedPurpose;
        String receivedPeerName = extractPeerName(peerName);
        this.remotePeerName = receivedPeerName;

        // If we have not sent our HELLO response yet, send it back with matching requestId
        if (helloSent.compareAndSet(false, true)) {
            try {
                String respPayload = (this.purpose == ConnectionPurpose.FILE_TRANSFER ? "FILE_TRANSFER|" : "") + localPeerName;
                Packet helloResp = Packet.text(MessageType.HELLO, packet.getRequestId(), respPayload);
                send(helloResp);
            } catch (IOException e) {
                notifyError(e);
            }
        }

        transitionState(ConnectionState.READY);
        readyLatch.countDown();
        com.landrop.util.AppLogger.debug("P2P", "Handshake completed: remotePeerName=" + receivedPeerName + " purpose=" + detectedPurpose);

        // Initiate cryptographic key exchange immediately upon READY
        initiateKeyExchange();
    }

    /**
     * Initiates cryptographic key exchange with the remote peer using ephemeral ECDH.
     */
    public void initiateKeyExchange() {
        if (keyExchangeSent.compareAndSet(false, true)) {
            try {
                long reqId = requestIdGenerator.nextId();
                Packet keyPacket = Packet.of(MessageType.KEY_EXCHANGE, reqId, cryptoSession.getLocalPublicKeyBytes());
                sendRaw(keyPacket);
            } catch (IOException e) {
                notifyError(e);
            }
        }
    }

    private void handleIncomingKeyExchange(Packet packet) {
        try {
            byte[] remotePublicKeyBytes = packet.getPayload();
            cryptoSession.completeKeyAgreement(remotePublicKeyBytes);

            // Respond with KEY_EXCHANGE_ACK containing our public key
            Packet ack = Packet.of(MessageType.KEY_EXCHANGE_ACK, packet.getRequestId(), cryptoSession.getLocalPublicKeyBytes());
            sendRaw(ack);

            secureLatch.countDown();
            notifyEncryptionEstablished(cryptoSession.getSecurityFingerprint());
            AppLogger.info("P2P", "E2E encryption established with " + remotePeerName + "! Fingerprint: " + cryptoSession.getSecurityFingerprint());
        } catch (Exception e) {
            notifyError(e);
        }
    }

    private void handleIncomingKeyExchangeAck(Packet packet) {
        try {
            byte[] remotePublicKeyBytes = packet.getPayload();
            cryptoSession.completeKeyAgreement(remotePublicKeyBytes);

            secureLatch.countDown();
            notifyEncryptionEstablished(cryptoSession.getSecurityFingerprint());
            AppLogger.info("P2P", "E2E encryption confirmed via ACK with " + remotePeerName + "! Fingerprint: " + cryptoSession.getSecurityFingerprint());
        } catch (Exception e) {
            notifyError(e);
        }
    }

    /**
     * Handles incoming PING packet by immediately returning a PONG response with the same request ID.
     */
    private void handleIncomingPing(Packet packet) {
        try {
            Packet pong = Packet.empty(MessageType.PONG, packet.getRequestId());
            send(pong);
        } catch (IOException e) {
            notifyError(e);
        }
    }

    /**
     * Handles incoming PONG packet by resolving the corresponding pending ping future and calculating latency.
     */
    private void handleIncomingPong(Packet packet) {
        PingTracker tracker = pendingPings.remove(packet.getRequestId());
        if (tracker != null) {
            long elapsedNano = System.nanoTime() - tracker.startNano();
            double latencyMs = elapsedNano / 1_000_000.0;
            tracker.future().complete(latencyMs);
        }
    }

    /**
     * Sends a packet over the TCP connection in a thread-safe, serialized manner.
     * Automatically encrypts non-handshake packets using AES-256-GCM when key agreement is established.
     *
     * @param packet the packet to encode and transmit
     * @throws IOException if connection is closed or an I/O error occurs
     */
    public void send(Packet packet) throws IOException {
        Objects.requireNonNull(packet, "Packet cannot be null");
        if (!isConnected()) {
            throw new IOException("Cannot send packet: Connection is closed (state: " + state.get() + ")");
        }

        // Control and crypto handshake packets are transmitted as raw framing
        if (packet.getType() == MessageType.HELLO ||
            packet.getType() == MessageType.KEY_EXCHANGE ||
            packet.getType() == MessageType.KEY_EXCHANGE_ACK) {
            sendRaw(packet);
            return;
        }

        // If encryption session is established, automatically wrap inside ENCRYPTED_PAYLOAD
        if (cryptoSession.isEstablished()) {
            try {
                byte[] innerBytes = packetEncoder.encodeToBytes(packet);
                byte[] ciphertext = cryptoSession.encrypt(innerBytes);
                Packet encPacket = Packet.of(MessageType.ENCRYPTED_PAYLOAD, packet.getRequestId(), ciphertext);
                sendRaw(encPacket);
                return;
            } catch (GeneralSecurityException e) {
                throw new IOException("Failed to encrypt packet of type " + packet.getType(), e);
            }
        }

        if (enforceEncryption) {
            throw new IOException("Cannot send plaintext packet: E2E encryption is strictly enforced.");
        }

        sendRaw(packet);
    }

    /**
     * Sends a raw unencrypted packet directly over the socket stream.
     */
    public void sendRaw(Packet packet) throws IOException {
        synchronized (writeLock) {
            packetEncoder.encode(packet, out);
            out.flush();
        }

        notifyPacketSent(packet);
    }

    /**
     * Initiates the protocol HELLO handshake with the specified local peer name using the configured purpose.
     *
     * @param localName the name identifying this peer
     * @throws IOException if network transmission fails
     */
    public void initiateHandshake(String localName) throws IOException {
        initiateHandshake(localName, this.purpose);
    }

    /**
     * Initiates the protocol HELLO handshake with the specified local peer name and explicit connection purpose.
     *
     * @param localName the name identifying this peer
     * @param purpose connection purpose (CHAT or FILE_TRANSFER)
     * @throws IOException if network transmission fails
     */
    public void initiateHandshake(String localName, ConnectionPurpose purpose) throws IOException {
        this.purpose = purpose != null ? purpose : ConnectionPurpose.CHAT;
        this.localPeerName = localName != null && !localName.isBlank() ? localName : "Peer";
        if (helloSent.compareAndSet(false, true)) {
            long reqId = requestIdGenerator.nextId();
            String payload = (this.purpose == ConnectionPurpose.FILE_TRANSFER ? "FILE_TRANSFER|" : "") + this.localPeerName;
            Packet helloPacket = Packet.text(MessageType.HELLO, reqId, payload);
            send(helloPacket);
        }
    }

    /**
     * Sends an asynchronous PING packet and returns a CompletableFuture providing the round-trip latency in milliseconds.
     *
     * @return CompletableFuture completing with round-trip latency in milliseconds
     * @throws IOException if transmission fails
     */
    public CompletableFuture<Double> ping() throws IOException {
        long reqId = requestIdGenerator.nextId();
        long startNano = System.nanoTime();
        CompletableFuture<Double> future = new CompletableFuture<>();
        pendingPings.put(reqId, new PingTracker(startNano, future));

        Packet pingPacket = Packet.empty(MessageType.PING, reqId);
        try {
            send(pingPacket);
        } catch (IOException e) {
            pendingPings.remove(reqId);
            throw e;
        }

        return future;
    }

    /**
     * Blocks until the connection transitions to READY or the timeout expires.
     *
     * @param timeout maximum time to wait
     * @param unit time unit
     * @return true if READY state was reached, false otherwise
     * @throws InterruptedException if interrupted while awaiting
     */
    public boolean awaitReady(long timeout, TimeUnit unit) throws InterruptedException {
        return readyLatch.await(timeout, unit);
    }

    /**
     * Registers a custom packet handler for a given message type.
     *
     * @param type the MessageType to handle
     * @param handler the PacketHandler implementation
     */
    public void registerHandler(MessageType type, PacketHandler handler) {
        if (handler != null) {
            customHandlers.put(type, handler);
        } else {
            customHandlers.remove(type);
        }
    }

    /**
     * Adds a connection lifecycle listener.
     *
     * @param listener the listener to attach
     */
    public void addListener(ConnectionListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * Removes a connection lifecycle listener.
     *
     * @param listener the listener to detach
     */
    public void removeListener(ConnectionListener listener) {
        listeners.remove(listener);
    }

    /**
     * Checks if the underlying socket is open and actively connected.
     *
     * @return true if connected, false otherwise
     */
    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    public ConnectionState getState() {
        return state.get();
    }

    public String getLocalPeerName() {
        return localPeerName;
    }

    public void setLocalPeerName(String localPeerName) {
        this.localPeerName = localPeerName != null && !localPeerName.isBlank() ? localPeerName : "Peer";
    }

    public String getRemotePeerName() {
        return remotePeerName;
    }

    public ConnectionPurpose getPurpose() {
        return purpose;
    }

    public void setPurpose(ConnectionPurpose purpose) {
        this.purpose = purpose != null ? purpose : ConnectionPurpose.CHAT;
    }

    public ConnectionPurpose getRemotePurpose() {
        return remotePurpose;
    }

    public void setRemotePurpose(ConnectionPurpose remotePurpose) {
        this.remotePurpose = remotePurpose != null ? remotePurpose : ConnectionPurpose.CHAT;
    }

    public RequestIdGenerator getRequestIdGenerator() {
        return requestIdGenerator;
    }

    public long getConnectionId() {
        return connectionId;
    }

    public Socket getSocket() {
        return socket;
    }

    private void transitionState(ConnectionState newState) {
        ConnectionState oldState = state.getAndSet(newState);
        if (oldState != newState) {
            for (ConnectionListener listener : listeners) {
                listener.onStateChanged(oldState, newState);
            }
        }
    }

    private void notifyPacketReceived(Packet packet) {
        for (ConnectionListener listener : listeners) {
            listener.onPacketReceived(packet);
        }
    }

    private void notifyPacketSent(Packet packet) {
        for (ConnectionListener listener : listeners) {
            listener.onPacketSent(packet);
        }
    }

    private void notifyError(Exception e) {
        for (ConnectionListener listener : listeners) {
            listener.onError(e);
        }
    }

    private void notifyDisconnected() {
        for (ConnectionListener listener : listeners) {
            listener.onDisconnected();
        }
    }

    private void notifyEncryptionEstablished(String fingerprint) {
        for (ConnectionListener listener : listeners) {
            try {
                listener.onEncryptionEstablished(fingerprint);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Checks if this peer connection has successfully established an authenticated E2E encrypted session.
     */
    public boolean isEncrypted() {
        return cryptoSession.isEstablished();
    }

    /**
     * Gets the 6-digit verification security fingerprint for user cross-verification.
     */
    public String getSecurityFingerprint() {
        return cryptoSession.getSecurityFingerprint();
    }

    /**
     * Checks whether plaintext transmissions are strictly forbidden on this connection.
     */
    public boolean isEncryptionEnforced() {
        return enforceEncryption;
    }

    /**
     * Sets whether plaintext transmissions are strictly forbidden on this connection.
     */
    public void setEncryptionEnforced(boolean enforce) {
        this.enforceEncryption = enforce;
    }

    /**
     * Awaits until the cryptographic E2E key exchange completes and the connection is secure.
     *
     * @param timeout duration to wait
     * @param unit time unit
     * @return true if secured within timeout, false otherwise
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean awaitSecure(long timeout, TimeUnit unit) throws InterruptedException {
        return secureLatch.await(timeout, unit);
    }

    @Override
    public void close() {
        this.running = false;
        readyLatch.countDown();
        secureLatch.countDown();

        // Fail any pending ping futures
        for (PingTracker tracker : pendingPings.values()) {
            tracker.future().completeExceptionally(new IOException("Connection closed before response received"));
        }
        pendingPings.clear();

        ConnectionState previous = state.getAndSet(ConnectionState.DISCONNECTED);

        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }

        if (previous != ConnectionState.DISCONNECTED) {
            com.landrop.util.AppLogger.debug("P2P", "Peer connection disconnected: " + remotePeerName + " (id=" + connectionId + ")");
            notifyDisconnected();
        }
    }

    private static String extractPeerName(String payload) {
        if (payload == null || payload.isBlank()) {
            return "Peer";
        }
        String trimmed = payload.trim();
        if (trimmed.startsWith("Hello from ")) {
            return trimmed.substring("Hello from ".length()).trim();
        }
        return trimmed;
    }
}
