package com.landrop.transfer;

import com.landrop.peer.PeerConnection;
import com.landrop.protocol.MessageType;
import com.landrop.protocol.Packet;
import com.landrop.transfer.messages.ChunkResponseMessage;
import com.landrop.transfer.messages.FileQueryMessage;
import com.landrop.transfer.messages.FileQueryResponseMessage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates a multi-peer download session across multiple source peers.
 * Handles peer discovery queries, worker lifecycle, chunk scheduling,
 * file assembly via positional FileChannel writes, and SHA-256 integrity verification.
 */
public class MultiPeerDownloadCoordinator implements AutoCloseable {

    private final MultiPeerDownload download;
    private final Map<String, PeerConnection> peerConnections = new ConcurrentHashMap<>();
    private final Map<String, PeerDownloadWorker> workers = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<ChunkResponseMessage>> pendingChunkResponses = new ConcurrentHashMap<>();

    private final CompletableFuture<MultiPeerDownload> completionFuture = new CompletableFuture<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Object scheduleLock = new Object();

    private volatile FileChannel partialFileChannel;

    public MultiPeerDownloadCoordinator(MultiPeerDownload download) {
        this.download = Objects.requireNonNull(download, "download cannot be null");
    }

    public MultiPeerDownload getDownload() {
        return download;
    }

    public CompletableFuture<MultiPeerDownload> getCompletionFuture() {
        return completionFuture;
    }

    /**
     * Queries a peer for file availability using FILE_QUERY.
     *
     * @param connection active PeerConnection to the peer
     * @param timeoutMs query timeout in milliseconds
     * @return CompletableFuture resolving to FileQueryResponseMessage
     */
    public CompletableFuture<FileQueryResponseMessage> queryFileAvailability(PeerConnection connection, long timeoutMs) {
        CompletableFuture<FileQueryResponseMessage> future = new CompletableFuture<>();

        try {
            connection.registerHandler(MessageType.FILE_QUERY_RESPONSE, packet -> {
                try {
                    FileQueryResponseMessage resp = FileQueryResponseMessage.fromBytes(packet.getPayload());
                    if (Objects.equals(resp.getQueryId(), download.getTransferId())) {
                        future.complete(resp);
                    }
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });

            FileQueryMessage query = new FileQueryMessage(
                    download.getTransferId(),
                    download.getFileName(),
                    download.getFileSize(),
                    download.getExpectedSha256(),
                    connection.getLocalPeerName(),
                    connection.getLocalPeerName()
            );

            long reqId = connection.getRequestIdGenerator().nextId();
            connection.send(Packet.of(MessageType.FILE_QUERY, reqId, query.toBytes()));

            // Schedule timeout
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(timeoutMs);
                    if (!future.isDone()) {
                        future.complete(FileQueryResponseMessage.unavailable(download.getTransferId(), "QUERY_TIMEOUT"));
                    }
                } catch (InterruptedException ignored) {
                }
            });

        } catch (Exception e) {
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * Adds an active source peer to this download session.
     *
     * @param peerState state tracker for this peer
     * @param connection active PeerConnection
     */
    public synchronized void addSourcePeer(PeerDownloadState peerState, PeerConnection connection) {
        Objects.requireNonNull(peerState, "peerState cannot be null");
        Objects.requireNonNull(connection, "connection cannot be null");

        String peerId = peerState.getPeerId();
        peerConnections.put(peerId, connection);

        // Register CHUNK_RESPONSE handler to route responses into pendingChunkResponses
        connection.registerHandler(MessageType.CHUNK_RESPONSE, packet -> {
            try {
                ChunkResponseMessage chunkMsg = ChunkResponseMessage.decode(packet.getPayload());
                String key = chunkMsg.transferId() + ":" + chunkMsg.chunkIndex();
                CompletableFuture<ChunkResponseMessage> cf = pendingChunkResponses.get(key);
                if (cf != null) {
                    cf.complete(chunkMsg);
                }
            } catch (Exception e) {
                System.err.println("[COORDINATOR] Error decoding CHUNK_RESPONSE: " + e.getMessage());
            }
        });

        synchronized (scheduleLock) {
            // 1. If download already in progress, start worker for this newly added peer FIRST
            if (partialFileChannel != null && partialFileChannel.isOpen() && running.get()) {
                PeerDownloadWorker worker = new PeerDownloadWorker(
                        download,
                        peerState,
                        connection,
                        partialFileChannel,
                        pendingChunkResponses,
                        this::scheduleWork
                );
                workers.put(peerId, worker);
                worker.start();
            }

            // 2. Register source peer only after worker is present in workers map
            download.registerSourcePeer(peerState);

            // 3. Schedule next assignments with the newly added peer ready
            if (partialFileChannel != null && partialFileChannel.isOpen() && running.get()) {
                scheduleWorkInternal();
            }
        }
    }

    /**
     * Starts the multi-peer download process across all registered source peers.
     */
    public synchronized void start() throws IOException {
        if (!running.get() || download.isTerminal()) {
            return;
        }

        download.setStatus(MultiPeerDownloadStatus.PREPARING);

        List<PeerDownloadState> connectedPeers = download.getConnectedSourcePeers();
        if (connectedPeers.isEmpty()) {
            fail("No available source peers to download file");
            return;
        }

        // 1. Prepare download directory and partial file (.landrop.part)
        Path partialPath = download.getPartialFilePath();
        if (partialPath.getParent() != null) {
            Files.createDirectories(partialPath.getParent());
        }

        this.partialFileChannel = FileChannel.open(
                partialPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE
        );

        // Preallocate file size if file is non-empty
        if (download.getFileSize() > 0) {
            partialFileChannel.truncate(download.getFileSize());
        }

        download.setStatus(MultiPeerDownloadStatus.DOWNLOADING);
        System.out.println("[MULTI-PEER] Download started transferId=" + download.getTransferId() +
                " sources=" + connectedPeers.size() + " totalChunks=" + download.getTotalChunks());

        synchronized (scheduleLock) {
            // 2. Start a worker for each connected source peer
            for (PeerDownloadState peer : connectedPeers) {
                PeerConnection conn = peerConnections.get(peer.getPeerId());
                if (conn != null && conn.isConnected()) {
                    PeerDownloadWorker worker = new PeerDownloadWorker(
                            download,
                            peer,
                            conn,
                            partialFileChannel,
                            pendingChunkResponses,
                            this::scheduleWork
                    );
                    workers.put(peer.getPeerId(), worker);
                    worker.start();
                }
            }

            // Special case: 0-byte file completes immediately
            if (download.getFileSize() == 0 || download.getTotalChunks() == 0) {
                finalizeAndVerify();
                return;
            }

            // 3. Initial chunk scheduling
            scheduleWorkInternal();
        }
    }

    /**
     * Schedules the next batch of chunk assignments across available workers.
     * Called whenever a chunk completes, fails, or a peer connects/disconnects.
     */
    public void scheduleWork() {
        if (!running.get() || download.isTerminal()) {
            return;
        }

        synchronized (scheduleLock) {
            scheduleWorkInternal();
        }
    }

    private void scheduleWorkInternal() {
        if (!running.get() || download.isTerminal()) {
            return;
        }

        ChunkManager chunkManager = download.getChunkManager();
        ChunkScheduler chunkScheduler = download.getChunkScheduler();

        // Check if all chunks are completed!
        if (chunkManager.isComplete()) {
            finalizeAndVerify();
            return;
        }

        // Check if all peers have disconnected
        List<PeerDownloadState> connected = download.getConnectedSourcePeers();
        if (connected.isEmpty()) {
            fail("All source peers disconnected before download completed");
            return;
        }

        // Distribute missing chunks across available workers
        List<ChunkScheduler.ChunkAssignment> assignments =
                chunkScheduler.scheduleNextAssignments(chunkManager, download.getSourcePeers());

        for (ChunkScheduler.ChunkAssignment assignment : assignments) {
            PeerDownloadWorker worker = workers.get(assignment.peerId());
            boolean enqueued = false;
            if (worker != null) {
                enqueued = worker.enqueueChunk(assignment.chunkIndex());
                if (enqueued && com.landrop.util.AppLogger.isDebugEnabled()) {
                    com.landrop.util.AppLogger.debug("MULTI-PEER", "Assigned chunk " + assignment.chunkIndex() + " to peer " + assignment.peerId());
                }
            }

            if (!enqueued) {
                // Safeguard: rollback assignment so the chunk is not orphaned in REQUESTED state
                chunkManager.markFailed(assignment.chunkIndex(), assignment.peerId());
                PeerDownloadState peer = download.getSourcePeer(assignment.peerId());
                if (peer != null) {
                    peer.decrementActiveChunks();
                }
            }
        }
    }

    /**
     * Verifies SHA-256 integrity and renames partial file to final destination.
     */
    private void finalizeAndVerify() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        Thread.ofVirtual().name("multi-peer-verify-" + download.getTransferId()).start(() -> {
            download.setStatus(MultiPeerDownloadStatus.VERIFYING);
            System.out.println("[VERIFY] Multi-peer SHA-256 verification started for " + download.getFileName());

            for (MultiPeerDownloadListener l : download.getListeners()) {
                try {
                    l.onVerifying(download);
                } catch (Exception ignored) {
                }
            }

            closeWorkers();
            closeChannel();

            try {
                Path partialPath = download.getPartialFilePath();
                if (!Files.exists(partialPath)) {
                    fail("Partial file missing at verification: " + partialPath);
                    return;
                }

                long actualSize = Files.size(partialPath);
                if (actualSize != download.getFileSize()) {
                    try {
                        Files.deleteIfExists(partialPath);
                    } catch (Exception ignored) {}
                    fail("File size mismatch: expected " + download.getFileSize() + ", got " + actualSize);
                    return;
                }

                String computedHash = FileTransferUtils.calculateSha256(partialPath);
                if (!FileTransferUtils.hashesMatch(computedHash, download.getExpectedSha256())) {
                    System.err.println("[VERIFY] Hash mismatch: expected " + download.getExpectedSha256() +
                            ", got " + computedHash);
                    try {
                        Files.deleteIfExists(partialPath);
                    } catch (Exception ignored) {}
                    fail("SHA-256 hash mismatch: integrity verification failed");
                    return;
                }

                // Atomic rename to final destination file
                Path destPath = download.getDestinationPath();
                if (destPath.getParent() != null) {
                    Files.createDirectories(destPath.getParent());
                }
                Files.move(partialPath, destPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

                download.setHashVerified(true);
                download.setVerifiedSha256(computedHash);
                download.setStatus(MultiPeerDownloadStatus.COMPLETED);
                System.out.println("[VERIFY] Multi-peer SHA-256 verification successful: " + computedHash);

                for (MultiPeerDownloadListener l : download.getListeners()) {
                    try {
                        l.onCompleted(download);
                    } catch (Exception ignored) {
                    }
                }

                completionFuture.complete(download);

            } catch (Exception e) {
                fail("Verification error: " + e.getMessage());
            }
        });
    }

    private void fail(String reason) {
        closeWorkers();
        closeChannel();

        download.setErrorMessage(reason);
        download.setStatus(MultiPeerDownloadStatus.FAILED);
        System.err.println("[MULTI-PEER] Download failed: " + reason);

        for (MultiPeerDownloadListener l : download.getListeners()) {
            try {
                l.onFailed(download, reason);
            } catch (Exception ignored) {
            }
        }

        completionFuture.completeExceptionally(new IOException(reason));
    }

    public void cancel() {
        if (running.compareAndSet(true, false)) {
            closeWorkers();
            closeChannel();
            download.setStatus(MultiPeerDownloadStatus.CANCELLED);
            completionFuture.cancel(true);
        }
    }

    private void closeWorkers() {
        for (PeerDownloadWorker w : workers.values()) {
            try {
                w.close();
            } catch (Exception ignored) {
            }
        }
        workers.clear();
    }

    private void closeChannel() {
        if (partialFileChannel != null) {
            try {
                partialFileChannel.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void close() {
        cancel();
    }
}
