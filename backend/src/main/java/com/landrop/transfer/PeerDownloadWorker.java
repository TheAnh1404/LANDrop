package com.landrop.transfer;

import com.landrop.peer.ConnectionListener;
import com.landrop.peer.PeerConnection;
import com.landrop.protocol.MessageType;
import com.landrop.protocol.Packet;
import com.landrop.transfer.messages.ChunkRequestMessage;
import com.landrop.transfer.messages.ChunkResponseMessage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Worker responsible for requesting, receiving, and writing chunks from a specific peer
 * into the shared partial file via positional FileChannel writes.
 */
public class PeerDownloadWorker implements AutoCloseable {

    public static final int MAX_CHUNK_RETRIES = 3;
    public static final long DEFAULT_CHUNK_TIMEOUT_MS = 5000L;

    private final MultiPeerDownload download;
    private final PeerDownloadState peerState;
    private final PeerConnection connection;
    private final FileChannel partialFileChannel;
    private final Map<String, CompletableFuture<ChunkResponseMessage>> pendingChunkResponses;
    private final Runnable onWorkCompletedCallback;

    private final BlockingQueue<Integer> chunkQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private volatile long chunkTimeoutMs = DEFAULT_CHUNK_TIMEOUT_MS;
    private Thread workerThread;

    public PeerDownloadWorker(
            MultiPeerDownload download,
            PeerDownloadState peerState,
            PeerConnection connection,
            FileChannel partialFileChannel,
            Map<String, CompletableFuture<ChunkResponseMessage>> pendingChunkResponses,
            Runnable onWorkCompletedCallback) {
        this.download = Objects.requireNonNull(download, "download cannot be null");
        this.peerState = Objects.requireNonNull(peerState, "peerState cannot be null");
        this.connection = Objects.requireNonNull(connection, "connection cannot be null");
        this.partialFileChannel = Objects.requireNonNull(partialFileChannel, "partialFileChannel cannot be null");
        this.pendingChunkResponses = Objects.requireNonNull(pendingChunkResponses, "pendingChunkResponses cannot be null");
        this.onWorkCompletedCallback = onWorkCompletedCallback;

        // Register connection listener to detect peer disconnection
        this.connection.addListener(new ConnectionListener() {
            @Override
            public void onDisconnected() {
                handleConnectionLoss("Peer connection closed");
            }

            @Override
            public void onError(Exception error) {
                handleConnectionLoss("Peer connection error: " + (error != null ? error.getMessage() : "unknown"));
            }
        });
    }

    public void setChunkTimeoutMs(long chunkTimeoutMs) {
        this.chunkTimeoutMs = Math.max(50L, chunkTimeoutMs);
    }

    public long getChunkTimeoutMs() {
        return chunkTimeoutMs;
    }

    public boolean enqueueChunk(int chunkIndex) {
        if (running.get() && peerState.isConnected()) {
            return chunkQueue.offer(chunkIndex);
        }
        return false;
    }

    public void start() {
        workerThread = Thread.ofVirtual()
                .name("peer-worker-" + peerState.getPeerId() + "-" + download.getTransferId())
                .start(this::runWorkerLoop);
    }

    private void runWorkerLoop() {
        while (running.get() && peerState.isConnected() && !download.isTerminal()) {
            try {
                Integer chunkIndex = chunkQueue.poll(200, TimeUnit.MILLISECONDS);
                if (chunkIndex == null) {
                    continue;
                }

                downloadChunkWithRetry(chunkIndex);

                // Notify scheduler that this worker is ready for more assignments
                if (onWorkCompletedCallback != null) {
                    onWorkCompletedCallback.run();
                }

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (running.get()) {
                    System.err.println("[WORKER] Error on peer " + peerState.getPeerDisplayName() + ": " + e.getMessage());
                }
            }
        }
    }

    private void downloadChunkWithRetry(int chunkIndex) {
        // If chunk was already completed by another worker, skip writing
        if (download.getChunkManager().isCompleted(chunkIndex)) {
            peerState.decrementActiveChunks();
            return;
        }

        boolean success = false;
        String responseKey = download.getTransferId() + ":" + chunkIndex;

        for (int attempt = 1; attempt <= MAX_CHUNK_RETRIES; attempt++) {
            if (!running.get() || !peerState.isConnected() || download.isTerminal()) {
                break;
            }

            CompletableFuture<ChunkResponseMessage> future = new CompletableFuture<>();
            pendingChunkResponses.put(responseKey, future);

            try {
                download.getChunkManager().markTransferring(chunkIndex);
                sendChunkRequest(chunkIndex);

                ChunkResponseMessage resp = future.get(chunkTimeoutMs, TimeUnit.MILLISECONDS);

                if (resp.chunkIndex() != chunkIndex) {
                    throw new IOException("Unexpected chunk index: got " + resp.chunkIndex() + ", expected " + chunkIndex);
                }

                int expectedLength = FileChunk.calculateChunkLength(
                        chunkIndex,
                        download.getTotalChunks(),
                        download.getChunkSize(),
                        download.getFileSize()
                );

                if (resp.chunkLength() != expectedLength) {
                    throw new IOException("Chunk length mismatch for chunk " + chunkIndex +
                            ": expected " + expectedLength + ", got " + resp.chunkLength());
                }

                // Positional write into shared partial file
                long offset = FileChunk.calculateChunkOffset(chunkIndex, download.getChunkSize());
                ByteBuffer buf = ByteBuffer.wrap(resp.data(), 0, resp.chunkLength());
                long currentOffset = offset;
                while (buf.hasRemaining()) {
                    int w = partialFileChannel.write(buf, currentOffset);
                    if (w <= 0) break;
                    currentOffset += w;
                }

                // Update ChunkManager and PeerDownloadState
                boolean newlyCompleted = download.getChunkManager().markCompleted(chunkIndex, peerState.getPeerId());
                if (newlyCompleted) {
                    peerState.incrementCompletedChunks();
                    peerState.addBytesReceived(resp.chunkLength());
                    download.recordBytesReceived(resp.chunkLength());
                    download.notifyChunkCompleted(chunkIndex, peerState.getPeerId(),
                            download.getCompletedChunkCount(), download.getTotalChunks());
                    if (com.landrop.util.AppLogger.isDebugEnabled()) {
                        com.landrop.util.AppLogger.debug("WORKER", "Chunk " + chunkIndex + " completed from " +
                                peerState.getPeerDisplayName() + " (" + download.getCompletedChunkCount() + "/" + download.getTotalChunks() + ")");
                    }
                } else {
                    peerState.decrementActiveChunks();
                }

                success = true;
                break;

            } catch (TimeoutException te) {
                System.err.println("[WORKER] Timeout chunk " + chunkIndex + " from " +
                        peerState.getPeerDisplayName() + " (attempt " + attempt + "/" + MAX_CHUNK_RETRIES + ")");
            } catch (Exception e) {
                System.err.println("[WORKER] Error downloading chunk " + chunkIndex + " from " +
                        peerState.getPeerDisplayName() + ": " + e.getMessage() +
                        " (attempt " + attempt + "/" + MAX_CHUNK_RETRIES + ")");
            } finally {
                pendingChunkResponses.remove(responseKey);
            }
        }

        if (!success) {
            // Failed all retries on this peer
            download.getChunkScheduler().handleChunkFailed(chunkIndex, peerState.getPeerId(), download.getChunkManager(), peerState);
            System.err.println("[WORKER] Chunk " + chunkIndex + " failed on peer " + peerState.getPeerDisplayName() +
                    ", returned to MISSING for reassignment");
        }
    }

    private void sendChunkRequest(int chunkIndex) throws IOException {
        ChunkRequestMessage req = new ChunkRequestMessage(download.getTransferId(), chunkIndex);
        long reqId = connection.getRequestIdGenerator().nextId();
        connection.send(Packet.of(MessageType.CHUNK_REQUEST, reqId, req.toBytes()));
    }

    private void handleConnectionLoss(String reason) {
        if (peerState.isConnected()) {
            System.out.println("[WORKER] Peer disconnected: " + peerState.getPeerDisplayName() + " (" + reason + ")");
            peerState.setConnected(false);
            download.getChunkScheduler().handlePeerDisconnected(peerState.getPeerId(), download.getChunkManager(), peerState);

            for (MultiPeerDownloadListener l : download.getListeners()) {
                try {
                    l.onPeerFailed(download, peerState.getPeerId(), reason);
                } catch (Exception ignored) {
                }
            }

            if (onWorkCompletedCallback != null) {
                onWorkCompletedCallback.run();
            }
        }
    }

    @Override
    public void close() {
        running.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
        }
    }
}
