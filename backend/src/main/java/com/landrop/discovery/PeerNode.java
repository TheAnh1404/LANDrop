package com.landrop.discovery;

import com.landrop.chat.ChatListener;
import com.landrop.chat.ChatMessage;
import com.landrop.chat.ChatService;
import com.landrop.peer.ConnectionState;
import com.landrop.peer.PeerConnection;
import com.landrop.peer.PeerIdentity;
import com.landrop.peer.PeerListener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates a peer's local P2P server, centralized discovery registration,
 * and interactive direct peer-to-peer chat sessions.
 */
public class PeerNode implements AutoCloseable {

    private final PeerIdentity identity;
    private final int localP2pPort;
    private final String discoveryHost;
    private final int discoveryPort;
    private final String advertisedHost;

    private final BufferedReader reader;
    private final PrintStream out;

    private PeerListener peerListener;
    private DiscoveryClient discoveryClient;
    private final List<PeerInfo> cachedPeers = new CopyOnWriteArrayList<>();

    private volatile PeerConnection activeConnection;
    private volatile ChatService activeChatService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Constructs a PeerNode with standard I/O (System.in and System.out).
     *
     * @param localP2pPort the local TCP port for P2P direct connections
     * @param discoveryHost discovery server host
     * @param discoveryPort discovery server port
     * @param displayName user-friendly peer display name
     */
    public PeerNode(int localP2pPort, String discoveryHost, int discoveryPort, String displayName) {
        this(localP2pPort, discoveryHost, discoveryPort, displayName, "127.0.0.1", System.in, System.out);
    }

    /**
     * Constructs a PeerNode with custom advertised host and custom streams.
     *
     * @param localP2pPort local TCP port
     * @param discoveryHost discovery server host
     * @param discoveryPort discovery server port
     * @param displayName user-friendly peer display name
     * @param advertisedHost IP or host address advertised to other peers
     * @param in input stream for reading user commands
     * @param out output stream for printing
     */
    public PeerNode(int localP2pPort, String discoveryHost, int discoveryPort, String displayName,
                    String advertisedHost, InputStream in, PrintStream out) {
        this.identity = new PeerIdentity(displayName);
        this.localP2pPort = localP2pPort;
        this.discoveryHost = Objects.requireNonNull(discoveryHost, "discoveryHost cannot be null");
        this.discoveryPort = discoveryPort;
        this.advertisedHost = (advertisedHost != null && !advertisedHost.isBlank()) ? advertisedHost : "127.0.0.1";
        this.reader = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(in, "InputStream cannot be null"), StandardCharsets.UTF_8));
        this.out = Objects.requireNonNull(out, "PrintStream cannot be null");
    }

    /**
     * Starts the peer node:
     * 1. Binds local P2P server socket and starts acceptor loop
     * 2. Connects to discovery server and registers
     * 3. Fetches initial peer list
     * 4. Starts periodic heartbeat
     * 5. Runs the interactive CLI loop
     */
    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        printBanner();

        // 1. Start local P2P listener
        this.peerListener = new PeerListener(localP2pPort);
        startP2pAcceptorLoop();

        // 2. Connect and register with Discovery Server
        synchronized (out) {
            out.println("Connecting to Discovery Server (" + discoveryHost + ":" + discoveryPort + ")...");
        }

        this.discoveryClient = new DiscoveryClient(discoveryHost, discoveryPort);
        try {
            discoveryClient.connect(5000);
            synchronized (out) {
                out.println("Connected.");
                out.println("Registering peer...");
            }

            PeerInfo myInfo = new PeerInfo(identity.getPeerId(), identity.getDisplayName(), advertisedHost, peerListener.getPort());
            boolean registered = discoveryClient.register(myInfo);
            if (!registered) {
                synchronized (out) {
                    out.println("[Discovery Error] Failed to register with discovery server.");
                }
            } else {
                discoveryClient.startHeartbeat(DiscoveryClient.DEFAULT_HEARTBEAT_INTERVAL_SEC);
            }
        } catch (IOException e) {
            synchronized (out) {
                out.println("[Discovery Warning] Could not connect to Discovery Server: " + e.getMessage());
            }
        }

        // 3. Request initial peer list
        refreshPeerList();

        printAvailableCommands();

        // 4. Run CLI input loop
        runCliLoop();
    }

    private void printBanner() {
        synchronized (out) {
            out.println("=================================");
            out.println("LANDrop - Peer");
            out.println("=================================");
            out.println();
            out.println("Peer ID:");
            out.println(identity.getPeerId());
            out.println();
            out.println("Display Name:");
            out.println(identity.getDisplayName());
            out.println();
            out.println("P2P Port:");
            out.println(localP2pPort);
            out.println();
        }
    }

    private void printAvailableCommands() {
        synchronized (out) {
            out.println("Commands:");
            out.println("  /peers              - Refresh and display online peers");
            out.println("  /connect <id|index> - Direct P2P connect to peer");
            out.println("  /ping               - Test latency with connected peer");
            out.println("  /help               - Show commands");
            out.println("  /quit               - Disconnect and exit");
            out.println();
        }
    }

    private void startP2pAcceptorLoop() {
        Thread.ofVirtual().name("p2p-acceptor-" + localP2pPort).start(() -> {
            while (running.get() && !peerListener.isClosed()) {
                try {
                    PeerConnection incoming = peerListener.acceptConnection();
                    incoming.setLocalPeerName(identity.getDisplayName());
                    incoming.start();

                    // Await incoming HELLO
                    boolean ready = incoming.awaitReady(10, TimeUnit.SECONDS);
                    if (ready) {
                        setActiveChatConnection(incoming, true);
                    }
                } catch (Exception e) {
                    if (running.get() && !peerListener.isClosed()) {
                        synchronized (out) {
                            out.println("[P2P Warning] Error accepting incoming connection: " + e.getMessage());
                        }
                    }
                }
            }
        });
    }

    private void runCliLoop() {
        try {
            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                if (line.startsWith("/")) {
                    handleCommand(line);
                    if (!running.get()) {
                        break;
                    }
                } else {
                    handleChatMessage(line);
                }
            }
        } catch (IOException e) {
            if (running.get()) {
                synchronized (out) {
                    out.println("Input error: " + e.getMessage());
                }
            }
        } finally {
            close();
        }
    }

    private void handleCommand(String commandLine) {
        String[] parts = commandLine.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";

        switch (cmd) {
            case "/peers" -> refreshPeerList();
            case "/connect" -> connectToPeer(arg);
            case "/ping" -> executePing();
            case "/help" -> printAvailableCommands();
            case "/quit" -> {
                synchronized (out) {
                    out.println("Exiting...");
                }
                close();
            }
            default -> {
                synchronized (out) {
                    out.println("Unknown command: " + cmd + ". Type /help for options.");
                    out.println();
                }
            }
        }
    }

    public synchronized void refreshPeerList() {
        if (discoveryClient == null || !discoveryClient.isConnected()) {
            synchronized (out) {
                out.println("[Discovery] Not connected to Discovery Server.");
                out.println();
            }
            return;
        }

        try {
            List<PeerInfo> peers = discoveryClient.requestPeerList();
            cachedPeers.clear();
            cachedPeers.addAll(peers);

            synchronized (out) {
                out.println();
                out.println("Online peers:");
                if (peers.isEmpty()) {
                    out.println("  (No other peers currently online)");
                } else {
                    for (int i = 0; i < peers.size(); i++) {
                        PeerInfo p = peers.get(i);
                        out.printf("  [%d] %s%n", (i + 1), p.getDisplayName());
                        out.printf("      ID: %s%n", p.getPeerId());
                        out.printf("      Address: %s:%d - %s%n%n", p.getHost(), p.getPort(), p.getStatus());
                    }
                }
                out.println();
            }
        } catch (IOException e) {
            synchronized (out) {
                out.println("[Discovery Error] Failed to fetch peers: " + e.getMessage());
                out.println();
            }
        }
    }

    public void connectToPeer(String targetIdentifier) {
        if (targetIdentifier == null || targetIdentifier.isBlank()) {
            synchronized (out) {
                out.println("Usage: /connect <peerId or index>");
                out.println();
            }
            return;
        }

        PeerInfo target = findPeer(targetIdentifier);
        if (target == null) {
            synchronized (out) {
                out.println("Peer '" + targetIdentifier + "' not found. Use /peers to see online peers.");
                out.println();
            }
            return;
        }

        synchronized (out) {
            out.println("[P2P] Connecting directly to " + target.getDisplayName() + " (" + target.getHost() + ":" + target.getPort() + ")...");
        }

        try {
            PeerConnection conn = PeerConnection.connectTo(target.getHost(), target.getPort(), 5000);
            conn.setLocalPeerName(identity.getDisplayName());
            conn.start();

            conn.initiateHandshake(identity.getDisplayName());
            boolean ready = conn.awaitReady(10, TimeUnit.SECONDS);

            if (ready) {
                synchronized (out) {
                    out.println("[P2P] Connected to " + target.getDisplayName() + ".");
                    out.println("[P2P] HELLO successful.");
                    out.println("[P2P] Chat connection established");
                    out.println();
                }
                setActiveChatConnection(conn, false);
            } else {
                synchronized (out) {
                    out.println("[P2P Error] Handshake with " + target.getDisplayName() + " timed out.");
                    out.println();
                }
                conn.close();
            }
        } catch (IOException | InterruptedException e) {
            synchronized (out) {
                out.println("[P2P Error] Failed to connect directly to " + target.getDisplayName() + ": " + e.getMessage());
                out.println();
            }
        }
    }

    private PeerInfo findPeer(String identifier) {
        try {
            int index = Integer.parseInt(identifier) - 1;
            if (index >= 0 && index < cachedPeers.size()) {
                return cachedPeers.get(index);
            }
        } catch (NumberFormatException ignored) {
        }

        for (PeerInfo p : cachedPeers) {
            if (p.getPeerId().equalsIgnoreCase(identifier) ||
                    p.getDisplayName().equalsIgnoreCase(identifier)) {
                return p;
            }
        }
        return null;
    }

    public synchronized void setActiveChatConnection(PeerConnection connection, boolean isIncoming) {
        if (this.activeConnection != null && this.activeConnection.isConnected()) {
            try {
                this.activeConnection.close();
            } catch (Exception ignored) {
            }
        }

        this.activeConnection = connection;
        this.activeChatService = new ChatService(connection, identity.getDisplayName());

        if (isIncoming) {
            synchronized (out) {
                out.println();
                out.println("[P2P] Incoming connection from " + connection.getRemotePeerName() + " established!");
                out.println("[P2P] Chat connection established");
                out.println();
            }
        }

        activeChatService.addListener(new ChatListener() {
            @Override
            public void onMessageReceived(ChatMessage message) {
                synchronized (out) {
                    out.println("[" + message.sender() + "] " + message.content());
                    out.println();
                }
            }

            @Override
            public void onMessageSent(ChatMessage message) {
                synchronized (out) {
                    out.println("You:");
                    out.println(message.content());
                    out.println();
                }
            }

            @Override
            public void onChatDisconnected() {
                synchronized (out) {
                    out.println("[P2P] Direct chat with " + connection.getRemotePeerName() + " disconnected.");
                    out.println();
                }
            }
        });
    }

    private void handleChatMessage(String content) {
        if (activeChatService == null || !activeChatService.isConnected()) {
            synchronized (out) {
                out.println("[Info] No active direct P2P chat connection. Use /peers and /connect <peerId> to connect.");
                out.println();
            }
            return;
        }

        try {
            activeChatService.sendMessage(content);
        } catch (IllegalArgumentException e) {
            synchronized (out) {
                out.println("[Error] " + e.getMessage());
                out.println();
            }
        } catch (IOException e) {
            synchronized (out) {
                out.println("[P2P Error] Failed to send message: " + e.getMessage());
                out.println();
            }
        }
    }

    private void executePing() {
        if (activeChatService == null || !activeChatService.isConnected()) {
            synchronized (out) {
                out.println("[Ping] No active P2P connection to ping. Connect to a peer first.");
                out.println();
            }
            return;
        }

        try {
            activeChatService.ping().thenAccept(latencyMs -> {
                synchronized (out) {
                    out.printf("[PONG] %.2f ms%n%n", latencyMs);
                }
            }).exceptionally(ex -> {
                synchronized (out) {
                    out.println("[PING Failed] " + ex.getMessage());
                    out.println();
                }
                return null;
            });
        } catch (IOException e) {
            synchronized (out) {
                out.println("[PING Failed] " + e.getMessage());
                out.println();
            }
        }
    }

    public PeerIdentity getIdentity() {
        return identity;
    }

    public int getLocalP2pPort() {
        return peerListener != null ? peerListener.getPort() : localP2pPort;
    }

    public DiscoveryClient getDiscoveryClient() {
        return discoveryClient;
    }

    public ChatService getActiveChatService() {
        return activeChatService;
    }

    public boolean isRunning() {
        return running.get();
    }

    @Override
    public void close() {
        running.set(false);

        if (discoveryClient != null) {
            discoveryClient.close();
        }

        if (activeConnection != null) {
            activeConnection.close();
        }

        if (peerListener != null) {
            try {
                peerListener.close();
            } catch (IOException ignored) {
            }
        }

        try {
            reader.close();
        } catch (IOException ignored) {
        }
    }
}
