package ac.haru.hikaricanvas.benchmark;

import ac.haru.hikaricanvas.benchmark.BenchmarkReport.BenchRow;
import ac.haru.hikaricanvas.benchmark.BenchmarkReport.Percentiles;
import ac.haru.hikaricanvas.render.AnimationTicker;
import ac.haru.hikaricanvas.script.Action;
import ac.haru.hikaricanvas.script.engine.TickerControl;
import ac.haru.hikaricanvas.script.engine.TraceStep;
import ac.haru.hikaricanvas.script.engine.TweenScheduler;
import ac.haru.hikaricanvas.state.Easing;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.state.RectElement;
import ac.haru.hikaricanvas.state.SolidFill;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 补间动画帧推进成本 benchmark 驱动（纯内存无头模式，零 Bukkit / DB / PacketEvents 依赖）。
 *
 * <p>测量 {@code n} 面活跃墙各持一个补间任务时，单次 {@link TweenScheduler#tickForTest()}
 * （状态深拷贝 + 属性插值 + {@link TickerControl#renderStatic} 投递）的累计 CPU 耗时
 * （固定静止时钟以锁定中间帧插值路径，详见 {@code docs/benchmark.md §7.1}）。</p>
 */
public final class TweenBenchmarkDriver {

    private static final int TARGETS_PER_WALL = 4;
    private static final int ELEMENTS_PER_WALL = 20;
    private static final Logger LOG = Logger.getLogger("hikari-bench-tween");

    private TweenBenchmarkDriver() {
    }

    /**
     * 运行补间帧率 benchmark，按 {@code wallCounts} 顺序返回各活跃墙数档位的耗时分位数。
     *
     * @param wallCounts    待测活跃墙数列表（如 {@code [1, 4, 16, 64]}）
     * @param warmupIters   预热 tick 轮数
     * @param measuredIters 正式测量 tick 轮数
     * @return 每个活跃墙数对应一行 {@link BenchRow}
     */
    public static List<BenchRow> measure(List<Integer> wallCounts, int warmupIters, int measuredIters) {
        List<BenchRow> rows = new ArrayList<>(wallCounts.size());
        for (int n : wallCounts) {
            rows.add(new BenchRow(n, measureN(n, warmupIters, measuredIters)));
        }
        return rows;
    }

    private static Percentiles measureN(int n, int warmupIters, int measuredIters) {
        List<ProjectState> states = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            states.add(buildWallState(ELEMENTS_PER_WALL));
        }

        FakeTickerControl ticker = new FakeTickerControl();
        long[] clockMs = {0L};

        TweenScheduler scheduler = new TweenScheduler(
                (wallId, blockId, elementId, rawPatch) -> TraceStep.ok(blockId, "action", "noop"),
                ticker,
                wallId -> {
                    int idx = Integer.parseInt(wallId.substring("bench-wall-".length()));
                    return states.get(idx);
                },
                () -> clockMs[0],
                Math.max(n, 1), 60, LOG);

        for (int i = 0; i < n; i++) {
            String wallId = "bench-wall-" + i;
            List<Action> body = new ArrayList<>(TARGETS_PER_WALL);
            for (int t = 0; t < TARGETS_PER_WALL; t++) {
                body.add(new Action.SetElementProperties(
                        "elem-" + t, Map.of("x", "100", "y", "50", "opacity", "0.8"), null));
            }
            scheduler.enqueue(wallId, "actions/0",
                    new Action.TweenBlock(10_000L, Easing.LINEAR, body));
        }

        long blackhole = 0L;
        for (int w = 0; w < warmupIters; w++) {
            scheduler.tickForTest();
            blackhole += ticker.renderCount;
        }
        BenchmarkRunner.sink += blackhole;

        double[] samplesMs = new double[measuredIters];
        for (int i = 0; i < measuredIters; i++) {
            long t0 = System.nanoTime();
            scheduler.tickForTest();
            long t1 = System.nanoTime();
            samplesMs[i] = (t1 - t0) / 1_000_000.0;
            blackhole += ticker.renderCount;
        }
        BenchmarkRunner.sink += blackhole;

        scheduler.shutdown();
        return Percentiles.of(samplesMs);
    }

    private static ProjectState buildWallState(int elemCount) {
        ProjectState state = new ProjectState(1, 1);
        for (int i = 0; i < elemCount; i++) {
            state.addElement(new RectElement(
                    "elem-" + i,
                    i * 5, i * 5, 40, 20,
                    0, false, true,
                    new SolidFill("#FF0000"), null,
                    null, null, null));
        }
        return state;
    }

    private static final class FakeTickerControl implements TickerControl {
        volatile long renderCount = 0L;

        @Override
        public AnimationTicker.Result play(String wallId, String timelineId) {
            return AnimationTicker.Result.WALL_NOT_FOUND;
        }

        @Override
        public void pause(String wallId) {}

        @Override
        public AnimationTicker.Result seek(String wallId, String timelineId, long atMs) {
            return AnimationTicker.Result.WALL_NOT_FOUND;
        }

        @Override
        public boolean isWallAnimating(String wallId) {
            return false;
        }

        @Override
        public void invalidate(String wallId) {}

        @Override
        public void refreshAutoPlay(String wallId) {}

        @Override
        public void renderStatic(String wallId, ProjectState frame) {
            renderCount++;
        }

        @Override
        public void clearStaticDiff(String wallId) {}
    }
}
