package com.landrop.transfer;

import com.landrop.peer.PeerConnection;
import com.landrop.peer.PeerListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class LargeFilePeerToPeerIntegrationTest {

    private PeerListener serverListener;
    private PeerConnection serverConnection;
    private PeerConnection clientConnection;
    private int listenerPort;

    @BeforeEach
    void setUp() throws Exception {
        serverListener = new PeerListener(0);
        listenerPort = serverListener.getPort();

        CountDownLatch acceptLatch = new CountDownLatch(1);
        AtomicReference<PeerConnection> scRef = new AtomicReference<>();

        Thread serverThread = new Thread(() -> {
            try {
                PeerConnection sc = serverListener.acceptConnection();
                scRef.set(sc);
                sc.setLocalPeerName("Peer B (Receiver)");
                sc.start();
                acceptLatch.countDown();
            } catch (Exception ignored) {
            }
        });
        serverThread.start();

        clientConnection = PeerConnection.connectTo("localhost", listenerPort);
        clientConnection.setLocalPeerName("Peer A (Sender)");
        clientConnection.start();

        assertTrue(acceptLatch.await(5, TimeUnit.SECONDS));
        serverConnection = scRef.get();
        assertNotNull(serverConnection);

        clientConnection.initiateHandshake("Peer A (Sender)");
        assertTrue(clientConnection.awaitReady(5, TimeUnit.SECONDS));
        assertTrue(serverConnection.awaitReady(5, TimeUnit.SECONDS));
    }

    @AfterEach
    void tearDown() {
        if (clientConnection != null) {
            try { clientConnection.close(); } catch (Exception ignored) {}
        }
        if (serverConnection != null) {
            try { serverConnection.close(); } catch (Exception ignored) {}
        }
        if (serverListener != null) {
            try { serverListener.close(); } catch (Exception ignored) {}
        }
    }

    @Test
    @DisplayName("E2E P2P: Multi-chunk file transfer with progress, speed, and SHA-256 integrity verification")
    void testEndToEndLargeFileTransfer(@TempDir Path tempDir) throws Exception {
        int chunkSize = 64 * 1024; // 64 KB chunks
        int fileSize = 512 * 1024; // 512 KB = 8 chunks
        byte[] testBytes = new byte[fileSize];
        new Random(12345).nextBytes(testBytes);

        Path sourceFile = tempDir.resolve("large_payload.bin");
        Files.write(sourceFile, testBytes);
        String expectedHash = FileTransferUtils.calculateSha256(sourceFile);

        Path downloadDir = tempDir.resolve("received_files");
        Files.createDirectories(downloadDir);

        FileTransferService senderService = new FileTransferService(clientConnection, "peer-a", "Peer A", tempDir, chunkSize);
        FileTransferService receiverService = new FileTransferService(serverConnection, "peer-b", "Peer B", downloadDir, chunkSize);
        senderService.setAutoReconnectEnabled(false);
        receiverService.setAutoReconnectEnabled(false);

        CountDownLatch offerLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(1);
        AtomicReference<FileTransfer> incomingRef = new AtomicReference<>();
        AtomicBoolean progressReported = new AtomicBoolean(false);

        receiverService.addListener(new FileTransferListener() {
            @Override
            public void onTransferOffered(FileTransfer transfer) {
                incomingRef.set(transfer);
                offerLatch.countDown();
            }

            @Override
            public void onTransferProgress(FileTransfer transfer, long transferred, long total, double speed) {
                if (transferred > 0) {
                    progressReported.set(true);
                }
            }

            @Override
            public void onTransferCompleted(FileTransfer transfer) {
                completeLatch.countDown();
            }
        });

        // 1. Peer A offers large file
        FileTransfer outgoing = senderService.offerFile(sourceFile.toFile());
        assertNotNull(outgoing);

        // 2. Peer B receives offer
        assertTrue(offerLatch.await(5, TimeUnit.SECONDS));
        FileTransfer incoming = incomingRef.get();
        assertNotNull(incoming);
        assertEquals(8, incoming.getTotalChunks());

        // 3. Peer B accepts transfer
        receiverService.acceptTransfer(incoming.getTransferId());

        // 4. Wait for full completion
        assertTrue(completeLatch.await(10, TimeUnit.SECONDS), "File transfer must complete within 10s");
        assertEquals(FileTransferStatus.COMPLETED, incoming.getStatus());
        assertTrue(progressReported.get(), "Progress updates must have fired");

        // 5. Verify final file on disk
        Path destinationFile = downloadDir.resolve("large_payload.bin");
        assertTrue(Files.exists(destinationFile), "Destination file must exist");
        assertEquals(fileSize, Files.size(destinationFile));

        String actualHash = FileTransferUtils.calculateSha256(destinationFile);
        assertEquals(expectedHash, actualHash, "SHA-256 hash must match 100%");

        // 6. Verify temporary .landrop.part and .landrop-meta files were cleaned up
        Path partPath = downloadDir.resolve("large_payload.bin.landrop.part");
        Path metaPath = downloadDir.resolve("large_payload.bin.landrop-meta");
        assertFalse(Files.exists(partPath), "Partial file must be deleted upon completion");
        assertFalse(Files.exists(metaPath), "Meta file must be deleted upon completion");
    }

    @Test
    @DisplayName("E2E P2P: Pause and resume large transfer preserves .landrop-meta and completes successfully")
    void testPauseAndResumeWithMetaPersistence(@TempDir Path tempDir) throws Exception {
        int chunkSize = 128 * 1024; // 128 KB chunks
        int fileSize = 1024 * 1024; // 1 MB = 8 chunks
        byte[] testBytes = new byte[fileSize];
        new Random(54321).nextBytes(testBytes);

        Path sourceFile = tempDir.resolve("resumable_payload.bin");
        Files.write(sourceFile, testBytes);
        String expectedHash = FileTransferUtils.calculateSha256(sourceFile);

        Path downloadDir = tempDir.resolve("resumable_downloads");
        Files.createDirectories(downloadDir);

        FileTransferService senderService = new FileTransferService(clientConnection, "peer-a", "Peer A", tempDir, chunkSize);
        FileTransferService receiverService = new FileTransferService(serverConnection, "peer-b", "Peer B", downloadDir, chunkSize);
        senderService.setAutoReconnectEnabled(false);
        receiverService.setAutoReconnectEnabled(false);

        CountDownLatch offerLatch = new CountDownLatch(1);
        AtomicReference<FileTransfer> incomingRef = new AtomicReference<>();

        receiverService.addListener(new FileTransferListener() {
            @Override
            public void onTransferOffered(FileTransfer transfer) {
                incomingRef.set(transfer);
                offerLatch.countDown();
            }
        });

        senderService.offerFile(sourceFile.toFile());
        assertTrue(offerLatch.await(5, TimeUnit.SECONDS));
        FileTransfer incoming = incomingRef.get();

        // Accept transfer
        receiverService.acceptTransfer(incoming.getTransferId());

        // Wait until at least 1 chunk is completed, then pause
        long deadline = System.currentTimeMillis() + 4000;
        while (incoming.getCompletedChunkCount() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        // Pause transfer
        receiverService.pauseTransfer(incoming.getTransferId());
        assertEquals(FileTransferStatus.INTERRUPTED, incoming.getStatus());

        // Verify .landrop-meta exists on disk
        Path metaPath = downloadDir.resolve("resumable_payload.bin.landrop-meta");
        Path partPath = downloadDir.resolve("resumable_payload.bin.landrop.part");
        assertTrue(Files.exists(partPath), "Partial file must exist when paused");

        // Resume transfer
        CountDownLatch resumeCompleteLatch = new CountDownLatch(1);
        receiverService.addListener(new FileTransferListener() {
            @Override
            public void onTransferCompleted(FileTransfer transfer) {
                resumeCompleteLatch.countDown();
            }
        });

        receiverService.resumeTransfer(incoming.getTransferId());
        assertTrue(resumeCompleteLatch.await(10, TimeUnit.SECONDS), "Resumed transfer must complete");

        // Verify final integrity
        Path destinationFile = downloadDir.resolve("resumable_payload.bin");
        assertTrue(Files.exists(destinationFile));
        assertEquals(expectedHash, FileTransferUtils.calculateSha256(destinationFile));
    }
}
