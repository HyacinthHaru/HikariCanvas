package moe.hikari.canvas.script.engine;

import moe.hikari.canvas.render.EasingSolver;
import moe.hikari.canvas.script.Action;
import moe.hikari.canvas.state.Easing;
import moe.hikari.canvas.state.EditSession;
import moe.hikari.canvas.state.Element;
import moe.hikari.canvas.state.ProjectState;
import moe.hikari.canvas.state.StrictNumber;
import moe.hikari.canvas.storage.WallRepo; // 生产构造用

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 补间动画引擎（scripting-tween.md P2；docs/scripting-tween.md T1-T10）。
 *
 * <p><b>架构路径 Z</b>：补间引擎维护内存中的临时插值帧，经 {@link TickerControl#renderStatic}
 * 渲到地图（不落 DB）；末帧经 {@link #ApplyManyFn} 落 DB（永久目标值）。</p>
 *
 * <p><b>三线程契约</b>：</p>
 * <ul>
 *   <li>enqueue — Runner 线程（ScriptRunner.runFrames）调用</li>
 *   <li>tick — 独立单线程 SES（{@code hikari-canvas-tween}）调用</li>
 *   <li>renderStatic — Ticker 线程（AnimationTicker 的 scheduler）调用</li>
 * </ul>
 *
 * <p>{@code active} ConcurrentHashMap 保证跨线程可见；{@link TweenTask} 字段全为 final
 * immutable（List.copyOf），构造后不修改，跨线程传递安全；frame（ProjectState）为 Java record
 * immutable，跨线程安全。</p>
 *
 * <p><b>MVP 限制</b>：一墙一补间（同 wallId 后来者接管旧任务，T8 简化版）；仅数值属性
 * （x/y/w/h/rotation/opacity）；颜色/fill 补间在 P3 接入。</p>
 */
public final class TweenScheduler {

    // ---------- seam 接口（生产装配 lambda，测试注 fake） ----------

    /**
     * 末帧落盘 seam（照 {@link ElementPropertyApplier#applyMany} 签名）。
     * 生产装配：{@code applier::applyMany}；测试注记录 fake。
     */
    @FunctionalInterface
    public interface ApplyManyFn {
        TraceStep apply(String wallId, String blockId, String elementId,
                        Map<String, String> rawPatch);
    }

    /**
     * Wall state 读取 seam（照 {@link AnimationTicker.WallSource} 范式）。
     * 生产装配：{@code wallRepo::loadById} 包一层；测试注内存 fake。
     */
    @FunctionalInterface
    public interface WallLoader {
        /** 按 id 取 ProjectState；不存在 / 加载失败返 {@code null}。 */
        ProjectState load(String wallId);
    }

    // ---------- 数据模型 ----------

    /**
     * 单属性补间目标。{@code from} / {@code to} 均为 double（数值属性）。
     */
    public record PropTarget(String elementId, String property, double from, double to) {}

    /**
     * 活跃补间任务。所有字段 final；frame baseState 是 immutable record；targets 是 List.copyOf。
     * tick 线程读取；enqueue 线程构造后 put 到 ConcurrentHashMap → tick 线程可见。
     *
     * <p>{@code fps} 是 enqueue 时从 wall 的 {@link ProjectState#effectiveTweenFps()} 读取的
     * per-wall 帧率——已被 config maxFps clamp，决定 renderStatic 节流间隔（1000/fps ms）。</p>
     */
    record TweenTask(String wallId, String blockId, List<PropTarget> targets,
                     long startMs, long durationMs, Easing easing,
                     ProjectState baseState, int fps) {
        TweenTask {
            targets = List.copyOf(targets);
        }
    }

    // ---------- 字段 ----------

    private final ScheduledExecutorService scheduler;
    /** key = wallId；MVP 一墙一补间。ConcurrentHashMap 保证 enqueue/tick 跨线程可见。 */
    private final Map<String, TweenTask> active = new ConcurrentHashMap<>();
    /**
     * per-wall 最后一次 renderStatic 时间戳（毫秒）。key = wallId。
     * <b>三线程契约：</b> tick 线程读写（节流 get/put + 任务结束 remove），<b>且 enqueue 线程
     * 接管时也 remove</b>（清旧 stale，让新任务首帧立即渲）——故跨 tick/Runner 两线程，
     * <b>必须 ConcurrentHashMap</b>：各操作原子、不会 rehash 并发 corrupt；接管瞬间 get/put
     * 与 remove 的语义竞态无害（最多新任务首帧节流判断偏一帧）。
     */
    private final Map<String, Long> lastRenderAt = new ConcurrentHashMap<>();
    private final ApplyManyFn applyFn;
    private final TickerControl ticker;
    private final WallLoader wallLoader;
    /** 时钟注入 seam（测试用）。 */
    private final LongSupplier clock;
    private final int maxConcurrent;
    /**
     * SES cadence 上限（config scripts.tween.max-fps；默 60）。
     * SES 以 1000/maxFps ms 固定 tick；per-wall 的 fps（来自 task.fps）在 tick 内节流 renderStatic。
     */
    private final int maxFps;
    private volatile boolean shutdown;
    private final Logger log;

    // ---------- 构造 ----------

    /**
     * 生产装配入口（WallRepo 版本）。
     *
     * @param applier       末帧落盘通道
     * @param ticker        Ticker 渲染通道（renderStatic / clearStaticDiff）
     * @param wallRepo      读 wall state（enqueue 时拿 base + from 值）
     * @param clock         时钟 seam（生产 {@code System::currentTimeMillis}）
     * @param maxConcurrent 同时活跃补间上限（超限新补间返 error）
     * @param maxFps        SES cadence 上限（config scripts.tween.max-fps；默 60）
     */
    public TweenScheduler(ElementPropertyApplier applier, TickerControl ticker,
                          WallRepo wallRepo, LongSupplier clock,
                          int maxConcurrent, int maxFps, Logger log) {
        this(applier::applyMany, ticker,
                wallId -> wallRepo.loadById(wallId).map(w -> w.state()).orElse(null),
                clock, maxConcurrent, maxFps, log);
    }

    /**
     * 测试装配 / seam 入口（全 fake 注入）。
     */
    TweenScheduler(ApplyManyFn applyFn, TickerControl ticker,
                   WallLoader wallLoader, LongSupplier clock,
                   int maxConcurrent, int maxFps, Logger log) {
        this.applyFn = applyFn;
        this.ticker = ticker;
        this.wallLoader = wallLoader;
        this.clock = clock;
        this.maxConcurrent = Math.max(1, maxConcurrent);
        this.maxFps = Math.max(1, maxFps);
        this.log = log;
        long cadenceMs = Math.max(1L, Math.round(1000.0 / this.maxFps));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "hikari-canvas-tween");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleAtFixedRate(this::tick, cadenceMs, cadenceMs, TimeUnit.MILLISECONDS);
    }

    // ---------- enqueue（Runner 线程） ----------

    /**
     * 注册一个补间任务（从 TweenBlock 的 body 解析 targets）。
     *
     * <p>调用方（ScriptRunner）在 Runner 线程调；结果 {@link TraceStep} 决定是否挂起：
     * {@code "ok"} → 挂起 durationMs；否则不挂起，脚本链继续。</p>
     *
     * @param wallId  当前墙
     * @param blockId 补间积木 blockId（用于 trace 定位）
     * @param tb      TweenBlock action（含 durationMs / easing / body）
     */
    public TraceStep enqueue(String wallId, String blockId, Action.TweenBlock tb) {
        if (shutdown) return TraceStep.error(blockId, "补间引擎已关停");
        if (wallId == null) return TraceStep.error(blockId, "wallId 缺失");
        if (tb.body().isEmpty()) return TraceStep.error(blockId, "补间 body 为空");

        // 并发数限制（已有同 wall 任务时接管，不计入 size 比较；先查 active.containsKey）
        boolean sameWallExists = active.containsKey(wallId);
        if (!sameWallExists && active.size() >= maxConcurrent) {
            return TraceStep.error(blockId, "补间已达上限（" + maxConcurrent + "）");
        }

        // 读 base state（从 DB，enqueue 时一次性拍快照）
        ProjectState baseState = wallLoader.load(wallId);
        if (baseState == null) {
            return TraceStep.error(blockId, "wall 不存在或无 state: " + wallId);
        }

        // 收集 PropTarget（从 body 的 SetElementProperties 解析 to + 读当前值 from）
        List<PropTarget> targets = new ArrayList<>();
        for (Action a : tb.body()) {
            if (!(a instanceof Action.SetElementProperties sep)) {
                // 非属性动作（校验层应已拦截，运行层防御性跳过）
                log.warning("[补间] body 含非属性动作: " + a.wireType() + " wall=" + wallId + " block=" + blockId);
                continue;
            }
            String elementId = sep.elementId();
            for (Map.Entry<String, String> entry : sep.patch().entrySet()) {
                String property = entry.getKey();
                String toStr = entry.getValue();
                // 仅支持数值属性（P2 MVP）
                if (!isNumericProperty(property)) {
                    return TraceStep.error(blockId, "补间 P2 仅支持数值属性（x/y/w/h/rotation/opacity），不支持: " + property);
                }
                // 读当前 from 值
                double from = readCurrentValue(baseState, elementId, property, blockId);
                // 解析 to 值（StrictNumber，与 ElementPropertyApplier.buildPatch 同语义）
                double to = StrictNumber.parse(toStr);
                if (!Double.isFinite(to)) {
                    return TraceStep.error(blockId, "补间目标值非有限数: property=" + property + " value=" + toStr);
                }
                // 同 wall 已有补间 → 接管：从当前插值位置作新 from（T8）
                TweenTask existing = active.get(wallId);
                if (existing != null) {
                    from = interpolatedValue(existing, elementId, property, clock.getAsLong());
                }
                targets.add(new PropTarget(elementId, property, from, to));
            }
        }
        if (targets.isEmpty()) {
            return TraceStep.error(blockId, "补间未解析到有效 targets");
        }

        // 同 wall 已有旧补间 → 末尾清 diff（接管，旧补间不再 tick）
        TweenTask old = active.get(wallId);
        if (old != null) {
            ticker.clearStaticDiff(wallId);
            // 接管时清旧 lastRenderAt，让新任务首帧立即渲染
            lastRenderAt.remove(wallId);
        }

        // per-wall 帧率：从 baseState.effectiveTweenFps() 读取，并受 maxFps 硬上限 clamp
        int wallFps = Math.min(maxFps, baseState.effectiveTweenFps());

        TweenTask task = new TweenTask(wallId, blockId, targets,
                clock.getAsLong(), tb.durationMs(),
                tb.easing() != null ? tb.easing() : Easing.LINEAR,
                baseState, wallFps);
        active.put(wallId, task);
        return TraceStep.ok(blockId, "action", "补间注册成功，targets=" + targets.size()
                + " duration=" + tb.durationMs() + "ms");
    }

    // ---------- tick（tween 线程，scheduleAtFixedRate） ----------

    private void tick() {
        if (shutdown || active.isEmpty()) return;
        long now = clock.getAsLong();
        // ConcurrentHashMap.entrySet() 快照迭代（其他线程 put/remove 不影响本次迭代）
        for (Map.Entry<String, TweenTask> entry : active.entrySet()) {
            String wallId = entry.getKey();
            TweenTask task = entry.getValue();
            try {
                tickOne(wallId, task, now);
            } catch (Throwable t) {
                // 任务级异常隔离：单 wall 补间失败不杀整个 tick 循环
                log.log(Level.WARNING, "[补间] tick 失败 wallId=" + wallId + ": " + t.getMessage(), t);
                // 出错也清理，防无限重试
                active.remove(wallId, task);
                ticker.clearStaticDiff(wallId);
            }
        }
    }

    private void tickOne(String wallId, TweenTask task, long now) {
        long elapsed = now - task.startMs();
        double local = task.durationMs() <= 0 ? 1.0
                : Math.min(1.0, Math.max(0.0, (double) elapsed / task.durationMs()));
        double eased = EasingSolver.ease(task.easing(), local);

        if (local >= 1.0) {
            // 末帧：总是渲 + 落 DB（目标值，按 elementId 分组合并 patch） + 清 diff + 注销
            ProjectState finalFrame = buildInterpolatedFrame(task, eased);
            if (finalFrame != null) {
                ticker.renderStatic(wallId, finalFrame);
            }
            Map<String, Map<String, String>> byElement = new java.util.LinkedHashMap<>();
            for (PropTarget pt : task.targets()) {
                byElement.computeIfAbsent(pt.elementId(), k -> new java.util.LinkedHashMap<>())
                         .put(pt.property(), formatFinalValue(pt.property(), pt.to()));
            }
            for (Map.Entry<String, Map<String, String>> el : byElement.entrySet()) {
                try {
                    applyFn.apply(wallId, task.blockId(), el.getKey(), el.getValue());
                } catch (Exception e) {
                    log.log(Level.WARNING, "[补间] 末帧 applyMany 失败 wallId=" + wallId
                            + " elementId=" + el.getKey() + ": " + e.getMessage(), e);
                }
            }
            active.remove(wallId, task);
            lastRenderAt.remove(wallId);
            ticker.clearStaticDiff(wallId);
            return;
        }

        // 中间帧：per-wall fps 节流 renderStatic（进度计算不受节流影响）
        long renderIntervalMs = Math.max(1L, Math.round(1000.0 / task.fps()));
        Long last = lastRenderAt.get(wallId);
        if (last == null || now - last >= renderIntervalMs) {
            ProjectState frame = buildInterpolatedFrame(task, eased);
            if (frame != null) {
                ticker.renderStatic(wallId, frame);
                lastRenderAt.put(wallId, now);
            }
        }
        // else: 节流跳过本次渲染，进度照旧推进（下次 tick 继续算 eased）
    }

    // ---------- 关停 ----------

    /** 幂等关停（cleanupResources：scriptRunner.shutdown 之后、animationTicker.shutdown 之前）。 */
    public void shutdown() {
        if (shutdown) return;
        shutdown = true;
        // 清理所有活跃补间的 staticDiff（防内存泄漏）
        for (String wallId : active.keySet()) {
            try {
                ticker.clearStaticDiff(wallId);
            } catch (Exception e) {
                // ignore
            }
        }
        active.clear();
        lastRenderAt.clear();
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

    // ---------- 内部 helper ----------

    /** 是否数值属性（P2 MVP 支持范围）。 */
    private static boolean isNumericProperty(String property) {
        return switch (property) {
            case "x", "y", "w", "h", "rotation", "opacity" -> true;
            default -> false;
        };
    }

    /**
     * 从 base state 读元素的当前属性值（仅数值属性）。
     * 元素/属性不存在时 → fallback 0.0（后续 target 解析无 from 值时给 0）。
     */
    private static double readCurrentValue(ProjectState state, String elementId,
                                           String property, String blockId) {
        for (var layer : state.layers()) {
            for (Element el : layer.elements()) {
                if (!el.id().equals(elementId)) continue;
                return readElementProperty(el, property);
            }
        }
        return 0.0; // 元素不存在，to-from delta 仍正确工作
    }

    /** 从单个 Element record 读数值属性（仅已知数值属性有语义，其他 0.0）。 */
    private static double readElementProperty(Element el, String property) {
        return switch (property) {
            case "x" -> el.x();
            case "y" -> el.y();
            case "w" -> el.w();
            case "h" -> el.h();
            case "rotation" -> el.rotation();
            case "opacity" -> el.effectiveOpacity();
            default -> 0.0;
        };
    }

    /**
     * 同 wall 接管时：从旧 TweenTask 插值当前值，作新 from（T8 平滑接管）。
     * 找不到 target 时 fallback 读 base state 当前值（由 enqueue 外层处理）。
     */
    private static double interpolatedValue(TweenTask task, String elementId,
                                            String property, long now) {
        long elapsed = now - task.startMs();
        double local = task.durationMs() <= 0 ? 1.0
                : Math.min(1.0, Math.max(0.0, (double) elapsed / task.durationMs()));
        double eased = EasingSolver.ease(task.easing(), local);
        for (PropTarget pt : task.targets()) {
            if (pt.elementId().equals(elementId) && pt.property().equals(property)) {
                return pt.from() + (pt.to() - pt.from()) * eased;
            }
        }
        return readCurrentValue(task.baseState(), elementId, property, "");
    }

    /**
     * 构造插值帧（纯内存，immutable ProjectState；不落 DB）。
     *
     * <p>选择路径：使用临时 {@link EditSession} + {@code updateElement}——EditSession 是纯重建
     * （修改 {@code HistoryStack} 内部 state record，<b>不</b>发包、<b>不</b>落 DB、<b>不</b>碰 Ticker），
     * 其 history/patch 产物即弃，仅取 {@code es.state()} 作输出 ProjectState。
     * 确认无副作用：{@link EditSession#updateElement} 是 {@code synchronized(this)} 纯 record 重建；
     * 网络推送 / 持久化均在 {@code SessionManager.persistWall} 链，本处不经过 SessionManager。</p>
     *
     * <p>对多个 target 属性，逐一在同一 EditSession 上叠加 updateElement（同一 elementId 可多次，
     * 每次是完整 patch map 里的部分属性；若属性来自不同元素则逐元素各调一次）。</p>
     *
     * @param eased  已经过缓动函数映射的插值进度 [0,1]
     */
    private static ProjectState buildInterpolatedFrame(TweenTask task, double eased) {
        // 按 elementId 分组，把同一元素的多属性合并到一次 updateElement 调用
        Map<String, Map<String, Object>> patches = new java.util.LinkedHashMap<>();
        for (PropTarget pt : task.targets()) {
            double value = pt.from() + (pt.to() - pt.from()) * eased;
            Object v = buildPatchValue(pt.property(), value);
            patches.computeIfAbsent(pt.elementId(), k -> new java.util.LinkedHashMap<>())
                   .put(pt.property(), v);
        }

        EditSession es = new EditSession(task.baseState());
        for (Map.Entry<String, Map<String, Object>> entry : patches.entrySet()) {
            EditSession.OpResult r = es.updateElement(entry.getKey(), entry.getValue());
            if (r instanceof EditSession.OpResult.Error er) {
                // updateElement 校验拒绝（如类型错配）：跳过该元素，不崩 tick
                return task.baseState();
            }
        }
        return es.state();
    }

    /**
     * 数值属性值 double → patch Object（同 {@link ElementPropertyApplier#buildPatch} 格式）。
     * x/y/w/h/rotation → int（round + clamp）；opacity → float [0,1]。
     */
    private static Object buildPatchValue(String property, double value) {
        return switch (property) {
            case "x", "y", "w", "h", "rotation" -> StrictNumber.clampInt(Math.round(value));
            case "opacity" -> (float) Math.min(1.0, Math.max(0.0, value));
            default -> StrictNumber.clampInt(Math.round(value));
        };
    }

    /**
     * 末帧落盘的字符串值（同 ElementPropertyApplier.buildPatch 输入格式）。
     * x/y/w/h/rotation → round(to) int string；opacity → double string [0,1]。
     */
    private static String formatFinalValue(String property, double to) {
        return switch (property) {
            case "x", "y", "w", "h", "rotation" -> String.valueOf(StrictNumber.clampInt(Math.round(to)));
            case "opacity" -> String.valueOf(Math.min(1.0, Math.max(0.0, to)));
            default -> String.valueOf(StrictNumber.clampInt(Math.round(to)));
        };
    }

    // ---------- 测试辅助（包级可见） ----------

    /** 活跃任务数（测试断言用）。 */
    int activeCount() {
        return active.size();
    }

    /** 是否有指定 wall 的活跃任务（测试断言用）。 */
    boolean hasActive(String wallId) {
        return active.containsKey(wallId);
    }

    /** 强制触发一次 tick（测试用，绕开 scheduleAtFixedRate）。 */
    void tickForTest() {
        tick();
    }
}
