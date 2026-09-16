package com.landrop.ui.services;

import com.landrop.chat.ChatMessage;
import com.landrop.discovery.DiscoveryServer;
import com.landrop.discovery.PeerInfo;
import com.landrop.ui.models.DiscoveryStatus;
import com.landrop.ui.models.P2PStatus;
import com.landrop.ui.models.UIMessage;
import javafx.application.Platform;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class UIStateServiceTest {

    @BeforeAll
    static void initJavaFX() {
        try {
            Platform.startup(() -> {});
            Platform.setImplicitExit(false);
        } catch (IllegalStateException ignored) {
            Platform.setImplicitExit(false);
        }
    }

    @Test
    void fallsBackToAnotherP2pPortWhenTheRequestedPortIsOccupied() throws Exception {
        DiscoveryServer discoveryServer = new DiscoveryServer(0);
        discoveryServer.startAsync();
        assertTrue(discoveryServer.awaitReady(5, TimeUnit.SECONDS));
        UIStateService peer = new UIStateService();

        try (ServerSocket occupiedPort = new ServerSocket(0)) {
            int requestedPort = occupiedPort.getLocalPort();
            peer.connectAndRegister("Fallback peer", requestedPort, "127.0.0.1",
                    discoveryServer.getPort(), "127.0.0.1").get(5, TimeUnit.SECONDS);

            assertNotEquals(requestedPort, peer.getLocalP2pPort());
            assertTrue(peer.getLocalP2pPort() > 0);
            assertEquals(DiscoveryStatus.CONNECTED, peer.discoveryStatusProperty().get());
        } finally {
            peer.shutdown();
            discoveryServer.stop();
        }
    }

    @Test
    void testDiscoveryRegistrationAndPeerList() throws Exception {
        // 1. Start discovery server on dynamic port
        DiscoveryServer discoveryServer = new DiscoveryServer(0);
        discoveryServer.startAsync();
        assertTrue(discoveryServer.awaitReady(5, TimeUnit.SECONDS));
        int discPort = discoveryServer.getPort();

        UIStateService peerA = new UIStateService();
        UIStateService peerB = new UIStateService();

        try {
            // Connect and register peer A
            peerA.connectAndRegister("Peer A", 0, "127.0.0.1", discPort, "127.0.0.1").get(5, TimeUnit.SECONDS);

            // Connect and register peer B
            peerB.connectAndRegister("Peer B", 0, "127.0.0.1", discPort, "127.0.0.1").get(5, TimeUnit.SECONDS);

            // Refresh peer A list
            peerA.refreshPeerList().get(5, TimeUnit.SECONDS);

            CountDownLatch latch = new CountDownLatch(1);
            Platform.runLater(() -> {
                assertEquals(DiscoveryStatus.CONNECTED, peerA.discoveryStatusProperty().get());
                assertEquals(1, peerA.getDiscoveredPeers().size());
                PeerInfo discovered = peerA.getDiscoveredPeers().getFirst();
                assertEquals("Peer B", discovered.getDisplayName());
                latch.countDown();
            });
            assertTrue(latch.await(5, TimeUnit.SECONDS));

        } finally {
            peerA.shutdown();
            peerB.shutdown();
            discoveryServer.stop();
        }
    }

    @Test
    void testDirectP2PConnectionAndMessaging() throws Exception {
        DiscoveryServer discoveryServer = new DiscoveryServer(0);
        discoveryServer.startAsync();
        assertTrue(discoveryServer.awaitReady(5, TimeUnit.SECONDS));
        int discPort = discoveryServer.getPort();

        UIStateService peerA = new UIStateService();
        UIStateService peerB = new UIStateService();

        try {
            peerA.connectAndRegister("Peer A", 0, "127.0.0.1", discPort, "127.0.0.1").get(5, TimeUnit.SECONDS);
            peerB.connectAndRegister("Peer B", 0, "127.0.0.1", discPort, "127.0.0.1").get(5, TimeUnit.SECONDS);

            // Peer A discovers Peer B
            peerA.refreshPeerList().get(5, TimeUnit.SECONDS);
            assertEquals(1, peerA.getDiscoveredPeers().size());
            PeerInfo peerBInfo = peerA.getDiscoveredPeers().getFirst();

            // Connect A -> B directly
            peerA.connectToPeer(peerBInfo).get(5, TimeUnit.SECONDS);

            // Give a moment for incoming acceptor on B to complete handshake
            Thread.sleep(500);

            // Assert P2P connected state
            CountDownLatch checkLatch = new CountDownLatch(1);
            Platform.runLater(() -> {
                assertEquals(P2PStatus.CONNECTED, peerA.p2pStatusProperty().get());
                assertEquals(P2PStatus.CONNECTED, peerB.p2pStatusProperty().get());
                assertEquals("Peer B", peerA.activeRemotePeerNameProperty().get());
                assertEquals("Peer A", peerB.activeRemotePeerNameProperty().get());
                checkLatch.countDown();
            });
            assertTrue(checkLatch.await(5, TimeUnit.SECONDS));

            // Send message A -> B
            peerA.sendMessage("Hello from A").get(5, TimeUnit.SECONDS);
            Thread.sleep(300);

            CountDownLatch msgLatch1 = new CountDownLatch(1);
            Platform.runLater(() -> {
                assertEquals(1, peerA.getActiveChatMessages().size());
                assertTrue(peerA.getActiveChatMessages().getFirst().isLocal());
                assertEquals("Hello from A", peerA.getActiveChatMessages().getFirst().content());

                assertEquals(1, peerB.getActiveChatMessages().size());
                assertFalse(peerB.getActiveChatMessages().getFirst().isLocal());
                assertEquals("Hello from A", peerB.getActiveChatMessages().getFirst().content());
                msgLatch1.countDown();
            });
            assertTrue(msgLatch1.await(5, TimeUnit.SECONDS));

            // Send reply B -> A
            peerB.sendMessage("Hi Peer A, this is Peer B!").get(5, TimeUnit.SECONDS);
            Thread.sleep(300);

            CountDownLatch msgLatch2 = new CountDownLatch(1);
            Platform.runLater(() -> {
                assertEquals(2, peerA.getActiveChatMessages().size());
                assertFalse(peerA.getActiveChatMessages().get(1).isLocal());
                assertEquals("Hi Peer A, this is Peer B!", peerA.getActiveChatMessages().get(1).content());

                assertEquals(2, peerB.getActiveChatMessages().size());
                assertTrue(peerB.getActiveChatMessages().get(1).isLocal());
                assertEquals("Hi Peer A, this is Peer B!", peerB.getActiveChatMessages().get(1).content());
                msgLatch2.countDown();
            });
            assertTrue(msgLatch2.await(5, TimeUnit.SECONDS));

            // Ping test
            Double latency = peerA.pingPeer().get(5, TimeUnit.SECONDS);
            assertNotNull(latency);
            assertTrue(latency >= 0.0);

            // Disconnect test
            peerA.disconnectP2P();
            Thread.sleep(200);

            CountDownLatch discLatch = new CountDownLatch(1);
            Platform.runLater(() -> {
                assertEquals(P2PStatus.DISCONNECTED, peerA.p2pStatusProperty().get());
                assertEquals(P2PStatus.DISCONNECTED, peerB.p2pStatusProperty().get());
                discLatch.countDown();
            });
            assertTrue(discLatch.await(5, TimeUnit.SECONDS));

        } finally {
            peerA.shutdown();
            peerB.shutdown();
            discoveryServer.stop();
        }
    }

    @Test
    void testP2PFileTransferFlow() throws Exception {
        DiscoveryServer discoveryServer = new DiscoveryServer(0);
        discoveryServer.startAsync();
        assertTrue(discoveryServer.awaitReady(5, TimeUnit.SECONDS));
        int discPort = discoveryServer.getPort();

        UIStateService peerA = new UIStateService();
        UIStateService peerB = new UIStateService();

        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("landrop_ui_test_");
        java.nio.file.Path sampleFile = tempDir.resolve("sample.txt");
        java.nio.file.Files.writeString(sampleFile, "LANDrop Phase 7 UI File Transfer Verification");

        try {
            peerA.connectAndRegister("Peer A", 0, "127.0.0.1", discPort, "127.0.0.1").get(5, TimeUnit.SECONDS);
            peerB.connectAndRegister("Peer B", 0, "127.0.0.1", discPort, "127.0.0.1").get(5, TimeUnit.SECONDS);

            peerA.refreshPeerList().get(5, TimeUnit.SECONDS);
            PeerInfo peerBInfo = peerA.getDiscoveredPeers().getFirst();

            peerA.connectToPeer(peerBInfo).get(5, TimeUnit.SECONDS);
            Thread.sleep(500);

            CountDownLatch offerLatch = new CountDownLatch(1);
            CountDownLatch completeLatchB = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<com.landrop.transfer.FileTransfer> offeredTransferRef = new java.util.concurrent.atomic.AtomicReference<>();

            peerB.setOnFileTransferOfferedCallback(transfer -> {
                offeredTransferRef.set(transfer);
                offerLatch.countDown();
            });

            peerB.setOnFileTransferUpdatedCallback(transfer -> {
                if (transfer.getStatus() == com.landrop.transfer.FileTransferStatus.COMPLETED) {
                    completeLatchB.countDown();
                }
            });

            // Peer A offers file
            com.landrop.transfer.FileTransfer outgoing = peerA.offerFile(sampleFile.toFile()).get(5, TimeUnit.SECONDS);
            assertNotNull(outgoing);

            assertTrue(offerLatch.await(5, TimeUnit.SECONDS));
            com.landrop.transfer.FileTransfer offered = offeredTransferRef.get();
            assertNotNull(offered);
            assertEquals("sample.txt", offered.getFileName());
            assertNotNull(offered.getFileSha256());

            // Peer B accepts
            peerB.acceptTransfer(offered.getTransferId()).get(5, TimeUnit.SECONDS);

            assertTrue(completeLatchB.await(10, TimeUnit.SECONDS));
            assertEquals(com.landrop.transfer.FileTransferStatus.COMPLETED, offered.getStatus());
            assertTrue(offered.isHashVerified());

        } finally {
            peerA.shutdown();
            peerB.shutdown();
            discoveryServer.stop();
            try {
                java.nio.file.Files.walk(tempDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .map(java.nio.file.Path::toFile)
                        .forEach(java.io.File::delete);
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void testStartupFileTransferServiceLifecycle() throws Exception {
        DiscoveryServer discoveryServer = new DiscoveryServer(0);
        discoveryServer.startAsync();
        assertTrue(discoveryServer.awaitReady(5, TimeUnit.SECONDS));
        int discPort = discoveryServer.getPort();

        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("landrop_lifecycle_test_");
        java.nio.file.Path sample = tempDir.resolve("auto_shared.txt");
        java.nio.file.Files.writeString(sample, "Auto scanned on startup");

        UIStateService peer = new UIStateService();
        peer.setConfiguredDownloadDirectory(tempDir);

        try {
            // Register with discovery
            peer.connectAndRegister("Peer Startup", 0, "127.0.0.1", discPort, "127.0.0.1").get(5, TimeUnit.SECONDS);

            // Verify FileTransferService initialized before any 1-to-1 P2P connect
            assertNotNull(peer.getFileTransferService(), "FileTransferService must be non-null after startup");
            assertNull(peer.getFileTransferService().getConnection(), "No 1-to-1 connection required at startup");
            assertEquals(P2PStatus.DISCONNECTED, peer.p2pStatusProperty().get());

            // Give async scan a moment to finish
            Thread.sleep(300);
            assertTrue(peer.getFileTransferService().getSharedFiles().size() >= 1, "Startup scan must have indexed shared file");

            // Disconnect P2P should not nullify FileTransferService
            peer.disconnectP2P();
            assertNotNull(peer.getFileTransferService(), "FileTransferService must survive disconnectP2P");

        } finally {
            peer.shutdown();
            discoveryServer.stop();
            try {
                java.nio.file.Files.walk(tempDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .map(java.nio.file.Path::toFile)
                        .forEach(java.io.File::delete);
            } catch (Exception ignored) {
            }
        }
    }
}
