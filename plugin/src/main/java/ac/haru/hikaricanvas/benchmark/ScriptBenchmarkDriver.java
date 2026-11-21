package ac.haru.hikaricanvas.benchmark;

import ac.haru.hikaricanvas.HikariCanvasConfig;
import ac.haru.hikaricanvas.benchmark.BenchmarkReport.BenchRow;
import ac.haru.hikaricanvas.benchmark.BenchmarkReport.Percentiles;
import ac.haru.hikaricanvas.script.Action;
import ac.haru.hikaricanvas.script.ScriptRule;
import ac.haru.hikaricanvas.script.Trigger;
import ac.haru.hikaricanvas.script.engine.ActionSink;
import ac.haru.hikaricanvas.script.engine.ConditionEvaluator;
import ac.haru.hikaricanvas.script.engine.ScriptBudget;
import ac.haru.hikaricanvas.script.engine.ScriptRunner;
import ac.haru.hikaricanvas.script.engine.TraceStep;
import ac.haru.hikaricanvas.script.engine.TriggerContext;
import ac.haru.hikaricanvas.variable.VariableStore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 脚本动作链开销 benchmark 驱动（纯内存无头模式，零 Bukkit / DB / PacketEvents 依赖）。
 *
 * <p>测量 {@link ScriptRunner} 执行含 {@code actionCount} 个 {@link Action.SetVariable} 动作的规则
 * 单轮端到端耗时（从 {@code submit} 投递到 trace 完成回调，含帧栈建立、O(1) 假 {@link ActionSink} 分发与
 * 轨迹收集；详见 {@code docs/benchmark.md §7.2}）。</p>
 */
public final class ScriptBenchmarkDriver {

    private static final Logger LOG = Logger.getLogger("hikari-bench-script");

    private ScriptBenchmarkDriver() {
    }

    /**
     * 运行脚本动作链 benchmark，按 {@code actionCounts} 顺序返回各档位的耗时分位数。
     *
     * @param actionCounts  待测动作数列表（如 {@code [1, 10, 25, 50]}）
     * @param warmupIters   预热轮数
     * @param measuredIters 正式测量轮数
     * @return 每个动作数对应一行 {@link BenchRow}
     */
    public static List<BenchRow> measure(List<Integer> actionCounts, int warmupIters, int measuredIters) {
        List<BenchRow> rows = new ArrayList<>(actionCounts.size());
        for (int n : actionCounts) {
            rows.add(new BenchRow(n, measureActionCount(n, warmupIters, measuredIters)));
        }
        return rows;
    }

    private static Percentiles measureActionCount(int actionCount, int warmupIters, int measuredIters) {
        HikariCanvasConfig.ScriptsConfig cfg = new HikariCanvasConfig.ScriptsConfig(
                16, actionCount + 1, 1000, 8);
        ScriptBudget budget = new ScriptBudget(cfg);
        ConditionEvaluator conditions = new ConditionEvaluator(LOG, (VariableStore) null);
        FakeActionSink sink = new FakeActionSink();
        ScriptRunner runner = new ScriptRunner(conditions, sink, budget, null, LOG);

        ScriptRule rule = buildRule("bench-wall", actionCount);
        TriggerContext ctx = new TriggerContext(TriggerContext.Source.WALL_READY, 0, null);

        long blackhole = 0L;
        for (int w = 0; w < warmupIters; w++) {
            CountDownLatch latch = new CountDownLatch(1);
            runner.submit("bench-wall", rule, ctx, steps -> latch.countDown());
            awaitLatch(latch);
            blackhole += sink.callCount;
        }
        BenchmarkRunner.sink += blackhole;

        double[] samplesMs = new double[measuredIters];
        for (int i = 0; i < measuredIters; i++) {
            CountDownLatch latch = new CountDownLatch(1);
            long t0 = System.nanoTime();
            runner.submit("bench-wall", rule, ctx, steps -> latch.countDown());
            awaitLatch(latch);
            long t1 = System.nanoTime();
            samplesMs[i] = (t1 - t0) / 1_000_000.0;
            blackhole += sink.callCount;
        }
        BenchmarkRunner.sink += blackhole;

        runner.shutdown();
        return Percentiles.of(samplesMs);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                LOG.warning("[bench-script] run timed out after 10s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ScriptRule buildRule(String wallId, int actionCount) {
        List<Action> actions = new ArrayList<>(actionCount);
        for (int i = 0; i < actionCount; i++) {
            actions.add(new Action.SetVariable("user/" + wallId + "/bench-var", String.valueOf(i)));
        }
        return new ScriptRule(null, null, true, "bench-rule",
                new Trigger.WallReady(), actions, "{}");
    }

    private static final class FakeActionSink implements ActionSink {
        volatile long callCount = 0L;

        @Override
        public TraceStep execute(String wallId, String blockId, Action action) {
            callCount++;
            return TraceStep.ok(blockId, "action", "bench-noop");
        }
    }
}
