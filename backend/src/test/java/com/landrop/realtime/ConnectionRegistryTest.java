package com.landrop.realtime;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionRegistryTest {
    @Test void parallelAddRemoveAndRepeatedClosePreserveSiblingConnection() throws Exception {
        var registry = new ConnectionRegistry();
        UUID user = UUID.randomUUID(), channel = UUID.randomUUID();
        var principal = new SocketPrincipal(user, "credential", Instant.now().plusSeconds(300));
        var stable = new ConnectionRegistry.Connection(UUID.randomUUID(),principal,channel,"desktop",Instant.now(),null);
        registry.add(stable);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Callable<Void>>();
            for (int i = 0; i < 100; i++) tasks.add(() -> {
                var temporary = new ConnectionRegistry.Connection(UUID.randomUUID(),principal,channel,"laptop",Instant.now(),null);
                registry.add(temporary); registry.remove(temporary); registry.remove(temporary); return null;
            });
            for (var future : executor.invokeAll(tasks)) future.get();
        }
        assertEquals(1,registry.count(user));
        assertEquals(List.of(stable),registry.snapshot());
        registry.remove(stable); assertEquals(0,registry.count(user)); assertTrue(registry.snapshot().isEmpty());
    }
}
