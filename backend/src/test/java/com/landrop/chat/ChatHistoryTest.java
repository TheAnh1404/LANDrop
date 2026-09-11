package com.landrop.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatHistoryTest {

    @Test
    @DisplayName("Verify message recording and unmodifiable message view in ChatHistory")
    void testRecordAndRetrieveMessages() {
        ChatHistory history = new ChatHistory();
        assertEquals(0, history.size());

        ChatMessage msg1 = new ChatMessage(1L, "Peer A", "Message 1");
        ChatMessage msg2 = new ChatMessage(2L, "Peer B", "Message 2");

        history.add(msg1);
        history.add(msg2);

        assertEquals(2, history.size());
        List<ChatMessage> list = history.getMessages();
        assertEquals(2, list.size());
        assertEquals("Message 1", list.get(0).content());
        assertEquals("Message 2", list.get(1).content());

        // View must be unmodifiable
        assertThrows(UnsupportedOperationException.class, () -> list.add(new ChatMessage(3L, "C", "Fail")));

        history.clear();
        assertEquals(0, history.size());
        assertTrue(history.getMessages().isEmpty());
    }

    @Test
    @DisplayName("Verify thread safety of ChatHistory under concurrent writes")
    void testConcurrentWrites() throws InterruptedException {
        ChatHistory history = new ChatHistory();
        int threads = 10;
        int messagesPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < messagesPerThread; j++) {
                        history.add(new ChatMessage(threadId * 1000L + j, "User" + threadId, "Msg" + j));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(threads * messagesPerThread, history.size());
    }
}
