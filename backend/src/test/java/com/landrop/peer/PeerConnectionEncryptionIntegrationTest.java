package com.landrop.peer;

import com.landrop.protocol.MessageType;
import com.landrop.protocol.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PeerConnectionEncryptionIntegrationTest {

    @Test
    @DisplayName("Verify automatic E2E ECDH key exchange and encrypted payload transmission between peers")
    void testEndToEndEncryptionHandshakeAndTransmission() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();

            CountDownLatch acceptedLatch = new CountDownLatch(1);
            AtomicReference<PeerConnection> serverConnRef = new AtomicReference<>();

            Thread serverThread = new Thread(() -> {
                try {
                    PeerConnection sc = new PeerConnection(serverSocket.accept());
                    serverConnRef.set(sc);
                    sc.setLocalPeerName("Bob");
                    sc.start();
                    acceptedLatch.countDown();
                } catch (IOException ignored) {
                }
            });
            serverThread.start();

            PeerConnection clientConn = PeerConnection.connectTo("localhost", port);
            clientConn.setLocalPeerName("Alice");
            clientConn.start();

            assertTrue(acceptedLatch.await(5, TimeUnit.SECONDS));
            PeerConnection serverConn = serverConnRef.get();
            assertNotNull(serverConn);

            // Register packet inspection to verify wire transmission is ENCRYPTED_PAYLOAD
            AtomicBoolean serverReceivedEncryptedPayload = new AtomicBoolean(false);
            serverConn.addListener(new ConnectionListener() {
                @Override
                public void onPacketReceived(Packet packet) {
                    if (packet.getType() == MessageType.ENCRYPTED_PAYLOAD) {
                        serverReceivedEncryptedPayload.set(true);
                    }
                }
            });

            // Register message handler on server for decrypted CHAT_MESSAGE
            CountDownLatch chatReceivedLatch = new CountDownLatch(1);
            AtomicReference<String> receivedMessageRef = new AtomicReference<>();
            serverConn.registerHandler(MessageType.CHAT_MESSAGE, packet -> {
                receivedMessageRef.set(packet.payloadAsString());
                chatReceivedLatch.countDown();
            });

            // Alice initiates HELLO handshake
            clientConn.initiateHandshake("Alice");

            // Both peers should become READY
            assertTrue(clientConn.awaitReady(5, TimeUnit.SECONDS), "Client should reach READY state");
            assertTrue(serverConn.awaitReady(5, TimeUnit.SECONDS), "Server should reach READY state");

            // Both peers should complete ECDH key exchange and become SECURE
            assertTrue(clientConn.awaitSecure(5, TimeUnit.SECONDS), "Client should complete E2E key exchange");
            assertTrue(serverConn.awaitSecure(5, TimeUnit.SECONDS), "Server should complete E2E key exchange");

            assertTrue(clientConn.isEncrypted(), "Client connection must be encrypted");
            assertTrue(serverConn.isEncrypted(), "Server connection must be encrypted");

            // Verify safety fingerprints match exactly
            String clientFingerprint = clientConn.getSecurityFingerprint();
            String serverFingerprint = serverConn.getSecurityFingerprint();
            assertNotNull(clientFingerprint);
            assertEquals(6, clientFingerprint.length());
            assertEquals(clientFingerprint, serverFingerprint, "Both peers must derive identical safety fingerprints");

            // Alice sends a secret chat message
            String secretText = "LANDrop v2.0 - Bảo mật cấp độ cao với AES-256-GCM!";
            Packet chatPacket = Packet.text(MessageType.CHAT_MESSAGE, 101L, secretText);
            clientConn.send(chatPacket);

            // Server must receive and successfully decrypt the message
            assertTrue(chatReceivedLatch.await(5, TimeUnit.SECONDS), "Server should receive decrypted message");
            assertEquals(secretText, receivedMessageRef.get());
            assertTrue(serverReceivedEncryptedPayload.get(), "Wire transmission must have used ENCRYPTED_PAYLOAD");

            clientConn.close();
            serverConn.close();
        }
    }

    @Test
    @DisplayName("Verify enforced encryption rejects plaintext sending if not established")
    void testEnforcedEncryptionRejectsPlaintextWhenUnsecured() throws Exception {
        PeerConnection conn = new PeerConnection();
        conn.setEncryptionEnforced(true);

        // When unencrypted and enforced, send should throw IOException
        Packet chatPacket = Packet.text(MessageType.CHAT_MESSAGE, 1L, "plaintext");
        assertThrows(IOException.class, () -> conn.send(chatPacket));
    }
}
