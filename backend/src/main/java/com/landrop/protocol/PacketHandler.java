package com.landrop.protocol;

/**
 * Functional interface for handling incoming protocol packets.
 */
@FunctionalInterface
public interface PacketHandler {

    /**
     * Handles the specified protocol packet.
     *
     * @param packet the received packet
     * @throws Exception if an error occurs during processing
     */
    void handle(Packet packet) throws Exception;
}
