package ac.haru.hikaricanvas.variable.plugin;

import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * Plugin Push API 限流器（{@code docs/dynamic-data.md §10.2}）。
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
 * Push API 调用方多为后台线程，竞争率高，用 atomic 版本。</p>
 *
 * @see HikariCanvasAPIImpl
 */
public final class PushRateLimiter {

    private static final Logger log = Logger.getLogger(PushRateLimiter.class.getName());

    /**
     * 限流参数（不可变）。从 {@code config.yml dynamic.push-rate-limit} 加载，
     * 详见 {@link ac.haru.hikaricanvas.HikariCanvasConfig}。
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
     * 测试用构造器——注入自定义时钟（public，让跨包单测可用）。
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
     * <p>要么全成功要么全失败：先看 circuit break → 超大单批 per-plugin 预拦截 → 加 global →
     * 加 per-plugin。任一阶段超限即 reject。已加进 window 的 count 不 rollback——
     * fixed-window 在下个秒自动归零，最坏 1s 内 reject 后续。</p>
     *
     * <p>"超大单批预拦截"——当单次 batch 的 {@code count} 本身就超过
     * per-plugin 限额（默 100）时，直接按 per-plugin drop tail 拒绝，<b>不</b>计入全局窗口、
     * <b>不</b>触发全服 10s 熔断。原实现先加全局：一个插件推一个超 global（默 1000）的大批
     * 会无辜熔断全服所有插件的 push。本拦截仍把 count 记进 per-plugin 窗口（保持"同窗口后续
     * 也 drop"语义）。注意：当 per-plugin 限额被配得 ≥ batch（如测试用 100_000）时，该批
     * 不触发本拦截，仍会按正常流程计入全局并可能触发全局熔断——这是预期（真·全服总量超标）。</p>
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

        long windowSec = now / 1000L;
        Window w = perPlugin.computeIfAbsent(plugin.getName(), k -> new Window());

        // 2. 超大单批预拦截——count 本身超 per-plugin 限额 → 局部 drop，
        //    不污染全局窗口、不触发全服熔断。仍把 count 记进 per-plugin 窗口（保持后续 drop）。
        if (count > config.perPluginPerSecond) {
            int pluginCount = w.addAndGet(windowSec, count);
            int prev = pluginCount - count;
            if (prev <= config.perPluginPerSecond) {
                log.warning("[HikariCanvas] plugin '" + plugin.getName()
                        + "' push batch exceeds per-plugin limit (" + count
                        + " > " + config.perPluginPerSecond + "/s); drop tail (no global circuit break)");
            }
            return false;
        }

        // 3. 全局限流
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

        // 4. per-plugin 限流
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
     * <p><b>线程安全：</b> {@code (windowSec, count)} 打包进<b>一个</b> {@link AtomicLong}
     * （高 32 位 = 窗口秒数截断，低 32 位 = 计数）走单次 CAS，窗口切换与计数是同一个原子动作，
     * 真正做到跨窗口零丢失。</p>
     *
     * <p>此前是两个独立 AtomicLong：先 CAS 换 windowSec、赢家 {@code count.set(0)}，
     * 输家直接 {@code count.addAndGet}。注释写着「输家的 add 落到新 window，无丢失」——
     * 实际交错是输家先 add、赢家随后 set(0) 把它抹掉。窗口切换那一瞬计数会少算，
     * 限流器允许一小撮突发溢出，而注释还告诉你这里没有丢失，将来谁拿它做配额审计就会栽。</p>
     *
     * <p>窗口秒数截断成 int 不影响判定：只比相等，2038 之后回绕仍是「同一秒相等、不同秒不等」。</p>
     */
    private static final class Window {
        /** 高 32 位 = windowSec（截断），低 32 位 = count。 */
        private final AtomicLong packed = new AtomicLong(0L);

        private static long pack(int windowSec, int count) {
            return ((long) windowSec << 32) | (count & 0xFFFF_FFFFL);
        }

        int addAndGet(long currentWindowSec, int delta) {
            int w = (int) currentWindowSec;
            while (true) {
                long cur = packed.get();
                int curW = (int) (cur >>> 32);
                int curC = (int) cur;
                // 窗口已切 → 从 delta 重新起算；同窗口 → 累加（饱和，不回绕）
                int next = (curW == w) ? saturatedAdd(curC, delta) : Math.max(0, delta);
                if (packed.compareAndSet(cur, pack(w, next))) {
                    return next;
                }
            }
        }

        private static int saturatedAdd(int a, int b) {
            long s = (long) a + b;
            return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, s));
        }

        int snapshotFor(long currentWindowSec) {
            long cur = packed.get();
            if ((int) (cur >>> 32) != (int) currentWindowSec) return 0;
            return Math.max(0, (int) cur);
        }

        void reset() {
            packed.set(0L);
        }
    }
}
