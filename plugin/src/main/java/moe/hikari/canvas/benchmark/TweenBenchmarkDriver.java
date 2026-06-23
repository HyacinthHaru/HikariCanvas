package moe.hikari.canvas.benchmark;

import moe.hikari.canvas.script.engine.TweenScheduler;
import moe.hikari.canvas.script.engine.TraceStep;
import moe.hikari.canvas.state.BlendMode;
import moe.hikari.canvas.state.Easing;
import moe.hikari.canvas.state.Layer;
import moe.hikari.canvas.state.ProjectState;
import moe.hikari.canvas.state.RectElement;
import moe.hikari.canvas.state.SolidFill;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 0.7.x 补间成本 benchmark 驱动（headless 纯内存，无 Bukkit / DB / PacketEvents）。
 *
 * <p><b>测量对象</b>：{@link TweenScheduler#buildInterpolatedFrame}（{@code deepCopyState}
 * + 插值 + EditSession 属性写回）—— N 面活跃墙各持一个补间任务时，tick 的累计 CPU 成本。</p>
 *
 * <p><b>装配方式</b>：</p>
 * <ul>
 *   <li>fake {@code TickerControl}：渲染调用记录 + 丢弃（不碰 MapPool）</li>
 *   <li>fake {@code WallLoader}：返回预先构造好的内存 ProjectState（含 elemCount 个 RectElement）</li>
 *   <li>fake {@code ApplyManyFn}：末帧落盘丢弃（不碰 DB）</li>
 *   <li>fake clock：静止时钟（0ms elapsed = 中间帧，始终取 eased=0 不触发末帧落盘逻辑）</li>
 * </ul>
 *
 * <p><b>测量点</b>：</p>
 * <ol>
 *   <li>{@code enqueue}（预热阶段）：N 面墙各注册一个补间任务，建立 active 表。</li>
 *   <li>{@code tickForTest}（测量阶段）：反复调用 tick，每次 tick 对所有 N 面活跃墙执行
 *       {@code buildInterpolatedFrame}。计时覆盖"N 面墙的完整 tick"，得出分布统计。</li>
 * </ol>
 *
 * <p>符合 0.5.0「工具不是保姆」哲学：只给数字，不做成本估算 / 不替服主决策。</p>
 */
public final class TweenBenchmarkDriver {

    /** 每个 wall 的补间目标元素数（模拟"一面墙有 4 个被补间的元素"的常见场景）。 */
    private static final int TARGETS_PER_WALL = 4;

    /** deepCopyState 时每层的元素数（即 ProjectState.layers[0].elements 大小）。 */
    private static final int ELEMENTS_PER_WALL = 20;

    private static final Logger LOG = Logger.getLogger("hikari-bench-tween");

    /**
     * 运行补间 benchmark，返回结构化结果行列表。
     *
     * @param wallCounts    待测的 N 值列表（如 {@code [1, 4, 16, 64]}）；每个 N 独立建 TweenScheduler
     * @param warmupIters   预热 tick 轮数（触发 JIT 编译，结果丢弃）
     * @param measuredIters 测量 tick 轮数
     * @return 每个墙数对应一行 {@link BenchRow}，文案由调用端按 locale 组装
     */
    public static List<BenchRow> measure(List<Integer> wallCounts, int warmupIters, int measuredIters) {
        List<BenchRow> rows = new ArrayList<>(wallCounts.size());
        for (int n : wallCounts) {
            Percentiles p = measureN(n, warmupIters, measuredIters);
            rows.add(new BenchRow(n, p));
        }
        return rows;
    }

    // ---------- 内部逻辑 ----------

    private static Percentiles measureN(int n, int warmupIters, int measuredIters) {
        // 构建 N 面 wall 的 ProjectState（每面 ELEMENTS_PER_WALL 个 RectElement）
        List<ProjectState> states = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            states.add(buildWallState(ELEMENTS_PER_WALL));
        }

        // fake TickerControl：丢弃所有渲染调用（不碰 MapPool / DB）
        FakeTickerControl ticker = new FakeTickerControl();
        // fake clock：固定 0ms（恒中间帧，不触发末帧落盘 + applyFn）
        long[] clockMs = {0L};

        // 构造 TweenScheduler（seam 入口）
        TweenScheduler scheduler = new TweenScheduler(
                (wallId, blockId, elementId, rawPatch) -> TraceStep.ok(blockId, "action", "noop"),
                ticker,
                wallId -> {
                    // wallLoader：按 wallId 后缀 index 返回对应 state
                    int idx = Integer.parseInt(wallId.substring("bench-wall-".length()));
                    return states.get(idx);
                },
                () -> clockMs[0],
                Math.max(n, 1), 60, LOG);

        // 注册 N 面墙的补间任务（enqueue，durationMs=10000ms 让时钟 0 时始终是中间帧）
        for (int i = 0; i < n; i++) {
            String wallId = "bench-wall-" + i;
            // buildTweenBlock：对 TARGETS_PER_WALL 个元素各补间 x 属性
            List<moe.hikari.canvas.script.Action> body = new ArrayList<>();
            for (int t = 0; t < TARGETS_PER_WALL; t++) {
                body.add(new moe.hikari.canvas.script.Action.SetElementProperties(
                        "elem-" + t, Map.of("x", "100", "y", "50", "opacity", "0.8"), null));
            }
            moe.hikari.canvas.script.Action.TweenBlock tb =
                    new moe.hikari.canvas.script.Action.TweenBlock(10_000L, Easing.LINEAR, body);
            scheduler.enqueue(wallId, "actions/0", tb);
        }

        // JIT 防优化 blackhole（防 tick 结果被消除）
        long blackhole = 0L;

        // 预热：触发 C2 编译
        for (int w = 0; w < warmupIters; w++) {
            scheduler.tickForTest();
            blackhole += ticker.renderCount;
        }
        SceneTimer.sink += blackhole; // 接 SceneTimer 的 sink，防消除

        // 测量：记录每轮 tick 的耗时（ns）
        double[] samplesMs = new double[measuredIters];
        for (int i = 0; i < measuredIters; i++) {
            long t0 = System.nanoTime();
            scheduler.tickForTest();
            long t1 = System.nanoTime();
            samplesMs[i] = (t1 - t0) / 1_000_000.0;
            blackhole += ticker.renderCount;
        }
        SceneTimer.sink += blackhole;

        // 关停（避免 SES 线程泄漏）
        scheduler.shutdown();

        return Percentiles.of(samplesMs);
    }

    /** 构造含 elemCount 个 RectElement 的 ProjectState（1x1 tile）。 */
    private static ProjectState buildWallState(int elemCount) {
        List<moe.hikari.canvas.state.Element> elements = new ArrayList<>(elemCount);
        for (int i = 0; i < elemCount; i++) {
            elements.add(new RectElement(
                    "elem-" + i,
                    i * 5, i * 5, 40, 20,
                    0, false, true,
                    new SolidFill("#FF0000"), null,
                    null, null, null));
        }
        Layer layer = new Layer("l-default", "Default Layer", true, false,
                1.0f, BlendMode.NORMAL, elements);
        List<Layer> layers = new ArrayList<>();
        layers.add(layer);
        return new ProjectState(0,
                new ProjectState.Canvas(1, 1, new SolidFill("#FFFFFF")),
                null, layers, "l-default",
                new ProjectState.History(0, 0),
                new ArrayList<>(), null, null);
    }

    // ---------- fake TickerControl ----------

    /**
     * 所有 TickerControl 方法全为 no-op / 默认返回；只记录 renderStatic 调用次数（用作 blackhole）。
     */
    private static final class FakeTickerControl implements moe.hikari.canvas.script.engine.TickerControl {
        volatile long renderCount = 0L;

        @Override
        public moe.hikari.canvas.render.AnimationTicker.Result play(String wallId, String timelineId) {
            return moe.hikari.canvas.render.AnimationTicker.Result.WALL_NOT_FOUND;
        }

        @Override
        public void pause(String wallId) {}

        @Override
        public moe.hikari.canvas.render.AnimationTicker.Result seek(String wallId, String timelineId, long atMs) {
            return moe.hikari.canvas.render.AnimationTicker.Result.WALL_NOT_FOUND;
        }

        @Override
        public boolean isWallAnimating(String wallId) {
            return false; // 始终"静态墙"，走 renderStatic 路径（不落 DB）
        }

        @Override
        public void invalidate(String wallId) {}

        @Override
        public void refreshAutoPlay(String wallId) {}

        @Override
        public void renderStatic(String wallId, moe.hikari.canvas.state.ProjectState frame) {
            renderCount++; // blackhole：让 frame 被"消费"，防 JIT 消除 buildInterpolatedFrame
        }

        @Override
        public void clearStaticDiff(String wallId) {}
    }
}
