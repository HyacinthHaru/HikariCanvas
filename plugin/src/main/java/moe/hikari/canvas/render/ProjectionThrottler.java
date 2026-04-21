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
        synchronized (b) {
            b.pending = b.pending == null ? region : b.pending.union(region);
            long now = System.currentTimeMillis();
            long since = now - b.lastProjectAt;
            if (since >= minIntervalMs) {
                flushLocked(sessionId, b, now);
            } else if (b.flushTask == null) {
                long waitMs = Math.max(1, minIntervalMs - since);
                long delayTicks = Math.max(1, (waitMs + 49) / 50);
                b.flushTask = Bukkit.getScheduler().runTaskLaterAsynchronously(
                        plugin, () -> onScheduledFlush(sessionId), delayTicks);
            }
            // else: 已调度，仅并入 pending
        }
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
}
