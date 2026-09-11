package com.landrop.chat;

import com.landrop.peer.PeerConnection;
import com.landrop.peer.PeerListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatCliTest {

    @Test
    @DisplayName("Verify ChatCli processes commands: /help, /ping, normal chat, and /quit")
    void testCliCommandsAndFlow() throws Exception {
        try (PeerListener listener = new PeerListener(0)) {
            int port = listener.getPort();

            CountDownLatch acceptLatch = new CountDownLatch(1);
            AtomicReference<PeerConnection> serverConnRef = new AtomicReference<>();

            Thread serverThread = new Thread(() -> {
                try {
                    PeerConnection sc = listener.acceptConnection();
                    serverConnRef.set(sc);
                    sc.setLocalPeerName("Peer B");
                    sc.start();
                    acceptLatch.countDown();
                } catch (Exception ignored) {
                }
            });
            serverThread.start();

            PeerConnection clientConn = PeerConnection.connectTo("localhost", port);
            clientConn.setLocalPeerName("Peer A");
            clientConn.start();

            assertTrue(acceptLatch.await(5, TimeUnit.SECONDS));
            PeerConnection serverConn = serverConnRef.get();

            clientConn.initiateHandshake("Peer A");
            assertTrue(clientConn.awaitReady(5, TimeUnit.SECONDS));
            assertTrue(serverConn.awaitReady(5, TimeUnit.SECONDS));

            ChatService chatService = new ChatService(clientConn, "Peer A");

            // Simulate user entering:
            // blank line
            // /help
            // Hello from CLI test
            // /ping
            // /quit
            String simulatedInput = "\n   \n/help\nHello from CLI test\n/ping\n/quit\n";
            ByteArrayInputStream in = new ByteArrayInputStream(simulatedInput.getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PrintStream printStream = new PrintStream(out, true, StandardCharsets.UTF_8);

            ChatCli cli = new ChatCli(chatService, in, printStream);
            cli.start();

            String output = out.toString(StandardCharsets.UTF_8);

            assertTrue(output.contains("LANDrop - Peer Chat"), "Must contain banner");
            assertTrue(output.contains("Connected to Peer B"), "Must show connected to Peer B");
            assertTrue(output.contains("Available commands:"), "Must show help message");
            assertTrue(output.contains("/ping"), "Help must list /ping");
            assertTrue(output.contains("/quit"), "Help must list /quit");
            assertTrue(output.contains("You:"), "Must display sent message header");
            assertTrue(output.contains("Hello from CLI test"), "Must display sent message content");
            assertTrue(output.contains("Disconnecting..."), "Must show disconnecting message on /quit");

            assertFalse(cli.isRunning(), "CLI must stop running after /quit");
            assertFalse(clientConn.isConnected(), "Connection should be closed after /quit");

            serverConn.close();
        }
    }
}
