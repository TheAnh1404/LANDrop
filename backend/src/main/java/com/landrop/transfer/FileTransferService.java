package com.landrop.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.peer.ConnectionListener;
import com.landrop.peer.PeerConnection;
import com.landrop.protocol.MessageType;
import com.landrop.protocol.Packet;
import com.landrop.protocol.ProtocolException;
import com.landrop.transfer.messages.ChunkRequestMessage;
import com.landrop.transfer.messages.ChunkResponseMessage;
import com.landrop.transfer.messages.FileDataChunk;
import com.landrop.transfer.messages.FileOfferMessage;
import com.landrop.transfer.messages.FileRequestMessage;
import com.landrop.transfer.messages.ResumeRequestMessage;
import com.landrop.transfer.messages.ResumeResponseMessage;
import com.landrop.transfer.messages.TransferAckMessage;

import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service managing peer-to-peer chunked file transfer operations over an established PeerConnection.
 * <p>
 * Phase 8 Architecture:
 * <ul>
 *   <li>Session-level transfer interruption detection without deleting partial (.landrop.part) files</li>
 *   <li>Transfer state preservation (transferId, BitSet chunk completion, transferred bytes, SHA-256)</li>
 *   <li>Controlled reconnect strategy with bounded backoff (MAX_RECONNECT_ATTEMPTS = 5)</li>
 *   <li>Two-way resume handshake (RESUME_REQUEST and RESUME_RESPONSE)</li>
 *   <li>Strict metadata and source file change validation on resume</li>
 *   <li>Resume from first missing chunk without re-transmitting completed chunks</li>
 *   <li>Per-chunk retry mechanism with per-chunk timeout (MAX_CHUNK_RETRIES = 3)</li>
 *   <li>Final file size check, streaming SHA-256 verification, and atomic rename</li>
 *   <li>Responsive multiplexed chat and ping during transfer and reconnect</li>
 * </ul>
 */
public class FileTransferService implements AutoCloseable {

    public static final int DEFAULT_CHUNK_SIZE = 1024 * 1024; // 1 MiB (1,048,576 bytes)
    public static final int MAX_RECONNECT_ATTEMPTS = 5;
    public static final long[] RECONNECT_DELAYS_MS = { 1000L, 2000L, 4000L, 8000L, 10000L };
    public static final int MAX_CHUNK_RETRIES = 3;
    public static final long DEFAULT_CHUNK_TIMEOUT_MS = 5000L;
    private static final long PROGRESS_THROTTLE_MS = 100L; // 10 Hz progress updates
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private volatile PeerConnection connection;
    private final String localPeerId;
    private final String localPeerName;
    private final Path downloadDirectory;
    private final int configuredChunkSize;

    private volatile long chunkTimeoutMs = DEFAULT_CHUNK_TIMEOUT_MS;
    private volatile long[] reconnectDelaysMs = RECONNECT_DELAYS_MS;
    private volatile boolean autoReconnectEnabled = true;
    private volatile String remoteHost;
    private volatile int remotePort = -1;
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);

    private final Map<String, FileTransfer> transfers = new ConcurrentHashMap<>();
    private final Map<String, ReceiverContext> openReceivers = new ConcurrentHashMap<>();
    private final Map<String, SenderContext> activeSenders = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<ChunkResponseMessage>> pendingChunkRequests = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<ResumeResponseMessage>> pendingResumeResponses = new ConcurrentHashMap<>();
    private final List<FileTransferListener> listeners = new CopyOnWriteArrayList<>();

    private final AtomicBoolean running = new AtomicBoolean(true);

    public static class ReceiverContext {
        private final AtomicReference<FileChannel> channelRef = new AtomicReference<>();
        private final Path tempPartPath;
        private final Path finalDestPath;
        private final long totalSize;
        private final int chunkSize;
        private final int totalChunks;
        private final String expectedSha256;
        private final BitSet receivedChunks;
        private final long startTimeMs;
        private final AtomicLong lastNotifyMs;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        public ReceiverContext(
                FileChannel channel,
                Path tempPartPath,
                Path finalDestPath,
                long totalSize,
                int chunkSize,
                int totalChunks,
                String expectedSha256,
                BitSet receivedChunks,
                long startTimeMs,
                AtomicLong lastNotifyMs) {
            this.channelRef.set(channel);
            this.tempPartPath = tempPartPath;
            this.finalDestPath = finalDestPath;
            this.totalSize = totalSize;
            this.chunkSize = chunkSize;
            this.totalChunks = totalChunks;
            this.expectedSha256 = expectedSha256;
            this.receivedChunks = receivedChunks != null ? receivedChunks : new BitSet(totalChunks);
            this.startTimeMs = startTimeMs;
            this.lastNotifyMs = lastNotifyMs;
        }

        public FileChannel channel() {
            return channelRef.get();
        }

        public synchronized FileChannel ensureOpenChannel() throws IOException {
            FileChannel ch = channelRef.get();
            if (ch == null || !ch.isOpen()) {
                ch = FileChannel.open(tempPartPath,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE);
                channelRef.set(ch);
            }
            return ch;
        }

        public synchronized void closeChannel() {
            FileChannel ch = channelRef.getAndSet(null);
            if (ch != null && ch.isOpen()) {
                try {
                    ch.force(true);
                    ch.close();
                } catch (Exception ignored) {
                }
            }
        }

        public Path tempPartPath() { return tempPartPath; }
        public Path finalDestPath() { return finalDestPath; }
        public long totalSize() { return totalSize; }
        public int chunkSize() { return chunkSize; }
        public int totalChunks() { return totalChunks; }
        public String expectedSha256() { return expectedSha256; }
        public BitSet receivedChunks() { return receivedChunks; }
        public long startTimeMs() { return startTimeMs; }
        public AtomicLong lastNotifyMs() { return lastNotifyMs; }
        public AtomicBoolean cancelled() { return cancelled; }
    }

    public record SenderContext(
            Path sourcePath,
            long fileSize,
            long lastModifiedTime,
            int chunkSize,
            int totalChunks,
            String fileSha256,
            AtomicBoolean cancelled) {}

    public FileTransferService(PeerConnection connection, String localPeerId, String localPeerName) throws IOException {
        this(connection, localPeerId, localPeerName, FileTransferUtils.getDefaultDownloadDirectory(), DEFAULT_CHUNK_SIZE);
    }

    public FileTransferService(PeerConnection connection, String localPeerId, String localPeerName, Path downloadDirectory) {
        this(connection, localPeerId, localPeerName, downloadDirectory, DEFAULT_CHUNK_SIZE);
    }

    public FileTransferService(PeerConnection connection, String localPeerId, String localPeerName, Path downloadDirectory, int chunkSize) {
        this.localPeerId = localPeerId != null ? localPeerId : UUID.randomUUID().toString();
        this.localPeerName = (localPeerName != null && !localPeerName.isBlank()) ? localPeerName : "Peer";
        this.downloadDirectory = Objects.requireNonNull(downloadDirectory, "downloadDirectory cannot be null");
        this.configuredChunkSize = chunkSize > 0 ? chunkSize : DEFAULT_CHUNK_SIZE;

        attachConnection(connection);
    }

    /**
     * Attaches a new or re-established PeerConnection to this FileTransferService.
     * Registers all packet handlers and adds a disconnection listener.
     *
     * @param newConnection the active PeerConnection
     */
    public synchronized void attachConnection(PeerConnection newConnection) {
        Objects.requireNonNull(newConnection, "PeerConnection cannot be null");
        this.connection = newConnection;

        // Register protocol packet handlers
        newConnection.registerHandler(MessageType.FILE_OFFER, this::handleFileOfferPacket);
        newConnection.registerHandler(MessageType.FILE_REQUEST, this::handleFileRequestPacket);
        newConnection.registerHandler(MessageType.CHUNK_REQUEST, this::handleChunkRequestPacket);
        newConnection.registerHandler(MessageType.CHUNK_RESPONSE, this::handleChunkResponsePacket);
        newConnection.registerHandler(MessageType.TRANSFER_ACK, this::handleTransferAckPacket);
        newConnection.registerHandler(MessageType.RESUME_REQUEST, this::handleResumeRequestPacket);
        newConnection.registerHandler(MessageType.RESUME_RESPONSE, this::handleResumeResponsePacket);
        newConnection.registerHandler(MessageType.FILE_DATA, this::handleLegacyFileDataPacket);

        // Listen for disconnect to interrupt active transfers cleanly
        newConnection.addListener(new ConnectionListener() {
            @Override
            public void onDisconnected() {
                handleConnectionLoss("Peer connection disconnected");
            }

            @Override
            public void onError(Exception error) {
                handleConnectionLoss("Connection error: " + (error != null ? error.getMessage() : "unknown"));
            }
        });
    }

    public PeerConnection getConnection() {
        return connection;
    }

    public void setRemoteEndpoint(String host, int port) {
        this.remoteHost = host;
        this.remotePort = port;
    }

    public String getRemoteHost() {
        return remoteHost;
    }

    public int getRemotePort() {
        return remotePort;
    }

    public void setChunkTimeoutMs(long chunkTimeoutMs) {
        this.chunkTimeoutMs = Math.max(50L, chunkTimeoutMs);
    }

    public long getChunkTimeoutMs() {
        return chunkTimeoutMs;
    }

    public void setReconnectDelaysMs(long[] delays) {
        if (delays != null && delays.length > 0) {
            this.reconnectDelaysMs = delays;
        }
    }

    public long[] getReconnectDelaysMs() {
        return reconnectDelaysMs;
    }

    public void setAutoReconnectEnabled(boolean enabled) {
        this.autoReconnectEnabled = enabled;
    }

    public boolean isAutoReconnectEnabled() {
        return autoReconnectEnabled;
    }

    /**
     * Offers a local file to the connected remote peer.
     * Calculates SHA-256 hash using streaming I/O before sending FILE_OFFER.
     *
     * @param file the local file to send
     * @return the created outgoing FileTransfer instance
     * @throws IOException if the file does not exist, cannot be read, or hashing fails
     */
    public FileTransfer offerFile(File file) throws IOException {
        Objects.requireNonNull(file, "file cannot be null");
        if (!file.exists() || !file.isFile()) {
            throw new FileNotFoundException("File not found or is a directory: " + file.getAbsolutePath());
        }
        if (!file.canRead()) {
            throw new IOException("Cannot read file (permission denied): " + file.getAbsolutePath());
        }

        String transferId = UUID.randomUUID().toString();
        String safeName = FileTransferUtils.sanitizeFilename(file.getName());
        long fileSize = file.length();
        long lastModified = file.lastModified();

        // Calculate SHA-256 streaming hash
        String sha256 = FileTransferUtils.calculateSha256(file.toPath());
        int chunkSize = configuredChunkSize;
        int totalChunks = FileChunk.calculateTotalChunks(fileSize, chunkSize);

        FileTransfer transfer = new FileTransfer(
                transferId,
                safeName,
                fileSize,
                FileTransferDirection.OUTGOING,
                connection.getRemotePeerName(),
                connection.getRemotePeerName(),
                file.toPath(),
                chunkSize,
                totalChunks,
                sha256
        );

        transfers.put(transferId, transfer);
        activeSenders.put(transferId, new SenderContext(
                file.toPath(),
                fileSize,
                lastModified,
                chunkSize,
                totalChunks,
                sha256,
                new AtomicBoolean(false)
        ));

        FileOfferMessage offer = new FileOfferMessage(
                transferId,
                safeName,
                fileSize,
                localPeerId,
                localPeerName,
                chunkSize,
                totalChunks,
                sha256
        );

        byte[] jsonBytes = OBJECT_MAPPER.writeValueAsBytes(offer);
        long reqId = connection.getRequestIdGenerator().nextId();
        Packet packet = Packet.of(MessageType.FILE_OFFER, reqId, jsonBytes);
        connection.send(packet);

        return transfer;
    }

    /**
     * Accepts a pending incoming file transfer.
     *
     * @param transferId the unique transfer identifier
     * @throws IOException if file channel cannot be opened or network fails
     */
    public void acceptTransfer(String transferId) throws IOException {
        FileTransfer transfer = transfers.get(transferId);
        if (transfer == null) {
            throw new IllegalArgumentException("Unknown transfer ID: " + transferId);
        }
        if (transfer.getDirection() != FileTransferDirection.INCOMING) {
            throw new IllegalStateException("Cannot accept an outgoing transfer");
        }
        if (!transfer.transitionStatus(FileTransferStatus.PENDING, FileTransferStatus.ACCEPTED)) {
            throw new IllegalStateException("Transfer is not PENDING (current: " + transfer.getStatus() + ")");
        }

        if (!Files.exists(downloadDirectory)) {
            Files.createDirectories(downloadDirectory);
        }

        Path destPath = FileTransferUtils.resolveUniqueDestination(downloadDirectory, transfer.getFileName());
        transfer.setLocalPath(destPath);

        // Zero-byte file handling
        if (transfer.getFileSize() == 0) {
            Files.createFile(destPath);
            String emptyHash = FileTransferUtils.calculateSha256(new byte[0]);
            transfer.setVerifiedSha256(emptyHash);
            transfer.setHashVerified(true);
            transfer.setStatus(FileTransferStatus.COMPLETED);

            // Send acceptance FILE_REQUEST
            FileRequestMessage acceptMsg = FileRequestMessage.accept(transferId);
            long reqId = connection.getRequestIdGenerator().nextId();
            connection.send(Packet.of(MessageType.FILE_REQUEST, reqId, OBJECT_MAPPER.writeValueAsBytes(acceptMsg)));

            // Send TRANSFER_ACK success
            TransferAckMessage ack = TransferAckMessage.success(transferId, 0);
            connection.send(Packet.of(MessageType.TRANSFER_ACK, reqId, OBJECT_MAPPER.writeValueAsBytes(ack)));

            notifyStarted(transfer);
            notifyCompleted(transfer);
            return;
        }

        // Prepare partial file path: <destPath>.landrop.part
        Path tempPartPath = destPath.resolveSibling(destPath.getFileName().toString() + ".landrop.part");
        Files.deleteIfExists(tempPartPath);

        FileChannel channel = FileChannel.open(tempPartPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);

        long now = System.currentTimeMillis();
        ReceiverContext ctx = new ReceiverContext(
                channel,
                tempPartPath,
                destPath,
                transfer.getFileSize(),
                transfer.getChunkSize(),
                transfer.getTotalChunks(),
                transfer.getFileSha256(),
                new BitSet(transfer.getTotalChunks()),
                now,
                new AtomicLong(now)
        );
        openReceivers.put(transferId, ctx);

        transfer.transitionStatus(FileTransferStatus.ACCEPTED, FileTransferStatus.TRANSFERRING);

        // Send acceptance FILE_REQUEST
        FileRequestMessage acceptMsg = FileRequestMessage.accept(transferId);
        long reqId = connection.getRequestIdGenerator().nextId();
        connection.send(Packet.of(MessageType.FILE_REQUEST, reqId, OBJECT_MAPPER.writeValueAsBytes(acceptMsg)));

        notifyStarted(transfer);

        // Start pulling chunks sequentially using virtual worker
        startChunkPullerWorker(transfer, ctx);
    }

    /**
     * Rejects a pending incoming file transfer.
     *
     * @param transferId the unique transfer identifier
     * @param reason optional rejection reason
     * @throws IOException if network transmission fails
     */
    public void rejectTransfer(String transferId, String reason) throws IOException {
        FileTransfer transfer = transfers.get(transferId);
        if (transfer == null) {
            throw new IllegalArgumentException("Unknown transfer ID: " + transferId);
        }
        if (transfer.getDirection() != FileTransferDirection.INCOMING) {
            throw new IllegalStateException("Cannot reject an outgoing transfer");
        }
        if (!transfer.transitionStatus(FileTransferStatus.PENDING, FileTransferStatus.REJECTED)) {
            return;
        }

        String actualReason = (reason != null && !reason.isBlank()) ? reason : "Transfer rejected by receiver";
        transfer.setErrorMessage(actualReason);

        FileRequestMessage rejectMsg = FileRequestMessage.reject(transferId, actualReason);
        long reqId = connection.getRequestIdGenerator().nextId();
        connection.send(Packet.of(MessageType.FILE_REQUEST, reqId, OBJECT_MAPPER.writeValueAsBytes(rejectMsg)));

        notifyRejected(transfer, actualReason);
    }

    /**
     * Cancels an active or pending transfer.
     *
     * @param transferId the transfer identifier
     */
    public void cancelTransfer(String transferId) {
        FileTransfer transfer = transfers.get(transferId);
        if (transfer == null || transfer.isTerminal()) {
            return;
        }

        transfer.setStatus(FileTransferStatus.CANCELLED);
        transfer.setErrorMessage("Transfer cancelled by user");

        // Cancel pending requests
        CompletableFuture<?> future = pendingChunkRequests.remove(transferId);
        if (future != null) {
            future.cancel(true);
        }
        CompletableFuture<?> resumeFut = pendingResumeResponses.remove(transferId);
        if (resumeFut != null) {
            resumeFut.cancel(true);
        }

        // Stop sender loop if outgoing
        SenderContext sender = activeSenders.remove(transferId);
        if (sender != null) {
            sender.cancelled().set(true);
        }

        // Clean up receiver channel and partial file if incoming
        ReceiverContext receiver = openReceivers.remove(transferId);
        if (receiver != null) {
            receiver.cancelled().set(true);
            receiver.closeChannel();
            try {
                Files.deleteIfExists(receiver.tempPartPath());
            } catch (Exception ignored) {
            }
        }

        // Notify peer via TRANSFER_ACK failure if still connected
        if (connection != null && connection.isConnected()) {
            try {
                TransferAckMessage ack = TransferAckMessage.failure(transferId, transfer.getTransferredBytes(), "Transfer cancelled");
                long reqId = connection.getRequestIdGenerator().nextId();
                connection.send(Packet.of(MessageType.TRANSFER_ACK, reqId, OBJECT_MAPPER.writeValueAsBytes(ack)));
            } catch (Exception ignored) {
            }
        }

        notifyFailed(transfer, "Transfer cancelled by user");
    }

    // ==========================================
    // RECONNECT & RESUME ENGINE (Phase 8)
    // ==========================================

    /**
     * Initiates resume handshake for an interrupted transfer.
     *
     * @param transferId unique transfer ID
     */
    public void resumeTransfer(String transferId) {
        FileTransfer transfer = transfers.get(transferId);
        if (transfer == null || transfer.isTerminal()) {
            return;
        }

        if (transfer.getDirection() == FileTransferDirection.INCOMING) {
            ReceiverContext ctx = openReceivers.get(transferId);
            if (ctx == null) {
                return;
            }

            Thread.ofVirtual().name("resume-incoming-" + transferId).start(() -> {
                try {
                    transfer.setStatus(FileTransferStatus.RESUMING);
                    int fromChunk = transfer.getFirstMissingChunk() >= 0 ? transfer.getFirstMissingChunk() : 0;
                    System.out.println("[RESUME] Initiating resume handshake transferId=" + transferId +
                            " fromChunk=" + fromChunk + "/" + transfer.getTotalChunks());
                    notifyResuming(transfer, fromChunk, transfer.getTotalChunks());

                    ResumeRequestMessage req = ResumeRequestMessage.of(transfer, ctx.receivedChunks());
                    CompletableFuture<ResumeResponseMessage> respFuture = new CompletableFuture<>();
                    pendingResumeResponses.put(transferId, respFuture);

                    long reqId = connection.getRequestIdGenerator().nextId();
                    connection.send(Packet.of(MessageType.RESUME_REQUEST, reqId, req.toBytes()));

                    ResumeResponseMessage resp = respFuture.get(chunkTimeoutMs, TimeUnit.MILLISECONDS);
                    pendingResumeResponses.remove(transferId);

                    if (!resp.isAccepted()) {
                        String reason = resp.getReason() != null ? resp.getReason() : "Resume rejected";
                        System.err.println("[RESUME] Resume rejected: " + reason);
                        failTransfer(transfer, reason);
                        return;
                    }

                    System.out.println("[RESUME] Resume accepted transferId=" + transferId);
                    transfer.setStatus(FileTransferStatus.TRANSFERRING);
                    transfer.setErrorMessage("");

                    startChunkPullerWorker(transfer, ctx);

                } catch (TimeoutException te) {
                    pendingResumeResponses.remove(transferId);
                    System.err.println("[RESUME] Resume handshake timed out for transferId=" + transferId);
                    handleConnectionLoss("Resume handshake timed out");
                } catch (Exception e) {
                    pendingResumeResponses.remove(transferId);
                    System.err.println("[RESUME] Resume handshake failed: " + e.getMessage());
                    handleConnectionLoss("Resume handshake error: " + e.getMessage());
                }
            });
        }
    }

    /**
     * Background worker pulling missing chunks sequentially with timeout and retry.
     */
    private void startChunkPullerWorker(FileTransfer transfer, ReceiverContext ctx) {
        Thread.ofVirtual().name("chunk-puller-" + transfer.getTransferId()).start(() -> {
            try {
                FileChannel channel = ctx.ensureOpenChannel();

                while (running.get() && !transfer.isTerminal() && !ctx.cancelled().get()) {
                    int nextMissing;
                    synchronized (ctx.receivedChunks()) {
                        nextMissing = ctx.receivedChunks().nextClearBit(0);
                    }

                    if (nextMissing >= ctx.totalChunks()) {
                        // All chunks received! Verify SHA-256 and finalize
                        finalizeAndVerifyIncomingTransfer(transfer, ctx);
                        break;
                    }

                    boolean chunkSuccess = false;
                    for (int attempt = 1; attempt <= MAX_CHUNK_RETRIES; attempt++) {
                        if (!running.get() || transfer.isTerminal() || ctx.cancelled().get()) {
                            break;
                        }
                        if (connection == null || !connection.isConnected()) {
                            break;
                        }

                        transfer.setChunkRetryIndex(nextMissing);
                        transfer.setChunkRetryAttempt(attempt);
                        transfer.setMaxChunkRetries(MAX_CHUNK_RETRIES);

                        if (attempt > 1) {
                            System.out.println("[RETRY] chunk=" + nextMissing + " attempt=" + attempt + "/" + MAX_CHUNK_RETRIES);
                            notifyChunkRetrying(transfer, nextMissing, attempt, MAX_CHUNK_RETRIES);
                        }

                        CompletableFuture<ChunkResponseMessage> future = new CompletableFuture<>();
                        pendingChunkRequests.put(transfer.getTransferId(), future);

                        try {
                            sendChunkRequest(transfer.getTransferId(), nextMissing);
                            ChunkResponseMessage chunkResp = future.get(chunkTimeoutMs, TimeUnit.MILLISECONDS);

                            if (chunkResp.chunkIndex() != nextMissing) {
                                throw new ProtocolException("Unexpected chunk index: " + chunkResp.chunkIndex() +
                                        ", expected: " + nextMissing);
                            }

                            int expectedLen = FileChunk.calculateChunkLength(nextMissing, ctx.totalChunks(),
                                    ctx.chunkSize(), ctx.totalSize());
                            if (chunkResp.chunkLength() != expectedLen) {
                                throw new ProtocolException("Chunk length mismatch for chunk " + nextMissing +
                                        ": expected " + expectedLen + ", got " + chunkResp.chunkLength());
                            }

                            // Write chunk to partial file at exact offset
                            long offset = FileChunk.calculateChunkOffset(nextMissing, ctx.chunkSize());
                            ByteBuffer bb = ByteBuffer.wrap(chunkResp.data());
                            long currentOffset = offset;
                            while (bb.hasRemaining()) {
                                int w = channel.write(bb, currentOffset);
                                if (w <= 0) break;
                                currentOffset += w;
                            }

                            // Mark chunk in BitSet
                            synchronized (ctx.receivedChunks()) {
                                ctx.receivedChunks().set(nextMissing);
                            }
                            transfer.markChunkCompleted(nextMissing);
                            transfer.setCurrentChunk(nextMissing + 1);
                            transfer.addTransferredBytes(chunkResp.chunkLength());

                            // Clear retry indicators
                            transfer.setChunkRetryAttempt(0);
                            transfer.setChunkRetryIndex(-1);

                            long transferred = transfer.getTransferredBytes();
                            long total = ctx.totalSize();
                            long now = System.currentTimeMillis();
                            long elapsed = now - ctx.startTimeMs();
                            double speed = elapsed > 0 ? (transferred * 1000.0) / elapsed : 0.0;
                            transfer.setSpeedBytesPerSec(speed);

                            long lastNotify = ctx.lastNotifyMs().get();
                            if (now - lastNotify >= PROGRESS_THROTTLE_MS || nextMissing + 1 >= ctx.totalChunks()) {
                                if (ctx.lastNotifyMs().compareAndSet(lastNotify, now)) {
                                    notifyProgress(transfer, transferred, total, speed);
                                }
                            }

                            chunkSuccess = true;
                            break; // Successfully got this chunk!

                        } catch (TimeoutException te) {
                            System.err.println("[TRANSFER] Chunk " + nextMissing + " request timed out (attempt " +
                                    attempt + "/" + MAX_CHUNK_RETRIES + ")");
                        } catch (Exception e) {
                            System.err.println("[TRANSFER] Chunk " + nextMissing + " error: " + e.getMessage() +
                                    " (attempt " + attempt + "/" + MAX_CHUNK_RETRIES + ")");
                        } finally {
                            pendingChunkRequests.remove(transfer.getTransferId());
                        }
                    }

                    if (!chunkSuccess) {
                        if (connection == null || !connection.isConnected()) {
                            // Interrupted by connection drop
                            handleConnectionLoss("Connection lost during chunk transfer");
                        } else {
                            // Repeated retry failure on healthy connection
                            failTransfer(transfer, "Failed downloading chunk " + nextMissing +
                                    " after " + MAX_CHUNK_RETRIES + " attempts");
                        }
                        break;
                    }
                }

            } catch (Exception e) {
                notifyError(e);
            }
        });
    }

    /**
     * Handles disconnection or socket error. Transitions active transfers to INTERRUPTED
     * and preserves .landrop.part files without deleting them.
     */
    private void handleConnectionLoss(String reason) {
        System.out.println("[TRANSFER] Connection lost: " + reason);

        // Cancel in-flight chunk requests and resume responses
        for (CompletableFuture<?> f : pendingChunkRequests.values()) {
            f.completeExceptionally(new IOException("Connection lost: " + reason));
        }
        pendingChunkRequests.clear();

        for (CompletableFuture<?> f : pendingResumeResponses.values()) {
            f.completeExceptionally(new IOException("Connection lost: " + reason));
        }
        pendingResumeResponses.clear();

        FileTransfer incomingToReconnect = null;

        for (FileTransfer transfer : transfers.values()) {
            if (transfer.getStatus() == FileTransferStatus.TRANSFERRING ||
                transfer.getStatus() == FileTransferStatus.RESUMING ||
                transfer.getStatus() == FileTransferStatus.ACCEPTED) {

                transfer.setStatus(FileTransferStatus.INTERRUPTED);
                transfer.setErrorMessage(reason);
                System.out.println("[TRANSFER] Transfer interrupted transferId=" + transfer.getTransferId() +
                        " completedChunks=" + transfer.getCompletedChunkCount() + "/" + transfer.getTotalChunks());

                // Safely close receiver channel without deleting partial file
                ReceiverContext rCtx = openReceivers.get(transfer.getTransferId());
                if (rCtx != null) {
                    rCtx.closeChannel();
                }

                notifyInterrupted(transfer, reason);

                if (transfer.getDirection() == FileTransferDirection.INCOMING && incomingToReconnect == null) {
                    incomingToReconnect = transfer;
                }
            }
        }

        // Trigger controlled reconnect if configured
        if (autoReconnectEnabled && incomingToReconnect != null && remoteHost != null && remotePort > 0) {
            scheduleReconnect(incomingToReconnect);
        }
    }

    /**
     * Bounded exponential backoff reconnect task (MAX_RECONNECT_ATTEMPTS = 5).
     */
    private void scheduleReconnect(FileTransfer transfer) {
        if (!reconnecting.compareAndSet(false, true)) {
            return;
        }

        Thread.ofVirtual().name("p2p-reconnector-" + transfer.getTransferId()).start(() -> {
            try {
                for (int attempt = 1; attempt <= MAX_RECONNECT_ATTEMPTS; attempt++) {
                    if (!running.get() || transfer.isTerminal()) {
                        break;
                    }

                    long delay = reconnectDelaysMs[Math.min(attempt - 1, reconnectDelaysMs.length - 1)];
                    transfer.setStatus(FileTransferStatus.RECONNECTING);
                    transfer.setReconnectAttempt(attempt);
                    transfer.setMaxReconnectAttempts(MAX_RECONNECT_ATTEMPTS);
                    System.out.println("[RECONNECT] Attempt " + attempt + "/" + MAX_RECONNECT_ATTEMPTS +
                            " peer=" + transfer.getRemotePeerName() + " (" + remoteHost + ":" + remotePort + ")");
                    notifyReconnecting(transfer, attempt, MAX_RECONNECT_ATTEMPTS);

                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }

                    try {
                        PeerConnection newConn = PeerConnection.connectTo(remoteHost, remotePort, 5000);
                        newConn.start();
                        newConn.initiateHandshake(localPeerName);
                        if (!newConn.awaitReady(5, TimeUnit.SECONDS)) {
                            newConn.close();
                            continue;
                        }

                        System.out.println("[RECONNECT] Connected successfully on attempt " + attempt);
                        attachConnection(newConn);
                        reconnecting.set(false);

                        // Resume transfer
                        resumeTransfer(transfer.getTransferId());
                        return;
                    } catch (Exception e) {
                        System.err.println("[RECONNECT] Attempt " + attempt + " failed: " + e.getMessage());
                    }
                }

                // All attempts failed!
                reconnecting.set(false);
                failTransfer(transfer, "Failed to reconnect to peer after " + MAX_RECONNECT_ATTEMPTS + " attempts");

            } catch (Exception e) {
                reconnecting.set(false);
                failTransfer(transfer, "Reconnection error: " + e.getMessage());
            }
        });
    }

    // ==========================================
    // INCOMING PACKET DISPATCH HANDLERS
    // ==========================================

    private void handleFileOfferPacket(Packet packet) {
        try {
            FileOfferMessage offer = OBJECT_MAPPER.readValue(packet.getPayload(), FileOfferMessage.class);

            try {
                offer.validate();
            } catch (ProtocolException validationEx) {
                FileRequestMessage rejectMsg = FileRequestMessage.reject(offer.getTransferId(),
                        "Invalid file metadata: " + validationEx.getMessage());
                long reqId = connection.getRequestIdGenerator().nextId();
                connection.send(Packet.of(MessageType.FILE_REQUEST, reqId, OBJECT_MAPPER.writeValueAsBytes(rejectMsg)));
                return;
            }

            FileTransfer transfer = new FileTransfer(
                    offer.getTransferId(),
                    offer.getFileName(),
                    offer.getFileSize(),
                    FileTransferDirection.INCOMING,
                    offer.getSenderPeerId(),
                    offer.getSenderDisplayName(),
                    null,
                    offer.getChunkSize(),
                    offer.getTotalChunks(),
                    offer.getFileSha256()
            );

            transfers.put(transfer.getTransferId(), transfer);
            notifyOffered(transfer);

        } catch (Exception e) {
            notifyError(e);
        }
    }

    private void handleFileRequestPacket(Packet packet) {
        try {
            FileRequestMessage request = OBJECT_MAPPER.readValue(packet.getPayload(), FileRequestMessage.class);
            FileTransfer transfer = transfers.get(request.getTransferId());
            if (transfer == null) {
                return;
            }

            if (request.isAccepted()) {
                if (transfer.transitionStatus(FileTransferStatus.PENDING, FileTransferStatus.TRANSFERRING)) {
                    notifyStarted(transfer);

                    // Zero-byte file already handled by receiver ack
                    if (transfer.getFileSize() == 0) {
                        transfer.setStatus(FileTransferStatus.COMPLETED);
                        transfer.setHashVerified(true);
                        activeSenders.remove(transfer.getTransferId());
                        notifyCompleted(transfer);
                    }
                }
            } else {
                transfer.setStatus(FileTransferStatus.REJECTED);
                String reason = request.getReason().isBlank() ? "Rejected by remote peer" : request.getReason();
                transfer.setErrorMessage(reason);
                activeSenders.remove(transfer.getTransferId());
                notifyRejected(transfer, reason);
            }

        } catch (Exception e) {
            notifyError(e);
        }
    }

    /**
     * Handles incoming CHUNK_REQUEST from receiver (Sender side).
     */
    private void handleChunkRequestPacket(Packet packet) {
        Thread.ofVirtual().name("handle-chunk-request").start(() -> {
            try {
                ChunkRequestMessage msg = ChunkRequestMessage.fromBytes(packet.getPayload());
                String transferId = msg.getTransferId();
                FileTransfer transfer = transfers.get(transferId);
                SenderContext senderCtx = activeSenders.get(transferId);

                if (transfer == null || senderCtx == null || senderCtx.cancelled().get()) {
                    return;
                }

                int chunkIndex = msg.getChunkIndex();
                if (chunkIndex < 0 || chunkIndex >= senderCtx.totalChunks()) {
                    failTransfer(transfer, "Invalid chunk index requested: " + chunkIndex +
                            " (total: " + senderCtx.totalChunks() + ")");
                    return;
                }

                // File consistency check (Section 22)
                Path sourcePath = senderCtx.sourcePath();
                if (!Files.exists(sourcePath) || Files.size(sourcePath) != senderCtx.fileSize()) {
                    failTransfer(transfer, "Source file modified or missing during transfer: " + sourcePath);
                    return;
                }

                // Calculate chunk offset and length
                long offset = FileChunk.calculateChunkOffset(chunkIndex, senderCtx.chunkSize());
                int length = FileChunk.calculateChunkLength(chunkIndex, senderCtx.totalChunks(),
                        senderCtx.chunkSize(), senderCtx.fileSize());

                byte[] chunkBuffer = new byte[length];
                try (FileChannel fc = FileChannel.open(sourcePath, StandardOpenOption.READ)) {
                    ByteBuffer bb = ByteBuffer.wrap(chunkBuffer);
                    long currentOffset = offset;
                    while (bb.hasRemaining()) {
                        int r = fc.read(bb, currentOffset);
                        if (r < 0) {
                            throw new EOFException("Premature EOF reading chunk " + chunkIndex + " from " + sourcePath);
                        }
                        currentOffset += r;
                    }
                }

                // Encode binary CHUNK_RESPONSE and transmit
                byte[] chunkPayload = ChunkResponseMessage.encode(transferId, chunkIndex, chunkBuffer, length);
                long reqId = connection.getRequestIdGenerator().nextId();
                connection.send(Packet.of(MessageType.CHUNK_RESPONSE, reqId, chunkPayload));

                // Update sender progress
                long sentBytes = offset + length;
                transfer.setTransferredBytes(sentBytes);
                transfer.setCurrentChunk(chunkIndex + 1);
                transfer.markChunkCompleted(chunkIndex);

                long now = System.currentTimeMillis();
                long elapsed = now - transfer.getStartTimeMs();
                double speed = elapsed > 0 ? (sentBytes * 1000.0) / elapsed : 0.0;
                transfer.setSpeedBytesPerSec(speed);

                notifyProgress(transfer, sentBytes, senderCtx.fileSize(), speed);

            } catch (Exception e) {
                notifyError(e);
            }
        });
    }

    /**
     * Handles incoming CHUNK_RESPONSE from sender (Receiver side).
     * Decodes binary packet and notifies the pending chunk future.
     */
    private void handleChunkResponsePacket(Packet packet) {
        try {
            ChunkResponseMessage chunk = ChunkResponseMessage.decode(packet.getPayload());
            CompletableFuture<ChunkResponseMessage> future = pendingChunkRequests.get(chunk.transferId());
            if (future != null) {
                future.complete(chunk);
            }
        } catch (Exception e) {
            notifyError(e);
        }
    }

    /**
     * Handles incoming RESUME_REQUEST from receiver (Sender side).
     */
    private void handleResumeRequestPacket(Packet packet) {
        Thread.ofVirtual().name("handle-resume-request").start(() -> {
            try {
                ResumeRequestMessage req = ResumeRequestMessage.fromBytes(packet.getPayload());
                String transferId = req.getTransferId();
                FileTransfer transfer = transfers.get(transferId);
                SenderContext senderCtx = activeSenders.get(transferId);

                long reqId = connection.getRequestIdGenerator().nextId();

                if (transfer == null || senderCtx == null) {
                    ResumeResponseMessage reject = ResumeResponseMessage.reject(transferId,
                            ResumeResponseMessage.REASON_TRANSFER_NOT_FOUND);
                    connection.send(Packet.of(MessageType.RESUME_RESPONSE, reqId, reject.toBytes()));
                    return;
                }

                // Metadata validation (Section 13)
                try {
                    req.validate();
                } catch (ProtocolException pe) {
                    ResumeResponseMessage reject = ResumeResponseMessage.reject(transferId,
                            ResumeResponseMessage.REASON_METADATA_MISMATCH + ": " + pe.getMessage());
                    connection.send(Packet.of(MessageType.RESUME_RESPONSE, reqId, reject.toBytes()));
                    return;
                }

                if (req.getFileSize() != senderCtx.fileSize()) {
                    ResumeResponseMessage reject = ResumeResponseMessage.reject(transferId,
                            ResumeResponseMessage.REASON_FILE_SIZE_MISMATCH);
                    connection.send(Packet.of(MessageType.RESUME_RESPONSE, reqId, reject.toBytes()));
                    return;
                }

                if (req.getChunkSize() != senderCtx.chunkSize()) {
                    ResumeResponseMessage reject = ResumeResponseMessage.reject(transferId,
                            ResumeResponseMessage.REASON_CHUNK_SIZE_MISMATCH);
                    connection.send(Packet.of(MessageType.RESUME_RESPONSE, reqId, reject.toBytes()));
                    return;
                }

                if (req.getTotalChunks() != senderCtx.totalChunks()) {
                    ResumeResponseMessage reject = ResumeResponseMessage.reject(transferId,
                            ResumeResponseMessage.REASON_METADATA_MISMATCH);
                    connection.send(Packet.of(MessageType.RESUME_RESPONSE, reqId, reject.toBytes()));
                    return;
                }

                if (!req.getFileSha256().equalsIgnoreCase(senderCtx.fileSha256())) {
                    ResumeResponseMessage reject = ResumeResponseMessage.reject(transferId,
                            ResumeResponseMessage.REASON_HASH_MISMATCH);
                    connection.send(Packet.of(MessageType.RESUME_RESPONSE, reqId, reject.toBytes()));
                    return;
                }

                // Source file check on disk (Section 22)
                Path sourcePath = senderCtx.sourcePath();
                if (!Files.exists(sourcePath) || Files.size(sourcePath) != senderCtx.fileSize()) {
                    ResumeResponseMessage reject = ResumeResponseMessage.reject(transferId,
                            ResumeResponseMessage.REASON_SOURCE_FILE_CHANGED);
                    connection.send(Packet.of(MessageType.RESUME_RESPONSE, reqId, reject.toBytes()));
                    failTransfer(transfer, "Source file modified or missing: " + sourcePath);
                    return;
                }

                // Check source file hash integrity
                String currentHash = FileTransferUtils.calculateSha256(sourcePath);
                if (!currentHash.equalsIgnoreCase(senderCtx.fileSha256())) {
                    ResumeResponseMessage reject = ResumeResponseMessage.reject(transferId,
                            ResumeResponseMessage.REASON_SOURCE_FILE_CHANGED);
                    connection.send(Packet.of(MessageType.RESUME_RESPONSE, reqId, reject.toBytes()));
                    failTransfer(transfer, "Source file hash modified during interruption: " + sourcePath);
                    return;
                }

                // Resume validation succeeded!
                BitSet completedBits = req.getCompletedChunksBitSet();
                transfer.setCompletedChunksBitSet(completedBits);
                transfer.setStatus(FileTransferStatus.TRANSFERRING);
                transfer.setErrorMessage("");

                System.out.println("[RESUME] Resume accepted transferId=" + transferId +
                        " nextMissingChunk=" + completedBits.nextClearBit(0));

                ResumeResponseMessage accept = ResumeResponseMessage.accept(transferId);
                connection.send(Packet.of(MessageType.RESUME_RESPONSE, reqId, accept.toBytes()));

                notifyStarted(transfer);

            } catch (Exception e) {
                notifyError(e);
            }
        });
    }

    /**
     * Handles incoming RESUME_RESPONSE from sender (Receiver side).
     */
    private void handleResumeResponsePacket(Packet packet) {
        try {
            ResumeResponseMessage resp = ResumeResponseMessage.fromBytes(packet.getPayload());
            CompletableFuture<ResumeResponseMessage> future = pendingResumeResponses.get(resp.getTransferId());
            if (future != null) {
                future.complete(resp);
            }
        } catch (Exception e) {
            notifyError(e);
        }
    }

    /**
     * Verifies file integrity, moves partial file to destination, and completes incoming transfer.
     */
    private void finalizeAndVerifyIncomingTransfer(FileTransfer transfer, ReceiverContext ctx) {
        try {
            openReceivers.remove(transfer.getTransferId());
            ctx.closeChannel();

            // Transition to VERIFYING state
            transfer.transitionStatus(FileTransferStatus.TRANSFERRING, FileTransferStatus.VERIFYING);
            notifyVerifying(transfer);

            // 1. Verify byte count on disk matches expected fileSize
            long diskSize = Files.size(ctx.tempPartPath());
            if (diskSize != ctx.totalSize()) {
                throw new IOException("Received file size on disk (" + diskSize +
                        " bytes) does not match expected size (" + ctx.totalSize() + " bytes)");
            }

            // 2. Compute SHA-256 of partial file
            System.out.println("[VERIFY] SHA-256 verification started for " + transfer.getFileName());
            String computedHash = FileTransferUtils.calculateSha256(ctx.tempPartPath());
            transfer.setVerifiedSha256(computedHash);

            // 3. Verify computed hash against expected hash
            if (!ctx.expectedSha256().isBlank() && !ctx.expectedSha256().equalsIgnoreCase(computedHash)) {
                throw new SecurityException("SHA-256 integrity verification failed: expected " +
                        ctx.expectedSha256() + ", but computed " + computedHash);
            }

            System.out.println("[VERIFY] SHA-256 verification successful: " + computedHash);

            // 4. Atomic move partial file -> final destination
            try {
                Files.move(ctx.tempPartPath(), ctx.finalDestPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(ctx.tempPartPath(), ctx.finalDestPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }

            transfer.setHashVerified(true);
            transfer.setLocalPath(ctx.finalDestPath());
            transfer.setStatus(FileTransferStatus.COMPLETED);

            // 5. Send TRANSFER_ACK success
            TransferAckMessage ack = TransferAckMessage.success(transfer.getTransferId(), ctx.totalSize());
            long reqId = connection.getRequestIdGenerator().nextId();
            connection.send(Packet.of(MessageType.TRANSFER_ACK, reqId, OBJECT_MAPPER.writeValueAsBytes(ack)));

            notifyCompleted(transfer);

        } catch (Exception e) {
            try {
                Files.deleteIfExists(ctx.tempPartPath());
            } catch (Exception ignored) {
            }
            failTransfer(transfer, "Verification failed: " + e.getMessage());
        }
    }

    private void sendChunkRequest(String transferId, int chunkIndex) throws IOException {
        ChunkRequestMessage req = ChunkRequestMessage.of(transferId, chunkIndex);
        long reqId = connection.getRequestIdGenerator().nextId();
        connection.send(Packet.of(MessageType.CHUNK_REQUEST, reqId, req.toBytes()));
    }

    private void handleTransferAckPacket(Packet packet) {
        try {
            TransferAckMessage ack = OBJECT_MAPPER.readValue(packet.getPayload(), TransferAckMessage.class);
            FileTransfer transfer = transfers.get(ack.getTransferId());
            if (transfer == null) {
                return;
            }

            activeSenders.remove(ack.getTransferId());

            if (ack.isSuccess()) {
                transfer.setStatus(FileTransferStatus.COMPLETED);
                transfer.setTransferredBytes(transfer.getFileSize());
                transfer.setCompletedChunks(transfer.getTotalChunks());
                transfer.setHashVerified(true);
                notifyCompleted(transfer);
            } else {
                transfer.setStatus(FileTransferStatus.FAILED);
                transfer.setErrorMessage(ack.getMessage());
                notifyFailed(transfer, ack.getMessage());
            }

        } catch (Exception e) {
            notifyError(e);
        }
    }

    /**
     * Backward compatibility handler for legacy Phase 6 FILE_DATA packets.
     */
    private void handleLegacyFileDataPacket(Packet packet) {
        try {
            FileDataChunk chunk = FileDataChunk.decode(packet.getPayload());
            String transferId = chunk.transferId();
            FileTransfer transfer = transfers.get(transferId);
            ReceiverContext ctx = openReceivers.get(transferId);

            if (transfer == null || ctx == null) {
                return;
            }

            ByteBuffer buf = ByteBuffer.wrap(chunk.data());
            long currentOffset = chunk.offset();
            FileChannel ch = ctx.ensureOpenChannel();
            while (buf.hasRemaining()) {
                int written = ch.write(buf, currentOffset);
                if (written <= 0) break;
                currentOffset += written;
            }

            transfer.addTransferredBytes(chunk.data().length);
            if (transfer.getTransferredBytes() >= ctx.totalSize()) {
                finalizeAndVerifyIncomingTransfer(transfer, ctx);
            }
        } catch (Exception e) {
            notifyError(e);
        }
    }

    private void failTransfer(FileTransfer transfer, String reason) {
        transfer.setStatus(FileTransferStatus.FAILED);
        transfer.setErrorMessage(reason);

        activeSenders.remove(transfer.getTransferId());
        ReceiverContext ctx = openReceivers.remove(transfer.getTransferId());
        if (ctx != null) {
            ctx.closeChannel();
            try {
                Files.deleteIfExists(ctx.tempPartPath());
            } catch (Exception ignored) {
            }
        }

        if (connection != null && connection.isConnected()) {
            try {
                TransferAckMessage ack = TransferAckMessage.failure(transfer.getTransferId(),
                        transfer.getTransferredBytes(), reason);
                long reqId = connection.getRequestIdGenerator().nextId();
                connection.send(Packet.of(MessageType.TRANSFER_ACK, reqId, OBJECT_MAPPER.writeValueAsBytes(ack)));
            } catch (Exception ignored) {
            }
        }

        notifyFailed(transfer, reason);
    }

    private void failAllActiveTransfers(String reason) {
        for (FileTransfer transfer : transfers.values()) {
            if (!transfer.isTerminal()) {
                transfer.setStatus(FileTransferStatus.FAILED);
                transfer.setErrorMessage(reason);
                notifyFailed(transfer, reason);
            }
        }

        for (ReceiverContext ctx : openReceivers.values()) {
            ctx.closeChannel();
            try {
                Files.deleteIfExists(ctx.tempPartPath());
            } catch (Exception ignored) {
            }
        }
        openReceivers.clear();
        activeSenders.clear();
    }

    // ==========================================
    // LISTENER REGISTRATION & EVENT DISPATCH
    // ==========================================

    public void addListener(FileTransferListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(FileTransferListener listener) {
        listeners.remove(listener);
    }

    public List<FileTransfer> getTransfers() {
        return new ArrayList<>(transfers.values());
    }

    public FileTransfer getTransfer(String transferId) {
        return transfers.get(transferId);
    }

    /**
     * Registers a transfer directly into the transfer map. Mainly used for testing or state restoration.
     *
     * @param transfer the transfer to register
     */
    public void registerTransfer(FileTransfer transfer) {
        if (transfer != null) {
            transfers.put(transfer.getTransferId(), transfer);
        }
    }

    public Path getDownloadDirectory() {
        return downloadDirectory;
    }

    public int getConfiguredChunkSize() {
        return configuredChunkSize;
    }

    private void notifyOffered(FileTransfer transfer) {
        for (FileTransferListener l : listeners) {
            try {
                l.onTransferOffered(transfer);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyStarted(FileTransfer transfer) {
        for (FileTransferListener l : listeners) {
            try {
                l.onTransferStarted(transfer);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyProgress(FileTransfer transfer, long transferred, long total, double speed) {
        for (FileTransferListener l : listeners) {
            try {
                l.onTransferProgress(transfer, transferred, total, speed);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyVerifying(FileTransfer transfer) {
        for (FileTransferListener l : listeners) {
            try {
                l.onTransferVerifying(transfer);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyInterrupted(FileTransfer transfer, String reason) {
        for (FileTransferListener l : listeners) {
            try {
                l.onTransferInterrupted(transfer, reason);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyReconnecting(FileTransfer transfer, int attempt, int maxAttempts) {
        for (FileTransferListener l : listeners) {
            try {
                l.onTransferReconnecting(transfer, attempt, maxAttempts);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyResuming(FileTransfer transfer, int fromChunk, int totalChunks) {
        for (FileTransferListener l : listeners) {
            try {
                l.onTransferResuming(transfer, fromChunk, totalChunks);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyChunkRetrying(FileTransfer transfer, int chunkIndex, int attempt, int maxAttempts) {
        for (FileTransferListener l : listeners) {
            try {
                l.onTransferChunkRetrying(transfer, chunkIndex, attempt, maxAttempts);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyCompleted(FileTransfer transfer) {
        for (FileTransferListener l : listeners) {
            try {
                l.onTransferCompleted(transfer);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyRejected(FileTransfer transfer, String reason) {
        for (FileTransferListener l : listeners) {
            try {
                l.onTransferRejected(transfer, reason);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyFailed(FileTransfer transfer, String reason) {
        for (FileTransferListener l : listeners) {
            try {
                l.onTransferFailed(transfer, reason);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyError(Exception error) {
        // Log internal error
    }

    public void shutdown() {
        close();
    }

    @Override
    public void close() {
        if (running.compareAndSet(true, false)) {
            for (CompletableFuture<?> f : pendingChunkRequests.values()) {
                f.cancel(true);
            }
            pendingChunkRequests.clear();
            for (CompletableFuture<?> f : pendingResumeResponses.values()) {
                f.cancel(true);
            }
            pendingResumeResponses.clear();
            failAllActiveTransfers("File transfer service closed");
            listeners.clear();
        }
    }
}
