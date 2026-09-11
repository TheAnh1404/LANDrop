package com.landrop.discovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerRegistryTest {

    @Test
    @DisplayName("Test 1 — Register peer: A registers, verify registry contains A")
    void test1_RegisterPeer() {
        PeerRegistry registry = new PeerRegistry();
        PeerInfo peerA = new PeerInfo("id-a", "Peer A", "127.0.0.1", 5001);

        PeerRegistry.RegistrationResult result = registry.register(peerA);
        assertTrue(result.success());
        assertEquals(1, registry.size());

        PeerInfo stored = registry.getPeer("id-a");
        assertNotNull(stored);
        assertEquals("Peer A", stored.getDisplayName());
        assertEquals(PeerStatus.ONLINE, stored.getStatus());
    }

    @Test
    @DisplayName("Test 2 — Register multiple peers: Register A, B, C; verify all exist")
    void test2_RegisterMultiplePeers() {
        PeerRegistry registry = new PeerRegistry();
        registry.register(new PeerInfo("id-a", "Peer A", "127.0.0.1", 5001));
        registry.register(new PeerInfo("id-b", "Peer B", "127.0.0.1", 5002));
        registry.register(new PeerInfo("id-c", "Peer C", "127.0.0.1", 5003));

        assertEquals(3, registry.size());
        assertNotNull(registry.getPeer("id-a"));
        assertNotNull(registry.getPeer("id-b"));
        assertNotNull(registry.getPeer("id-c"));
    }

    @Test
    @DisplayName("Test 3 — Peer list excludes requester: A requests list, verify B and C exist, A does not")
    void test3_PeerListExcludesRequester() {
        PeerRegistry registry = new PeerRegistry();
        registry.register(new PeerInfo("id-a", "Peer A", "127.0.0.1", 5001));
        registry.register(new PeerInfo("id-b", "Peer B", "127.0.0.1", 5002));
        registry.register(new PeerInfo("id-c", "Peer C", "127.0.0.1", 5003));

        List<PeerInfo> peerListForA = registry.getPeerList("id-a");
        assertEquals(2, peerListForA.size());
        assertTrue(peerListForA.stream().anyMatch(p -> p.getPeerId().equals("id-b")));
        assertTrue(peerListForA.stream().anyMatch(p -> p.getPeerId().equals("id-c")));
        assertFalse(peerListForA.stream().anyMatch(p -> p.getPeerId().equals("id-a")), "Requester A must be excluded");
    }

    @Test
    @DisplayName("Test 4 — Duplicate peer ID: Reject duplicate registration while peer is active")
    void test4_DuplicatePeerIdHandling() {
        PeerRegistry registry = new PeerRegistry(15000);
        PeerInfo peerA1 = new PeerInfo("id-a", "Peer A", "127.0.0.1", 5001);
        PeerRegistry.RegistrationResult res1 = registry.register(peerA1);
        assertTrue(res1.success());

        // Attempt duplicate registration of active peer
        PeerInfo peerA2 = new PeerInfo("id-a", "Peer A Duplicate", "127.0.0.1", 5009);
        PeerRegistry.RegistrationResult res2 = registry.register(peerA2);
        assertFalse(res2.success(), "Should reject duplicate active registration");
        assertTrue(res2.message().contains("currently active"));

        // Original info remains intact
        assertEquals("Peer A", registry.getPeer("id-a").getDisplayName());
    }

    @Test
    @DisplayName("Test 5 — Heartbeat: Register A, send heartbeat, verify lastSeen changes")
    void test5_HeartbeatUpdatesLastSeen() throws Exception {
        PeerRegistry registry = new PeerRegistry();
        PeerInfo peerA = new PeerInfo("id-a", "Peer A", "127.0.0.1", 5001);
        peerA.setLastSeen(1000L); // simulate older timestamp
        registry.register(peerA);

        long oldLastSeen = registry.getPeer("id-a").getLastSeen();
        Thread.sleep(10);

        boolean ok = registry.updateHeartbeat("id-a");
        assertTrue(ok);

        long newLastSeen = registry.getPeer("id-a").getLastSeen();
        assertTrue(newLastSeen > oldLastSeen, "lastSeen timestamp must be updated after heartbeat");
    }

    @Test
    @DisplayName("Test 6 — Timeout: Register peer, simulate stale lastSeen, run cleanup, verify peer removed")
    void test6_TimeoutRemovesStalePeers() {
        PeerRegistry registry = new PeerRegistry(1000); // 1-second timeout
        PeerInfo peerA = new PeerInfo("id-a", "Peer A", "127.0.0.1", 5001);
        registry.register(peerA);
        peerA.setLastSeen(System.currentTimeMillis() - 2000); // 2 seconds ago (stale)

        PeerInfo peerB = new PeerInfo("id-b", "Peer B", "127.0.0.1", 5002);
        registry.register(peerB);

        assertEquals(2, registry.size());

        List<PeerInfo> removed = registry.cleanupStalePeers();
        assertEquals(1, removed.size());
        assertEquals("id-a", removed.get(0).getPeerId());

        assertNull(registry.getPeer("id-a"), "Stale peer A must be removed");
        assertNotNull(registry.getPeer("id-b"), "Active peer B must remain");
    }

    @Test
    @DisplayName("Test 7 — Unregister: Register A, unregister A, verify removed")
    void test7_UnregisterPeer() {
        PeerRegistry registry = new PeerRegistry();
        registry.register(new PeerInfo("id-a", "Peer A", "127.0.0.1", 5001));
        assertEquals(1, registry.size());

        PeerInfo removed = registry.unregister("id-a");
        assertNotNull(removed);
        assertEquals("id-a", removed.getPeerId());
        assertEquals(0, registry.size());
        assertNull(registry.getPeer("id-a"));
    }

    @Test
    @DisplayName("Test 8 — Concurrent registration: Register multiple peers concurrently, verify consistency")
    void test8_ConcurrentRegistration() throws InterruptedException {
        PeerRegistry registry = new PeerRegistry();
        int peerCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(peerCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < peerCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    PeerInfo p = new PeerInfo("peer-" + index, "User " + index, "127.0.0.1", 6000 + index);
                    if (registry.register(p).success()) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(peerCount, successCount.get());
        assertEquals(peerCount, registry.size());
    }
}
