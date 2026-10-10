package com.landrop.server.desktop;

import com.landrop.server.bootstrap.ServerBootstrap;
import com.landrop.util.AppLogger;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Serializes real server lifecycle actions off the Swing event thread. */
public final class DesktopServerController {
    public enum State { STOPPED, STARTING, RUNNING, STOPPING, FAILED }
    interface RuntimeHandle {
        void start() throws Exception;
        void stop();
        default ServerBootstrap bootstrap() { return null; }
    }

    private final Supplier<RuntimeHandle> factory;
    private final BiConsumer<State, String> listener;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "desktop-server-lifecycle"));
    private volatile State state = State.STOPPED;
    private volatile RuntimeHandle runtime;
    private boolean closing;
    private volatile com.landrop.server.config.ServerConfig serviceConfig;

    public DesktopServerController(Supplier<DesktopServerSettings> settings, BiConsumer<State, String> listener) {
        this.listener = listener;
        this.factory = () -> new RuntimeHandle() {
            private final ServerBootstrap backend = new ServerBootstrap(nextConfig(settings.get()));
            @Override public void start() throws Exception { backend.start(); }
            @Override public void stop() {
                if (backend.isRunning()) backend.stop();
                else {
                    // A failed startup may already have bound a listener before the running flag was set.
                    try { if (backend.getRealtimeServer() != null) backend.getRealtimeServer().stop(1000); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                    if (backend.getHttpServer() != null) backend.getHttpServer().stop(0);
                    backend.getClientRegistry().clear();
                    backend.getTransferCoordinator().clear();
                }
            }
            @Override public ServerBootstrap bootstrap() { return backend; }
        };
    }

    DesktopServerController(Supplier<RuntimeHandle> factory, BiConsumer<State, String> listener, boolean ignored) {
        this.factory = factory;
        this.listener = listener;
    }

    public State state() { return state; }
    public void setServiceConfig(com.landrop.server.config.ServerConfig config) { serviceConfig = config; }
    private com.landrop.server.config.ServerConfig nextConfig(DesktopServerSettings settings) {
        var base = serviceConfig == null
                ? new com.landrop.server.config.ServerConfigManager(settings.serverConfig()).getPendingConfig() : serviceConfig;
        return new com.landrop.server.config.ServerConfig(settings.bindHost(), settings.httpPort(), settings.wsPort(),
                base.getProtocolVersion(), base.getHeartbeatIntervalMs(), base.getHeartbeatTimeoutMs(), base.getEnvironment(), base.getLogLevel());
    }
    public ServerBootstrap bootstrap() { RuntimeHandle current = runtime; return current == null ? null : current.bootstrap(); }

    public synchronized boolean start() {
        if (closing || runtime != null || (state != State.STOPPED && state != State.FAILED)) return false;
        change(State.STARTING, "Đang kiểm tra dịch vụ và mở cổng kết nối…");
        worker.execute(this::startRuntime);
        return true;
    }

    public synchronized boolean stop() {
        if (closing || (state != State.RUNNING && !(state == State.FAILED && runtime != null))) return false;
        change(State.STOPPING, "Đang đóng kết nối và dừng các dịch vụ…");
        worker.execute(() -> stopRuntime(false));
        return true;
    }

    public synchronized boolean restart() {
        if (closing || state != State.RUNNING) return false;
        change(State.STOPPING, "Đang khởi động lại máy chủ…");
        worker.execute(() -> {
            if (stopRuntime(true)) {
                change(State.STARTING, "Đang khởi động máy chủ với cấu hình đã áp dụng…");
                startRuntime();
            }
        });
        return true;
    }

    public synchronized CompletableFuture<Void> shutdown() {
        closing = true;
        CompletableFuture<Void> completion = new CompletableFuture<>();
        if (worker.isShutdown()) { completion.complete(null); return completion; }
        worker.execute(() -> {
            try {
                if (runtime != null && !stopRuntime(false)) throw new IllegalStateException("Không thể dừng máy chủ.");
                worker.shutdown();
                completion.complete(null);
            } catch (Exception error) {
                synchronized (this) { closing = false; }
                completion.completeExceptionally(error);
            }
        });
        return completion;
    }

    private void startRuntime() {
        RuntimeHandle next = null;
        try {
            next = factory.get();
            runtime = next;
            next.start();
            change(State.RUNNING, "Máy chủ sẵn sàng nhận kết nối.");
        } catch (Exception failure) {
            if (next != null) {
                try { next.stop(); runtime = null; }
                catch (Exception cleanup) { AppLogger.warn("DesktopServer", "Startup cleanup failed. Stop must be retried."); }
            }
            AppLogger.error("DesktopServer", "Không thể khởi động máy chủ", failure);
            change(State.FAILED, "Không thể khởi động. Kiểm tra cổng, cấu hình và nhật ký để xử lý lỗi.");
        }
    }

    private boolean stopRuntime(boolean restarting) {
        try {
            RuntimeHandle current = runtime;
            if (current != null) current.stop();
            runtime = null;
            if (!restarting) change(State.STOPPED, "Máy chủ đã dừng. Bạn có thể thay đổi cấu hình hoặc khởi động lại.");
            return true;
        } catch (Exception failure) {
            AppLogger.error("DesktopServer", "Không thể dừng máy chủ", failure);
            change(State.FAILED, "Dừng máy chủ chưa hoàn tất. Kiểm tra nhật ký trước khi tiếp tục.");
            return false;
        }
    }

    private void change(State next, String message) {
        state = next;
        listener.accept(next, message);
    }
}
