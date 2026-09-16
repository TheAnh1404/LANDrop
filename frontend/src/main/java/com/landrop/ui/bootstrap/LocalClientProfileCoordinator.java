package com.landrop.ui.bootstrap;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * Assigns each concurrently running desktop process a separate local profile.
 * The loopback socket is held for the process lifetime, making the allocation
 * atomic across launchers without relying on a stale lock file.
 */
public final class LocalClientProfileCoordinator implements AutoCloseable {
    private static final int LOCK_PORT_START = 47601;
    private static final int MAX_LOCAL_INSTANCES = 32;

    private final String profile;
    private final ServerSocket lockSocket;

    private LocalClientProfileCoordinator(String profile, ServerSocket lockSocket) {
        this.profile = profile;
        this.lockSocket = lockSocket;
    }

    /**
     * Uses an explicit profile unchanged. Without one, the first process uses
     * the normal profile and every additional process receives an isolated one.
     */
    public static LocalClientProfileCoordinator acquire(String requestedProfile) {
        if (requestedProfile != null && !requestedProfile.isBlank()) {
            return new LocalClientProfileCoordinator(requestedProfile.trim(), null);
        }

        for (int slot = 1; slot <= MAX_LOCAL_INSTANCES; slot++) {
            try {
                ServerSocket socket = new ServerSocket();
                socket.setReuseAddress(false);
                socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), LOCK_PORT_START + slot - 1));
                String profile = slot == 1 ? "default" : "instance-" + slot;
                return new LocalClientProfileCoordinator(profile, socket);
            } catch (IOException ignored) {
                // A live client (or another program) owns this slot; try the next one.
            }
        }
        throw new IllegalStateException("Too many LANDrop clients are already running on this computer.");
    }

    public String profile() {
        return profile;
    }

    @Override
    public void close() {
        if (lockSocket == null) {
            return;
        }
        try {
            lockSocket.close();
        } catch (IOException ignored) {
            // Closing during application shutdown is best effort.
        }
    }
}
