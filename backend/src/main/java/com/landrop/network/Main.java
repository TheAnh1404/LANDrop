package com.landrop.network;

import com.landrop.chat.ChatCli;
import com.landrop.chat.ChatService;
import com.landrop.peer.PeerConnection;
import com.landrop.peer.PeerListener;

import java.io.IOException;
import java.net.BindException;
import java.net.ConnectException;
import java.util.concurrent.TimeUnit;

/**
 * Main entry point for the LANDrop application.
 * Supports launching real-time peer-to-peer interactive chat over the custom TCP binary protocol,
 * with backward compatibility for Phase 2 protocol exchange flows.
 */
public class Main {

    public static void main(String[] args) {
        if (args.length < 2) {
            printUsage();
            return;
        }

        String mode = args[0].trim().toLowerCase();

        switch (mode) {
            case "server" -> handleServer(args);
            case "client" -> handleClient(args);
            default -> printUsage();
        }
    }

    private static void handleServer(String[] args) {
        int port;
        try {
            port = Integer.parseInt(args[1].trim());
            if (port < 1 || port > 65535) {
                System.err.println("Invalid port number: " + args[1] + ". Port must be between 1 and 65535.");
                return;
            }
        } catch (NumberFormatException e) {
            System.err.println("Invalid port number: " + args[1]);
            printUsage();
            return;
        }

        boolean phase2Mode = args.length > 2 && "--phase2".equalsIgnoreCase(args[2].trim());
        if (phase2Mode) {
            runPhase2Server(port);
            return;
        }

        runChatServer(port);
    }

    private static void runChatServer(int port) {
        System.out.println("=================================");
        System.out.println("LANDrop - Peer Server");
        System.out.println("=================================");
        System.out.println();
        System.out.println("Starting server on port: " + port);
        System.out.println("Waiting for peer connection...");
        System.out.println();

        try (PeerListener listener = new PeerListener(port)) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    listener.close();
                } catch (IOException ignored) {
                }
            }, "server-shutdown-hook"));

            PeerConnection connection = listener.acceptConnection();
            connection.setLocalPeerName("Peer B");
            connection.start();

            // Await HELLO handshake from connecting peer
            try {
                boolean ready = connection.awaitReady(10, TimeUnit.SECONDS);
                if (!ready) {
                    System.err.println("Warning: Handshake timed out. Proceeding to chat session...");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            ChatService chatService = new ChatService(connection, "Peer B");
            ChatCli cli = new ChatCli(chatService);
            cli.start();

        } catch (BindException e) {
            System.err.println("Unable to start server.");
            System.err.println("Port " + port + " is already in use.");
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }

    private static void runPhase2Server(int port) {
        PeerServer server = new PeerServer(port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "shutdown-hook"));
        server.start();
    }

    private static void handleClient(String[] args) {
        if (args.length < 3) {
            printUsage();
            return;
        }

        String host = args[1].trim();
        int port;
        try {
            port = Integer.parseInt(args[2].trim());
            if (port < 1 || port > 65535) {
                System.err.println("Invalid port number: " + args[2] + ". Port must be between 1 and 65535.");
                return;
            }
        } catch (NumberFormatException e) {
            System.err.println("Invalid port number: " + args[2]);
            printUsage();
            return;
        }

        boolean phase2Mode = args.length > 3 && "--phase2".equalsIgnoreCase(args[3].trim());
        if (phase2Mode) {
            String message = args.length > 4 ? args[4] : "Hello from Peer A";
            runPhase2Client(host, port, message);
            return;
        }

        runChatClient(host, port);
    }

    private static void runChatClient(String host, int port) {
        System.out.println("=================================");
        System.out.println("LANDrop - Peer Client");
        System.out.println("=================================");
        System.out.println();
        System.out.println("Connecting to " + host + ":" + port + "...");
        System.out.println();

        try {
            PeerConnection connection = PeerConnection.connectTo(host, port, 5000);
            connection.setLocalPeerName("Peer A");
            connection.start();

            // Initiate HELLO handshake
            connection.initiateHandshake("Peer A");

            try {
                boolean ready = connection.awaitReady(10, TimeUnit.SECONDS);
                if (!ready) {
                    System.err.println("Warning: Handshake timed out. Proceeding to chat session...");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            ChatService chatService = new ChatService(connection, "Peer A");
            ChatCli cli = new ChatCli(chatService);
            cli.start();

        } catch (ConnectException e) {
            System.err.println("Unable to connect to " + host + ":" + port + ".");
            System.err.println("Make sure the peer server is running.");
        } catch (IOException e) {
            System.err.println("Connection error: " + e.getMessage());
        }
    }

    private static void runPhase2Client(String host, int port, String message) {
        PeerClient client = new PeerClient(host, port);
        try {
            client.executePhase2Flow(message);
        } catch (IOException ignored) {
        }
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println();
        System.out.println("Chat Server (Phase 3):");
        System.out.println("  server <port>");
        System.out.println();
        System.out.println("Chat Client (Phase 3):");
        System.out.println("  client <host> <port>");
        System.out.println();
        System.out.println("Legacy Phase 2 Options:");
        System.out.println("  server <port> --phase2");
        System.out.println("  client <host> <port> --phase2 [message]");
    }
}
