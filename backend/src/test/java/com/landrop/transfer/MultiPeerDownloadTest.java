package com.landrop.transfer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class MultiPeerDownloadTest {

    private static final String DUMMY_HASH = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    @DisplayName("Verify MultiPeerDownload initialization and chunk calculations")
    void testInitialization(@TempDir Path tempDir) {
        Path dest = tempDir.resolve("sample.iso");
        MultiPeerDownload dl = new MultiPeerDownload(
                "tx-1",
                "sample.iso",
                10_000_000L,
                1024 * 1024,
                DUMMY_HASH,
                dest);

        assertEquals("tx-1", dl.getTransferId());
        assertEquals("sample.iso", dl.getFileName());
        assertEquals(10_000_000L, dl.getFileSize());
        assertEquals(1024 * 1024, dl.getChunkSize());
        assertEquals(10, dl.getTotalChunks()); // ceil(10,000,000 / 1,048,576) = 10
        assertEquals(dest, dl.getDestinationPath());
        assertEquals(dest.resolveSibling("sample.iso.landrop.part"), dl.getPartialFilePath());
        assertEquals(MultiPeerDownloadStatus.PENDING, dl.getStatus());
        assertFalse(dl.isTerminal());
        assertEquals(0.0, dl.getProgressPercentage(), 0.001);
    }

    @Test
    @DisplayName("Verify status transitions and listener callbacks")
    void testStatusTransitions(@TempDir Path tempDir) {
        Path dest = tempDir.resolve("test.bin");
        MultiPeerDownload dl = new MultiPeerDownload("tx-2", "test.bin", 1000L, 100, DUMMY_HASH, dest);

        List<MultiPeerDownloadStatus> recordedTransitions = new ArrayList<>();
        dl.addListener(new MultiPeerDownloadListener() {
            @Override
            public void onStatusChanged(MultiPeerDownload download, MultiPeerDownloadStatus oldStatus,
                    MultiPeerDownloadStatus newStatus) {
                recordedTransitions.add(newStatus);
            }
        });

        dl.setStatus(MultiPeerDownloadStatus.DISCOVERING_PEERS);
        dl.setStatus(MultiPeerDownloadStatus.PREPARING);
        dl.setStatus(MultiPeerDownloadStatus.DOWNLOADING);
        dl.setStatus(MultiPeerDownloadStatus.VERIFYING);
        dl.setStatus(MultiPeerDownloadStatus.COMPLETED);

        assertEquals(List.of(
                MultiPeerDownloadStatus.DISCOVERING_PEERS,
                MultiPeerDownloadStatus.PREPARING,
                MultiPeerDownloadStatus.DOWNLOADING,
                MultiPeerDownloadStatus.VERIFYING,
                MultiPeerDownloadStatus.COMPLETED), recordedTransitions);
        assertTrue(dl.isTerminal());
    }

    @Test
    @DisplayName("Verify speed and ETA formatting utilities")
    void testSpeedAndEtaFormatting() {
        assertEquals("ETA 00:30", MultiPeerDownload.formatEta(30));
        assertEquals("ETA 02:15", MultiPeerDownload.formatEta(135));
        assertEquals("ETA 01:05:20", MultiPeerDownload.formatEta(3920));
        assertEquals("ETA --", MultiPeerDownload.formatEta(-1));

        assertEquals("0 B/s", MultiPeerDownload.formatSpeed(0));
        assertEquals("500 B/s", MultiPeerDownload.formatSpeed(500));
        assertEquals("1.5 KB/s", MultiPeerDownload.formatSpeed(1536));
        assertEquals("18.2 MB/s", MultiPeerDownload.formatSpeed(18.2 * 1024 * 1024));
        assertEquals("1.2 GB/s", MultiPeerDownload.formatSpeed(1.2 * 1024 * 1024 * 1024));
    }

    @Test
    @DisplayName("Verify concurrent positional chunk writes into single partial file")
    void testConcurrentPositionalChunkWrites(@TempDir Path tempDir) throws Exception {
        int chunkSize = 64 * 1024; // 64 KB
        int totalChunks = 8;
        int fileSize = (totalChunks - 1) * chunkSize + (32 * 1024); // 7 full chunks + 1 half chunk (32 KB)

        byte[] originalBytes = new byte[fileSize];
        new Random(42).nextBytes(originalBytes);
        String expectedHash = FileTransferUtils.calculateSha256(originalBytes);

        Path destPath = tempDir.resolve("assembled.bin");
        Path partPath = tempDir.resolve("assembled.bin.landrop.part");

        try (FileChannel channel = FileChannel.open(partPath,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {

            channel.truncate(fileSize);

            // Simulate 4 concurrent workers writing different chunks out-of-order
            ExecutorService executor = Executors.newFixedThreadPool(4);
            CountDownLatch doneLatch = new CountDownLatch(totalChunks);

            // Scramble chunk order
            List<Integer> chunkIndices = Arrays.asList(3, 0, 7, 2, 5, 1, 6, 4);

            for (int chunkIndex : chunkIndices) {
                executor.submit(() -> {
                    try {
                        long offset = FileChunk.calculateChunkOffset(chunkIndex, chunkSize);
                        int len = FileChunk.calculateChunkLength(chunkIndex, totalChunks, chunkSize, fileSize);

                        byte[] chunkData = Arrays.copyOfRange(originalBytes, (int) offset, (int) offset + len);

                        // Positional write
                        ByteBuffer buf = ByteBuffer.wrap(chunkData);
                        long current = offset;
                        while (buf.hasRemaining()) {
                            int written = channel.write(buf, current);
                            if (written <= 0)
                                break;
                            current += written;
                        }
                    } catch (IOException e) {
                        fail("Channel write failed: " + e.getMessage());
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
            executor.shutdown();
        }

        // Verify partial file size and contents
        assertEquals(fileSize, Files.size(partPath));
        byte[] readBack = Files.readAllBytes(partPath);
        assertArrayEquals(originalBytes, readBack);

        // Verify SHA-256 matches
        String computedHash = FileTransferUtils.calculateSha256(partPath);
        assertEquals(expectedHash, computedHash);
    }
}
