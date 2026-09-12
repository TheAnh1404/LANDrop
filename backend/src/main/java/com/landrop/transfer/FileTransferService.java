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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service managing peer-to-peer chunked file transfer operations over an established PeerConnection.
 * <p>
 * Phase 7 Architecture:
 * <ul>
 *   <li>Sequential pull-based chunk streaming (Receiver requests chunk -> Sender sends chunk)</li>
 *   <li>Zero-based chunk indexing with configurable chunk size (default 1 MiB)</li>
 *   <li>Streaming SHA-256 hash calculation and integrity verification</li>
 *   <li>Temporary partial file (.landrop.part) with atomic rename upon verification</li>
 *   <li>Sender source file consistency checks</li>
 *   <li>Duplicate chunk filtering using BitSet</li>
 *   <li>Zero-byte file handling</li>
 *   <li>Non-blocking virtual threads for file I/O and packet dispatch, keeping chat responsive</li>
 * </ul>
 */
public class FileTransferService implements AutoCloseable {

    public static final int DEFAULT_CHUNK_SIZE = 1024 * 1024; // 1 MiB (1,048,576 bytes)
    private static final long PROGRESS_THROTTLE_MS = 100L; // 10 Hz progress updates
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final PeerConnection connection;
    private final String localPeerId;
    private final String localPeerName;
    private final Path downloadDirectory;
    private final int configuredChunkSize;

    private final Map<String, FileTransfer> transfers = new ConcurrentHashMap<>();
    private final Map<String, ReceiverContext> openReceivers = new ConcurrentHashMap<>();
    private final Map<String, SenderContext> activeSenders = new ConcurrentHashMap<>();
    private final List<FileTransferListener> listeners = new CopyOnWriteArrayList<>();

    private final AtomicBoolean running = new AtomicBoolean(true);

    private record ReceiverContext(
            FileChannel channel,
            Path tempPartPath,
            Path finalDestPath,
            long totalSize,
            int chunkSize,
            int totalChunks,
            String expectedSha256,
            BitSet receivedChunks,
            long startTimeMs,
            AtomicLong lastNotifyMs,
            AtomicBoolean cancelled) {}

    private record SenderContext(
            Path sourcePath,
            long fileSize,
            long lastModifiedTime,
            int chunkSize,
            int totalChunks,
            String fileSha256,
            AtomicBoolean cancelled) {}

    /**
     * Constructs a FileTransferService bound to the specified PeerConnection with default download directory and 1 MiB chunks.
     *
     * @param connection active PeerConnection
     * @param localPeerId local peer ID
     * @param localPeerName local display name
     * @throws IOException if default download directory cannot be created
     */
    public FileTransferService(PeerConnection connection, String localPeerId, String localPeerName) throws IOException {
        this(connection, localPeerId, localPeerName, FileTransferUtils.getDefaultDownloadDirectory(), DEFAULT_CHUNK_SIZE);
    }

    /**
     * Constructs a FileTransferService with a custom download directory and default chunk size (1 MiB).
     *
     * @param connection active PeerConnection
     * @param localPeerId local peer ID
     * @param localPeerName local display name
     * @param downloadDirectory custom target download folder
     */
    public FileTransferService(PeerConnection connection, String localPeerId, String localPeerName, Path downloadDirectory) {
        this(connection, localPeerId, localPeerName, downloadDirectory, DEFAULT_CHUNK_SIZE);
    }

    /**
     * Constructs a FileTransferService with custom download directory and custom chunk size.
     *
     * @param connection active PeerConnection
     * @param localPeerId local peer ID
     * @param localPeerName local display name
     * @param downloadDirectory custom target download folder
     * @param chunkSize chunk size in bytes (> 0)
     */
    public FileTransferService(PeerConnection connection, String localPeerId, String localPeerName, Path downloadDirectory, int chunkSize) {
        this.connection = Objects.requireNonNull(connection, "PeerConnection cannot be null");
        this.localPeerId = localPeerId != null ? localPeerId : UUID.randomUUID().toString();
        this.localPeerName = (localPeerName != null && !localPeerName.isBlank()) ? localPeerName : "Peer";
        this.downloadDirectory = Objects.requireNonNull(downloadDirectory, "downloadDirectory cannot be null");
        this.configuredChunkSize = chunkSize > 0 ? chunkSize : DEFAULT_CHUNK_SIZE;

        // Register protocol packet handlers
        connection.registerHandler(MessageType.FILE_OFFER, this::handleFileOfferPacket);
        connection.registerHandler(MessageType.FILE_REQUEST, this::handleFileRequestPacket);
        connection.registerHandler(MessageType.CHUNK_REQUEST, this::handleChunkRequestPacket);
        connection.registerHandler(MessageType.CHUNK_RESPONSE, this::handleChunkResponsePacket);
        connection.registerHandler(MessageType.TRANSFER_ACK, this::handleTransferAckPacket);
        connection.registerHandler(MessageType.FILE_DATA, this::handleLegacyFileDataPacket);

        // Listen for disconnect to fail active transfers cleanly
        connection.addListener(new ConnectionListener() {
            @Override
            public void onDisconnected() {
                failAllActiveTransfers("Peer connection disconnected");
            }

            @Override
            public void onError(Exception error) {
                failAllActiveTransfers("Connection error: " + error.getMessage());
            }
        });
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

        // Zero-byte file handling (Section 28)
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
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);

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
                new AtomicLong(now),
                new AtomicBoolean(false)
        );
        openReceivers.put(transferId, ctx);

        transfer.transitionStatus(FileTransferStatus.ACCEPTED, FileTransferStatus.TRANSFERRING);

        // Send acceptance FILE_REQUEST
        FileRequestMessage acceptMsg = FileRequestMessage.accept(transferId);
        long reqId = connection.getRequestIdGenerator().nextId();
        connection.send(Packet.of(MessageType.FILE_REQUEST, reqId, OBJECT_MAPPER.writeValueAsBytes(acceptMsg)));

        notifyStarted(transfer);

        // Request first chunk (index 0)
        sendChunkRequest(transferId, 0);
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

        // Stop sender loop if outgoing
        SenderContext sender = activeSenders.remove(transferId);
        if (sender != null) {
            sender.cancelled().set(true);
        }

        // Clean up receiver channel and partial file if incoming
        ReceiverContext receiver = openReceivers.remove(transferId);
        if (receiver != null) {
            receiver.cancelled().set(true);
            try {
                receiver.channel().close();
                Files.deleteIfExists(receiver.tempPartPath());
            } catch (Exception ignored) {
            }
        }

        // Notify peer via TRANSFER_ACK failure if still connected
        if (connection.isConnected()) {
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
    // INCOMING PACKET DISPATCH HANDLERS
    // ==========================================

    private void handleFileOfferPacket(Packet packet) {
        try {
            FileOfferMessage offer = OBJECT_MAPPER.readValue(packet.getPayload(), FileOfferMessage.class);

            // Validate remote metadata strictly (Section 4)
            try {
                offer.validate();
            } catch (ProtocolException validationEx) {
                // Reject invalid offer
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
                    // For non-empty files, sender waits for CHUNK_REQUEST from receiver
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
                transfer.setCompletedChunks(chunkIndex + 1);

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
     */
    private void handleChunkResponsePacket(Packet packet) {
        Thread.ofVirtual().name("handle-chunk-response").start(() -> {
            try {
                ChunkResponseMessage chunk = ChunkResponseMessage.decode(packet.getPayload());
                String transferId = chunk.transferId();
                FileTransfer transfer = transfers.get(transferId);
                ReceiverContext ctx = openReceivers.get(transferId);

                if (transfer == null || ctx == null || ctx.cancelled().get()) {
                    return;
                }

                int chunkIndex = chunk.chunkIndex();
                if (chunkIndex < 0 || chunkIndex >= ctx.totalChunks()) {
                    failTransfer(transfer, "Invalid chunk index received: " + chunkIndex +
                            " (expected < " + ctx.totalChunks() + ")");
                    return;
                }

                // Duplicate chunk check (Section 23)
                synchronized (ctx.receivedChunks()) {
                    if (ctx.receivedChunks().get(chunkIndex)) {
                        return; // Ignore duplicate chunk cleanly
                    }
                }

                // Verify chunk length
                int expectedLen = FileChunk.calculateChunkLength(chunkIndex, ctx.totalChunks(),
                        ctx.chunkSize(), ctx.totalSize());
                if (chunk.chunkLength() != expectedLen) {
                    failTransfer(transfer, "Chunk length mismatch for chunk " + chunkIndex +
                            ": expected " + expectedLen + ", got " + chunk.chunkLength());
                    return;
                }

                // Write chunk to partial file at offset
                long offset = FileChunk.calculateChunkOffset(chunkIndex, ctx.chunkSize());
                ByteBuffer bb = ByteBuffer.wrap(chunk.data());
                long currentOffset = offset;
                while (bb.hasRemaining()) {
                    int w = ctx.channel().write(bb, currentOffset);
                    if (w <= 0) {
                        break;
                    }
                    currentOffset += w;
                }

                // Mark chunk in BitSet
                boolean allReceived;
                int completedCount;
                synchronized (ctx.receivedChunks()) {
                    ctx.receivedChunks().set(chunkIndex);
                    completedCount = ctx.receivedChunks().cardinality();
                    allReceived = (completedCount == ctx.totalChunks());
                }

                // Update transfer progress
                transfer.setCompletedChunks(completedCount);
                transfer.setCurrentChunk(chunkIndex + 1);
                transfer.addTransferredBytes(chunk.chunkLength());

                long transferred = transfer.getTransferredBytes();
                long total = ctx.totalSize();
                long now = System.currentTimeMillis();
                long elapsed = now - ctx.startTimeMs();
                double speed = elapsed > 0 ? (transferred * 1000.0) / elapsed : 0.0;
                transfer.setSpeedBytesPerSec(speed);

                long lastNotify = ctx.lastNotifyMs().get();
                if (now - lastNotify >= PROGRESS_THROTTLE_MS || allReceived) {
                    if (ctx.lastNotifyMs().compareAndSet(lastNotify, now)) {
                        notifyProgress(transfer, transferred, total, speed);
                    }
                }

                // Sequential flow (Section 11)
                if (!allReceived) {
                    // Request next chunk sequentially
                    sendChunkRequest(transferId, chunkIndex + 1);
                } else {
                    // All chunks received! Start SHA-256 verification and finalization
                    finalizeAndVerifyIncomingTransfer(transfer, ctx);
                }

            } catch (Exception e) {
                notifyError(e);
            }
        });
    }

    /**
     * Verifies file integrity, moves partial file to destination, and completes incoming transfer.
     */
    private void finalizeAndVerifyIncomingTransfer(FileTransfer transfer, ReceiverContext ctx) {
        try {
            openReceivers.remove(transfer.getTransferId());
            ctx.channel().force(true);
            ctx.channel().close();

            // Transition to VERIFYING state (Section 15)
            transfer.transitionStatus(FileTransferStatus.TRANSFERRING, FileTransferStatus.VERIFYING);
            notifyVerifying(transfer);

            // 1. Verify byte count on disk matches expected fileSize
            long diskSize = Files.size(ctx.tempPartPath());
            if (diskSize != ctx.totalSize()) {
                throw new IOException("Received file size on disk (" + diskSize +
                        " bytes) does not match expected size (" + ctx.totalSize() + " bytes)");
            }

            // 2. Compute SHA-256 of partial file
            String computedHash = FileTransferUtils.calculateSha256(ctx.tempPartPath());
            transfer.setVerifiedSha256(computedHash);

            // 3. Verify computed hash against expected hash
            if (!ctx.expectedSha256().isBlank() && !ctx.expectedSha256().equalsIgnoreCase(computedHash)) {
                throw new SecurityException("SHA-256 integrity verification failed: expected " +
                        ctx.expectedSha256() + ", but computed " + computedHash);
            }

            // 4. Atomic move partial file -> final destination (Section 13)
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

    private void sendChunkRequest(String transferId, int chunkIndex) {
        try {
            ChunkRequestMessage req = ChunkRequestMessage.of(transferId, chunkIndex);
            long reqId = connection.getRequestIdGenerator().nextId();
            connection.send(Packet.of(MessageType.CHUNK_REQUEST, reqId, req.toBytes()));
        } catch (Exception e) {
            FileTransfer transfer = transfers.get(transferId);
            if (transfer != null) {
                failTransfer(transfer, "Failed sending CHUNK_REQUEST for chunk " + chunkIndex + ": " + e.getMessage());
            }
        }
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
            while (buf.hasRemaining()) {
                int written = ctx.channel().write(buf, currentOffset);
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
            try {
                ctx.channel().close();
                Files.deleteIfExists(ctx.tempPartPath());
            } catch (Exception ignored) {
            }
        }

        if (connection.isConnected()) {
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
            try {
                ctx.channel().close();
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
        // Log or handle internal unexpected error
    }

    @Override
    public void close() {
        if (running.compareAndSet(true, false)) {
            failAllActiveTransfers("File transfer service closed");
            listeners.clear();
        }
    }
}
