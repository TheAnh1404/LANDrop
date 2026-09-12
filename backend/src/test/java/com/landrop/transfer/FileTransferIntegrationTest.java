package com.landrop.transfer;

import com.landrop.chat.ChatListener;
import com.landrop.chat.ChatMessage;
import com.landrop.chat.ChatService;
import com.landrop.peer.PeerConnection;
import com.landrop.peer.PeerListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class FileTransferIntegrationTest {

    private PeerListener serverListener;
    private PeerConnection serverConnection;
    private PeerConnection clientConnection;

    @BeforeEach
    void setUp() throws Exception {
        serverListener = new PeerListener(0);
        int port = serverListener.getPort();

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

        clientConnection = PeerConnection.connectTo("localhost", port);
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
            try {
                clientConnection.close();
            } catch (Exception ignored) {
            }
        }
        if (serverConnection != null) {
            try {
                serverConnection.close();
            } catch (Exception ignored) {
            }
        }
        if (serverListener != null) {
            try {
                serverListener.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    @DisplayName("End-to-end: Peer A sends small text file to Peer B successfully")
    void testTransferSmallFile(@TempDir Path tempDir) throws Exception {
        Path sourceFile = tempDir.resolve("source.txt");
        String originalContent = "LANDrop Phase 6 — Direct P2P File Transfer Test Content!";
        Files.writeString(sourceFile, originalContent, StandardCharsets.UTF_8);

        Path downloadDir = tempDir.resolve("downloads");
        Files.createDirectories(downloadDir);

        FileTransferService serviceA = new FileTransferService(clientConnection, "peer-a", "Peer A");
        FileTransferService serviceB = new FileTransferService(serverConnection, "peer-b", "Peer B", downloadDir);

        CountDownLatch offerLatch = new CountDownLatch(1);
        CountDownLatch completeLatchA = new CountDownLatch(1);
        CountDownLatch completeLatchB = new CountDownLatch(1);
        AtomicReference<FileTransfer> incomingTransferRef = new AtomicReference<>();

        serviceB.addListener(new FileTransferListener() {
            @Override
            public void onTransferOffered(FileTransfer transfer) {
                incomingTransferRef.set(transfer);
                offerLatch.countDown();
            }

            @Override
            public void onTransferCompleted(FileTransfer transfer) {
                completeLatchB.countDown();
            }
        });

        serviceA.addListener(new FileTransferListener() {
            @Override
            public void onTransferCompleted(FileTransfer transfer) {
                completeLatchA.countDown();
            }
        });

        // 1. Peer A offers file
        FileTransfer outgoing = serviceA.offerFile(sourceFile.toFile());
        assertNotNull(outgoing);
        assertEquals(FileTransferDirection.OUTGOING, outgoing.getDirection());

        // 2. Peer B receives offer
        assertTrue(offerLatch.await(5, TimeUnit.SECONDS), "Peer B should receive FILE_OFFER");
        FileTransfer incoming = incomingTransferRef.get();
        assertNotNull(incoming);
        assertEquals(outgoing.getTransferId(), incoming.getTransferId());
        assertEquals("source.txt", incoming.getFileName());
        assertEquals(sourceFile.toFile().length(), incoming.getFileSize());

        // 3. Peer B accepts
        serviceB.acceptTransfer(incoming.getTransferId());

        // 4. Wait for both sides to complete
        assertTrue(completeLatchB.await(5, TimeUnit.SECONDS), "Peer B should complete transfer");
        assertTrue(completeLatchA.await(5, TimeUnit.SECONDS), "Peer A should complete transfer");

        assertEquals(FileTransferStatus.COMPLETED, outgoing.getStatus());
        assertEquals(FileTransferStatus.COMPLETED, incoming.getStatus());

        // 5. Verify received file content
        assertNotNull(incoming.getLocalPath());
        assertTrue(Files.exists(incoming.getLocalPath()));
        assertEquals(sourceFile.toFile().length(), Files.size(incoming.getLocalPath()));
        assertEquals(originalContent, Files.readString(incoming.getLocalPath(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("End-to-end: Peer A sends zero-byte file to Peer B")
    void testTransferZeroByteFile(@TempDir Path tempDir) throws Exception {
        Path emptyFile = tempDir.resolve("empty.txt");
        Files.createFile(emptyFile);

        Path downloadDir = tempDir.resolve("downloads");
        Files.createDirectories(downloadDir);

        FileTransferService serviceA = new FileTransferService(clientConnection, "peer-a", "Peer A");
        FileTransferService serviceB = new FileTransferService(serverConnection, "peer-b", "Peer B", downloadDir);

        CountDownLatch offerLatch = new CountDownLatch(1);
        CountDownLatch completeLatchA = new CountDownLatch(1);
        CountDownLatch completeLatchB = new CountDownLatch(1);
        AtomicReference<FileTransfer> incomingRef = new AtomicReference<>();

        serviceB.addListener(new FileTransferListener() {
            @Override
            public void onTransferOffered(FileTransfer transfer) {
                incomingRef.set(transfer);
                offerLatch.countDown();
            }

            @Override
            public void onTransferCompleted(FileTransfer transfer) {
                completeLatchB.countDown();
            }
        });

        serviceA.addListener(new FileTransferListener() {
            @Override
            public void onTransferCompleted(FileTransfer transfer) {
                completeLatchA.countDown();
            }
        });

        FileTransfer outgoing = serviceA.offerFile(emptyFile.toFile());
        assertTrue(offerLatch.await(5, TimeUnit.SECONDS));

        serviceB.acceptTransfer(incomingRef.get().getTransferId());

        assertTrue(completeLatchB.await(5, TimeUnit.SECONDS));
        assertTrue(completeLatchA.await(5, TimeUnit.SECONDS));

        assertEquals(FileTransferStatus.COMPLETED, outgoing.getStatus());
        assertEquals(FileTransferStatus.COMPLETED, incomingRef.get().getStatus());
        assertTrue(Files.exists(incomingRef.get().getLocalPath()));
        assertEquals(0, Files.size(incomingRef.get().getLocalPath()));
    }

    @Test
    @DisplayName("End-to-end: Multi-chunk file transfer (200 KB) with integrity verification")
    void testTransferMultiChunkFile(@TempDir Path tempDir) throws Exception {
        int fileSize = 200 * 1024; // 200 KB -> 4 chunks (64KB each)
        byte[] data = new byte[fileSize];
        new Random(42).nextBytes(data);

        Path largeFile = tempDir.resolve("binary_data.bin");
        Files.write(largeFile, data);

        Path downloadDir = tempDir.resolve("downloads");
        Files.createDirectories(downloadDir);

        FileTransferService serviceA = new FileTransferService(clientConnection, "peer-a", "Peer A");
        FileTransferService serviceB = new FileTransferService(serverConnection, "peer-b", "Peer B", downloadDir);

        CountDownLatch offerLatch = new CountDownLatch(1);
        CountDownLatch completeLatchA = new CountDownLatch(1);
        CountDownLatch completeLatchB = new CountDownLatch(1);
        AtomicReference<FileTransfer> incomingRef = new AtomicReference<>();

        serviceB.addListener(new FileTransferListener() {
            @Override
            public void onTransferOffered(FileTransfer transfer) {
                incomingRef.set(transfer);
                offerLatch.countDown();
            }

            @Override
            public void onTransferCompleted(FileTransfer transfer) {
                completeLatchB.countDown();
            }
        });

        serviceA.addListener(new FileTransferListener() {
            @Override
            public void onTransferCompleted(FileTransfer transfer) {
                completeLatchA.countDown();
            }
        });

        FileTransfer outgoing = serviceA.offerFile(largeFile.toFile());
        assertTrue(offerLatch.await(5, TimeUnit.SECONDS));

        serviceB.acceptTransfer(incomingRef.get().getTransferId());

        assertTrue(completeLatchB.await(10, TimeUnit.SECONDS));
        assertTrue(completeLatchA.await(10, TimeUnit.SECONDS));

        Path dest = incomingRef.get().getLocalPath();
        assertNotNull(dest);
        assertTrue(Files.exists(dest));
        assertEquals(fileSize, Files.size(dest));
        assertArrayEquals(data, Files.readAllBytes(dest));
    }

    @Test
    @DisplayName("End-to-end: Transfer rejection flow")
    void testTransferRejection(@TempDir Path tempDir) throws Exception {
        Path testFile = tempDir.resolve("to_reject.txt");
        Files.writeString(testFile, "Reject me");

        FileTransferService serviceA = new FileTransferService(clientConnection, "peer-a", "Peer A");
        FileTransferService serviceB = new FileTransferService(serverConnection, "peer-b", "Peer B", tempDir);

        CountDownLatch offerLatch = new CountDownLatch(1);
        CountDownLatch rejectLatchA = new CountDownLatch(1);
        AtomicReference<FileTransfer> incomingRef = new AtomicReference<>();
        AtomicReference<String> rejectReasonRef = new AtomicReference<>();

        serviceB.addListener(new FileTransferListener() {
            @Override
            public void onTransferOffered(FileTransfer transfer) {
                incomingRef.set(transfer);
                offerLatch.countDown();
            }
        });

        serviceA.addListener(new FileTransferListener() {
            @Override
            public void onTransferRejected(FileTransfer transfer, String reason) {
                rejectReasonRef.set(reason);
                rejectLatchA.countDown();
            }
        });

        FileTransfer outgoing = serviceA.offerFile(testFile.toFile());
        assertTrue(offerLatch.await(5, TimeUnit.SECONDS));

        serviceB.rejectTransfer(incomingRef.get().getTransferId(), "Not interested");

        assertTrue(rejectLatchA.await(5, TimeUnit.SECONDS));
        assertEquals(FileTransferStatus.REJECTED, outgoing.getStatus());
        assertEquals("Not interested", rejectReasonRef.get());
    }

    @Test
    @DisplayName("End-to-end: Chat messages can be sent and received during active file streaming")
    void testChatConcurrentWithFileTransfer(@TempDir Path tempDir) throws Exception {
        int fileSize = 500 * 1024; // 500 KB
        byte[] data = new byte[fileSize];
        new Random(99).nextBytes(data);

        Path largeFile = tempDir.resolve("stream_test.bin");
        Files.write(largeFile, data);

        Path downloadDir = tempDir.resolve("downloads");
        Files.createDirectories(downloadDir);

        FileTransferService transferServiceA = new FileTransferService(clientConnection, "peer-a", "Peer A");
        FileTransferService transferServiceB = new FileTransferService(serverConnection, "peer-b", "Peer B", downloadDir);

        ChatService chatA = new ChatService(clientConnection, "Peer A");
        ChatService chatB = new ChatService(serverConnection, "Peer B");

        CountDownLatch offerLatch = new CountDownLatch(1);
        CountDownLatch transferCompleteLatch = new CountDownLatch(1);
        CountDownLatch chatReceivedLatch = new CountDownLatch(1);
        AtomicReference<FileTransfer> incomingRef = new AtomicReference<>();
        AtomicReference<String> chatMsgRef = new AtomicReference<>();

        transferServiceB.addListener(new FileTransferListener() {
            @Override
            public void onTransferOffered(FileTransfer transfer) {
                incomingRef.set(transfer);
                offerLatch.countDown();
            }

            @Override
            public void onTransferCompleted(FileTransfer transfer) {
                transferCompleteLatch.countDown();
            }
        });

        chatB.addListener(new ChatListener() {
            @Override
            public void onMessageReceived(ChatMessage message) {
                chatMsgRef.set(message.content());
                chatReceivedLatch.countDown();
            }
        });

        // 1. Offer and accept file
        transferServiceA.offerFile(largeFile.toFile());
        assertTrue(offerLatch.await(5, TimeUnit.SECONDS));
        transferServiceB.acceptTransfer(incomingRef.get().getTransferId());

        // 2. Concurrently send a chat message while file transfer is active
        chatA.sendMessage("Concurrent chat message during transfer!");

        // 3. Verify chat is received without delay
        assertTrue(chatReceivedLatch.await(5, TimeUnit.SECONDS), "Chat message should be received while file is transferring");
        assertEquals("Concurrent chat message during transfer!", chatMsgRef.get());

        // 4. Verify file transfer also completes successfully
        assertTrue(transferCompleteLatch.await(10, TimeUnit.SECONDS), "File transfer should complete successfully");
        assertEquals(fileSize, Files.size(incomingRef.get().getLocalPath()));
        assertArrayEquals(data, Files.readAllBytes(incomingRef.get().getLocalPath()));
    }
}
