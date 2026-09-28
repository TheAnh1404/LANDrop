package com.landrop.discovery;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class UdpDiscoveryServiceTest {

    @Test
    public void testUdpPeerDiscoveryBetweenTwoInstances() throws Exception {
        int testUdpPort = 18889;

        String peerAId = UUID.randomUUID().toString();
        String peerBId = UUID.randomUUID().toString();

        CountDownLatch peerADiscoveredLatch = new CountDownLatch(1);
        CountDownLatch peerBDiscoveredLatch = new CountDownLatch(1);

        AtomicReference<PeerInfo> discoveredByA = new AtomicReference<>();
        AtomicReference<PeerInfo> discoveredByB = new AtomicReference<>();

        try (UdpDiscoveryService serviceA = new UdpDiscoveryService(peerAId, "Alice", 6001, testUdpPort);
             UdpDiscoveryService serviceB = new UdpDiscoveryService(peerBId, "Bob", 6002, testUdpPort)) {

            serviceA.addPeerDiscoveredListener(peer -> {
                if (peer.getPeerId().equals(peerBId)) {
                    discoveredByA.set(peer);
                    peerADiscoveredLatch.countDown();
                }
            });

            serviceB.addPeerDiscoveredListener(peer -> {
                if (peer.getPeerId().equals(peerAId)) {
                    discoveredByB.set(peer);
                    peerBDiscoveredLatch.countDown();
                }
            });

            serviceA.start();
            serviceB.start();

            // Trigger broadcast
            serviceA.broadcastPresence();
            serviceB.broadcastPresence();

            assertTrue(peerADiscoveredLatch.await(5, TimeUnit.SECONDS), "Peer A should discover Peer B via UDP");
            assertNotNull(discoveredByA.get());
            assertEquals("Bob", discoveredByA.get().getDisplayName());
            assertEquals(6002, discoveredByA.get().getPort());

            assertTrue(peerBDiscoveredLatch.await(5, TimeUnit.SECONDS), "Peer B should discover Peer A via UDP");
            assertNotNull(discoveredByB.get());
            assertEquals("Alice", discoveredByB.get().getDisplayName());
            assertEquals(6001, discoveredByB.get().getPort());
        }
    }
}
