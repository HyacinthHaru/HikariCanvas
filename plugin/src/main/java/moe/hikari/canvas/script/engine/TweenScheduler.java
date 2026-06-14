package moe.hikari.canvas.script.engine;

import moe.hikari.canvas.render.ColorLerp;
import moe.hikari.canvas.render.EasingSolver;
import moe.hikari.canvas.script.Action;
import moe.hikari.canvas.state.BrushStrokeElement;
import moe.hikari.canvas.state.CircleElement;
import moe.hikari.canvas.state.Easing;
import moe.hikari.canvas.state.EditSession;
import moe.hikari.canvas.state.Element;
import moe.hikari.canvas.state.ElementValidator;
import moe.hikari.canvas.state.Fill;
import moe.hikari.canvas.state.IconElement;
import moe.hikari.canvas.state.Layer;
import moe.hikari.canvas.state.PathElement;
import moe.hikari.canvas.state.ProjectState;
import moe.hikari.canvas.state.RectElement;
import moe.hikari.canvas.state.ShapeElement;
import moe.hikari.canvas.state.SolidFill;
import moe.hikari.canvas.state.StrictNumber;
import moe.hikari.canvas.state.TextElement;
import moe.hikari.canvas.storage.WallRepo; // 生产构造用

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * immutable（List.copyOf），构造后不修改，跨线程传递安全。<b>注意（P3-16）</b>：
 * {@code ProjectState} record 的<b>外壳</b>不可变，但内层 {@code layers} / 每层 {@code elements}
 * 是可变 {@code ArrayList}——故 {@code baseState} 绝不直接产帧传给异步读的
 * {@link TickerControl#renderStatic}；{@link #buildInterpolatedFrame} 每帧 {@link #deepCopyState}
 * 拍独立副本（layers + elements 列表全新），返回的 frame 才真正跨线程不可变安全。</p>
 *
 * <p><b>P3 属性支持</b>：数值（x/y/w/h/rotation/opacity）/ 颜色（TextElement.color,
 * {@link ColorLerp#lerpHex}）/ fill（几何元素 {@link ColorLerp#lerpFill}）；含 {@code ${var:}}
 * 的颜色/fill 退化末帧瞬切（不每帧 resolve）。一墙一补间（同 wallId 后来者接管，T8 简化版）。</p>
 *
 * <p><b>P3 共存分流</b>（§5）：每 tick 现查 {@link TickerControl#isWallAnimating(String)}；
 * 有时间轴 → 每渲帧 {@link ApplyManyFn} 落 DB（让 Ticker reload 叠加关键帧）；
 * 静态墙 → renderStatic 渲临时态 + 末帧落 DB。</p>
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
     * 单属性补间目标（P3 三态 sealed interface）。所有实现均 immutable record。
     *
     * <ul>
     *   <li>{@link NumericTarget} — 数值属性（x/y/w/h/rotation/opacity），double 插值</li>
     *   <li>{@link ColorTarget} — 颜色属性（TextElement.color），{@link ColorLerp#lerpHex}；
     *       含 {@code ${var:}} 的颜色不插值（退化末帧瞬切，见 §4 规则）</li>
     *   <li>{@link FillTarget} — fill 属性（Rect/Icon/Path/Circle/Shape/Brush），
     *       {@link ColorLerp#lerpFill}；snap = true 时同样末帧瞬切</li>
     * </ul>
     *
     * <p>三线程契约：所有字段 final immutable，构造后不修改，跨 enqueue/tick 线程传递安全。</p>
     */
    public sealed interface PropTarget permits TweenScheduler.NumericTarget,
            TweenScheduler.ColorTarget, TweenScheduler.FillTarget {
        String elementId();
        String property();
    }

    /** 数值属性补间目标（x/y/w/h/rotation/opacity）。 */
    public record NumericTarget(String elementId, String property,
                                double from, double to) implements PropTarget {}

    /**
     * 颜色属性补间目标（TextElement.color）。
     * {@code snap=true} 表示 from/to 含变量占位符，不做中间插值（末帧瞬切）。
     */
    public record ColorTarget(String elementId, String property,
                              String from, String to,
                              boolean snap) implements PropTarget {}

    /**
     * fill 属性补间目标（几何/图标/笔刷元素）。
     * {@code snap=true} 表示含变量，不做插值（末帧瞬切）。
     */
    public record FillTarget(String elementId, String property,
                             Fill from, Fill to,
                             boolean snap) implements PropTarget {}

    /**
     * 活跃补间任务。所有字段 final；targets 是 List.copyOf。{@code baseState} 仅作为「补间起点
     * 快照」<b>只读</b>持有——其 record 外壳不可变，但内层 layers/elements 是可变 ArrayList，
     * 故 tick 路径产帧时 {@link #buildInterpolatedFrame} 必先 {@link #deepCopyState} 拍副本再
     * mutate，绝不原地改 baseState（P3-16）。
     * tick 线程读取；enqueue 线程构造后 put 到 ConcurrentHashMap → tick 线程可见。
     *
     * <p>{@code fps} 是 enqueue 时从 wall 的 {@link ProjectState#effectiveTweenFps()} 读取的
     * per-wall 帧率——已被 config maxFps clamp，决定 renderStatic 节流间隔（1000/fps ms）。</p>
     *
     * <p><b>E1（补间→落盘→续接的时序承诺）</b>：{@code onComplete} 是脚本续接回调——把
     * 「补间末帧落盘完成后才续接脚本后续动作」（scripting-tween.md §2.2 步骤 6）这一顺序承诺
     * 落地。构造时已被 {@link #oneShot(Runnable)} <b>一次性包装</b>（内部 AtomicBoolean CAS），
     * 故 {@link #fireComplete} 在任何终结点（正常末帧落盘后 / tick 异常清理 / 接管）重复调都<b>恰
     * 一次</b>生效。可为 {@code null}（无续接需求，如纯演示拼接或测试）。<b>注意</b>：record
     * {@code equals}/{@code hashCode} 因新增 onComplete（Runnable，identity 语义）改变，但
     * {@code active.remove(wallId, task)} 始终传<b>同一实例</b>（identity 自反相等），CAS 不受影响；
     * 全代码无两个不同 TweenTask 实例相等比较。</p>
     */
    record TweenTask(String wallId, String blockId, List<PropTarget> targets,
                     long startMs, long durationMs, Easing easing,
                     ProjectState baseState, int fps, Runnable onComplete) {
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
     * 测试 / benchmark 装配 seam 入口（全 fake 注入）。
     */
    public TweenScheduler(ApplyManyFn applyFn, TickerControl ticker,
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
     * 无续接回调的注册重载（演示拼接 / 测试用——补间照常跑 + 末帧落盘，但不续接任何脚本）。
     * 等价 {@code enqueue(wallId, blockId, tb, null)}。
     */
    public TraceStep enqueue(String wallId, String blockId, Action.TweenBlock tb) {
        return enqueue(wallId, blockId, tb, null);
    }

    /**
     * 注册一个补间任务（从 TweenBlock 的 body 解析 targets）。
     *
     * <p>调用方（ScriptRunner）在 Runner 线程调；结果 {@link TraceStep} 决定是否挂起：
     * {@code "ok"} → 挂起（等 {@code onComplete} 回调续接，<b>不再</b>由 Runner 自按 durationMs
     * 定时）；否则不挂起，脚本链继续。</p>
     *
     * <p><b>E1 时序承诺</b>：{@code onComplete} 由本引擎在<b>补间末帧落盘完成后</b>（正常完成）/
     * tick 异常清理后 / 被同墙新补间接管后<b>恰一次</b>触发——把 scripting-tween.md §2.2
     * 「补间完→落盘→续接」的顺序落地。回调内部应把脚本续接<b>投递到 Runner SES</b>（绝不在
     * tween 线程跑脚本续接）；本引擎只负责<b>在正确时点</b>调用它一次。{@code onComplete} 仅在
     * 返回 {@code "ok"} 且 {@code durationMs > 0}（即真正挂起）时才会被触发——失败 / 0 时长场景
     * 由调用方（ScriptRunner）当场不挂起继续，不依赖本回调。</p>
     *
     * @param wallId     当前墙
     * @param blockId    补间积木 blockId（用于 trace 定位）
     * @param tb         TweenBlock action（含 durationMs / easing / body）
     * @param onComplete 末帧落盘后的脚本续接回调（可 {@code null}）；本引擎一次性包装后在终结点触发
     */
    public TraceStep enqueue(String wallId, String blockId, Action.TweenBlock tb, Runnable onComplete) {
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
        TweenTask existingForTakeover = active.get(wallId); // 接管快照（接管时从当前插值位作新 from，T8）
        long nowForTakeover = clock.getAsLong();
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
                // P3：支持数值 + color + fill 属性
                if (!isTweenableProperty(property)) {
                    return TraceStep.error(blockId, "补间不支持属性: " + property
                            + "（支持: x/y/w/h/rotation/opacity/color/fill）");
                }
                PropTarget target = buildTarget(baseState, existingForTakeover, nowForTakeover,
                        elementId, property, toStr, blockId);
                if (target == null) {
                    return TraceStep.error(blockId, "补间 target 构建失败: property=" + property
                            + " elementId=" + elementId);
                }
                targets.add(target);
            }
        }
        if (targets.isEmpty()) {
            return TraceStep.error(blockId, "补间未解析到有效 targets");
        }

        // per-wall 帧率：从 baseState.effectiveTweenFps() 读取，并受 maxFps 硬上限 clamp
        int wallFps = Math.min(maxFps, baseState.effectiveTweenFps());

        // E1：一次性包装续接回调，挂到 task 上——在终结点（末帧落盘后 / 异常 / 接管）恰一次触发。
        TweenTask task = new TweenTask(wallId, blockId, targets,
                clock.getAsLong(), tb.durationMs(),
                tb.easing() != null ? tb.easing() : Easing.LINEAR,
                baseState, wallFps, oneShot(onComplete));

        // 同 wall 已有旧补间 → 接管：清 diff + 清旧 lastRenderAt（新任务首帧立即渲），
        // 且替换为新 task 后<b>触发旧补间的续接回调</b>（旧脚本不永久挂起，T8 接管语义）。
        TweenTask old = active.put(wallId, task);
        if (old != null) {
            ticker.clearStaticDiff(wallId);
            lastRenderAt.remove(wallId);
            // 接管发生在 Runner 线程；fireComplete 内只 schedule 投递到 Runner SES（见调用方），
            // 不在此跑脚本续接。一次性保证由 old 自身的 oneShot 包装兜底。
            fireComplete(old);
        }
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
                // E1：异常也要触发续接回调，让脚本以「补间失败」状态续接 —— 否则脚本永久挂起。
                // 触发在 tween 线程；回调内只把续接 schedule 到 Runner SES（不在 tween 线程跑脚本）。
                fireComplete(task);
            }
        }
    }

    private void tickOne(String wallId, TweenTask task, long now) {
        long elapsed = now - task.startMs();
        double local = task.durationMs() <= 0 ? 1.0
                : Math.min(1.0, Math.max(0.0, (double) elapsed / task.durationMs()));
        double eased = EasingSolver.ease(task.easing(), local);

        // P3 共存分流：每 tick 现查（不缓存——补间期间用户可能开/关时间轴）
        boolean animating = ticker.isWallAnimating(wallId);

        if (local >= 1.0) {
            // 末帧：两种墙都落 DB（目标值，按 elementId 分组合并 patch） + 清 diff + 注销
            // 静态墙额外渲末帧（防止末帧前的节流导致画面停在中间值）
            if (!animating) {
                ProjectState finalFrame = buildInterpolatedFrame(task, eased);
                if (finalFrame != null) {
                    ticker.renderStatic(wallId, finalFrame);
                }
            }
            Map<String, Map<String, String>> byElement = new java.util.LinkedHashMap<>();
            for (PropTarget pt : task.targets()) {
                byElement.computeIfAbsent(pt.elementId(), k -> new java.util.LinkedHashMap<>())
                         .put(pt.property(), formatFinalValue(pt));
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
            if (!animating) ticker.clearStaticDiff(wallId);
            // E1（顺序承诺）：续接<b>必在末帧落盘（上面 applyFn.apply）之后</b>触发——这样脚本
            // 后续动作（读元素 x/y、waitUntil/if 读几何）读到的是补间<b>目标值</b>而非起始值。
            // 触发在 tween 线程；回调内只把续接 schedule 到 Runner SES，不在 tween 线程跑脚本。
            fireComplete(task);
            return;
        }

        // 中间帧：per-wall fps 节流（进度计算不受节流影响）
        long renderIntervalMs = Math.max(1L, Math.round(1000.0 / task.fps()));
        Long last = lastRenderAt.get(wallId);
        if (last == null || now - last >= renderIntervalMs) {
            if (animating) {
                // 有时间轴：每渲帧 applyMany 落 DB，让时间轴下帧 reload 叠加（§5 共存方案）
                Map<String, Map<String, String>> byElement = new java.util.LinkedHashMap<>();
                for (PropTarget pt : task.targets()) {
                    byElement.computeIfAbsent(pt.elementId(), k -> new java.util.LinkedHashMap<>())
                             .put(pt.property(), interpolatedValueStr(pt, eased));
                }
                for (Map.Entry<String, Map<String, String>> el : byElement.entrySet()) {
                    try {
                        applyFn.apply(wallId, task.blockId(), el.getKey(), el.getValue());
                    } catch (Exception e) {
                        log.log(Level.WARNING, "[补间] 中间帧 applyMany 失败 wallId=" + wallId
                                + " elementId=" + el.getKey() + ": " + e.getMessage(), e);
                    }
                }
            } else {
                // 静态墙：渲临时态不落 DB（路径 Z）
                ProjectState frame = buildInterpolatedFrame(task, eased);
                if (frame != null) {
                    ticker.renderStatic(wallId, frame);
                }
            }
            lastRenderAt.put(wallId, now);
        }
        // else: 节流跳过本次渲染，进度照旧推进（下次 tick 继续算 eased）
    }

    // ---------- 关停 ----------

    /**
     * 幂等关停（cleanupResources：scriptRunner.shutdown 之后、animationTicker.shutdown 之前）。
     *
     * <p><b>E1</b>：关停<b>不触发</b>活跃补间的续接回调——关服路径下 ScriptRunner 已先 shutdown，
     * 其 SES 拒新任务、session 已死，续接无意义（与 ScriptRunner wait/playTimelineAwait 的
     * 「shutdown 竞态丢弃不回调」契约一致）。仅清 staticDiff + 清表 + 关 SES。</p>
     */
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

    /**
     * E1：把脚本续接回调包装成<b>一次性</b> Runnable（AtomicBoolean CAS 守护）。{@code null} 入参
     * 返回 {@code null}（无续接需求）。包装后即便 {@link #fireComplete} 在多个终结点（末帧 / 异常 /
     * 接管）被重复调，真正 body 也只跑一次——满足「续接恰一次」契约。
     */
    private static Runnable oneShot(Runnable body) {
        if (body == null) return null;
        AtomicBoolean fired = new AtomicBoolean(false);
        return () -> {
            if (fired.compareAndSet(false, true)) {
                body.run();
            }
        };
    }

    /**
     * E1：在补间终结点触发 task 的续接回调（一次性，由 {@link #oneShot} 包装兜底幂等）。
     *
     * <p><b>线程语义</b>：本方法可能在 <i>tween 线程</i>（tickOne 正常末帧 / tick 异常清理）或
     * <i>Runner 线程</i>（enqueue 接管）被调；它<b>不</b>跑脚本逻辑，只调用回调 body——而 body
     * （来自 ScriptRunner）内部只把脚本续接 {@code schedule} 投递到 Runner SES（见 ScriptRunner
     * 的 TweenBlock 分支），故脚本续接<b>始终</b>在 Runner 线程跑，绝不在 tween 线程。回调 body
     * 自身抛异常被吞 + WARNING，不杀 tween 线程的 tick 循环。</p>
     */
    private void fireComplete(TweenTask task) {
        if (task == null) return;
        Runnable cb = task.onComplete();
        if (cb == null) return;
        try {
            cb.run();
        } catch (Throwable t) {
            log.log(Level.WARNING, "[补间] 续接回调抛异常（已吞）wallId=" + task.wallId()
                    + " block=" + task.blockId() + ": " + t.getMessage(), t);
        }
    }

    /** P3：是否可补间属性（数值 + color + fill）。 */
    private static boolean isTweenableProperty(String property) {
        return switch (property) {
            case "x", "y", "w", "h", "rotation", "opacity", "color", "fill" -> true;
            default -> false;
        };
    }

    /**
     * 构建单个 PropTarget（按属性类型分流）。返回 null 表示构建失败（enqueue 返 error）。
     */
    private static PropTarget buildTarget(ProjectState baseState, TweenTask existing,
                                          long nowForTakeover,
                                          String elementId, String property,
                                          String toStr, String blockId) {
        if (isNumericProperty(property)) {
            double from = readNumericValue(baseState, elementId, property);
            double to = StrictNumber.parse(toStr);
            if (!Double.isFinite(to)) return null;
            // 接管：从旧补间当前插值位置作新 from（T8 平滑接管）
            if (existing != null) {
                double takeover = interpolatedNumeric(existing, elementId, property, nowForTakeover);
                if (Double.isFinite(takeover)) from = takeover;
            }
            return new NumericTarget(elementId, property, from, to);
        }
        if ("color".equals(property)) {
            // toStr 是 hex 字符串（setColor 友好积木产出 color 键，TextElement.color）
            boolean snapTo = containsVar(toStr);
            String from = readColorValue(baseState, elementId);
            boolean snapFrom = from != null && containsVar(from);
            boolean snap = snapTo || snapFrom;
            if (from == null) from = "#FFFFFF"; // 元素不存在或非 text，fallback
            return new ColorTarget(elementId, property, from, toStr, snap);
        }
        if ("fill".equals(property)) {
            // toStr 是 #RRGGBB 字符串（几何元素如 Rect/Circle/Shape/Path/Brush/Icon 的 fill 属性）
            boolean snap = containsVar(toStr);
            Fill fromFill = readFillValue(baseState, elementId);
            if (fromFill == null) fromFill = new SolidFill(toStr); // 元素不存在 fallback
            Fill toFill = parseFillSafe(toStr);
            if (toFill == null) return null; // 非法 fill 字符串
            return new FillTarget(elementId, property, fromFill, toFill, snap);
        }
        return null;
    }

    /** 是否数值属性（x/y/w/h/rotation/opacity）。 */
    private static boolean isNumericProperty(String property) {
        return switch (property) {
            case "x", "y", "w", "h", "rotation", "opacity" -> true;
            default -> false;
        };
    }

    /** 检测字符串是否含变量占位符 {@code ${var:}}。 */
    private static boolean containsVar(String s) {
        return s != null && s.contains("${var:");
    }

    /**
     * 从 base state 读元素的数值属性值（仅数值属性）。
     * 元素/属性不存在时 → fallback 0.0。
     */
    private static double readNumericValue(ProjectState state, String elementId, String property) {
        for (var layer : state.layers()) {
            for (Element el : layer.elements()) {
                if (!el.id().equals(elementId)) continue;
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
        }
        return 0.0;
    }

    /**
     * 从 base state 读 TextElement 的 color（String）。非 TextElement 或不存在 → null。
     */
    private static String readColorValue(ProjectState state, String elementId) {
        for (var layer : state.layers()) {
            for (Element el : layer.elements()) {
                if (!el.id().equals(elementId)) continue;
                if (el instanceof TextElement t) return t.color();
                return null; // 非 text 元素无 color
            }
        }
        return null;
    }

    /**
     * 从 base state 读元素的 fill（Fill）。不支持 fill 的元素类型（TextElement/ImageElement）→ null。
     */
    private static Fill readFillValue(ProjectState state, String elementId) {
        for (var layer : state.layers()) {
            for (Element el : layer.elements()) {
                if (!el.id().equals(elementId)) continue;
                return switch (el) {
                    case RectElement r -> r.fill();
                    case IconElement ic -> ic.fill();
                    case PathElement p -> p.fill();
                    case CircleElement c -> c.fill();
                    case ShapeElement sh -> sh.fill();
                    case BrushStrokeElement br -> br.fill();
                    default -> null;
                };
            }
        }
        return null;
    }

    /** 安全解析 fill 字符串（#RRGGBB → SolidFill）；失败返 null。 */
    private static Fill parseFillSafe(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return ElementValidator.parseFillNullable(s);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 同 wall 接管时：从旧 TweenTask 中插值当前数值，作新 from（T8 平滑接管）。
     * 找不到 NumericTarget 时返 NaN（调用方 fallback 读 base state）。
     */
    private static double interpolatedNumeric(TweenTask task, String elementId,
                                              String property, long now) {
        long elapsed = now - task.startMs();
        double local = task.durationMs() <= 0 ? 1.0
                : Math.min(1.0, Math.max(0.0, (double) elapsed / task.durationMs()));
        double eased = EasingSolver.ease(task.easing(), local);
        for (PropTarget pt : task.targets()) {
            if (pt instanceof NumericTarget n
                    && n.elementId().equals(elementId) && n.property().equals(property)) {
                return n.from() + (n.to() - n.from()) * eased;
            }
        }
        return Double.NaN;
    }

    /**
     * 构造插值帧（纯内存，<b>每帧独立深拷贝副本</b>；不落 DB）。P3 三态分流。
     *
     * <p><b>P3-16 跨线程撕裂读修复</b>：{@link EditSession} 构造器按<b>引用</b>持有 state，
     * {@code updateElement} 通过 {@code elements().set(idx, ...)} <b>原地 mutate</b> 这个
     * （非线程安全的）{@code ArrayList}，且 {@code es.state()} 返回的就是传入的同一对象。
     * 若直接 {@code new EditSession(task.baseState())}，本方法产出的 frame 与 baseState 共享
     * 内层 Layer 的 element 列表；该 frame 经 {@code renderStatic} <b>异步在 Ticker 线程</b>读，
     * 而 tween 线程下一 tick 又在同一列表上 set/iterate → 对非线程安全 ArrayList 的并发改读 =
     * 撕裂读。故构造 EditSession 前先 {@link #deepCopyState} 拍出独立副本：layers + 每个
     * Layer 的 elements 列表都是新对象（Element / Timeline / Canvas record 不可变可按引用共享），
     * 让返回的 frame 真正 immutable（不被后续 tick 的 mutate 污染），跨线程安全。</p>
     *
     * <p>临时 {@link EditSession} + {@code updateElement}——EditSession 纯 record 重建，
     * <b>不</b>发包、<b>不</b>落 DB、<b>不</b>碰 Ticker；产物即弃，仅取 {@code es.state()}。</p>
     *
     * @param eased 已经过缓动函数映射的插值进度 [0,1]
     */
    private static ProjectState buildInterpolatedFrame(TweenTask task, double eased) {
        // 按 elementId 分组，把同一元素的多属性合并到一次 updateElement 调用
        Map<String, Map<String, Object>> patches = new java.util.LinkedHashMap<>();
        for (PropTarget pt : task.targets()) {
            Object v = buildPatchValue(pt, eased);
            if (v == null) continue; // snap 目标（末帧瞬切）或插值失败，中间帧跳过
            patches.computeIfAbsent(pt.elementId(), k -> new java.util.LinkedHashMap<>())
                   .put(pt.property(), v);
        }
        // patches 为空（全 snap 目标）也返回独立副本——保持「frame 是 baseState 的隔离拷贝」
        // 这一不变式（绝不把共享 baseState 直接漏给异步读的 renderStatic）
        ProjectState frame = deepCopyState(task.baseState());
        if (patches.isEmpty()) return frame;

        EditSession es = new EditSession(frame);
        for (Map.Entry<String, Map<String, Object>> entry : patches.entrySet()) {
            EditSession.OpResult r = es.updateElement(entry.getKey(), entry.getValue());
            if (r instanceof EditSession.OpResult.Error) {
                // 校验拒绝（如 color 给非 text 元素）：退回独立副本（已对其它 entry 部分 mutate，
                // 但仍是与 baseState 隔离的拷贝，不污染共享 baseState），不崩 tick
                return frame;
            }
        }
        return es.state();
    }

    /**
     * 深拷贝 {@link ProjectState}（P3-16）：照 {@code ProjectState.restore} 的成熟范式重建 ——
     * 每个 {@link Layer} 用 {@code new ArrayList<>(l.elements())} 重建 element 列表，保证
     * 返回 state 的 layers 列表 <b>与</b> 每个 Layer 的 elements 列表都是独立可变副本（后续
     * {@code EditSession.updateElement} 的原地 set 只动副本，不触碰 baseState）。
     *
     * <p>{@link Element} / {@link ProjectState.Canvas} / {@link moe.hikari.canvas.state.Timeline}
     * 均为不可变 record，按引用共享即线程安全（与 {@code ProjectState.restore} 对 timelines 的处理一致）。</p>
     */
    private static ProjectState deepCopyState(ProjectState src) {
        List<Layer> copiedLayers = new ArrayList<>(src.layers().size());
        for (Layer l : src.layers()) {
            copiedLayers.add(new Layer(
                    l.id(), l.name(), l.visible(), l.locked(),
                    l.opacity(), l.blendMode(), l.colorTag(),
                    new ArrayList<>(l.elements())));
        }
        // Jackson 反序列化构造器（v1Elements=null，走 v2Layers 分支）。timelines 按引用共享
        // （Timeline record 不可变，深冻结 tracks）；canvas / history / activeIds 同样不可变或值语义。
        return new ProjectState(
                src.version(),
                src.canvas(),
                null,
                copiedLayers,
                src.activeLayerId(),
                src.history(),
                new ArrayList<>(src.timelines()),
                src.activeTimelineId(),
                src.tweenFps());
    }

    /**
     * 按 PropTarget 类型计算当前插值 patch 值（Object 格式，传给 EditSession.updateElement）。
     * snap=true 的 ColorTarget/FillTarget → null（中间帧不渲，末帧瞬切）。
     */
    private static Object buildPatchValue(PropTarget pt, double eased) {
        return switch (pt) {
            case NumericTarget n -> {
                double value = n.from() + (n.to() - n.from()) * eased;
                yield switch (n.property()) {
                    case "x", "y", "w", "h", "rotation" -> StrictNumber.clampInt(Math.round(value));
                    case "opacity" -> (float) Math.min(1.0, Math.max(0.0, value));
                    default -> StrictNumber.clampInt(Math.round(value));
                };
            }
            case ColorTarget c -> {
                if (c.snap()) yield null; // 含变量，中间帧不渲
                yield ColorLerp.lerpHex(c.from(), c.to(), eased);
            }
            case FillTarget f -> {
                if (f.snap()) yield null; // 含变量，中间帧不渲
                Fill interpolated = ColorLerp.lerpFill(f.from(), f.to(), eased);
                if (interpolated == null) yield null;
                // Fill → Map（parseFillNullable 接受 Map<?,?> 形态）
                yield fillToMap(interpolated);
            }
        };
    }

    /**
     * 按 PropTarget 类型计算插值中间帧的字符串值（用于 animating 墙的 applyFn.apply）。
     * snap=true 的目标返 toStr（瞬切目标值）。
     */
    private static String interpolatedValueStr(PropTarget pt, double eased) {
        return switch (pt) {
            case NumericTarget n -> {
                double value = n.from() + (n.to() - n.from()) * eased;
                yield switch (n.property()) {
                    case "x", "y", "w", "h", "rotation" ->
                            String.valueOf(StrictNumber.clampInt(Math.round(value)));
                    case "opacity" ->
                            String.valueOf(Math.min(1.0, Math.max(0.0, value)));
                    default ->
                            String.valueOf(StrictNumber.clampInt(Math.round(value)));
                };
            }
            case ColorTarget c -> c.snap() ? c.to() : ColorLerp.lerpHex(c.from(), c.to(), eased);
            case FillTarget f -> {
                if (f.snap()) yield fillToString(f.to());
                Fill interpolated = ColorLerp.lerpFill(f.from(), f.to(), eased);
                yield fillToString(interpolated != null ? interpolated : f.to());
            }
        };
    }

    /**
     * 末帧落盘字符串值（ElementPropertyApplier.applyFn.apply 接受 Map<String,String> rawPatch）。
     */
    private static String formatFinalValue(PropTarget pt) {
        return switch (pt) {
            case NumericTarget n -> switch (n.property()) {
                case "x", "y", "w", "h", "rotation" ->
                        String.valueOf(StrictNumber.clampInt(Math.round(n.to())));
                case "opacity" -> String.valueOf(Math.min(1.0, Math.max(0.0, n.to())));
                default -> String.valueOf(StrictNumber.clampInt(Math.round(n.to())));
            };
            case ColorTarget c -> c.to();
            case FillTarget f -> fillToString(f.to());
        };
    }

    /**
     * Fill → JSON object 形态 Map（parseFillNullable 接受的 Map<?,?> 格式）。
     * 序列化失败 → null（调用方 skip）。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<String, Object> fillToMap(Fill fill) {
        if (fill == null) return null;
        try {
            return (Map<String, Object>) ElementValidator.FILL_MAPPER.convertValue(fill, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Fill → 字符串（applyFn.apply rawPatch 格式）。
     * SolidFill → color hex；其他 → JSON 字符串（ElementPropertyApplier.buildPatch 接受
     * HEX_FILL 正则；渐变 fill 在 applyMany 路径下走 parseFillNullable(Map) 兼容）。
     * 降级：JSON 序列化失败 → "#FFFFFF"。
     */
    private static String fillToString(Fill fill) {
        if (fill == null) return "#FFFFFF";
        if (fill instanceof SolidFill sf) {
            return sf.color() != null ? sf.color() : "#FFFFFF";
        }
        // LinearGradient / RadialGradient：applyMany → buildPatch("fill", jsonStr) 目前只接受 HEX
        // 须直接走 applyMany 的 merged patch（Map<String,Object>）路径。
        // applyFn.apply 的 rawPatch 是 Map<String,String>，fill 值只能是字符串。
        // 渐变 fill 退化：取首 stop 颜色作 SolidFill（保证落盘）
        return extractFirstStopColor(fill);
    }

    /** 从渐变 fill 提取首个 stop 颜色（fallback SolidFill 字符串）。 */
    private static String extractFirstStopColor(Fill fill) {
        if (fill instanceof moe.hikari.canvas.state.LinearGradient lg
                && lg.stops() != null && !lg.stops().isEmpty()
                && lg.stops().get(0) != null) {
            String c = lg.stops().get(0).color();
            return c != null ? c : "#FFFFFF";
        }
        if (fill instanceof moe.hikari.canvas.state.RadialGradient rg
                && rg.stops() != null && !rg.stops().isEmpty()
                && rg.stops().get(0) != null) {
            String c = rg.stops().get(0).color();
            return c != null ? c : "#FFFFFF";
        }
        return "#FFFFFF";
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

    /** 强制触发一次 tick（测试 / benchmark 用，绕开 scheduleAtFixedRate）。 */
    public void tickForTest() {
        tick();
    }
}
