package moe.hikari.canvas.variable.provider;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 0.4.0-P1-E：异步 Provider 调度框架。
 *
 * <p>跑在守护线程池（2 thread, daemon=true），<b>不在主线程 resolve</b>——满足
 * {@code docs/dynamic-data.md §10 性能纪律：resolve 不在主线程} 要求。</p>
 *
 * <p>P1 阶段 framework only：{@link ProviderBootstrap#initialize} 不注册任何 provider；
 * P3 阶段在那里加 SystemVariableProvider / PapiVariableBridge / ScoreboardProvider /
 * ManualScheduleProvider 注册。</p>
 *
 * <p><b>线程安全</b>：{@link #register} / {@link #unregister} / {@link #shutdown} 都用
 * {@link ConcurrentHashMap#putIfAbsent} / {@link ConcurrentHashMap#remove} 保证不重复
 * 注册 / 重复关停。{@link #shutdown} 后再 {@link #register} 抛 IllegalStateException。</p>
 */
public final class VariableProviderDaemon {

    private static final Logger log = Logger.getLogger(VariableProviderDaemon.class.getName());

    private final ScheduledExecutorService scheduler;
    private final Map<String, VariableProvider> providers = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private volatile boolean shutdown = false;

    public VariableProviderDaemon() {
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "hikari-canvas-var-resolve");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 注册 provider。同 namespace 重复注册抛 {@link IllegalStateException}。
     *
     * <p>流程：putIfAbsent → {@link VariableProvider#initialize()} → 若 {@link
     * VariableProvider#refreshInterval()} &gt; 0 安排定时 refresh。initialize 抛异常 → 回滚
     * putIfAbsent + log warning + 静默返回（不传播）。</p>
     */
    public void register(VariableProvider provider) {
        Objects.requireNonNull(provider, "provider");
        if (shutdown) {
            throw new IllegalStateException("Daemon is shut down");
        }
        String ns = provider.namespace();
        Objects.requireNonNull(ns, "provider.namespace");
        if (providers.putIfAbsent(ns, provider) != null) {
            throw new IllegalStateException(
                    "Provider for namespace '" + ns + "' already registered");
        }
        try {
            provider.initialize();
        } catch (Exception e) {
            log.log(Level.WARNING,
                    "Provider " + ns + " initialize() failed; not registered: " + e.getMessage(), e);
            providers.remove(ns);
            return;
        }
        // schedule refresh
        Duration interval = provider.refreshInterval();
        long intervalMs = interval == null ? 0L : interval.toMillis();
        if (intervalMs > 0) {
            ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(
                    () -> {
                        try {
                            provider.refresh();
                        } catch (Exception e) {
                            log.log(Level.WARNING,
                                    "Provider " + ns + " refresh() failed: " + e.getMessage(), e);
                        }
                    },
                    intervalMs, intervalMs, TimeUnit.MILLISECONDS);
            scheduledTasks.put(ns, task);
        }
        log.info("Registered VariableProvider: " + ns + " (interval=" + intervalMs + "ms)");
    }

    /**
     * 注销 provider：取消定时任务 + 调 {@link VariableProvider#shutdown()}。namespace 不存在静默返回。
     */
    public void unregister(String namespace) {
        VariableProvider p = providers.remove(namespace);
        if (p == null) return;
        ScheduledFuture<?> task = scheduledTasks.remove(namespace);
        if (task != null) {
            task.cancel(false);
        }
        try {
            p.shutdown();
        } catch (Exception e) {
            log.log(Level.WARNING,
                    "Provider " + namespace + " shutdown() failed: " + e.getMessage(), e);
        }
    }

    /** 已注册 provider 的不可变快照。 */
    public List<VariableProvider> registeredProviders() {
        return List.copyOf(providers.values());
    }

    /** 当前是否已 shutdown（测试 / 内省用）。 */
    public boolean isShutdown() {
        return shutdown;
    }

    /**
     * 关停所有 provider + scheduler。多次调用幂等（已 shutdown 直接返回）。
     */
    public void shutdown() {
        if (shutdown) return;
        shutdown = true;
        for (String ns : List.copyOf(providers.keySet())) {
            unregister(ns);
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
