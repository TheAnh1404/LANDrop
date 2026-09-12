package com.landrop.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.landrop.peer.ConnectionListener;
import com.landrop.peer.PeerConnection;
import com.landrop.protocol.MessageType;
import com.landrop.protocol.Packet;
import com.landrop.protocol.ProtocolException;
import com.landrop.transfer.messages.FileDataChunk;
import com.landrop.transfer.messages.FileOfferMessage;
import com.landrop.transfer.messages.FileRequestMessage;
import com.landrop.transfer.messages.TransferAckMessage;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service managing peer-to-peer file transfer operations over an established PeerConnection.
 * <p>
 * Ensures thread isolation, non-blocking asynchronous transmission using virtual threads,
 * safe buffered streaming with framed FILE_DATA chunks (default 64 KB),
 * filename sanitization, path traversal defenses, and throttled UI progress reporting.
 */
public class FileTransferService implements AutoCloseable {

    public static final int DEFAULT_CHUNK_SIZE = 64 * 1024; // 64 KB
    private static final long PROGRESS_THROTTLE_MS = 100L; // 10 Hz progress updates
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final PeerConnection connection;
    private final String localPeerId;
    private final String localPeerName;
    private final Path downloadDirectory;

    private final Map<String, FileTransfer> transfers = new ConcurrentHashMap<>();
    private final Map<String, ReceiverContext> openReceivers = new ConcurrentHashMap<>();
    private final Map<String, SenderContext> activeSenders = new ConcurrentHashMap<>();
    private final List<FileTransferListener> listeners = new CopyOnWriteArrayList<>();

    private final AtomicBoolean running = new AtomicBoolean(true);

    private record ReceiverContext(FileChannel channel, Path destinationPath, long totalSize, long startTimeMs, long lastNotifyMs, long lastBytes) {}
    private record SenderContext(AtomicBoolean cancelled) {}

    /**
     * Constructs a FileTransferService bound to the specified PeerConnection with default download directory.
     *
     * @param connection active PeerConnection
     * @param localPeerId local peer ID
     * @param localPeerName local display name
     * @throws IOException if default download directory cannot be created
     */
    public FileTransferService(PeerConnection connection, String localPeerId, String localPeerName) throws IOException {
        this(connection, localPeerId, localPeerName, FileTransferUtils.getDefaultDownloadDirectory());
    }

    /**
     * Constructs a FileTransferService with a custom download directory.
     *
     * @param connection active PeerConnection
     * @param localPeerId local peer ID
     * @param localPeerName local display name
     * @param downloadDirectory custom target download folder
     */
    public FileTransferService(PeerConnection connection, String localPeerId, String localPeerName, Path downloadDirectory) {
        this.connection = Objects.requireNonNull(connection, "PeerConnection cannot be null");
        this.localPeerId = localPeerId != null ? localPeerId : UUID.randomUUID().toString();
        this.localPeerName = (localPeerName != null && !localPeerName.isBlank()) ? localPeerName : "Peer";
        this.downloadDirectory = Objects.requireNonNull(downloadDirectory, "downloadDirectory cannot be null");

        // Register protocol packet handlers
        connection.registerHandler(MessageType.FILE_OFFER, this::handleFileOfferPacket);
        connection.registerHandler(MessageType.FILE_REQUEST, this::handleFileRequestPacket);
        connection.registerHandler(MessageType.FILE_DATA, this::handleFileDataPacket);
        connection.registerHandler(MessageType.TRANSFER_ACK, this::handleTransferAckPacket);

        // Listen for disconnect to fail active transfers
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
     *
     * @param file the local file to send
     * @return the created outgoing FileTransfer instance
     * @throws IOException if the file does not exist, cannot be read, or network fails
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

        FileTransfer transfer = new FileTransfer(
                transferId,
                safeName,
                fileSize,
                FileTransferDirection.OUTGOING,
                connection.getRemotePeerName(),
                connection.getRemotePeerName(),
                file.toPath()
        );

        transfers.put(transferId, transfer);

        FileOfferMessage offer = new FileOfferMessage(
                transferId,
                safeName,
                fileSize,
                localPeerId,
                localPeerName
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

        // Handle zero-byte file immediately
        if (transfer.getFileSize() == 0) {
            Files.createFile(destPath);
            transfer.setStatus(FileTransferStatus.COMPLETED);

            // Send acceptance FILE_REQUEST
            FileRequestMessage acceptMsg = FileRequestMessage.accept(transferId);
            long reqId = connection.getRequestIdGenerator().nextId();
            connection.send(Packet.of(MessageType.FILE_REQUEST, reqId, OBJECT_MAPPER.writeValueAsBytes(acceptMsg)));

            // Send TRANSFER_ACK success
            TransferAckMessage ack = TransferAckMessage.success(transferId, 0);
            connection.send(Packet.of(MessageType.TRANSFER_ACK, reqId, OBJECT_MAPPER.writeValueAsBytes(ack)));

            notifyCompleted(transfer);
            return;
        }

        FileChannel channel = FileChannel.open(destPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);

        long now = System.currentTimeMillis();
        openReceivers.put(transferId, new ReceiverContext(channel, destPath, transfer.getFileSize(), now, now, 0));

        FileRequestMessage acceptMsg = FileRequestMessage.accept(transferId);
        long reqId = connection.getRequestIdGenerator().nextId();
        connection.send(Packet.of(MessageType.FILE_REQUEST, reqId, OBJECT_MAPPER.writeValueAsBytes(acceptMsg)));

        notifyStarted(transfer);
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
            try {
                receiver.channel().close();
                Files.deleteIfExists(receiver.destinationPath());
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

            FileTransfer transfer = new FileTransfer(
                    offer.getTransferId(),
                    offer.getFileName(),
                    offer.getFileSize(),
                    FileTransferDirection.INCOMING,
                    offer.getSenderPeerId(),
                    offer.getSenderDisplayName(),
                    null
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

                    // If zero-byte file, complete immediately
                    if (transfer.getFileSize() == 0) {
                        transfer.setStatus(FileTransferStatus.COMPLETED);
                        notifyCompleted(transfer);
                        return;
                    }

                    // Start streaming file in dedicated virtual thread
                    AtomicBoolean cancelFlag = new AtomicBoolean(false);
                    activeSenders.put(transfer.getTransferId(), new SenderContext(cancelFlag));

                    Thread.ofVirtual()
                            .name("file-sender-" + transfer.getTransferId())
                            .start(() -> sendFileStream(transfer, cancelFlag));
                }
            } else {
                transfer.setStatus(FileTransferStatus.REJECTED);
                String reason = request.getReason().isBlank() ? "Rejected by remote peer" : request.getReason();
                transfer.setErrorMessage(reason);
                notifyRejected(transfer, reason);
            }

        } catch (Exception e) {
            notifyError(e);
        }
    }

    private void handleFileDataPacket(Packet packet) {
        try {
            FileDataChunk chunk = FileDataChunk.decode(packet.getPayload());
            String transferId = chunk.transferId();

            FileTransfer transfer = transfers.get(transferId);
            ReceiverContext ctx = openReceivers.get(transferId);

            if (transfer == null || ctx == null) {
                return;
            }

            // Ensure status is TRANSFERRING
            transfer.transitionStatus(FileTransferStatus.ACCEPTED, FileTransferStatus.TRANSFERRING);

            ByteBuffer buf = ByteBuffer.wrap(chunk.data());
            long currentOffset = chunk.offset();
            while (buf.hasRemaining()) {
                int written = ctx.channel().write(buf, currentOffset);
                if (written <= 0) {
                    break;
                }
                currentOffset += written;
            }

            transfer.addTransferredBytes(chunk.data().length);

            // Throttled progress calculation
            long transferred = transfer.getTransferredBytes();
            long total = ctx.totalSize();
            long now = System.currentTimeMillis();

            long elapsedTotal = now - ctx.startTimeMs();
            double overallSpeed = elapsedTotal > 0 ? (transferred * 1000.0) / elapsedTotal : 0.0;
            transfer.setSpeedBytesPerSec(overallSpeed);

            if (now - ctx.lastNotifyMs() >= PROGRESS_THROTTLE_MS || transferred >= total) {
                notifyProgress(transfer, transferred, total, overallSpeed);
            }

            // Check if transfer complete
            if (transferred >= total) {
                openReceivers.remove(transferId);
                ctx.channel().close();

                transfer.setStatus(FileTransferStatus.COMPLETED);

                // Acknowledge completion to sender
                TransferAckMessage ack = TransferAckMessage.success(transferId, transferred);
                long reqId = connection.getRequestIdGenerator().nextId();
                connection.send(Packet.of(MessageType.TRANSFER_ACK, reqId, OBJECT_MAPPER.writeValueAsBytes(ack)));

                notifyCompleted(transfer);
            }

        } catch (Exception e) {
            // Clean up on receiver error
            try {
                ReceiverContext ctx = openReceivers.remove(packet.getRequestId() + "");
                if (ctx != null) {
                    ctx.channel().close();
                    Files.deleteIfExists(ctx.destinationPath());
                }
            } catch (Exception ignored) {
            }
            notifyError(e);
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

    // ==========================================
    // ASYNCHRONOUS FILE SENDER LOOP (STREAMED)
    // ==========================================

    private void sendFileStream(FileTransfer transfer, AtomicBoolean cancelFlag) {
        Path sourcePath = transfer.getLocalPath();
        if (sourcePath == null || !Files.exists(sourcePath)) {
            failTransfer(transfer, "Source file missing: " + sourcePath);
            return;
        }

        byte[] buffer = new byte[DEFAULT_CHUNK_SIZE];
        long offset = 0;
        long totalSize = transfer.getFileSize();
        long startTime = System.currentTimeMillis();
        long lastNotifyTime = startTime;

        try (InputStream in = Files.newInputStream(sourcePath)) {
            int bytesRead;
            while (running.get() && !cancelFlag.get() && (bytesRead = in.read(buffer)) != -1) {
                if (!connection.isConnected()) {
                    throw new IOException("Connection lost during file transfer");
                }

                // Encode binary chunk
                byte[] chunkPayload = FileDataChunk.encode(transfer.getTransferId(), offset, buffer, bytesRead);
                long reqId = connection.getRequestIdGenerator().nextId();
                Packet dataPacket = Packet.of(MessageType.FILE_DATA, reqId, chunkPayload);

                // Serialized write across thread-safe connection
                connection.send(dataPacket);

                offset += bytesRead;
                transfer.setTransferredBytes(offset);

                // Throttled speed and progress calculation
                long now = System.currentTimeMillis();
                long elapsed = now - startTime;
                double speed = elapsed > 0 ? (offset * 1000.0) / elapsed : 0.0;
                transfer.setSpeedBytesPerSec(speed);

                if (now - lastNotifyTime >= PROGRESS_THROTTLE_MS || offset >= totalSize) {
                    notifyProgress(transfer, offset, totalSize, speed);
                    lastNotifyTime = now;
                }
            }

            if (cancelFlag.get()) {
                transfer.setStatus(FileTransferStatus.CANCELLED);
                notifyFailed(transfer, "Transfer cancelled by sender");
            }

        } catch (Exception e) {
            activeSenders.remove(transfer.getTransferId());
            failTransfer(transfer, "Failed while reading/sending file: " + e.getMessage());
        }
    }

    private void failTransfer(FileTransfer transfer, String reason) {
        transfer.setStatus(FileTransferStatus.FAILED);
        transfer.setErrorMessage(reason);

        if (connection.isConnected()) {
            try {
                TransferAckMessage ack = TransferAckMessage.failure(transfer.getTransferId(), transfer.getTransferredBytes(), reason);
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
                Files.deleteIfExists(ctx.destinationPath());
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
        for (FileTransferListener l : listeners) {
            try {
                l.onTransferFailed(null, error.getMessage());
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void close() {
        this.running.set(false);
        failAllActiveTransfers("FileTransferService closed");
    }
}
