package moe.hikari.canvas.render;

import moe.hikari.canvas.session.Session;
import moe.hikari.canvas.session.SessionManager;
import moe.hikari.canvas.state.EditSession;
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
// 非 final：测试需可子类化覆盖 submit 做记录型 fake（0.8-A2 Task 12 投影接入；
// 生产无子类，行为不变）。
public class ProjectionThrottler {

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

    /**
     * 0.6 P2 分流 gate（docs/architecture.md §5.1「两条产帧路径」）：动画接管期间，
     * 编辑 op 产生的 reactive flush 退让给 AnimationTicker（编辑可见性由
     * 「持久化完成 → ticker.invalidate → 下一 tick 重载」保证，延迟 ≤ 1 帧）。
     * null = 无 gate（旧装配 / 测试零侵入）。
     */
    private volatile AnimationTickerGate animationGate;

    private static final class Bucket {
        DirtyRegion pending;
        /**
         * P3-54：上次 flush 的时间戳，单位 <b>纳秒</b>（{@link System#nanoTime()} 单调钟读数），
         * 免疫 NTP 回拨 / VM 时间同步导致的 {@code since} 为负、帧被推迟问题。
         *
         * <p>注意：{@code nanoTime()} 原点任意（可负），仅差值有意义，故不能像旧
         * {@code currentTimeMillis} 那样靠初值 0 推断「从未 flush」——首帧立即下发改由
         * {@link #projectedOnce} 标志判定。</p>
         */
        long lastProjectAtNanos;
        /** P3-54：是否已 flush 过一次。false 时下一次 submit 强制立即 flush（保「首帧立即」契约）。 */
        boolean projectedOnce;
        BukkitTask flushTask;
    }

    /** 节流间隔从毫秒换算到纳秒（{@link Bucket#lastProjectAtNanos} 同源单调钟比较）。 */
    private static final long MS_TO_NANOS = 1_000_000L;

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

    /** 0.6 P2：装配层注入分流 gate（HikariCanvas.onEnable；测试 / 旧路径不注 = 无 gate）。 */
    public void setAnimationGate(AnimationTickerGate gate) {
        this.animationGate = gate;
    }

    /**
     * 提交一次脏矩形。立刻 flush 或与已有 pending 合并并调度尾帧。
     * {@code region == null} 时 no-op。
     *
     * <p>0.6 P2：wall 被 AnimationTicker 接管（播放中）时整次 submit 退让——不入 pending、
     * 不调度（避免两条产帧路径对同一 mapId 交错写）。暂停 / 注销后 reactive 路径自然恢复。</p>
     */
    public void submit(String sessionId, DirtyRegion region) {
        if (region == null) return;
        AnimationTickerGate gate = this.animationGate;
        if (gate != null) {
            var s = sessionManager.byId(sessionId);
            String wallId = s != null ? s.wallId() : null;
            if (wallId != null && gate.isWallAnimating(wallId)) {
                // 被吸收的渲染意图转给 Ticker（invalidate → 下一帧重载 + 全量补发）
                gate.onReactiveYield(wallId);
                return;
            }
        }
        Bucket b = bySession.computeIfAbsent(sessionId, k -> new Bucket());
        long effectiveMs = effectiveInterval(sessionId);
        long effectiveNanos = effectiveMs * MS_TO_NANOS;
        synchronized (b) {
            b.pending = b.pending == null ? region : b.pending.union(region);
            long now = System.nanoTime();
            // P3-54：nanoTime 单调，正常情况下 since >= 0；用 Math.max(0, ..) 兜底极端
            // nanoTime 实现回绕（理论上 ~292 年才回绕，仍防御一手）。
            long sinceNanos = Math.max(0L, now - b.lastProjectAtNanos);
            // 首帧立即下发（nanoTime 原点任意，不能靠 lastProjectAtNanos==0 推断）。
            if (!b.projectedOnce || sinceNanos >= effectiveNanos) {
                flushLocked(sessionId, b, now);
            } else if (b.flushTask == null) {
                long waitMs = Math.max(1L, (effectiveNanos - sinceNanos) / MS_TO_NANOS);
                long delayTicks = Math.max(1L, (waitMs + 49) / 50);
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
            flushLocked(sessionId, b, System.nanoTime());
        }
    }

    /**
     * 必须在持有 {@code b} 锁下调用。
     *
     * <p>P2-11：{@code project()} 抛异常时不丢脏区域——只有成功返回后才清 {@code pending}
     * 并推进 {@code lastProjectAt}；失败时把 {@code toProject} 通过 {@link DirtyRegion#union}
     * 并回 {@code pending} 并保持 {@code lastProjectAt} 不变（不推进时间基），让下一次 submit
     * 自动重投递该区域，兑现 architecture.md §5.1.5「session 关闭前最后一帧 100% 正确」承诺。</p>
     *
     * <p><b>P1-8（数据竞争修复）：</b>{@code project()} 内 {@code CanvasCompositor.rasterize}
     * 会遍历 {@code ProjectState} 的 layers / 各 layer 的 elements live ArrayList。这些列表的
     * 结构修改全部发生在 {@link EditSession} 的 {@code synchronized(this)} mutator 里，而
     * rasterize 此前不持该监视器——两侧无共享锁，并发 WS 编辑 op 与变量驱动重画会对同一
     * ArrayList 边改边迭代，抛 {@code ConcurrentModificationException} / 撕裂读。这里在
     * {@code session.editSession()} 监视器内调 {@code project()}，让 rasterize 入口的
     * {@code List.copyOf} 浅拷贝与 mutator 互斥（拷贝完成后即是不可变快照，后续迭代不再需要锁）。
     * 锁顺序恒为 {@code Bucket → EditSession}（dispatcher 在 EditSession mutator 返回<b>后</b>才
     * 调 {@code submit}，从不反向），无死锁；editSession 为 null（SELECTING 阶段）时无 live 列表
     * 可竞争，直接 project。</p>
     */
    private void flushLocked(String sessionId, Bucket b, long nowNanos) {
        DirtyRegion toProject = b.pending;
        if (toProject == null) {
            // 无脏区域：推进时间基即可（保持原有「空 flush 也刷新窗口」语义）。
            b.lastProjectAtNanos = nowNanos;
            return;
        }
        Session s = sessionManager.byId(sessionId);
        if (s == null) {
            // 会话已消亡，丢弃脏区域并推进时间基。
            b.pending = null;
            b.lastProjectAtNanos = nowNanos;
            b.projectedOnce = true;
            return;
        }
        // 0.6 P2 审查确认项 #6：尾帧延迟 flush 也要查 gate——submit 时未播放、flush 执行前
        // play 落在延迟窗口内的情形，否则这次 reactive 直写会与 Ticker 抢同一 mapId。
        AnimationTickerGate gate = this.animationGate;
        if (gate != null && s.wallId() != null && gate.isWallAnimating(s.wallId())) {
            gate.onReactiveYield(s.wallId());
            b.pending = null;
            b.lastProjectAtNanos = nowNanos;
            b.projectedOnce = true;
            return;
        }
        try {
            projectUnderEditLock(s, toProject);
            // 成功：清 pending 并推进时间基。
            b.pending = null;
            b.lastProjectAtNanos = nowNanos;
            b.projectedOnce = true;
        } catch (Exception e) {
            // 失败：把脏区域并回 pending（与期间新并入的 region 合并），不推进时间基，
            // 也不置 projectedOnce——下次 submit 会因 !projectedOnce 立即重试该帧，
            // 避免像素永久陈旧。
            b.pending = b.pending == null ? toProject : b.pending.union(toProject);
            plugin.getLogger().warning(
                    "ProjectionThrottler: flush failed sid=" + sessionId + " err=" + e.getMessage());
        }
    }

    /**
     * P1-8：在 session 的 {@link EditSession} 监视器内执行投影，让 {@code rasterize} 入口对
     * layers / elements live 列表的浅拷贝与 EditSession mutator 互斥（消除 fail-fast 迭代撕裂）。
     * editSession 为 null（SELECTING 阶段尚未 confirm）时无 live 列表可竞争，直接 project。
     */
    private void projectUnderEditLock(Session s, DirtyRegion toProject) {
        EditSession es = s.editSession();
        if (es == null) {
            projector.project(s, toProject);
            return;
        }
        synchronized (es) {
            projector.project(s, toProject);
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
            flushLocked(sessionId, b, System.nanoTime());
        }
    }
}
