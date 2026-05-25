package moe.hikari.canvas.variable.plugin;

import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * 0.4.0-P4-P：Plugin Push API 限流器（{@code docs/dynamic-data.md §10.2}）。
 *
 * <h2>双层限流</h2>
 * <ul>
 *   <li><b>per-plugin</b> 默认 100/s — 超出 drop tail + WARN log（每秒最多一次）</li>
 *   <li><b>全局</b> 默认 1000/s — 超出触发保护期（默认 10s 内 reject 全部 push）+ WARN log</li>
 * </ul>
 *
 * <h2>实现</h2>
 * <p>1s 固定窗口 token bucket（简单、可预测、O(1) {@link ConcurrentHashMap}）。
 * 窗口 key = {@code epochSecond}；每秒自动重置（lazy reset on access）。</p>
 *
 * <h2>批量计费</h2>
 * <p>{@link #tryAcquireBatch} 给 {@link HikariCanvasAPIImpl#setVariables} 用——一次性
 * 占 N 个 token，要么全成要么全 reject（不部分写入）；超限时 <b>不</b> 部分占用全局窗口
 * （globalCount 已经 add 进去，无法 rollback；这是 fixed-window 的固有妥协——
 * 等下个 window 自动重置即可，最坏 1s 内 reject 后续）。</p>
 *
 * <h2>线程安全</h2>
 * <p>全 {@link ConcurrentHashMap} + {@link AtomicLong}，无锁、无 synchronized 块。
 * 与 {@link moe.hikari.canvas.session.SessionRateLimiter} 风格一致但用 atomic 版本——
 * Push API 调用方多为后台线程，竞争率高于 session input。</p>
 *
 * @see HikariCanvasAPIImpl
 */
public final class PushRateLimiter {

    private static final Logger log = Logger.getLogger(PushRateLimiter.class.getName());

    /**
     * 限流参数（不可变）。从 {@code config.yml dynamic.push-rate-limit} 加载，
     * 详见 {@link moe.hikari.canvas.HikariCanvasConfig}。
     *
     * @param perPluginPerSecond   单插件每秒 push 上限（默认 100）
     * @param globalPerSecond      全局每秒 push 上限（默认 1000）
     * @param globalCircuitBreakMs 全局触限后保护期（默认 10_000ms）
     */
    public record Config(
            int perPluginPerSecond,
            int globalPerSecond,
            long globalCircuitBreakMs
    ) {
        public Config {
            if (perPluginPerSecond <= 0) {
                throw new IllegalArgumentException("perPluginPerSecond must be > 0");
            }
            if (globalPerSecond <= 0) {
                throw new IllegalArgumentException("globalPerSecond must be > 0");
            }
            if (globalCircuitBreakMs < 0) {
                throw new IllegalArgumentException("globalCircuitBreakMs must be >= 0");
            }
        }

        public static Config defaults() {
            return new Config(100, 1000, 10_000L);
        }

        /** 测试 / 关闭限流用：永远允许（per-plugin / global 都设到 Integer.MAX_VALUE）。 */
        public static Config unlimited() {
            return new Config(Integer.MAX_VALUE, Integer.MAX_VALUE, 0);
        }
    }

    private final Config config;
    private final ConcurrentHashMap<String, Window> perPlugin = new ConcurrentHashMap<>();
    private final Window global = new Window();
    private final AtomicLong globalCircuitBreakUntil = new AtomicLong(0L);

    /** 测试 seam：让单测注入虚拟时间源（{@link System#currentTimeMillis} 默认）。 */
    private final LongSupplier clock;

    public PushRateLimiter(Config config) {
        this(config, System::currentTimeMillis);
    }

    /**
     * 测试用构造器——注入自定义时钟（0.4.8 hotfix 起 public 化，让跨包单测可用）。
     * 生产代码请用 {@link #PushRateLimiter(Config)} 单参版本。
     */
    public PushRateLimiter(Config config, LongSupplier clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ──────────────────────────────────────────────────────────────────
    //  Public API
    // ──────────────────────────────────────────────────────────────────

    /**
     * 尝试占用一个 push token（{@link HikariCanvasAPIImpl#setVariable} 单值路径用）。
     *
     * @param plugin 调用方插件（非 null）
     * @return {@code true} = 允许；{@code false} = 限流拒绝（调用方应 drop，limiter 内已 log）
     */
    public boolean tryAcquire(Plugin plugin) {
        return tryAcquireBatch(plugin, 1);
    }

    /**
     * 尝试占用 N 个 push token（{@link HikariCanvasAPIImpl#setVariables} 批量路径用）。
     *
     * <p>要么全成功要么全失败：先看 circuit break → 加 global → 加 per-plugin。
     * 任一阶段超限即 reject。已加进 window 的 count 不 rollback——
     * fixed-window 在下个秒自动归零，最坏 1s 内 reject 后续。</p>
     *
     * @param plugin 调用方插件（非 null）
     * @param count  请求 token 数（必须 &gt; 0）
     * @return {@code true} = 全部允许；{@code false} = 任一层超限
     */
    public boolean tryAcquireBatch(Plugin plugin, int count) {
        Objects.requireNonNull(plugin, "plugin");
        if (count <= 0) {
            throw new IllegalArgumentException("count must be > 0, got " + count);
        }

        long now = clock.getAsLong();

        // 1. 全局保护期检查
        long breakUntil = globalCircuitBreakUntil.get();
        if (breakUntil > 0 && now < breakUntil) {
            return false;
        }

        // 2. 全局限流
        long windowSec = now / 1000L;
        int globalCount = global.addAndGet(windowSec, count);
        if (globalCount > config.globalPerSecond) {
            // 触发全局保护期：CAS 防多线程并发 log
            if (globalCircuitBreakUntil.compareAndSet(breakUntil, now + config.globalCircuitBreakMs)) {
                log.warning("[HikariCanvas] global push rate exceeded ("
                        + globalCount + "/s > " + config.globalPerSecond
                        + "/s); circuit broken for " + config.globalCircuitBreakMs + "ms");
            }
            return false;
        }

        // 3. per-plugin 限流
        Window w = perPlugin.computeIfAbsent(plugin.getName(), k -> new Window());
        int pluginCount = w.addAndGet(windowSec, count);
        if (pluginCount > config.perPluginPerSecond) {
            // 跨阈值时 log 一次（多次 over 不重复）
            int prev = pluginCount - count;
            if (prev <= config.perPluginPerSecond) {
                log.warning("[HikariCanvas] plugin '" + plugin.getName()
                        + "' push rate exceeded (" + pluginCount + "/s > "
                        + config.perPluginPerSecond + "/s); drop tail");
            }
            return false;
        }
        return true;
    }

    // ──────────────────────────────────────────────────────────────────
    //  测试 / 监控
    // ──────────────────────────────────────────────────────────────────

    /** 测试 / 监控：当前 per-plugin 窗口计数（窗口若已过期返 0）。 */
    public int currentPluginCount(String pluginName) {
        Window w = perPlugin.get(pluginName);
        if (w == null) return 0;
        long now = clock.getAsLong();
        return w.snapshotFor(now / 1000L);
    }

    /** 测试 / 监控：当前全局窗口计数（窗口若已过期返 0）。 */
    public int currentGlobalCount() {
        long now = clock.getAsLong();
        return global.snapshotFor(now / 1000L);
    }

    /** 测试 / 监控：是否处于全局保护期。 */
    public boolean isGlobalCircuitBroken() {
        long until = globalCircuitBreakUntil.get();
        return until > 0 && clock.getAsLong() < until;
    }

    /** 测试用：清零所有窗口 + 保护期。 */
    public void resetForTest() {
        perPlugin.clear();
        global.reset();
        globalCircuitBreakUntil.set(0L);
    }

    // ──────────────────────────────────────────────────────────────────
    //  Internal
    // ──────────────────────────────────────────────────────────────────

    /**
     * 1s 固定窗口计数器。{@code windowSec} 变化时 lazy reset。
     *
     * <p>线程安全：addAndGet 用 CAS 重置 windowSec 后再 add count；
     * 并发场景下多个线程都看到旧 window 时只有一个 CAS 成功（清零），
     * 其余线程的 add 落到新 window，无丢失。</p>
     */
    private static final class Window {
        private final AtomicLong windowSec = new AtomicLong(0L);
        private final AtomicLong count = new AtomicLong(0L);

        int addAndGet(long currentWindowSec, int delta) {
            long w = windowSec.get();
            if (w != currentWindowSec) {
                if (windowSec.compareAndSet(w, currentWindowSec)) {
                    count.set(0L);
                }
                // CAS 失败说明并发已 reset，继续 add 即可
            }
            long c = count.addAndGet(delta);
            return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, c));
        }

        int snapshotFor(long currentWindowSec) {
            if (windowSec.get() != currentWindowSec) return 0;
            long c = count.get();
            return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, c));
        }

        void reset() {
            windowSec.set(0L);
            count.set(0L);
        }
    }
}
