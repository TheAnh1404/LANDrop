package com.landrop.file.service;

import com.landrop.database.DatabaseManager;
import com.landrop.database.model.Device;
import com.landrop.database.model.FileRecord;
import com.landrop.database.model.TransferRecord;
import com.landrop.database.repository.DeviceRepository;
import com.landrop.database.repository.FileRepository;
import com.landrop.database.repository.TransferRepository;
import com.landrop.database.repository.WorkspaceRepository;
import com.landrop.file.dto.DeviceDto;
import com.landrop.file.dto.FileDto;
import com.landrop.file.dto.TransferDto;
import com.landrop.file.exception.FileException;
import com.landrop.file.model.TransferStatus;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/**
 * Service coordinating user-authorized LAN file transfer requests and lifecycle states.
 */
public class TransferCoordinationService {

    private final DatabaseManager databaseManager;
    private final TransferRepository transferRepository;
    private final FileRepository fileRepository;
    private final DeviceRepository deviceRepository;
    private final FileManagementService fileManagementService;
    private volatile TransferBroadcaster broadcaster;

    public TransferCoordinationService(DatabaseManager databaseManager,
                                       TransferRepository transferRepository,
                                       FileRepository fileRepository,
                                       DeviceRepository deviceRepository,
                                       FileManagementService fileManagementService,
                                       TransferBroadcaster broadcaster) {
        this.databaseManager = databaseManager;
        this.transferRepository = transferRepository;
        this.fileRepository = fileRepository;
        this.deviceRepository = deviceRepository;
        this.fileManagementService = fileManagementService;
        this.broadcaster = broadcaster;
    }

    public TransferCoordinationService() {
        this(DatabaseManager.getInstance(), new TransferRepository(), new FileRepository(),
             new DeviceRepository(), new FileManagementService(), null);
    }

    public void setBroadcaster(TransferBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    // =========================================================================
    // Device Registration & Mapping
    // =========================================================================

    public DeviceDto registerDevice(UUID userId, String deviceId, String deviceName, String peerId, String ipAddress, Integer port) throws SQLException {
        if (deviceId == null || deviceId.trim().isBlank()) {
            throw new FileException(400, "INVALID_REQUEST", "Device ID is required.");
        }
        if (deviceName == null || deviceName.trim().isBlank()) {
            throw new FileException(400, "INVALID_REQUEST", "Device name is required.");
        }

        Device toUpsert = new Device(
            deviceId.trim(),
            userId,
            deviceName.trim(),
            peerId != null ? peerId.trim() : null,
            ipAddress != null ? ipAddress.trim() : null,
            port
        );

        Device saved = deviceRepository.upsert(toUpsert);
        return toDeviceDto(saved, true);
    }

    public List<DeviceDto> listUserDevices(UUID userId) throws SQLException {
        List<Device> devices = deviceRepository.findByUserId(userId);
        return devices.stream().map(d -> toDeviceDto(d, isDeviceRecent(d))).toList();
    }

    public Optional<DeviceDto> getDevice(String deviceId) throws SQLException {
        return deviceRepository.findById(deviceId).map(d -> toDeviceDto(d, isDeviceRecent(d)));
    }

    public Optional<DeviceDto> getDeviceByPeer(String peerId) throws SQLException {
        return deviceRepository.findByPeerId(peerId).map(d -> toDeviceDto(d, isDeviceRecent(d)));
    }

    private boolean isDeviceRecent(Device d) {
        // Active if seen in last 10 minutes
        return d.lastSeenAt() != null && d.lastSeenAt().isAfter(Instant.now().minusSeconds(600));
    }

    // =========================================================================
    // Transfer Lifecycle
    // =========================================================================

    public TransferDto requestTransfer(UUID senderUserId, UUID fileId, UUID receiverUserId, String senderDeviceId, String receiverDeviceId) throws SQLException {
        if (fileId == null) {
            throw new FileException(400, "INVALID_REQUEST", "File ID is required.");
        }
        if (receiverUserId == null) {
            throw new FileException(400, "INVALID_REQUEST", "Receiver User ID is required.");
        }

        FileRecord file = fileRepository.findById(fileId)
            .orElseThrow(() -> new FileException(404, "FILE_NOT_FOUND", "File was not found."));

        if (file.isDeleted()) {
            throw new FileException(400, "INVALID_REQUEST", "Cannot transfer a deleted file.");
        }

        // Verify sender and receiver workspace membership
        fileManagementService.requireWorkspaceMember(file.workspaceId(), senderUserId);
        fileManagementService.requireWorkspaceMember(file.workspaceId(), receiverUserId);

        // Validate sender device if specified
        if (senderDeviceId != null && !senderDeviceId.isBlank()) {
            Optional<Device> sDev = deviceRepository.findById(senderDeviceId);
            if (sDev.isPresent() && !sDev.get().userId().equals(senderUserId)) {
                throw new FileException(403, "FORBIDDEN", "Sender device does not belong to sender user.");
            }
        }

        // Validate receiver device if specified
        if (receiverDeviceId != null && !receiverDeviceId.isBlank()) {
            Optional<Device> rDev = deviceRepository.findById(receiverDeviceId);
            if (rDev.isPresent() && !rDev.get().userId().equals(receiverUserId)) {
                throw new FileException(403, "FORBIDDEN", "Receiver device does not belong to receiver user.");
            }
        }

        TransferRecord record = new TransferRecord(fileId, senderUserId, receiverUserId, senderDeviceId, receiverDeviceId);
        TransferRecord created = transferRepository.create(record);
        TransferDto dto = toTransferDto(created, file);

        // Realtime dispatch: transfer:request to receiver
        emit("transfer:request", receiverUserId, receiverDeviceId, dto);
        return dto;
    }

    public TransferDto getTransfer(UUID userId, UUID transferId) throws SQLException {
        TransferRecord record = transferRepository.findById(transferId)
            .orElseThrow(() -> new FileException(404, "TRANSFER_NOT_FOUND", "Transfer was not found."));

        if (!record.senderUserId().equals(userId) && !record.receiverUserId().equals(userId)) {
            // Check if workspace admin
            Optional<FileRecord> fileOpt = fileRepository.findById(record.fileId());
            if (fileOpt.isPresent()) {
                fileManagementService.requireWorkspaceMember(fileOpt.get().workspaceId(), userId);
            } else {
                throw new FileException(403, "FORBIDDEN", "Not authorized to access this transfer.");
            }
        }

        FileRecord file = fileRepository.findById(record.fileId()).orElse(null);
        return toTransferDto(record, file);
    }

    public TransferDto acceptTransfer(UUID receiverUserId, UUID transferId, String receiverDeviceId) throws SQLException {
        TransferRecord existing = transferRepository.findById(transferId)
            .orElseThrow(() -> new FileException(404, "TRANSFER_NOT_FOUND", "Transfer was not found."));

        if (!existing.receiverUserId().equals(receiverUserId)) {
            throw new FileException(403, "FORBIDDEN", "Only the intended receiver can accept this transfer.");
        }

        TransferStatus current = parseStatus(existing.status());
        if (!current.canTransitionTo(TransferStatus.ACCEPTED)) {
            throw new FileException(400, "INVALID_TRANSITION", "Cannot accept transfer in status " + current);
        }

        String effectiveReceiverDev = receiverDeviceId != null && !receiverDeviceId.isBlank()
            ? receiverDeviceId : existing.receiverDeviceId();

        Instant now = Instant.now();
        TransferRecord updated = transferRepository.updateStatus(transferId, TransferStatus.ACCEPTED.name(), now, null, null, null);
        FileRecord file = fileRepository.findById(updated.fileId()).orElse(null);
        TransferDto dto = toTransferDto(updated, file);

        // Realtime dispatch: transfer:accepted to sender
        emit("transfer:accepted", updated.senderUserId(), updated.senderDeviceId(), dto);
        return dto;
    }

    public TransferDto rejectTransfer(UUID receiverUserId, UUID transferId, String reason) throws SQLException {
        TransferRecord existing = transferRepository.findById(transferId)
            .orElseThrow(() -> new FileException(404, "TRANSFER_NOT_FOUND", "Transfer was not found."));

        if (!existing.receiverUserId().equals(receiverUserId)) {
            throw new FileException(403, "FORBIDDEN", "Only the intended receiver can reject this transfer.");
        }

        TransferStatus current = parseStatus(existing.status());
        if (!current.canTransitionTo(TransferStatus.REJECTED)) {
            throw new FileException(400, "INVALID_TRANSITION", "Cannot reject transfer in status " + current);
        }

        TransferRecord updated = transferRepository.updateStatus(transferId, TransferStatus.REJECTED.name(), null, null, null, reason);
        FileRecord file = fileRepository.findById(updated.fileId()).orElse(null);
        TransferDto dto = toTransferDto(updated, file);

        // Realtime dispatch: transfer:rejected to sender
        emit("transfer:rejected", updated.senderUserId(), updated.senderDeviceId(), dto);
        return dto;
    }

    public TransferDto startTransferring(UUID callerUserId, UUID transferId) throws SQLException {
        TransferRecord existing = transferRepository.findById(transferId)
            .orElseThrow(() -> new FileException(404, "TRANSFER_NOT_FOUND", "Transfer was not found."));

        if (!existing.senderUserId().equals(callerUserId) && !existing.receiverUserId().equals(callerUserId)) {
            throw new FileException(403, "FORBIDDEN", "Only transfer participants can start transferring.");
        }

        TransferStatus current = parseStatus(existing.status());
        if (!current.canTransitionTo(TransferStatus.TRANSFERRING)) {
            throw new FileException(400, "INVALID_TRANSITION", "Cannot transition to TRANSFERRING from " + current);
        }

        TransferRecord updated = transferRepository.updateStatus(transferId, TransferStatus.TRANSFERRING.name(),
            existing.startedAt() != null ? existing.startedAt() : Instant.now(), null, null, null);
        FileRecord file = fileRepository.findById(updated.fileId()).orElse(null);
        TransferDto dto = toTransferDto(updated, file);

        emit("transfer:started", updated.senderUserId(), updated.senderDeviceId(), dto);
        emit("transfer:started", updated.receiverUserId(), updated.receiverDeviceId(), dto);
        return dto;
    }

    public void updateProgress(UUID callerUserId, UUID transferId, long bytesTransferred, long totalBytes, double speedBytesPerSec) throws SQLException {
        TransferRecord existing = transferRepository.findById(transferId)
            .orElseThrow(() -> new FileException(404, "TRANSFER_NOT_FOUND", "Transfer was not found."));

        if (!existing.senderUserId().equals(callerUserId) && !existing.receiverUserId().equals(callerUserId)) {
            throw new FileException(403, "FORBIDDEN", "Only transfer participants can report progress.");
        }

        TransferStatus current = parseStatus(existing.status());
        if (current == TransferStatus.ACCEPTED) {
            // Auto-advance to TRANSFERRING upon first progress byte
            startTransferring(callerUserId, transferId);
        } else if (current != TransferStatus.TRANSFERRING) {
            throw new FileException(400, "INVALID_REQUEST", "Cannot report progress for transfer in status " + current);
        }

        Map<String, Object> progressPayload = new LinkedHashMap<>();
        progressPayload.put("transferId", transferId);
        progressPayload.put("bytesTransferred", bytesTransferred);
        progressPayload.put("totalBytes", totalBytes);
        progressPayload.put("speedBytesPerSec", speedBytesPerSec);
        progressPayload.put("percentage", totalBytes > 0 ? (bytesTransferred * 100.0 / totalBytes) : 0.0);

        emit("transfer:progress", existing.senderUserId(), existing.senderDeviceId(), progressPayload);
        emit("transfer:progress", existing.receiverUserId(), existing.receiverDeviceId(), progressPayload);
    }

    public TransferDto completeTransfer(UUID callerUserId, UUID transferId) throws SQLException {
        TransferRecord existing = transferRepository.findById(transferId)
            .orElseThrow(() -> new FileException(404, "TRANSFER_NOT_FOUND", "Transfer was not found."));

        if (!existing.senderUserId().equals(callerUserId) && !existing.receiverUserId().equals(callerUserId)) {
            throw new FileException(403, "FORBIDDEN", "Only transfer participants can complete this transfer.");
        }

        TransferStatus current = parseStatus(existing.status());
        if (!current.canTransitionTo(TransferStatus.COMPLETED)) {
            throw new FileException(400, "INVALID_TRANSITION", "Cannot complete transfer in status " + current);
        }

        Instant now = Instant.now();
        TransferRecord updated = transferRepository.updateStatus(transferId, TransferStatus.COMPLETED.name(), null, now, null, null);
        FileRecord file = fileRepository.findById(updated.fileId()).orElse(null);
        TransferDto dto = toTransferDto(updated, file);

        emit("transfer:completed", updated.senderUserId(), updated.senderDeviceId(), dto);
        emit("transfer:completed", updated.receiverUserId(), updated.receiverDeviceId(), dto);
        return dto;
    }

    public TransferDto failTransfer(UUID callerUserId, UUID transferId, String error) throws SQLException {
        TransferRecord existing = transferRepository.findById(transferId)
            .orElseThrow(() -> new FileException(404, "TRANSFER_NOT_FOUND", "Transfer was not found."));

        if (!existing.senderUserId().equals(callerUserId) && !existing.receiverUserId().equals(callerUserId)) {
            throw new FileException(403, "FORBIDDEN", "Only transfer participants can report transfer failure.");
        }

        TransferStatus current = parseStatus(existing.status());
        if (!current.canTransitionTo(TransferStatus.FAILED)) {
            throw new FileException(400, "INVALID_TRANSITION", "Cannot fail transfer in status " + current);
        }

        Instant now = Instant.now();
        TransferRecord updated = transferRepository.updateStatus(transferId, TransferStatus.FAILED.name(), null, null, now, error);
        FileRecord file = fileRepository.findById(updated.fileId()).orElse(null);
        TransferDto dto = toTransferDto(updated, file);

        emit("transfer:failed", updated.senderUserId(), updated.senderDeviceId(), dto);
        emit("transfer:failed", updated.receiverUserId(), updated.receiverDeviceId(), dto);
        return dto;
    }

    public TransferDto cancelTransfer(UUID callerUserId, UUID transferId, String reason) throws SQLException {
        TransferRecord existing = transferRepository.findById(transferId)
            .orElseThrow(() -> new FileException(404, "TRANSFER_NOT_FOUND", "Transfer was not found."));

        if (!existing.senderUserId().equals(callerUserId) && !existing.receiverUserId().equals(callerUserId)) {
            throw new FileException(403, "FORBIDDEN", "Only transfer participants can cancel this transfer.");
        }

        TransferStatus current = parseStatus(existing.status());
        if (!current.canTransitionTo(TransferStatus.CANCELLED)) {
            throw new FileException(400, "INVALID_TRANSITION", "Cannot cancel transfer in status " + current);
        }

        TransferRecord updated = transferRepository.updateStatus(transferId, TransferStatus.CANCELLED.name(), null, null, null, reason);
        FileRecord file = fileRepository.findById(updated.fileId()).orElse(null);
        TransferDto dto = toTransferDto(updated, file);

        emit("transfer:cancelled", updated.senderUserId(), updated.senderDeviceId(), dto);
        emit("transfer:cancelled", updated.receiverUserId(), updated.receiverDeviceId(), dto);
        return dto;
    }

    private void emit(String event, UUID userId, String deviceId, Object payload) {
        if (broadcaster != null) {
            try {
                broadcaster.broadcast(userId, deviceId, event, payload);
            } catch (Exception ignored) {}
        }
    }

    private TransferStatus parseStatus(String s) {
        try {
            return TransferStatus.valueOf(s);
        } catch (Exception e) {
            return TransferStatus.PENDING;
        }
    }

    public TransferDto toTransferDto(TransferRecord r, FileRecord f) {
        FileDto fileDto = f != null ? fileManagementService.toFileDto(f) : null;
        return new TransferDto(
            r.id(),
            r.fileId(),
            fileDto,
            r.senderUserId(),
            r.receiverUserId(),
            r.senderDeviceId(),
            r.receiverDeviceId(),
            r.status(),
            r.createdAt() != null ? r.createdAt().toString() : null,
            r.startedAt() != null ? r.startedAt().toString() : null,
            r.completedAt() != null ? r.completedAt().toString() : null,
            r.failedAt() != null ? r.failedAt().toString() : null,
            r.errorMessage()
        );
    }

    public DeviceDto toDeviceDto(Device d, boolean isOnline) {
        return new DeviceDto(
            d.id(),
            d.userId(),
            d.deviceName(),
            d.peerId(),
            d.ipAddress(),
            d.port(),
            d.lastSeenAt() != null ? d.lastSeenAt().toString() : null,
            isOnline
        );
    }
}
