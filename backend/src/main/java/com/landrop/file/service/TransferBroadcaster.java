package com.landrop.file.service;

import java.util.UUID;

/**
 * Interface for broadcasting transfer lifecycle events to specific users and devices.
 */
@FunctionalInterface
public interface TransferBroadcaster {
    void broadcast(UUID userId, String deviceId, String event, Object payload);
}
