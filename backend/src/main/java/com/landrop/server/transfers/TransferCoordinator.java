package com.landrop.server.transfers;

import com.landrop.server.clients.ClientConnection;
import com.landrop.server.clients.ClientRegistry;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * In-memory Control Plane Transfer Coordinator managing file transfer signaling and state transitions.
 * Pure control plane: does not handle binary chunk data.
 */
public class TransferCoordinator {

    private static final Pattern HEX_64 = Pattern.compile("^[a-fA-F0-9]{64}$");
    private static final long MAX_FILE_SIZE = 5L * 1024L * 1024L * 1024L * 1024L; // 5 TB upper bound

    private final ClientRegistry clientRegistry;
    private final Map<String, TransferRecord> transfers = new ConcurrentHashMap<>();

    public TransferCoordinator(ClientRegistry clientRegistry) {
        this.clientRegistry = Objects.requireNonNull(clientRegistry, "clientRegistry cannot be null");
    }

    /**
     * Validates file metadata and initiates a transfer request in PENDING state.
     */
    public TransferRecord requestTransfer(String senderConnId,
                                          String receiverConnId,
                                          String fileName,
                                          long fileSize,
                                          String sha256) {
        if (senderConnId == null || senderConnId.isBlank()) {
            throw new IllegalArgumentException("Sender connection ID is required.");
        }
        if (receiverConnId == null || receiverConnId.isBlank()) {
            throw new IllegalArgumentException("Receiver connection ID is required.");
        }
        if (senderConnId.equals(receiverConnId)) {
            throw new IllegalArgumentException("Sender and receiver cannot be the exact same connection.");
        }

        // Validate target receiver in registry
        Optional<ClientConnection> receiver = clientRegistry.get(receiverConnId);
        if (receiver.isEmpty() || !receiver.get().isOpen()) {
            throw new IllegalStateException("RECEIVER_NOT_FOUND");
        }

        // Validate file metadata
        String sanitizedFileName = validateAndSanitizeFileName(fileName);
        if (fileSize < 0) {
            throw new IllegalArgumentException("File size cannot be negative.");
        }
        if (fileSize > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File size exceeds server limit of 5 TB.");
        }

        String validHash = null;
        if (sha256 != null && !sha256.isBlank()) {
            String trimmedHash = sha256.trim();
            if (!HEX_64.matcher(trimmedHash).matches()) {
                throw new IllegalArgumentException("Invalid SHA-256 hash format.");
            }
            validHash = trimmedHash.toLowerCase();
        }

        String transferId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        TransferRecord record = new TransferRecord(
            transferId,
            senderConnId,
            receiverConnId,
            sanitizedFileName,
            fileSize,
            validHash,
            TransferStatus.PENDING,
            now,
            now,
            null
        );

        transfers.put(transferId, record);
        return record;
    }

    public TransferRecord acceptTransfer(String transferId, String receiverConnId) {
        TransferRecord record = getRequired(transferId);
        if (!record.receiverConnectionId().equals(receiverConnId)) {
            throw new IllegalArgumentException("Only target receiver can accept this transfer.");
        }
        if (record.status() != TransferStatus.PENDING) {
            throw new IllegalStateException("Cannot accept transfer in status " + record.status());
        }

        TransferRecord updated = record.withStatus(TransferStatus.ACCEPTED, null);
        transfers.put(transferId, updated);
        return updated;
    }

    public TransferRecord rejectTransfer(String transferId, String receiverConnId, String reason) {
        TransferRecord record = getRequired(transferId);
        if (!record.receiverConnectionId().equals(receiverConnId)) {
            throw new IllegalArgumentException("Only target receiver can reject this transfer.");
        }
        if (record.status().isTerminal()) {
            throw new IllegalStateException("Cannot reject a terminated transfer.");
        }

        TransferRecord updated = record.withStatus(TransferStatus.REJECTED, reason != null ? reason : "Rejected by receiver");
        transfers.put(transferId, updated);
        return updated;
    }

    public TransferRecord cancelTransfer(String transferId, String actorConnId, String reason) {
        TransferRecord record = getRequired(transferId);
        if (!record.senderConnectionId().equals(actorConnId) && !record.receiverConnectionId().equals(actorConnId)) {
            throw new IllegalArgumentException("Only participants can cancel this transfer.");
        }
        if (record.status().isTerminal()) {
            return record; // Already terminal
        }

        TransferRecord updated = record.withStatus(TransferStatus.CANCELLED, reason != null ? reason : "Cancelled by peer");
        transfers.put(transferId, updated);
        return updated;
    }

    public TransferRecord updateStatus(String transferId, TransferStatus newStatus, String errorMessage) {
        TransferRecord record = getRequired(transferId);
        TransferRecord updated = record.withStatus(newStatus, errorMessage);
        transfers.put(transferId, updated);
        return updated;
    }

    public Optional<TransferRecord> getTransfer(String transferId) {
        if (transferId == null) return Optional.empty();
        return Optional.ofNullable(transfers.get(transferId));
    }

    public long countActiveTransfers() {
        return transfers.values().stream().filter(t -> !t.status().isTerminal()).count();
    }

    /**
     * Cleans up all non-terminal transfers involving a disconnected client.
     * Returns list of affected transfers so peer can be notified.
     */
    public List<TransferRecord> onClientDisconnected(String connectionId) {
        if (connectionId == null) return List.of();
        List<TransferRecord> affected = new ArrayList<>();

        for (TransferRecord record : transfers.values()) {
            if (!record.status().isTerminal()
                    && (record.senderConnectionId().equals(connectionId) || record.receiverConnectionId().equals(connectionId))) {
                TransferRecord cancelled = record.withStatus(TransferStatus.CANCELLED, "PEER_DISCONNECTED");
                transfers.put(record.transferId(), cancelled);
                affected.add(cancelled);
            }
        }
        return affected;
    }

    private TransferRecord getRequired(String transferId) {
        return getTransfer(transferId)
                .orElseThrow(() -> new NoSuchElementException("Transfer not found: " + transferId));
    }

    private static String validateAndSanitizeFileName(String fileName) {
        if (fileName == null || fileName.trim().isBlank()) {
            throw new IllegalArgumentException("File name cannot be empty.");
        }
        String trimmed = fileName.trim();
        if (trimmed.length() > 255) {
            throw new IllegalArgumentException("File name cannot exceed 255 characters.");
        }
        // Extract plain base name if path components present, preventing path traversal
        String baseName = trimmed.replace('\\', '/');
        int lastSlash = baseName.lastIndexOf('/');
        if (lastSlash >= 0) {
            baseName = baseName.substring(lastSlash + 1).trim();
        }
        if (baseName.isEmpty() || baseName.equals(".") || baseName.equals("..")) {
            throw new IllegalArgumentException("Invalid file name: cannot be traversal sequence.");
        }
        return baseName;
    }
}
