package com.landrop.discovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerNodeTest {

    @Test
    @DisplayName("Verify PeerNode startup, /peers, /help, and /quit commands with DiscoveryServer")
    void testPeerNodeCliLifecycle() throws Exception {
        try (DiscoveryServer discoveryServer = new DiscoveryServer(0)) {
            discoveryServer.startAsync();
            assertTrue(discoveryServer.awaitReady(5, TimeUnit.SECONDS));
            int discoveryPort = discoveryServer.getPort();

            // Simulate user input for PeerNode:
            // /help
            // /peers
            // /quit
            String input = "/help\n/peers\n/quit\n";
            ByteArrayInputStream in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PrintStream printStream = new PrintStream(out, true, StandardCharsets.UTF_8);

            PeerNode node = new PeerNode(0, "localhost", discoveryPort, "TestPeer", "127.0.0.1", in, printStream);

            node.start();

            String output = out.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("LANDrop - Peer"));
            assertTrue(output.contains("Display Name:"));
            assertTrue(output.contains("TestPeer"));
            assertTrue(output.contains("Connecting to Discovery Server"));
            assertTrue(output.contains("Connected."));
            assertTrue(output.contains("Commands:"));
            assertTrue(output.contains("/peers"));
            assertTrue(output.contains("/connect"));
            assertTrue(output.contains("/quit"));
            assertTrue(output.contains("Exiting..."));

            assertFalse(node.isRunning());
        }
    }
}
