package com.landrop.transfer;

import com.landrop.peer.PeerConnection;
import com.landrop.peer.PeerListener;
import com.landrop.protocol.MessageType;
import com.landrop.protocol.Packet;
import com.landrop.transfer.messages.ResumeRequestMessage;
import com.landrop.transfer.messages.ResumeResponseMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class FileTransferResumeIntegrationTest {

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
                sc.setLocalPeerName("Peer B");
                sc.start();
                acceptLatch.countDown();
            } catch (Exception ignored) {
            }
        });
        serverThread.start();

        clientConnection = PeerConnection.connectTo("localhost", listenerPort);
        clientConnection.setLocalPeerName("Peer A");
        clientConnection.start();

        assertTrue(acceptLatch.await(5, TimeUnit.SECONDS));
        serverConnection = scRef.get();
        assertNotNull(serverConnection);

        clientConnection.initiateHandshake("Peer A");
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
    @DisplayName("Scenario B: Interrupted transfer -> Reconnect -> Resume missing chunks -> COMPLETED")
    void testInterruptedTransferResume(@TempDir Path tempDir) throws Exception {
        int chunkSize = 64 * 1024; // 64 KB chunks
        int fileSize = 256 * 1024; // 256 KB = 4 chunks
        byte[] originalBytes = new byte[fileSize];
        new Random(42).nextBytes(originalBytes);

        Path sourceFile = tempDir.resolve("resume_source.bin");
        Files.write(sourceFile, originalBytes);
        String expectedHash = FileTransferUtils.calculateSha256(sourceFile);

        Path downloadDir = tempDir.resolve("downloads");
        Files.createDirectories(downloadDir);

        FileTransferService senderService = new FileTransferService(clientConnection, "peer-a", "Peer A", tempDir, chunkSize);
        FileTransferService receiverService = new FileTransferService(serverConnection, "peer-b", "Peer B", downloadDir, chunkSize);
        senderService.setAutoReconnectEnabled(false);
        receiverService.setAutoReconnectEnabled(false);

        CountDownLatch offerLatch = new CountDownLatch(1);
        CountDownLatch interruptLatch = new CountDownLatch(1);
        AtomicReference<FileTransfer> offeredTransferRef = new AtomicReference<>();

        receiverService.addListener(new FileTransferListener() {
            @Override
            public void onTransferOffered(FileTransfer transfer) {
                offeredTransferRef.set(transfer);
                offerLatch.countDown();
            }

            @Override
            public void onTransferInterrupted(FileTransfer transfer, String reason) {
                interruptLatch.countDown();
            }
        });

        CountDownLatch chunk0Delivered = new CountDownLatch(1);
        CountDownLatch blockFurtherChunks = new CountDownLatch(1);

        clientConnection.registerHandler(MessageType.CHUNK_REQUEST, packet -> {
            try {
                com.landrop.transfer.messages.ChunkRequestMessage req =
                        com.landrop.transfer.messages.ChunkRequestMessage.fromBytes(packet.getPayload());
                if (req.getChunkIndex() == 0) {
                    byte[] buf = new byte[chunkSize];
                    try (java.nio.channels.FileChannel fc = java.nio.channels.FileChannel.open(sourceFile, java.nio.file.StandardOpenOption.READ)) {
                        fc.read(java.nio.ByteBuffer.wrap(buf), 0);
                    }
                    byte[] payload = com.landrop.transfer.messages.ChunkResponseMessage.encode(req.getTransferId(), 0, buf, chunkSize);
                    long reqId = clientConnection.getRequestIdGenerator().nextId();
                    clientConnection.send(Packet.of(MessageType.CHUNK_RESPONSE, reqId, payload));
                    chunk0Delivered.countDown();
                } else {
                    blockFurtherChunks.await(5, TimeUnit.SECONDS);
                }
            } catch (Exception ignored) {
            }
        });

        // 1. Peer A offers file
        FileTransfer outgoingTransfer = senderService.offerFile(sourceFile.toFile());
        assertNotNull(outgoingTransfer);

        assertTrue(offerLatch.await(5, TimeUnit.SECONDS));
        FileTransfer incomingTransfer = offeredTransferRef.get();
        assertNotNull(incomingTransfer);

        // 2. Peer B accepts transfer
        receiverService.acceptTransfer(incomingTransfer.getTransferId());

        // 3. Wait until chunk 0 is delivered, then forcibly sever the TCP connection
        assertTrue(chunk0Delivered.await(5, TimeUnit.SECONDS), "Chunk 0 must be delivered");
        long deadline = System.currentTimeMillis() + 3000;
        while (incomingTransfer.getCompletedChunkCount() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, incomingTransfer.getCompletedChunkCount(), "Exactly 1 chunk should be completed before severance");

        // Sever the connection
        clientConnection.close();
        serverConnection.close();
        blockFurtherChunks.countDown();

        // 4. Verify INTERRUPTED state
        assertTrue(interruptLatch.await(5, TimeUnit.SECONDS));
        assertEquals(FileTransferStatus.INTERRUPTED, incomingTransfer.getStatus());

        // Verify partial file is preserved!
        Path tempPartPath = downloadDir.resolve(incomingTransfer.getFileName() + ".landrop.part");
        assertTrue(Files.exists(tempPartPath), "Partial file .landrop.part must survive connection loss!");
        assertTrue(Files.size(tempPartPath) > 0, "Partial file must contain transferred data!");

        // 5. Reconnect new PeerConnections
        CountDownLatch acceptLatch2 = new CountDownLatch(1);
        AtomicReference<PeerConnection> scRef2 = new AtomicReference<>();

        Thread serverThread2 = new Thread(() -> {
            try {
                PeerConnection sc = serverListener.acceptConnection();
                scRef2.set(sc);
                sc.setLocalPeerName("Peer B");
                sc.start();
                acceptLatch2.countDown();
            } catch (Exception ignored) {
            }
        });
        serverThread2.start();

        PeerConnection clientConn2 = PeerConnection.connectTo("localhost", listenerPort);
        clientConn2.setLocalPeerName("Peer A");
        clientConn2.start();

        assertTrue(acceptLatch2.await(5, TimeUnit.SECONDS));
        PeerConnection serverConn2 = scRef2.get();

        clientConn2.initiateHandshake("Peer A");
        assertTrue(clientConn2.awaitReady(5, TimeUnit.SECONDS));
        assertTrue(serverConn2.awaitReady(5, TimeUnit.SECONDS));

        // Attach new connections to existing transfer services
        senderService.attachConnection(clientConn2);
        receiverService.attachConnection(serverConn2);

        CountDownLatch completeLatch = new CountDownLatch(1);
        CountDownLatch resumingLatch = new CountDownLatch(1);

        receiverService.addListener(new FileTransferListener() {
            @Override
            public void onTransferResuming(FileTransfer transfer, int fromChunk, int totalChunks) {
                resumingLatch.countDown();
            }

            @Override
            public void onTransferCompleted(FileTransfer transfer) {
                completeLatch.countDown();
            }
        });

        // 6. Resume transfer
        receiverService.resumeTransfer(incomingTransfer.getTransferId());
        assertTrue(resumingLatch.await(5, TimeUnit.SECONDS));

        // 7. Verify transfer completes and hashes match
        assertTrue(completeLatch.await(10, TimeUnit.SECONDS));
        assertEquals(FileTransferStatus.COMPLETED, incomingTransfer.getStatus());
        assertTrue(incomingTransfer.isHashVerified());
        assertEquals(expectedHash, incomingTransfer.getVerifiedSha256());

        // Verify partial file was cleanly renamed to final destination
        assertFalse(Files.exists(tempPartPath), ".part file must be removed after successful verification");
        Path finalFile = incomingTransfer.getLocalPath();
        assertNotNull(finalFile);
        assertTrue(Files.exists(finalFile));
        assertEquals(fileSize, Files.size(finalFile));
        assertArrayEquals(originalBytes, Files.readAllBytes(finalFile));

        clientConn2.close();
        serverConn2.close();
    }

    @Test
    @DisplayName("Scenario E: Metadata mismatch during resume -> Rejected by sender")
    void testResumeMetadataMismatchRejected(@TempDir Path tempDir) throws Exception {
        Path sourceFile = tempDir.resolve("mismatch.txt");
        Files.writeString(sourceFile, "Metadata Mismatch Test");
        String hash = FileTransferUtils.calculateSha256(sourceFile);

        FileTransferService senderService = new FileTransferService(clientConnection, "peer-a", "Peer A", tempDir, 1024);
        FileTransfer tx = senderService.offerFile(sourceFile.toFile());

        // Send a RESUME_REQUEST with a wrong file size
        ResumeRequestMessage badRequest = new ResumeRequestMessage(
                tx.getTransferId(),
                tx.getFileName(),
                999999L, // wrong size!
                1024,
                FileChunk.calculateTotalChunks(999999L, 1024),
                hash,
                ""
        );

        CountDownLatch respLatch = new CountDownLatch(1);
        AtomicReference<ResumeResponseMessage> respRef = new AtomicReference<>();

        serverConnection.registerHandler(MessageType.RESUME_RESPONSE, packet -> {
            try {
                respRef.set(ResumeResponseMessage.fromBytes(packet.getPayload()));
                respLatch.countDown();
            } catch (Exception ignored) {
            }
        });

        long reqId = serverConnection.getRequestIdGenerator().nextId();
        serverConnection.send(Packet.of(MessageType.RESUME_REQUEST, reqId, badRequest.toBytes()));

        assertTrue(respLatch.await(5, TimeUnit.SECONDS));
        ResumeResponseMessage resp = respRef.get();
        assertNotNull(resp);
        assertFalse(resp.isAccepted());
        assertEquals(ResumeResponseMessage.REASON_FILE_SIZE_MISMATCH, resp.getReason());
    }

    @Test
    @DisplayName("Scenario F: Source file modified during interruption -> Rejected by sender")
    void testResumeSourceFileModifiedRejected(@TempDir Path tempDir) throws Exception {
        Path sourceFile = tempDir.resolve("source_mod.txt");
        Files.writeString(sourceFile, "Original Content Before Interruption");

        FileTransferService senderService = new FileTransferService(clientConnection, "peer-a", "Peer A", tempDir, 1024);
        FileTransfer tx = senderService.offerFile(sourceFile.toFile());

        // Modify source file content during interruption!
        Files.writeString(sourceFile, "TAMPERED CONTENT AFTER INTERRUPTION!");

        ResumeRequestMessage req = ResumeRequestMessage.of(tx, new BitSet());

        CountDownLatch respLatch = new CountDownLatch(1);
        AtomicReference<ResumeResponseMessage> respRef = new AtomicReference<>();

        serverConnection.registerHandler(MessageType.RESUME_RESPONSE, packet -> {
            try {
                respRef.set(ResumeResponseMessage.fromBytes(packet.getPayload()));
                respLatch.countDown();
            } catch (Exception ignored) {
            }
        });

        long reqId = serverConnection.getRequestIdGenerator().nextId();
        serverConnection.send(Packet.of(MessageType.RESUME_REQUEST, reqId, req.toBytes()));

        assertTrue(respLatch.await(5, TimeUnit.SECONDS));
        ResumeResponseMessage resp = respRef.get();
        assertNotNull(resp);
        assertFalse(resp.isAccepted());
        assertEquals(ResumeResponseMessage.REASON_SOURCE_FILE_CHANGED, resp.getReason());
    }

    @Test
    @DisplayName("Scenario G: Reconnect fails after MAX_RECONNECT_ATTEMPTS -> FAILED")
    void testReconnectExhaustedFailsTransfer(@TempDir Path tempDir) throws Exception {
        Path downloadDir = tempDir.resolve("downloads");
        Files.createDirectories(downloadDir);

        FileTransferService receiverService = new FileTransferService(serverConnection, "peer-b", "Peer B", downloadDir, 1024);
        // Configure unreachable target port and very short delays for fast testing
        receiverService.setRemoteEndpoint("127.0.0.1", 59999); // unreachable port
        receiverService.setReconnectDelaysMs(new long[]{ 20L, 20L, 20L, 20L, 20L });
        receiverService.setAutoReconnectEnabled(true);

        FileTransfer transfer = new FileTransfer(
                "tx-exhaust",
                "test.bin",
                2048,
                FileTransferDirection.INCOMING,
                "peer-a",
                "Peer A",
                downloadDir.resolve("test.bin"),
                1024,
                2,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
        transfer.setStatus(FileTransferStatus.TRANSFERRING);

        // Put transfer into receiverService
        receiverService.registerTransfer(transfer);

        CountDownLatch failLatch = new CountDownLatch(1);
        receiverService.addListener(new FileTransferListener() {
            @Override
            public void onTransferFailed(FileTransfer t, String reason) {
                if (t.getTransferId().equals("tx-exhaust")) {
                    failLatch.countDown();
                }
            }
        });

        // Close connection to trigger interruption and reconnect loop
        serverConnection.close();

        // Transfer should transition to INTERRUPTED -> RECONNECTING -> FAILED
        assertTrue(failLatch.await(15, TimeUnit.SECONDS));
        assertEquals(FileTransferStatus.FAILED, transfer.getStatus());
        assertTrue(transfer.getErrorMessage().contains("after 5 attempts") || transfer.getErrorMessage().contains("Failed to reconnect"));
    }

    @Test
    @DisplayName("Scenario C: Chunk fails once -> Retry chunk -> Success")
    void testChunkRetryTransientFailureSuccess(@TempDir Path tempDir) throws Exception {
        Path sourceFile = tempDir.resolve("retry_file.txt");
        String content = "LANDrop Per-Chunk Retry Test Content for Phase 8 Verification!";
        Files.writeString(sourceFile, content);

        Path downloadDir = tempDir.resolve("downloads");
        Files.createDirectories(downloadDir);

        FileTransferService senderService = new FileTransferService(clientConnection, "peer-a", "Peer A", tempDir, 16);
        FileTransferService receiverService = new FileTransferService(serverConnection, "peer-b", "Peer B", downloadDir, 16);
        receiverService.setChunkTimeoutMs(600); // 600ms timeout for test speed
        receiverService.setAutoReconnectEnabled(false);

        // Intercept chunk request on client connection to drop the first request for chunk 0
        java.util.concurrent.atomic.AtomicInteger chunk0RequestCount = new java.util.concurrent.atomic.AtomicInteger(0);
        CountDownLatch retryLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(1);

        receiverService.addListener(new FileTransferListener() {
            @Override
            public void onTransferChunkRetrying(FileTransfer transfer, int chunkIndex, int attempt, int maxAttempts) {
                if (chunkIndex == 0 && attempt == 2) {
                    retryLatch.countDown();
                }
            }

            @Override
            public void onTransferCompleted(FileTransfer transfer) {
                completeLatch.countDown();
            }
        });

        CountDownLatch offerLatch = new CountDownLatch(1);
        AtomicReference<FileTransfer> incomingRef = new AtomicReference<>();
        receiverService.addListener(new FileTransferListener() {
            @Override
            public void onTransferOffered(FileTransfer transfer) {
                incomingRef.set(transfer);
                offerLatch.countDown();
            }
        });

        // Intercept clientConnection CHUNK_REQUEST handler: drop first request
        clientConnection.registerHandler(MessageType.CHUNK_REQUEST, packet -> {
            try {
                com.landrop.transfer.messages.ChunkRequestMessage msg =
                        com.landrop.transfer.messages.ChunkRequestMessage.fromBytes(packet.getPayload());
                if (msg.getChunkIndex() == 0 && chunk0RequestCount.incrementAndGet() == 1) {
                    // Drop first request to simulate packet loss / timeout
                    return;
                }
                // Handle normally on subsequent requests
                long offset = FileChunk.calculateChunkOffset(msg.getChunkIndex(), 16);
                int len = FileChunk.calculateChunkLength(msg.getChunkIndex(),
                        FileChunk.calculateTotalChunks(Files.size(sourceFile), 16), 16, Files.size(sourceFile));
                byte[] buf = new byte[len];
                try (java.nio.channels.FileChannel fc = java.nio.channels.FileChannel.open(sourceFile)) {
                    fc.read(java.nio.ByteBuffer.wrap(buf), offset);
                }
                byte[] payload = com.landrop.transfer.messages.ChunkResponseMessage.encode(msg.getTransferId(), msg.getChunkIndex(), buf, len);
                long reqId = clientConnection.getRequestIdGenerator().nextId();
                clientConnection.send(Packet.of(MessageType.CHUNK_RESPONSE, reqId, payload));
            } catch (Exception ignored) {
            }
        });

        senderService.offerFile(sourceFile.toFile());
        assertTrue(offerLatch.await(5, TimeUnit.SECONDS));

        receiverService.acceptTransfer(incomingRef.get().getTransferId());

        // Verify chunk retry was triggered
        assertTrue(retryLatch.await(5, TimeUnit.SECONDS));

        // Verify transfer completes successfully after retry
        assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
        assertEquals(FileTransferStatus.COMPLETED, incomingRef.get().getStatus());
        assertTrue(incomingRef.get().isHashVerified());
        assertTrue(chunk0RequestCount.get() >= 2);
    }

    @Test
    @DisplayName("Scenario D: Chunk fails three times -> Transfer fails safely")
    void testChunkRetryExhaustedFailure(@TempDir Path tempDir) throws Exception {
        Path sourceFile = tempDir.resolve("fail_file.txt");
        Files.writeString(sourceFile, "Chunk Retry Exhaustion Test");

        Path downloadDir = tempDir.resolve("downloads");
        Files.createDirectories(downloadDir);

        FileTransferService senderService = new FileTransferService(clientConnection, "peer-a", "Peer A", tempDir, 16);
        FileTransferService receiverService = new FileTransferService(serverConnection, "peer-b", "Peer B", downloadDir, 16);
        receiverService.setChunkTimeoutMs(300); // Fast timeout
        receiverService.setAutoReconnectEnabled(false);

        // Drop ALL chunk requests on clientConnection
        clientConnection.registerHandler(MessageType.CHUNK_REQUEST, packet -> {
            // Drop silently to simulate repeated failure
        });

        CountDownLatch offerLatch = new CountDownLatch(1);
        CountDownLatch failLatch = new CountDownLatch(1);
        AtomicReference<FileTransfer> incomingRef = new AtomicReference<>();

        receiverService.addListener(new FileTransferListener() {
            @Override
            public void onTransferOffered(FileTransfer transfer) {
                incomingRef.set(transfer);
                offerLatch.countDown();
            }

            @Override
            public void onTransferFailed(FileTransfer transfer, String reason) {
                failLatch.countDown();
            }
        });

        senderService.offerFile(sourceFile.toFile());
        assertTrue(offerLatch.await(5, TimeUnit.SECONDS));

        receiverService.acceptTransfer(incomingRef.get().getTransferId());

        // Transfer should fail safely after 3 failed attempts
        assertTrue(failLatch.await(5, TimeUnit.SECONDS));
        FileTransfer transfer = incomingRef.get();
        assertEquals(FileTransferStatus.FAILED, transfer.getStatus());
        assertTrue(transfer.getErrorMessage().contains("after 3 attempts"));
    }
}
