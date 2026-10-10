package com.landrop.device.service;

import com.landrop.auth.exception.ConflictException;
import com.landrop.auth.exception.ForbiddenException;
import com.landrop.auth.exception.NotFoundException;
import com.landrop.auth.exception.ValidationException;
import com.landrop.auth.model.AuthErrorCode;
import com.landrop.auth.model.RefreshToken;
import com.landrop.auth.repository.RefreshTokenRepository;
import com.landrop.auth.security.SessionVerifier;
import com.landrop.database.model.Device;
import com.landrop.database.repository.DeviceRepository;
import com.landrop.device.dto.DeviceDto;
import com.landrop.device.dto.DeviceEnrollRequest;
import com.landrop.device.dto.DeviceRenameRequest;
import com.landrop.server.clients.ClientRegistry;
import com.landrop.admin.logging.OperationalLogService;
import com.landrop.util.AppLogger;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestration service for persistent device enrollment, ownership validation,
 * and user device management (FOUNDATION-04A.5 - 04A.7, 04A.13 - 04A.16).
 */
public class DeviceService {

    private static final String TAG = "DeviceService";

    private final DeviceRepository deviceRepository;
    private final ClientRegistry clientRegistry;
    private final RefreshTokenRepository sessionRepository;

    public DeviceService(DeviceRepository deviceRepository, ClientRegistry clientRegistry) {
        this(deviceRepository, clientRegistry, null);
    }

    public DeviceService(DeviceRepository deviceRepository, ClientRegistry clientRegistry,
                         RefreshTokenRepository sessionRepository) {
        this.deviceRepository = Objects.requireNonNull(deviceRepository, "deviceRepository cannot be null");
        this.clientRegistry = clientRegistry;
        this.sessionRepository = sessionRepository;
    }

    public DeviceDto enrollDevice(UUID userId, DeviceEnrollRequest request) throws SQLException {
        return enrollDevice(userId, null, request);
    }

    public DeviceDto enrollDevice(UUID userId, UUID sessionId, DeviceEnrollRequest request) throws SQLException {
        if (userId == null) {
            throw new ValidationException("User identity required from authenticated session");
        }
        if (request == null) {
            throw new ValidationException("Device enrollment payload cannot be empty");
        }
        request.validate();

        String clientInstanceId = request.clientInstanceId().trim();
        String deviceName = request.resolvedName();
        String platform = request.resolvedPlatform();
        String clientVersion = request.resolvedClientVersion();

        Enrollment enrollment = deviceRepository.inTransaction(conn -> {
            deviceRepository.lockClientInstallation(conn, clientInstanceId);
            Optional<Device> existingOpt = deviceRepository.findByClientInstanceId(conn, clientInstanceId);
            if (existingOpt.isPresent() && !existingOpt.get().userId().equals(userId)) {
                throw new ConflictException("This client installation belongs to another user. Ask an administrator to resolve ownership.",
                        AuthErrorCode.DEVICE_ALREADY_OWNED);
            }
            RefreshToken session = sessionRepository != null
                    ? SessionVerifier.requireActive(sessionRepository, sessionId, userId) : null;
            if (session != null && session.deviceId() != null
                    && (existingOpt.isEmpty() || !session.deviceId().equals(existingOpt.get().id()))) {
                throw new ConflictException("Session is already bound to another device", AuthErrorCode.DEVICE_CONFLICT);
            }
            Device savedDevice;

            if (existingOpt.isPresent()) {
                Device existing = existingOpt.get();
                // Same user: update existing device metadata
                Device updated = new Device(
                    existing.id(),
                    userId,
                    clientInstanceId,
                    deviceName,
                    platform,
                    clientVersion,
                    Device.STATUS_ACTIVE,
                    existing.peerId(),
                    existing.ipAddress(),
                    existing.port(),
                    Instant.now(),
                    existing.createdAt(),
                    Instant.now()
                );
                savedDevice = deviceRepository.upsert(conn, updated);
            } else {
                // New device: assign persistent device ID and persist
                String deviceId = "dev_" + UUID.randomUUID().toString();
                Device newDevice = new Device(
                    deviceId,
                    userId,
                    clientInstanceId,
                    deviceName,
                    platform,
                    clientVersion,
                    Device.STATUS_ACTIVE,
                    null,
                    null,
                    null,
                    Instant.now(),
                    Instant.now(),
                    Instant.now()
                );
                savedDevice = deviceRepository.upsert(conn, newDevice);
            }

            if (session != null && !sessionRepository.bindDevice(conn, sessionId, userId, savedDevice.id())) {
                throw new ConflictException("Session could not be bound to this device", AuthErrorCode.DEVICE_CONFLICT);
            }
            return new Enrollment(savedDevice, existingOpt.isPresent());
        });
        Device savedDevice = enrollment.device();
        AppLogger.info(TAG, "Persisted enrolled device: " + savedDevice.id() + " for user " + userId);
        OperationalLogService.getInstance().info(enrollment.reconnected() ? "DEVICE_RECONNECTED" : "DEVICE_ENROLLED",
                userId.toString(), savedDevice.id(),
                enrollment.reconnected() ? "Existing client installation enrolled again." : "New client installation enrolled.");
        return DeviceDto.from(savedDevice, isDeviceOnline(savedDevice));
    }

    private record Enrollment(Device device, boolean reconnected) {}

    public List<DeviceDto> listUserDevices(UUID userId) throws SQLException {
        if (userId == null) return List.of();

        List<Device> devices = deviceRepository.findByUserId(userId);
        List<DeviceDto> dtos = new ArrayList<>(devices.size());

        for (Device d : devices) {
            boolean isOnline = isDeviceOnline(d);
            dtos.add(DeviceDto.from(d, isOnline));
        }
        return dtos;
    }

    public DeviceDto renameDevice(UUID userId, String deviceId, DeviceRenameRequest request) throws SQLException {
        if (userId == null) {
            throw new ValidationException("User identity required");
        }
        if (deviceId == null || deviceId.isBlank()) {
            throw new ValidationException("deviceId is required");
        }
        if (request == null) {
            throw new ValidationException("Rename payload cannot be empty");
        }
        request.validate();

        Optional<Device> devOpt = deviceRepository.findById(deviceId);
        if (devOpt.isEmpty()) {
            throw new NotFoundException("Device not found: " + deviceId, AuthErrorCode.DEVICE_NOT_FOUND);
        }

        Device device = devOpt.get();
        if (!device.userId().equals(userId)) {
            throw new ForbiddenException("You do not have permission to modify this device", AuthErrorCode.FORBIDDEN);
        }

        String newName = request.resolvedName();
        if (!deviceRepository.updateName(deviceId, newName)) {
            throw new NotFoundException("Device not found: " + deviceId, AuthErrorCode.DEVICE_NOT_FOUND);
        }

        Device updated = device.withDeviceName(newName);
        boolean isOnline = isDeviceOnline(updated);
        AppLogger.info(TAG, "User " + userId + " renamed device " + deviceId + " to '" + newName + "'");
        return DeviceDto.from(updated, isOnline);
    }

    public Optional<DeviceDto> getDevice(String deviceId) throws SQLException {
        if (deviceId == null || deviceId.isBlank()) return Optional.empty();
        return deviceRepository.findById(deviceId)
                .map(d -> DeviceDto.from(d, isDeviceOnline(d)));
    }

    public boolean isDeviceOnline(Device device) {
        if (device == null || clientRegistry == null) return false;
        return clientRegistry.isDeviceOnline(device.id());
    }
}
