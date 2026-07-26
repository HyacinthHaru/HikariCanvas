package ac.haru.hikaricanvas.render;

import ac.haru.hikaricanvas.state.LoopMode;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.state.Timeline;
import ac.haru.hikaricanvas.state.TriggerConfig;
import ac.haru.hikaricanvas.state.TriggerType;
import ac.haru.hikaricanvas.storage.WallRepo;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 时间轴动画产帧引擎（docs/architecture.md §5.5 / docs/timeline.md §3.1）。
 *
 * <p><b>线程模型：</b>单线程 {@link ScheduledExecutorService}（照 {@code VariableProviderDaemon}
 * 范式：daemon 线程 + 任务级异常隔离 + 幂等关停）。<b>不用 Bukkit 定时器</b>——Bukkit scheduler
 * 最细 1 tick = 50ms，给不出高于 20fps 的刷新率。单线程串行出帧也是 {@link BufferPool}
 * 线程限定安全的前提。Ticker 线程禁碰非线程安全 Bukkit API（viewer 查询走
 * {@code CanvasProjector} 已验证的只读路径）。</p>
 *
 * <p><b>状态缓存：</b>每个播放中的 wall 缓存 {@link WallRepo.Wall}（state + mapIds + key），
 * <b>不</b>每 tick 走 {@code loadById}（20fps = 20 DB 读/秒/墙，不可接受）。编辑持久化后由
 * 装配层调 {@link #invalidate}，下一 tick 重载——编辑可见延迟 ≤ 1 帧。
 * 补间引擎用的 {@link #renderStatic} 走同一条纪律（缓存表 {@code staticWalls}，补间默认 30fps，
 * 每帧一次 DB 读比时间轴路径判死刑的数字还高）。</p>
 *
 * <p><b>播放语义（MANUAL）：</b></p>
 * <ul>
 *   <li>{@code play}：暂停态恢复（保位置）；未注册则从 0 开始。timelineId 缺省取
 *       {@code activeTimelineId}。一墙一时刻只播一条 timeline。</li>
 *   <li>{@code pause}：停 cadence、记住位置；{@link #isWallAnimating} 转 false（分流 gate 放行
 *       reactive 路径）。不持久化——重启后 LOOP 墙总是自动播。</li>
 *   <li>{@code seek}：定位（播放/暂停态均可；未注册时建暂停态条目，等 play 从该处续）。</li>
 *   <li>{@code ONCE} 播到尾：渲染末帧一次后自动注销（不烧空 tick）。</li>
 *   <li>viewer-gated：无观察者跳帧不渲染；位置按墙钟推进，观察者回来时动画在正确时刻。</li>
 * </ul>
 */
public final class AnimationTicker implements AnimationTickerGate {

    /**
     * wall 数据源 seam（照 {@code VariableSubCommand.WallSource} 范式）：
     * 生产走 {@link #fromRepo(WallRepo)}；测试注内存 fake，不碰 SQLite。
     */
    public interface WallSource {
        /** 按 id 取 wall；不存在 / 加载失败返 {@code null}（实现侧自行 log）。 */
        WallRepo.Wall load(String wallId);

        /** 全量列举（启动期自动播扫描用）；失败返空表。 */
        List<WallRepo.Wall> loadAll();
    }

    /** 生产装配：包一层 {@link WallRepo}（异常吞掉转 null/空表，Ticker 路径不被 DB 抖动打断）。 */
    public static WallSource fromRepo(WallRepo repo) {
        Logger log = Logger.getLogger(AnimationTicker.class.getName());
        return new WallSource() {
            @Override
            public WallRepo.Wall load(String wallId) {
                try {
                    return repo.loadById(wallId).orElse(null);
                } catch (Exception e) {
                    log.warning("AnimationTicker.WallSource: loadById failed wallId="
                            + wallId + " err=" + e.getMessage());
                    return null;
                }
            }

            @Override
            public List<WallRepo.Wall> loadAll() {
                try {
                    return repo.loadAll();
                } catch (Exception e) {
                    log.warning("AnimationTicker.WallSource: loadAll failed: " + e.getMessage());
                    return List.of();
                }
            }
        };
    }

    /** 渲染出口 seam：实现 = CanvasProjector 侧（B1 装配）；测试可 fake。 */
    public interface FrameRenderer {

        /**
         * 渲染插值帧并发包。实现侧负责：① 观察者查询（无观察者且 {@code force == false}
         * 时返回 {@code -1}，<b>不 rasterize</b>——viewer-gated 的真正省 CPU 点在此）；
         * ② per-map 帧间 diff（只发像素变了的 map）与新观察者全量补发，工作区在
         * {@code diff}（<b>仅 Ticker 线程访问</b>，控制线程不得触碰）。
         *
         * @param force {@code true} = 无观察者也渲染并更新像素缓存（ONCE 末帧终态）
         * @return {@code -1} = 因无观察者跳过（未 rasterize）；{@code >= 0} = 实际 update 的 map 数
         */
        int renderFrame(WallRepo.Wall wall, ProjectState frame, FrameDiff diff, boolean force);
    }

    /** per-wall 帧间 diff 工作区（projector 实现侧拥有；<b>仅 Ticker 线程访问</b>）。 */
    public static final class FrameDiff {
        /** 上一帧每张 map 的调色板字节（index 对齐 mapIds）；null = 尚无基准。 */
        public byte[][] lastFrames;
        /** 上一帧的观察者集合（新观察者出现 → 全量补发）。 */
        public Set<UUID> lastViewerIds;

        public void reset() {
            lastFrames = null;
            lastViewerIds = null;
        }
    }

    /** play/seek 的结果（dispatcher 映射到协议错误码）。 */
    public enum Result { OK, WALL_NOT_FOUND, TIMELINE_NOT_FOUND }

    private static final class PlaybackEntry {
        final String wallId;
        WallRepo.Wall wall;          // 缓存（invalidate 后重载）
        Timeline timeline;
        String timelineId;
        long basisNanos;             // 播放基准（nano 时刻）
        long basisPosMs;             // 基准时刻的播放位置
        boolean paused;
        boolean invalidated;
        /**
         * 失效代次：{@link #invalidate} 每次自增。{@link #play} 在锁外读 DB，进锁后比对这个数 ——
         * 不等就说明手上的 wall 快照在 load 期间已被编辑覆盖过，不能拿它回填。
         */
        long invalidationGen;
        /**
         * diff 只许 Ticker 线程触碰。控制线程（play 非 resume /
         * invalidate）想清 diff 时置此标志，由下一 tick 在 Ticker 线程上执行 reset。
         */
        boolean diffResetPending;
        ScheduledFuture<?> task;     // null = 暂停（无 cadence）
        long cadenceMs;
        final FrameDiff diff = new FrameDiff();

        PlaybackEntry(String wallId) {
            this.wallId = wallId;
        }
    }

    private final WallSource wallSource;
    private final FrameRenderer renderer;
    private final int maxFps;
    private final Logger log;

    private final ScheduledExecutorService scheduler;
    private final Map<String, PlaybackEntry> entries = new ConcurrentHashMap<>();
    /**
     * 补间引擎（TweenScheduler）专用 per-wall diff 表。key = wallId。
     * 仅 Ticker 线程（scheduler）读写（renderStatic 经 scheduler.execute 投递到 Ticker 线程），
     * 契约同 {@link PlaybackEntry#diff}——线程限定安全。
     */
    private final Map<String, FrameDiff> staticDiffs = new ConcurrentHashMap<>();
    /**
     * 补间静态帧路径的 wall 元数据缓存（mapIds / world / key），key = wallId。
     * 与 {@link #staticDiffs} 同生命周期、同线程契约（仅 Ticker 线程读写）；
     * 由 {@link #invalidate}（编辑落库）与 {@link #clearStaticDiff}（补间结束）失效。
     */
    private final Map<String, WallRepo.Wall> staticWalls = new ConcurrentHashMap<>();
    private volatile boolean shutdown;

    /** {@link #play} 撞上并发 invalidate 时的重读上限；用尽后退化为「置 invalidated 交给下一 tick」。 */
    private static final int MAX_PLAY_RELOAD_RETRIES = 3;

    /** 时钟注入 seam（测试用）。 */
    private volatile LongSupplier nanoClock = System::nanoTime;

    /**
     * 数值轨 {@code ${var:X}} 求值 seam（raw + wallId → 数值；rendering.md §9.5）。
     * 装配层注 {@code VariableInterpolator::resolveAsNumber}；null = 无变量支持
     * （Str 关键帧里的纯数字仍可用、变量引用退 0）。
     */
    public interface WallNumberResolver {
        double resolve(String raw, String wallId);
    }

    private volatile WallNumberResolver numberResolver;

    /** 装配 seam（见 {@link WallNumberResolver}）。 */
    public void setNumberResolver(WallNumberResolver resolver) {
        this.numberResolver = resolver;
    }

    /** 按 wall 绑定的 per-tick resolver；无全局 resolver 时返 null（插值器本地解析纯数字）。 */
    private KeyframeInterpolator.NumberResolver resolverFor(String wallId) {
        WallNumberResolver r = this.numberResolver;
        return r == null ? null : raw -> r.resolve(raw, wallId);
    }

    /** 测试 seam：直接驱动一次 tick（绕开 scheduler）。 */
    void tickOnceForTest(String wallId) {
        PlaybackEntry e = entries.get(wallId);
        if (e != null) tick(e);
    }

    /**
     * 测试 seam：在 Ticker 线程排一道屏障任务并阻塞等其完成。
     * 单线程 scheduler 的 FIFO 保证：本方法返回时，此前 {@code scheduler.execute} 投递的
     * 所有任务（renderStatic / clearStaticDiff 的 trampoline）均已执行完毕。
     */
    void flushSchedulerForTest() {
        try {
            scheduler.submit(() -> { }).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("flushSchedulerForTest failed", e);
        }
    }

    /** 测试 seam：当前 staticDiffs 是否残留指定 wall 条目。 */
    boolean hasStaticDiffForTest(String wallId) {
        return staticDiffs.containsKey(wallId);
    }

    /** 测试 seam：当前 staticDiffs 条目数。 */
    int staticDiffCountForTest() {
        return staticDiffs.size();
    }

    void setNanoClock(LongSupplier clock) {
        this.nanoClock = clock;
    }

    public AnimationTicker(WallSource wallSource, FrameRenderer renderer, int maxFps, Logger log) {
        this.wallSource = wallSource;
        this.renderer = renderer;
        this.maxFps = Math.max(1, maxFps);
        this.log = log;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "hikari-canvas-anim-ticker");
            t.setDaemon(true);
            return t;
        });
    }

    /** 把 {@link BufferPool} 绑定到 Ticker 线程（装配层构造后调一次；在 Ticker 线程上执行绑定）。 */
    public void bindBufferPool(BufferPool pool) {
        if (pool == null) return;
        scheduler.execute(pool::bindToCurrentThread);
    }

    // ---------- 播放控制（dispatcher / 装配层线程调；entry 级同步） ----------

    /**
     * 播放。{@code timelineId == null} 取 wall 的 {@code activeTimelineId}。
     * 暂停态且同 timeline → 恢复（保位置）；否则从 0 开始。
     */
    public Result play(String wallId, String timelineId) {
        if (shutdown || wallId == null) return Result.WALL_NOT_FOUND;

        // loadWall 是 DB 读，必须放锁外（entry 锁被 isWallAnimating 等热路径共用，不能压在 IO 上）。
        // 代价是「锁外读到的快照可能在进锁前就被编辑覆盖」：schedule 触发型时间轴每秒调一次 play，
        // 与 persistWall→invalidate 撞上时，旧写法无条件 e.wall = w + e.invalidated = false
        // 会把编辑连同失效标志一起吞掉，墙上一直渲染编辑前的状态直到下次 invalidate。
        // 这里用 invalidationGen 做版本比对：不等就重新 load；重试用尽仍撞上就置 invalidated，
        // 交给下一 tick 重载 —— 绝不静默采用过期快照。
        for (int attempt = 0; ; attempt++) {
            PlaybackEntry pre = entries.get(wallId);
            long genBefore = pre == null ? 0L : generationOf(pre);

            WallRepo.Wall w = loadWall(wallId);
            if (w == null) return Result.WALL_NOT_FOUND;
            Timeline tl = resolveTimeline(w.state(), timelineId);
            if (tl == null) return Result.TIMELINE_NOT_FOUND;

            // TOCTOU：computeIfAbsent 与进入 entry 锁之间，并发 stopWall
            // 可能已把该 entry 摘出 map——若仍在其上 schedule 会产生「永不可达的孤儿 cadence 任务」。
            // 锁内复核 map 成员资格，被摘除则重试拿新 entry；stopWall 的 remove 也在 entry 锁内，
            // 两者互斥后该窗口被关死。
            PlaybackEntry e = entries.computeIfAbsent(wallId, PlaybackEntry::new);
            synchronized (e) {
                if (entries.get(wallId) != e) continue;
                boolean stale = e.invalidationGen != genBefore;
                if (stale && attempt < MAX_PLAY_RELOAD_RETRIES) continue;   // 重新 load 一遍
                boolean resume = e.paused && tl.id().equals(e.timelineId);
                e.wall = w;
                e.timeline = tl;
                e.timelineId = tl.id();
                e.invalidated = stale;   // 重试用尽仍撞上 → 让下一 tick 自己重载
                e.basisNanos = nanoClock.getAsLong();
                if (!resume) {
                    e.basisPosMs = 0L;
                }
                // diff 基准无条件重置。暂停期间 gate 放行 reactive 路径直写地图
                // （projectByWall / 补间静态帧），恢复播放后 per-map diff 会以为「像素没变」
                // 而永不重推，画面卡在暂停帧上。不直接动 e.diff（在飞 tick 可能正用它），
                // 置标志由 Ticker 线程消化。
                e.diffResetPending = true;
                e.paused = false;
                schedule(e, cadenceOf(tl));
                return Result.OK;
            }
        }
    }

    /** 锁外读 {@code play} 用的失效代次快照。 */
    private static long generationOf(PlaybackEntry e) {
        synchronized (e) {
            return e.invalidationGen;
        }
    }

    /** 暂停（幂等；未注册 = no-op）。位置保留，gate 立即放行 reactive 路径。 */
    public void pause(String wallId) {
        PlaybackEntry e = wallId == null ? null : entries.get(wallId);
        if (e == null) return;
        synchronized (e) {
            if (entries.get(wallId) != e) return;   // 已被并发 stopWall 摘除
            if (!e.paused) {
                e.basisPosMs = currentPosMs(e);
                e.paused = true;
            }
            cancelTask(e);
        }
    }

    /**
     * 定位到 {@code atMs}。未注册时建暂停态条目（随后 play 从该处续）。
     * 播放态保持播放（从新位置继续）。
     */
    public Result seek(String wallId, String timelineId, long atMs) {
        if (shutdown || wallId == null) return Result.WALL_NOT_FOUND;
        while (true) {
            PlaybackEntry e = entries.get(wallId);
            if (e == null) {
                WallRepo.Wall w = loadWall(wallId);
                if (w == null) return Result.WALL_NOT_FOUND;
                Timeline tl = resolveTimeline(w.state(), timelineId);
                if (tl == null) return Result.TIMELINE_NOT_FOUND;
                e = entries.computeIfAbsent(wallId, PlaybackEntry::new);
                synchronized (e) {
                    if (entries.get(wallId) != e) continue;   // 并发摘除 → 重试（同款复核）
                    if (e.timeline == null) {
                        // 我们刚创建的空 entry → 填充为暂停态；并发 play 已填则不覆盖播放态
                        e.wall = w;
                        e.timeline = tl;
                        e.timelineId = tl.id();
                        e.paused = true;
                    }
                    e.basisPosMs = clampPos(atMs, e.timeline);
                    e.basisNanos = nanoClock.getAsLong();
                    return Result.OK;
                }
            }
            synchronized (e) {
                if (entries.get(wallId) != e) continue;
                Timeline tl = e.timeline;
                if (tl == null) return Result.TIMELINE_NOT_FOUND;
                e.basisPosMs = clampPos(atMs, tl);
                e.basisNanos = nanoClock.getAsLong();
                return Result.OK;
            }
        }
    }

    /** 注销（wall 删除 / timeline 删除 / ONCE 播完）。幂等。 */
    public void stopWall(String wallId) {
        if (wallId == null) return;
        PlaybackEntry e = entries.get(wallId);
        if (e == null) return;
        synchronized (e) {
            // remove 在 entry 锁内执行（与 play/seek 的锁内成员资格复核互斥，关死 TOCTOU 窗口）；
            // remove(key, value) 双参形态防误删并发 play 重试后塞入的新 entry
            if (entries.remove(wallId, e)) {
                cancelTask(e);
                e.paused = true;   // 防御：in-flight tick 经 paused / 成员资格双重早退
            }
        }
    }

    /**
     * 缓存失效（编辑持久化完成后由装配层调；gate 退让 reactive 意图时也经
     * {@link #onReactiveYield} 转到此，docs/architecture.md §5.5 状态缓存）。
     *
     * <ul>
     *   <li>播放中：置标志，下一 tick 重载 wall + 重解析 timeline + 全量补发；
     *       timeline 已不存在 → 自动注销。</li>
     *   <li>暂停态：没有 tick 来消化标志 → 当场在调用线程重载（持久化路径线程，
     *       一次有界 DB 读）；wall / timeline 消失 → 当场注销，杜绝 stale 条目滞留。</li>
     * </ul>
     */
    public void invalidate(String wallId) {
        if (wallId == null) return;
        // 补间静态帧那条路径的 wall 缓存也要失效 —— 那种墙通常没有 timeline entry，
        // 放在下面的 entry 判空之后就永远轮不到。
        invalidateStaticWall(wallId);
        PlaybackEntry e = entries.get(wallId);
        if (e == null) return;
        synchronized (e) {
            if (entries.get(wallId) != e) return;
            e.invalidationGen++;   // 让并发 play 看见「你手上的快照过期了」
            // 被吸收的 reactive 意图（编辑 flush / wall.refresh）转为下一帧全量补发，
            // 避免 per-map diff 跳过「像素没变但 ItemFrame 重挂」的 map
            e.diffResetPending = true;
            if (e.paused) {
                reloadLocked(e);
            } else {
                e.invalidated = true;
            }
        }
    }

    /** gate 回调：reactive 产帧意图被丢弃 → 转为 invalidate。 */
    @Override
    public void onReactiveYield(String wallId) {
        invalidate(wallId);
    }

    /**
     * 自动播放复核（启动恢复后逐墙调；session 关闭钩子调）：
     * wall 有 {@code activeTimelineId} 且 LOOP 且未注册 → 自动 play（timeline.md §5.2
     * 「wall ready 自动播 LOOP 的简化默认」）。wall 不存在 → 注销。
     */
    public void refreshAutoPlay(String wallId) {
        if (shutdown || wallId == null) return;
        WallRepo.Wall w = loadWall(wallId);
        if (w == null || w.state() == null) {
            stopWall(wallId);
            return;
        }
        if (entries.containsKey(wallId)) {
            invalidate(wallId);
            return;
        }
        String activeId = w.state().activeTimelineId();
        if (activeId == null) return;
        Timeline tl = resolveTimeline(w.state(), activeId);
        if (autoLoopEligible(tl)) {
            log.fine("[timeline] wall " + wallId + " auto-playing loop animation " + activeId + " (triggered by editor close / refresh)");
            play(wallId, activeId);
        }
    }

    /**
     * wall-ready 自动 LOOP 播仅对 {@code MANUAL} 触发生效。{@code VARIABLE_CHANGE} /
     * {@code SCHEDULE} 触发的时间轴不在此自动播——改由 {@link TimelineTriggerRegistry} 在绑定变量
     * 变化时驱动 {@link #play}（timeline.md §5.2）。
     */
    private static boolean autoLoopEligible(Timeline tl) {
        if (tl == null || tl.loopMode() != LoopMode.LOOP) return false;
        TriggerConfig trig = tl.trigger();
        return trig == null || trig.type() == TriggerType.MANUAL;
    }

    /**
     * 启动期全库扫描自动播（WallRestorer.restore 之后调）。返回注册数。
     *
     * @param excludeWallIds 排除集（restore 失败的 wall 其 mapIds 已
     *                       releaseToFree，注册播放会向已归还 / 可能被复用的 mapId 写像素——
     *                       传 {@code WallRestorer.failedRestoreWallIds()}）；null = 不排除
     */
    public int autoRegisterAll(Set<String> excludeWallIds) {
        if (shutdown) return 0;
        int n = 0;
        for (WallRepo.Wall w : wallSource.loadAll()) {
            if (excludeWallIds != null && excludeWallIds.contains(w.wallId())) continue;
            ProjectState s = w.state();
            if (s == null || s.activeTimelineId() == null) continue;
            Timeline tl = resolveTimeline(s, s.activeTimelineId());
            if (autoLoopEligible(tl)) {
                if (play(w.wallId(), tl.id()) == Result.OK) n++;
            }
        }
        return n;
    }

    /**
     * 补间引擎用：渲染某 wall 的一帧补间中间态（不落 DB；Ticker 线程执行保 BufferPool/diff 契约）。
     *
     * <p>该 wall 的时间轴<b>正在播</b>时本方法静默 no-op（timeline tick 优先）。判定必须与
     * {@link #isWallAnimating} 用同一条谓词：补间引擎正是按它分流的 —— 之前这里用
     * {@code entries.containsKey}，把暂停态也算作「有人产帧」，于是暂停 / seek 过一次的墙上
     * 补间的每一帧都被吞掉，画面完全不动。</p>
     *
     * <p>wall 元数据（mapIds / world / key）按 wallId 缓存，与 {@link #staticDiffs} 同生命周期。
     * 类契约写死了「不每 tick loadById」，补间默认 30fps，这条路径每帧一次 DB 读 + 全量 JSON
     * 反序列化比时间轴路径判死刑的 20 读/秒还高。缓存由 {@link #invalidate} /
     * {@link #clearStaticDiff} 失效。</p>
     *
     * <p>任意线程可调（投递 scheduler.execute 到 Ticker 线程）；frame 必须 immutable 不可变。</p>
     */
    public void renderStatic(String wallId, ProjectState frame) {
        if (shutdown || wallId == null || frame == null) return;
        scheduler.execute(() -> {
            try {
                // 只有真正在播的时间轴才让位；暂停态不产帧，让位等于把这一帧扔了
                if (isWallAnimating(wallId)) return;
                WallRepo.Wall wall = staticWalls.get(wallId);
                if (wall == null) {
                    wall = wallSource.load(wallId);
                    if (wall == null) return;
                    staticWalls.put(wallId, wall);
                }
                FrameDiff diff = staticDiffs.computeIfAbsent(wallId, k -> new FrameDiff());
                renderer.renderFrame(wall, frame, diff, false);
            } catch (Throwable t) {
                log.log(Level.WARNING, "renderStatic failed wallId=" + wallId + ": " + t.getMessage(), t);
            }
        });
    }

    /**
     * 丢掉补间静态帧路径的 wall 缓存。{@link #staticWalls} 契约是「仅 Ticker 线程读写」，
     * 所以走 {@code scheduler.execute} 投递（同 {@link #clearStaticDiff} 的理由）。
     */
    private void invalidateStaticWall(String wallId) {
        if (shutdown) return;
        try {
            scheduler.execute(() -> staticWalls.remove(wallId));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // 关停竞态，随进程退出整体回收
        }
    }

    /**
     * 补间引擎用：补间结束（或中途取消）后清理 per-wall 静态 diff，防内存泄漏。任意线程可调。
     *
     * <p>{@link #staticDiffs} 契约是「仅 Ticker 线程（scheduler）读写」，故本方法的 remove
     * <b>必须经 {@code scheduler.execute} 投递到 Ticker 线程</b>，不能在调用线程直接
     * {@code staticDiffs.remove}——否则与异步 {@link #renderStatic} 形成竞态：补间末帧序列
     * 「先 clearStaticDiff(remove) 再 renderStatic(异步投 Ticker 线程 computeIfAbsent 新建空
     * FrameDiff)」会让该条目永不被清，每个静态墙补间完泄漏一条 {@code byte[mapCount][16384]}。
     * 单线程 executor 的 FIFO 保证此 remove 排在同一序列里 {@link #renderStatic} 投递的
     * computeIfAbsent 任务<b>之后</b>执行，孤儿条目被关死。</p>
     */
    public void clearStaticDiff(String wallId) {
        if (shutdown || wallId == null) return;
        try {
            scheduler.execute(() -> {
                staticDiffs.remove(wallId);
                staticWalls.remove(wallId);
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // 关停竞态（scheduler 已 shutdown）——staticDiffs 会随进程退出整体回收，无需补救
        }
    }

    /** 幂等关停（onDisable，DB close 之前）。 */
    public void shutdown() {
        if (shutdown) return;
        shutdown = true;
        for (String wallId : List.copyOf(entries.keySet())) {
            stopWall(wallId);
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

    // ---------- gate ----------

    @Override
    public boolean isWallAnimating(String wallId) {
        if (shutdown || wallId == null) return false;
        PlaybackEntry e = entries.get(wallId);
        if (e == null) return false;
        synchronized (e) {
            return !e.paused;
        }
    }

    /** 观测：当前注册（含暂停）的 wall 数。 */
    public int registeredCount() {
        return entries.size();
    }

    // ---------- tick（仅 scheduler 线程） ----------

    private void tick(PlaybackEntry e) {
        try {
            if (shutdown) return;
            WallRepo.Wall w;
            Timeline tl;
            long posMs;
            boolean resetDiff;
            synchronized (e) {
                if (e.paused) return;
                // stopWall 后 cancel(false) 不中断在飞 run——成员资格复核弃帧，
                // 防对已注销（可能已删除、mapIds 已归还）的 wall 再渲染
                if (entries.get(e.wallId) != e) return;
                if (e.invalidated && !reloadLocked(e)) return;   // 重载失败已注销
                w = e.wall;
                tl = e.timeline;
                if (w == null || tl == null) return;
                posMs = currentPosMs(e);
                resetDiff = e.diffResetPending;
                e.diffResetPending = false;
            }
            // diff 只在 Ticker 线程触碰；单线程 scheduler 串行保证与 renderFrame 不交错
            if (resetDiff) e.diff.reset();

            KeyframeInterpolator.NumberResolver resolver = resolverFor(e.wallId);

            if (tl.loopMode() == LoopMode.ONCE && posMs >= tl.durationMs()) {
                // 末帧渲染一次（force：无视 viewer——更新像素缓存让后到的观察者看到终态）后注销
                ProjectState frame = KeyframeInterpolator.interpolate(
                        w.state(), tl, tl.durationMs(), resolver);
                renderer.renderFrame(w, frame, e.diff, true);
                stopWall(e.wallId);
                return;
            }

            // viewer-gated 在 renderFrame 实现侧（无观察者返 -1 不 rasterize，免双扫描）；
            // 位置按墙钟推进，观察者回来时动画在正确时刻
            int t = KeyframeInterpolator.mapTime(posMs, tl.durationMs(), tl.loopMode());
            ProjectState frame = KeyframeInterpolator.interpolate(w.state(), tl, t, resolver);
            renderer.renderFrame(w, frame, e.diff, false);
        } catch (Throwable th) {
            // 任务级异常隔离：单帧失败不杀 cadence（照 VariableProviderDaemon 范式）
            log.log(Level.WARNING,
                    "AnimationTicker tick failed wallId=" + e.wallId + ": " + th.getMessage(), th);
        }
    }

    /** 持 entry 锁调用。重载缓存；wall / timeline 消失 → 注销并返回 false。 */
    private boolean reloadLocked(PlaybackEntry e) {
        WallRepo.Wall w = loadWall(e.wallId);
        if (w == null) {
            removeLocked(e);
            return false;
        }
        Timeline tl = resolveTimeline(w.state(), e.timelineId);
        if (tl == null) {
            removeLocked(e);
            return false;
        }
        e.wall = w;
        long newCadence = cadenceOf(tl);
        // 暂停态不 schedule（invalidate 当场重载路径会走到这——暂停态没有 cadence，
        // 只更新 cadenceMs 字段；play 恢复时总是按最新 timeline 重新 schedule）
        if (!e.paused && (e.timeline == null || newCadence != e.cadenceMs)) {
            schedule(e, newCadence);    // fps 改了 → 换 cadence
        } else {
            e.cadenceMs = newCadence;
        }
        e.timeline = tl;
        e.invalidated = false;
        return true;
    }

    /** 持 entry 锁调用。把 entry 摘出 map + 取消任务（reload 失败 / stale 清理用）。 */
    private void removeLocked(PlaybackEntry e) {
        entries.remove(e.wallId, e);
        cancelTask(e);
        e.paused = true;
    }

    // ---------- 内部 ----------

    /** 持 entry 锁调用。 */
    private void schedule(PlaybackEntry e, long cadenceMs) {
        cancelTask(e);
        e.cadenceMs = cadenceMs;
        if (!shutdown) {
            e.task = scheduler.scheduleAtFixedRate(
                    () -> tick(e), cadenceMs, cadenceMs, TimeUnit.MILLISECONDS);
        }
    }

    /** 持 entry 锁调用。 */
    private void cancelTask(PlaybackEntry e) {
        if (e.task != null) {
            e.task.cancel(false);
            e.task = null;
        }
    }

    private long currentPosMs(PlaybackEntry e) {
        if (e.paused) return e.basisPosMs;
        return e.basisPosMs + (nanoClock.getAsLong() - e.basisNanos) / 1_000_000L;
    }

    private long cadenceOf(Timeline tl) {
        int fps = Math.min(Math.max(tl.fps(), 1), maxFps);
        return Math.max(1L, Math.round(1000.0 / fps));
    }

    private static long clampPos(long atMs, Timeline tl) {
        return Math.min(Math.max(atMs, 0L), tl.durationMs());
    }

    private WallRepo.Wall loadWall(String wallId) {
        return wallSource.load(wallId);
    }

    private static Timeline resolveTimeline(ProjectState state, String timelineId) {
        if (state == null) return null;
        String want = timelineId != null ? timelineId : state.activeTimelineId();
        if (want == null) return null;
        for (Timeline t : state.timelines()) {
            if (t.id().equals(want)) return t;
        }
        return null;
    }
}
