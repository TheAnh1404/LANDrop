package com.landrop.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestIdGeneratorTest {

    @Test
    @DisplayName("Verify sequential generation starting from 1")
    void testSequentialGeneration() {
        RequestIdGenerator generator = new RequestIdGenerator();
        assertEquals(1L, generator.nextId());
        assertEquals(2L, generator.nextId());
        assertEquals(3L, generator.nextId());
    }

    @Test
    @DisplayName("Verify thread-safety and unique ID generation across concurrent threads")
    void testConcurrentUniqueIds() throws InterruptedException {
        RequestIdGenerator generator = new RequestIdGenerator();
        int threadCount = 10;
        int idsPerThread = 1000;
        int totalIds = threadCount * idsPerThread;

        Set<Long> generatedIds = ConcurrentHashMap.newKeySet();
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                for (int j = 0; j < idsPerThread; j++) {
                    generatedIds.add(generator.nextId());
                }
                latch.countDown();
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(totalIds, generatedIds.size(), "Every generated request ID must be unique");
    }
}
