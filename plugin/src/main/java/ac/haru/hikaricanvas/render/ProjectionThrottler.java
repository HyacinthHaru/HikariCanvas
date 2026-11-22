package ac.haru.hikaricanvas.render;

import ac.haru.hikaricanvas.session.Session;
import ac.haru.hikaricanvas.session.SessionManager;
import ac.haru.hikaricanvas.state.EditSession;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 投影端节流：把编辑 op 产生的 {@link DirtyRegion} 做 per-session 合并，
 * 按 {@code minIntervalMs} 上限（默认 200ms = 5 fps）下发到 {@link CanvasProjector}。
 *
 * <p>契约见 {@code docs/architecture.md §5.1}：
 * <ul>
 *   <li>静止：无 op = 无推送</li>
 *   <li>输入中：按 {@code minIntervalMs} 节流合并（默认 5 fps）</li>
 *   <li>会话关闭前：{@link #flushNow} 同步推送尾帧</li>
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
 * <p><b>线程：</b> async scheduler + {@code ConcurrentMap} + per-session {@code synchronized(bucket)}。
 * 和 CanvasProjector 一致，整条链都不需要主线程。</p>
 */
// 非 final：测试需可子类化覆盖 submit 做记录型 fake；生产无子类，行为不变。
public class ProjectionThrottler {

    /** 5 fps = 200ms；runTaskLaterAsynchronously 以 tick 为单位（50ms/tick）。 */
    public static final long DEFAULT_MIN_INTERVAL_MS = 200L;

    private final JavaPlugin plugin;
    private final SessionManager sessionManager;
    private final CanvasProjector projector;
    private final long minIntervalMs;

    private final ConcurrentMap<String, Bucket> bySession = new ConcurrentHashMap<>();

    /**
     * 自适应渲染：per-session 间隔覆盖。
     *
     * <p>HikariCanvas 注入的自适应 listener 在 {@link ac.haru.hikaricanvas.variable.VariableStore.ChangeType#WALL_REFS_UPDATED}
     * 时调 {@link #setIntervalForSession} 把绑定到含高频变量 wall 的 session 切到 50ms（20 fps）；
     * wall 不再含高频引用时调 {@link #clearSessionInterval} 回落到默认 {@link #minIntervalMs}。</p>
     */
    private final ConcurrentMap<String, Long> sessionIntervalOverride = new ConcurrentHashMap<>();

    /**
     * 分流 gate（docs/architecture.md §5.1「两条产帧路径」）：动画接管期间，
     * 编辑 op 产生的 reactive flush 退让给 AnimationTicker（编辑可见性由
     * 「持久化完成 → ticker.invalidate → 下一 tick 重载」保证，延迟 ≤ 1 帧）。
     * null = 无 gate（旧装配 / 测试零侵入）。
     */
    private volatile AnimationTickerGate animationGate;

    private static final class Bucket {
        DirtyRegion pending;
        /**
         * 上次 flush 的时间戳，单位 <b>纳秒</b>（{@link System#nanoTime()} 单调钟读数），
         * 免疫 NTP 回拨 / VM 时间同步导致的差值为负、帧被推迟问题。
         *
         * <p>{@code nanoTime()} 原点任意（可负），仅差值有意义；首帧立即下发由
         * {@link #projectedOnce} 标志判定。</p>
         */
        long lastProjectAtNanos;
        /** 是否已 flush 过一次。false 时下一次 submit 强制立即 flush（保「首帧立即」契约）。 */
        boolean projectedOnce;
        /** 连续 flush 失败次数；成功即复位。达 {@link #FAILURE_CIRCUIT_BREAK} 触发熔断。 */
        int consecutiveFailures;
        /**
         * 是否有一次投影正在飞行中（已认领脏区域、尚未回写结果）。
         *
         * <p>投影本身在 Bucket 锁<b>外</b>执行（否则会与 EditSession 锁构成 ABBA 死锁），
         * 于是同一 session 的投影不再天然被 Bucket 监视器串行化——靠本标志继续保证串行。</p>
         */
        boolean projecting;
        BukkitTask flushTask;
    }

    /** 节流间隔从毫秒换算到纳秒（{@link Bucket#lastProjectAtNanos} 同源单调钟比较）。 */
    private static final long MS_TO_NANOS = 1_000_000L;

    /**
     * 连续 flush 失败多少次后熔断该 bucket（丢 pending、停止每帧重试）。
     *
     * <p>畸形元素（超大 glow / 非法 brush size）会让每次 rasterize 都抛，重试链本身
     * 成为放大器——OOM 场景下更是每帧反复申请大 buffer。3 次足够区分「偶发竞态」与
     * 「这份 state 就是渲染不了」。</p>
     */
    static final int FAILURE_CIRCUIT_BREAK = 3;

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

    /** 装配层注入分流 gate（HikariCanvas.onEnable；测试 / 旧路径不注 = 无 gate）。 */
    public void setAnimationGate(AnimationTickerGate gate) {
        this.animationGate = gate;
    }

    /**
     * 提交一次脏矩形。立刻 flush 或与已有 pending 合并并调度尾帧。
     * {@code region == null} 时 no-op。
     *
     * <p>wall 被 AnimationTicker 接管（播放中）时整次 submit 退让——不入 pending、
     * 不调度（避免两条产帧路径对同一 mapId 交错写）。暂停 / 注销后 reactive 路径自然恢复。</p>
     */
    public void submit(String sessionId, DirtyRegion region) {
        if (region == null) return;
        AnimationTickerGate gate = this.animationGate;
        if (gate != null) {
            Session s = sessionFor(sessionId);
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
        DirtyRegion claimed;
        long now;
        synchronized (b) {
            b.pending = b.pending == null ? region : b.pending.union(region);
            now = System.nanoTime();
            long elapsed = now - b.lastProjectAtNanos;
            // 首帧立即下发（nanoTime 原点任意，由 projectedOnce 判定）。
            if (!b.projectedOnce || elapsed >= effectiveNanos) {
                claimed = claimFlush(sessionId, b, now);
            } else {
                claimed = null;
                if (b.flushTask == null) {
                    long waitMs = Math.max(1L, (effectiveNanos - elapsed) / MS_TO_NANOS);
                    long delayTicks = Math.max(1L, (waitMs + 49) / 50);
                    b.flushTask = Bukkit.getScheduler().runTaskLaterAsynchronously(
                            plugin, () -> onScheduledFlush(sessionId), delayTicks);
                }
                // else: 已调度，仅并入 pending
            }
        }
        // 出锁投影：projectUnderEditLock 会取 EditSession 监视器，绝不能在持 Bucket 锁时做。
        if (claimed != null) {
            performFlush(sessionId, b, claimed, now);
        }
    }

    /**
     * 自适应渲染：取该 session 的有效节流间隔。
     * 优先 {@link #sessionIntervalOverride}；未设置时回落到构造期 {@link #minIntervalMs}。
     */
    private long effectiveInterval(String sessionId) {
        Long override = sessionIntervalOverride.get(sessionId);
        return override == null ? minIntervalMs : override;
    }

    /**
     * 自适应渲染：覆盖某 session 的节流间隔（ms）。
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

    /** 清除 session 间隔覆盖，回落到默认 {@link #minIntervalMs}。 */
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
        DirtyRegion claimed;
        long now = System.nanoTime();
        synchronized (b) {
            b.flushTask = null;
            claimed = claimFlush(sessionId, b, now);
        }
        if (claimed != null) {
            performFlush(sessionId, b, claimed, now);
        }
    }

    /**
     * 必须在持有 {@code b} 锁下调用。决定本次是否投影，并「认领」待投影的脏区域。
     *
     * <p><b>返回非 null 时，调用方必须在释放 {@code b} 锁之后调 {@link #performFlush}。</b>
     * 认领即把 {@code pending} 清空并置 {@code projecting}——投影期间新来的 submit 并入下一批，
     * 由本次投影收尾时续排。</p>
     *
     * <p>{@code project()} 抛异常时不丢脏区域——只有成功返回后才推进 {@code lastProjectAt}；
     * 失败时把 {@code toProject} 通过 {@link DirtyRegion#union} 并回 {@code pending} 并保持
     * {@code lastProjectAt} 不变（不推进时间基），让下一次 submit 自动重投递该区域，兑现
     * architecture.md §5.1.5「session 关闭前最后一帧 100% 正确」承诺。</p>
     *
     * <p><b>并发与锁序不变式：</b>{@link #projectUnderEditLock} 在 {@code session.editSession()}
     * 监视器内调用 {@code project()}，使 {@code CanvasCompositor.rasterize} 入口对 layers / elements
     * 的 {@code List.copyOf} 浅拷贝与 {@link EditSession} mutator 互斥。本类<b>绝不</b>在持有
     * {@code Bucket} 锁时获取 {@link EditSession} 锁（claim / perform 两段式，两把锁无交集），
     * 从而消除与 {@code EditSession.*Variable* → VariableStore → submit} 路径的反向锁序死锁；
     * 同一会话的投影串行由 {@code Bucket.projecting} 标志保证。</p>
     */
    private DirtyRegion claimFlush(String sessionId, Bucket b, long nowNanos) {
        DirtyRegion toProject = b.pending;
        if (toProject == null) {
            // 无脏区域：推进时间基即可（空 flush 仍刷新节流窗口）。
            b.lastProjectAtNanos = nowNanos;
            return null;
        }
        Session s = sessionFor(sessionId);
        if (s == null) {
            // 会话已消亡，丢弃脏区域并推进时间基。
            b.pending = null;
            b.lastProjectAtNanos = nowNanos;
            b.projectedOnce = true;
            return null;
        }
        // 尾帧延迟 flush 也要查 gate——submit 时未播放、flush 执行前
        // play 落在延迟窗口内的情形，否则这次 reactive 直写会与 Ticker 抢同一 mapId。
        AnimationTickerGate gate = this.animationGate;
        if (gate != null && s.wallId() != null && gate.isWallAnimating(s.wallId())) {
            gate.onReactiveYield(s.wallId());
            b.pending = null;
            b.lastProjectAtNanos = nowNanos;
            b.projectedOnce = true;
            return null;
        }
        if (b.projecting) {
            // 已有一次投影在飞行中。脏区域留在 pending，由那次投影收尾时续排，保证同一会话投影串行。
            return null;
        }
        // 认领这批脏区域：清空 pending 让投影期间新来的 submit 并入下一批。
        b.pending = null;
        b.projecting = true;
        return toProject;
    }

    /**
     * 真正执行投影 + 结果回写。<b>调用方必须已经释放 {@code b} 的监视器。</b>
     *
     * <p>见 {@link #claimFlush} 的锁序说明——本方法内部会取 {@link EditSession} 监视器，
     * 若调用方仍持 Bucket 锁就会引入 ABBA 死锁。</p>
     */
    private void performFlush(String sessionId, Bucket b, DirtyRegion toProject, long nowNanos) {
        Session s = sessionFor(sessionId);
        Throwable failure = null;
        try {
            if (s != null) {
                projectUnderEditLock(s, toProject);
            }
        } catch (Throwable t) {
            // 捕获 Throwable（含 OutOfMemoryError / StackOverflowError 等）：记录失败并纳入熔断计数，
            // 避免 Error 冒泡导致单面墙畸形数据打死投影线程或每帧无上限重试大分配。
            failure = t;
        }
        synchronized (b) {
            b.projecting = false;
            if (failure == null) {
                b.lastProjectAtNanos = nowNanos;
                b.projectedOnce = true;
                b.consecutiveFailures = 0;
            } else {
                b.pending = b.pending == null ? toProject : b.pending.union(toProject);
                b.consecutiveFailures++;
                if (b.consecutiveFailures >= FAILURE_CIRCUIT_BREAK) {
                    // 熔断：连续失败到阈值就丢弃 pending 并推进时间基，让这条 bucket 停止
                    // 每帧重试。像素停在最后一次成功的帧；用户改动 / 重连会重新 submit。
                    b.pending = null;
                    b.lastProjectAtNanos = nowNanos;
                    b.projectedOnce = true;
                    plugin.getLogger().severe(
                            "ProjectionThrottler: flush failed " + b.consecutiveFailures
                                    + "x consecutively, circuit-breaking sid=" + sessionId
                                    + " err=" + failure);
                } else {
                    plugin.getLogger().warning("ProjectionThrottler: flush failed sid=" + sessionId
                            + " err=" + failure.getMessage());
                }
            }
            // 出锁投影期间新并入的脏区域在此显式补排一次尾帧。
            if (b.pending != null && b.flushTask != null) {
                return;
            }
            if (b.pending != null) {
                scheduleTailFlush(sessionId, b);
            }
        }
    }

    /** 在持 {@code b} 锁下调用：按有效节流间隔排一次尾帧 flush。 */
    private void scheduleTailFlush(String sessionId, Bucket b) {
        // 测试装配传 null plugin（无 Bukkit server），此时不排尾帧，脏区域留在 pending
        // 等下一次 submit。生产恒非 null。
        if (plugin == null) return;
        long delayTicks = Math.max(1L, (effectiveInterval(sessionId) + 49) / 50);
        b.flushTask = Bukkit.getScheduler().runTaskLaterAsynchronously(
                plugin, () -> onScheduledFlush(sessionId), delayTicks);
    }

    /**
     * session 查找 seam。
     *
     * <p>package-private 可覆盖：{@link ac.haru.hikaricanvas.session.SessionManager} 是 final
     * 且装配链很重（confirm 需 MapPool / WallResolver / 主线程），锁序回归测试只需要一个持有
     * 真 {@link EditSession} 的 Session。生产行为逐字不变。</p>
     */
    Session sessionFor(String sessionId) {
        return sessionManager == null ? null : sessionManager.byId(sessionId);
    }

    /**
     * 投影 seam。
     *
     * <p>package-private 可覆盖：{@link CanvasProjector} 是 final class（不是接口），
     * 锁序回归测试需要一个能在投影中途阻塞的假投影来撑开并发窗口。生产行为逐字不变。</p>
     */
    void doProject(Session s, DirtyRegion toProject) {
        projector.project(s, toProject);
    }

    /**
     * 在 session 的 {@link EditSession} 监视器内执行投影，让 {@code rasterize} 入口对
     * layers / elements live 列表的浅拷贝与 EditSession mutator 互斥（消除 fail-fast 迭代撕裂）。
     * editSession 为 null（SELECTING 阶段尚未 confirm）时无 live 列表可竞争，直接 project。
     */
    private void projectUnderEditLock(Session s, DirtyRegion toProject) {
        EditSession es = s.editSession();
        if (es == null) {
            doProject(s, toProject);
            return;
        }
        synchronized (es) {
            doProject(s, toProject);
        }
    }

    /** session 结束时清理，避免 BukkitTask 泄漏到下个 session。 */
    public void discardSession(String sessionId) {
        // 自适应间隔覆盖也要清，避免泄漏到下个 session（虽然 sessionId 是 UUID 不会重）。
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
     * session cancel / 关闭前同步 flush pending region，
     * 确保最后一帧编辑落到地图上。如果调用方紧接着会 {@link #discardSession}，
     * 调用顺序应该是先 flushNow 后 discardSession——flushNow 持锁短暂跑 projector.project，
     * 不影响 discardSession 的 Bukkit task cancel。
     *
     * <p>session 已不在 SessionManager 时（forget 已先发生）{@link #claimFlush} 内部
     * 自然短路（{@code sessionManager.byId(sessionId) == null}），安全 no-op。</p>
     */
    public void flushNow(String sessionId) {
        Bucket b = bySession.get(sessionId);
        if (b == null) return;
        DirtyRegion claimed;
        long now = System.nanoTime();
        synchronized (b) {
            if (b.pending == null) return;
            if (b.flushTask != null) {
                b.flushTask.cancel();
                b.flushTask = null;
            }
            claimed = claimFlush(sessionId, b, now);
        }
        // 出锁投影（同 submit）。这正是死锁最致命的入口：/canvas cancel 与 SessionReaper
        // 都经 preForgetHook 走到这里，一旦在持 Bucket 锁时等 EditSession 锁，主线程永久冻结。
        if (claimed != null) {
            performFlush(sessionId, b, claimed, now);
        }
    }
}
