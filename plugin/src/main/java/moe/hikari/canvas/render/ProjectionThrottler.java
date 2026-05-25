package moe.hikari.canvas.render;

import moe.hikari.canvas.session.Session;
import moe.hikari.canvas.session.SessionManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 投影端节流：把编辑 op 产生的 {@link DirtyRegion} 做 per-session 合并，
 * 按 {@code minIntervalMs} 上限（M3 默认 200ms = 5 fps）下发到 {@link CanvasProjector}。
 *
 * <p>契约见 {@code docs/architecture.md §5.1}：
 * <ul>
 *   <li>静止：无 op = 无推送</li>
 *   <li>输入中：5 fps 上限</li>
 *   <li>提交：全量推送（由 SessionManager.commit 另走路径）</li>
 * </ul>
 *
 * <p><b>策略：</b></p>
 * <ul>
 *   <li>首次 submit：立即 flush</li>
 *   <li>距离上次 flush &lt; minIntervalMs：region 并入 pending，调度 {@code runTaskLaterAsynchronously}
 *       在窗口耗尽时补送尾帧；已有 pending 就不重复调度（coalesce）</li>
 *   <li>session 结束：{@link #discardSession} 取消 pending task、清状态</li>
 * </ul>
 *
 * <p><b>线程：</b> async scheduler + `ConcurrentMap` + per-session `synchronized(bucket)`。
 * 和 T7 CanvasProjector 一致，整条链都不需要主线程。</p>
 */
public final class ProjectionThrottler {

    /** 5 fps = 200ms；runTaskLaterAsynchronously 以 tick 为单位（50ms/tick）。 */
    public static final long DEFAULT_MIN_INTERVAL_MS = 200L;

    private final JavaPlugin plugin;
    private final SessionManager sessionManager;
    private final CanvasProjector projector;
    private final long minIntervalMs;

    private final ConcurrentMap<String, Bucket> bySession = new ConcurrentHashMap<>();

    /**
     * 0.4.0 方案 B 自适应渲染：per-session 间隔覆盖。
     *
     * <p>HikariCanvas 注入的自适应 listener 在 {@link moe.hikari.canvas.variable.VariableStore.ChangeType#WALL_REFS_UPDATED}
     * 时调 {@link #setIntervalForSession} 把绑定到含高频变量 wall 的 session 切到 50ms（20 fps）；
     * wall 不再含高频引用时调 {@link #clearSessionInterval} 回落到默认 {@link #minIntervalMs}。</p>
     */
    private final ConcurrentMap<String, Long> sessionIntervalOverride = new ConcurrentHashMap<>();

    private static final class Bucket {
        DirtyRegion pending;
        long lastProjectAt;
        BukkitTask flushTask;
    }

    public ProjectionThrottler(JavaPlugin plugin,
                               SessionManager sessionManager,
                               CanvasProjector projector) {
        this(plugin, sessionManager, projector, DEFAULT_MIN_INTERVAL_MS);
    }

    public ProjectionThrottler(JavaPlugin plugin,
                               SessionManager sessionManager,
                               CanvasProjector projector,
                               long minIntervalMs) {
        this.plugin = plugin;
        this.sessionManager = sessionManager;
        this.projector = projector;
        this.minIntervalMs = minIntervalMs;
    }

    /**
     * 提交一次脏矩形。立刻 flush 或与已有 pending 合并并调度尾帧。
     * {@code region == null} 时 no-op。
     */
    public void submit(String sessionId, DirtyRegion region) {
        if (region == null) return;
        Bucket b = bySession.computeIfAbsent(sessionId, k -> new Bucket());
        long effective = effectiveInterval(sessionId);
        synchronized (b) {
            b.pending = b.pending == null ? region : b.pending.union(region);
            long now = System.currentTimeMillis();
            long since = now - b.lastProjectAt;
            if (since >= effective) {
                flushLocked(sessionId, b, now);
            } else if (b.flushTask == null) {
                long waitMs = Math.max(1, effective - since);
                long delayTicks = Math.max(1, (waitMs + 49) / 50);
                b.flushTask = Bukkit.getScheduler().runTaskLaterAsynchronously(
                        plugin, () -> onScheduledFlush(sessionId), delayTicks);
            }
            // else: 已调度，仅并入 pending
        }
    }

    /**
     * 0.4.0 方案 B 自适应渲染：取该 session 的有效节流间隔。
     * 优先 {@link #sessionIntervalOverride}；未设置时回落到构造期 {@link #minIntervalMs}。
     */
    private long effectiveInterval(String sessionId) {
        Long override = sessionIntervalOverride.get(sessionId);
        return override == null ? minIntervalMs : override;
    }

    /**
     * 0.4.0 方案 B 自适应渲染：覆盖某 session 的节流间隔（ms）。
     *
     * <p>典型用法：含 {@code schedule:<wallId>/eta_seconds} / {@code system/server.tick} 引用的 wall
     * 切到 50ms（20 fps）；wall 不再含高频引用时调 {@link #clearSessionInterval} 回落。
     * 入参 ≤ 0 等同 {@link #clearSessionInterval}。</p>
     *
     * <p>线程安全：单 key put / get，{@link ConcurrentHashMap} 自身串行化。</p>
     */
    public void setIntervalForSession(String sessionId, long intervalMs) {
        if (sessionId == null) return;
        if (intervalMs <= 0) {
            sessionIntervalOverride.remove(sessionId);
        } else {
            sessionIntervalOverride.put(sessionId, intervalMs);
        }
    }

    /** 0.4.0 方案 B：清除 session 间隔覆盖，回落到默认 {@link #minIntervalMs}。 */
    public void clearSessionInterval(String sessionId) {
        if (sessionId == null) return;
        sessionIntervalOverride.remove(sessionId);
    }

    /** 测试 / 调试：当前 session 的有效间隔（ms）。 */
    public long effectiveIntervalForTest(String sessionId) {
        return effectiveInterval(sessionId);
    }

    private void onScheduledFlush(String sessionId) {
        Bucket b = bySession.get(sessionId);
        if (b == null) return;
        synchronized (b) {
            b.flushTask = null;
            flushLocked(sessionId, b, System.currentTimeMillis());
        }
    }

    /** 必须在持有 {@code b} 锁下调用。 */
    private void flushLocked(String sessionId, Bucket b, long now) {
        DirtyRegion toProject = b.pending;
        b.pending = null;
        b.lastProjectAt = now;
        if (toProject == null) return;
        Session s = sessionManager.byId(sessionId);
        if (s == null) return;  // 会话已消亡，丢弃
        try {
            projector.project(s, toProject);
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "ProjectionThrottler: flush failed sid=" + sessionId + " err=" + e.getMessage());
        }
    }

    /** session 结束时清理，避免 BukkitTask 泄漏到下个 session。 */
    public void discardSession(String sessionId) {
        // 0.4.0 方案 B：自适应间隔覆盖也要清，避免泄漏到下个 session（虽然 sessionId 是 UUID 不会重）。
        sessionIntervalOverride.remove(sessionId);
        Bucket b = bySession.remove(sessionId);
        if (b == null) return;
        synchronized (b) {
            if (b.flushTask != null) {
                b.flushTask.cancel();
                b.flushTask = null;
            }
            b.pending = null;
        }
    }

    /**
     * Ultrareview 2026-05-25 #5：session cancel / 关闭前同步 flush pending region，
     * 确保最后一帧编辑落到地图上。如果调用方紧接着会 {@link #discardSession}，
     * 调用顺序应该是先 flushNow 后 discardSession——flushNow 持锁短暂跑 projector.project，
     * 不影响 discardSession 的 Bukkit task cancel。
     *
     * <p>session 已不在 SessionManager 时（forget 已先发生）{@link #flushLocked} 内部
     * 自然短路（{@code sessionManager.byId(sessionId) == null}），安全 no-op。</p>
     */
    public void flushNow(String sessionId) {
        Bucket b = bySession.get(sessionId);
        if (b == null) return;
        synchronized (b) {
            if (b.pending == null) return;
            if (b.flushTask != null) {
                b.flushTask.cancel();
                b.flushTask = null;
            }
            flushLocked(sessionId, b, System.currentTimeMillis());
        }
    }
}
