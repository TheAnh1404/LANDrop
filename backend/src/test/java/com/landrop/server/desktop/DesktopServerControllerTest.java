package com.landrop.server.desktop;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.landrop.server.desktop.DesktopServerController.State.*;

class DesktopServerControllerTest {
    private static class Runtime implements DesktopServerController.RuntimeHandle {
        final List<String> calls;
        final String name;
        boolean failStart;
        final AtomicBoolean failStop = new AtomicBoolean();
        Runtime(List<String> calls, String name) { this.calls = calls; this.name = name; }
        @Override public void start() { calls.add(name + ":start"); if (failStart) throw new IllegalStateException("test start failure"); }
        @Override public void stop() { calls.add(name + ":stop"); if (failStop.getAndSet(false)) throw new IllegalStateException("test stop failure"); }
    }
    private static void awaitState(BlockingQueue<DesktopServerController.State> events, DesktopServerController.State expected) throws Exception {
        for (int i = 0; i < 8; i++) {
            var state = events.poll(3, TimeUnit.SECONDS);
            assertNotNull(state, "Waiting for " + expected);
            if (state == expected) return;
        }
        fail("State did not reach " + expected);
    }

    @Test void doubleStartIsIgnoredAndRestartStopsBeforeStartingNewRuntime() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        var states = new LinkedBlockingQueue<DesktopServerController.State>();
        var counter = new AtomicInteger();
        var controller = new DesktopServerController(() -> new Runtime(calls, "r" + counter.incrementAndGet()), (state, message) -> states.add(state), true);
        try {
            assertTrue(controller.start()); assertFalse(controller.start()); awaitState(states, RUNNING);
            assertTrue(controller.restart()); assertFalse(controller.start()); awaitState(states, RUNNING);
            assertEquals(List.of("r1:start", "r1:stop", "r2:start"), calls);
            assertTrue(controller.stop()); awaitState(states, STOPPED);
            assertEquals("r2:stop", calls.getLast());
        } finally { controller.shutdown().get(3, TimeUnit.SECONDS); }
    }

    @Test void startupFailureCleansPartiallyStartedRuntimeAndCanRetry() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        var states = new LinkedBlockingQueue<DesktopServerController.State>();
        var runtime = new Runtime(calls, "r1"); runtime.failStart = true;
        var controller = new DesktopServerController(() -> runtime, (state, message) -> states.add(state), true);
        try {
            controller.start(); awaitState(states, FAILED);
            assertEquals(List.of("r1:start", "r1:stop"), calls);
            runtime.failStart = false;
            assertTrue(controller.start()); awaitState(states, RUNNING);
        } finally { controller.shutdown().get(3, TimeUnit.SECONDS); }
    }

    @Test void failedStopCannotBeOverwrittenByStartAndCanBeRetried() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        var states = new LinkedBlockingQueue<DesktopServerController.State>();
        var runtime = new Runtime(calls, "r1");
        var controller = new DesktopServerController(() -> runtime, (state, message) -> states.add(state), true);
        try {
            controller.start(); awaitState(states, RUNNING);
            runtime.failStop.set(true); controller.stop(); awaitState(states, FAILED);
            assertFalse(controller.start());
            assertTrue(controller.stop()); awaitState(states, STOPPED);
            assertEquals(List.of("r1:start", "r1:stop", "r1:stop"), calls);
        } finally { controller.shutdown().get(3, TimeUnit.SECONDS); }
    }

    @Test void failedShutdownCanBeRetriedWithoutLeavingServiceRunning() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        var states = new LinkedBlockingQueue<DesktopServerController.State>();
        var runtime = new Runtime(calls, "r1");
        var controller = new DesktopServerController(() -> runtime, (state, message) -> states.add(state), true);
        controller.start(); awaitState(states, RUNNING);
        runtime.failStop.set(true);
        assertThrows(ExecutionException.class, () -> controller.shutdown().get(3, TimeUnit.SECONDS));
        assertFalse(controller.start());
        controller.shutdown().get(3, TimeUnit.SECONDS);
        assertEquals(STOPPED, controller.state());
        assertFalse(controller.start());
    }

    @Test void closeDuringStartupWaitsThenStopsBeforeCompleting() throws Exception {
        var starting = new CountDownLatch(1); var release = new CountDownLatch(1);
        var stopped = new AtomicBoolean();
        var controller = new DesktopServerController(() -> new DesktopServerController.RuntimeHandle() {
            @Override public void start() throws Exception { starting.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); }
            @Override public void stop() { stopped.set(true); }
        }, (state, message) -> { }, true);
        controller.start(); assertTrue(starting.await(3, TimeUnit.SECONDS));
        var completion = controller.shutdown();
        try { assertFalse(completion.isDone()); assertFalse(controller.start()); }
        finally { release.countDown(); completion.get(3, TimeUnit.SECONDS); }
        assertTrue(stopped.get()); assertEquals(STOPPED, controller.state());
    }

    @Test void realBackendRestartsOnConfiguredPortsAndReleasesBothListeners() throws Exception {
        String database = System.getProperty("landrop.database.enabled");
        String environment = System.getProperty("landrop.environment");
        System.setProperty("landrop.database.enabled", "false");
        System.setProperty("landrop.environment", "test");
        var states = new LinkedBlockingQueue<DesktopServerController.State>();
        int httpPort, wsPort;
        try (var http = new java.net.ServerSocket(0); var ws = new java.net.ServerSocket(0)) {
            httpPort = http.getLocalPort(); wsPort = ws.getLocalPort();
        }
        var settings = new DesktopServerSettings("127.0.0.1", httpPort, wsPort, false, 3000, false);
        var controller = new DesktopServerController(() -> settings, (state, message) -> states.add(state));
        try {
            assertTrue(controller.start()); awaitState(states, RUNNING);
            var first = controller.bootstrap();
            assertEquals(httpPort, first.getHttpServer().getPort());
            assertEquals(wsPort, first.getRealtimeServer().getPort());
            try (var http = java.net.http.HttpClient.newHttpClient()) {
                var response = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(settings.clientUrl("192.168.1.10", httpPort)))
                        .GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertTrue(response.body().contains("LANDrop"));
            }
            controller.setServiceConfig(new com.landrop.server.config.ServerConfig("127.0.0.1", httpPort, wsPort, 1, 7000, 12000, "test", "DEBUG"));
            assertTrue(controller.restart()); awaitState(states, RUNNING);
            assertNotSame(first, controller.bootstrap());
            assertEquals(7000, controller.bootstrap().getConfig().getHeartbeatIntervalMs());
            assertEquals(12000, controller.bootstrap().getConfig().getHeartbeatTimeoutMs());
            assertEquals("DEBUG", controller.bootstrap().getConfig().getLogLevel());
            assertFalse(first.isRunning());
            controller.shutdown().get(5, TimeUnit.SECONDS);
            try (var http = new java.net.ServerSocket(httpPort); var ws = new java.net.ServerSocket(wsPort)) {
                assertTrue(http.isBound()); assertTrue(ws.isBound());
            }
        } finally {
            controller.shutdown().get(5, TimeUnit.SECONDS);
            if (database == null) System.clearProperty("landrop.database.enabled"); else System.setProperty("landrop.database.enabled", database);
            if (environment == null) System.clearProperty("landrop.environment"); else System.setProperty("landrop.environment", environment);
        }
    }
}
