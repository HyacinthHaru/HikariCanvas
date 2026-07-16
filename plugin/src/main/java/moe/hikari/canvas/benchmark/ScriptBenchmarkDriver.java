package moe.hikari.canvas.benchmark;

import moe.hikari.canvas.HikariCanvasConfig;
import moe.hikari.canvas.script.Action;
import moe.hikari.canvas.script.ScriptRule;
import moe.hikari.canvas.script.Trigger;
import moe.hikari.canvas.script.engine.ActionSink;
import moe.hikari.canvas.script.engine.ConditionEvaluator;
import moe.hikari.canvas.script.engine.ScriptBudget;
import moe.hikari.canvas.script.engine.ScriptRunner;
import moe.hikari.canvas.script.engine.TraceStep;
import moe.hikari.canvas.script.engine.TriggerContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 脚本动作链开销 benchmark 驱动（headless 纯内存，无 Bukkit / DB / PacketEvents）。
 *
 * <p><b>测量对象</b>：{@link ScriptRunner} 执行一条含 actionCount 个动作的规则一轮的耗时——
 * 包含帧栈遍历、{@link ActionSink#execute} 调用（fake sink，每次 O(1) 返回 ok step）、
 * trace 收集、finish 等全链路开销。</p>
 *
 * <p><b>装配方式</b>：</p>
 * <ul>
 *   <li>生产 {@link ScriptRunner}（真实单线程 daemon SES）——{@code TaskScheduler} 是包级
 *       私有接口，外部包不可注入 trampoline；改用真实 SES + {@link CountDownLatch} 等待 run 结束。</li>
 *   <li>fake {@link ActionSink}：每个动作返回 ok {@link TraceStep}（O(1)，无 DB / Bukkit 副作用）</li>
 *   <li>真实 {@link ConditionEvaluator}（{@code VariableStore=null}）——bench 规则仅含
 *       {@code SetVariable}，无 {@code if} 条件，{@code eval} 不被调用，null store 不触发 NPE</li>
 *   <li>真 {@link ScriptBudget}（maxActionsPerRun=actionCount+1，保证不被熔断）</li>
 * </ul>
 *
 * <p><b>测量点</b>：从 {@code runner.submit()} 到 trace callback 回调（run 结束信号）的 wall-clock
 * 时间，覆盖 SES 投递 + 帧栈建立 + 所有 actionCount 次 sink 调用 + trace 聚合 + callback 回调。</p>
 */
public final class ScriptBenchmarkDriver {

    private static final Logger LOG = Logger.getLogger("hikari-bench-script");

    /**
     * 运行脚本链 benchmark，返回结构化结果行列表。
     *
     * @param actionCounts  待测的动作数列表（如 {@code [1, 10, 25, 50]}，含 max-actions-per-run 边界）
     * @param warmupIters   预热轮数（触发 JIT 编译）
     * @param measuredIters 测量轮数
     * @return 每个动作数对应一行 {@link BenchRow}，文案由调用端按 locale 组装
     */
    public static List<BenchRow> measure(List<Integer> actionCounts, int warmupIters, int measuredIters) {
        List<BenchRow> rows = new ArrayList<>(actionCounts.size());
        for (int n : actionCounts) {
            Percentiles p = measureActionCount(n, warmupIters, measuredIters);
            rows.add(new BenchRow(n, p));
        }
        return rows;
    }

    // ---------- 内部逻辑 ----------

    private static Percentiles measureActionCount(int actionCount, int warmupIters, int measuredIters) {
        // ScriptBudget：maxActionsPerRun = actionCount+1（保证不被动作数熔断）
        // maxRunsPerSecond = 1000（远超测量频率，保证不被 rate 闸拦截）
        HikariCanvasConfig.ScriptsConfig cfg = new HikariCanvasConfig.ScriptsConfig(
                16, actionCount + 1, 1000, 8);
        ScriptBudget budget = new ScriptBudget(cfg);

        // 真实 ConditionEvaluator（VariableStore=null）——bench 规则只含 SetVariable，
        // 无 if 条件，eval 不会被调用，null store 不触发 NPE。
        // 强制转型消除 ConditionEvaluator(Logger,VariableStore) vs (Logger,Function) 歧义。
        ConditionEvaluator conditions = new ConditionEvaluator(LOG,
                (moe.hikari.canvas.variable.VariableStore) null);

        // fake ActionSink：每次 O(1) 返回 ok（不碰任何外部资源）
        FakeActionSink sink = new FakeActionSink();

        // 生产 ScriptRunner（自建单线程 daemon SES）
        // TaskScheduler 是包级私有接口，外部包不可注入 trampoline——用真实 SES + CountDownLatch 等待。
        ScriptRunner runner = new ScriptRunner(conditions, sink, budget, null, LOG);

        // 构造含 actionCount 个 SetVariable 动作的规则
        ScriptRule rule = buildRule("bench-wall", actionCount);
        TriggerContext ctx = new TriggerContext(TriggerContext.Source.WALL_READY, 0, null);

        // JIT 防优化 blackhole
        long blackhole = 0L;

        // 预热：触发 JIT C2 编译
        for (int w = 0; w < warmupIters; w++) {
            CountDownLatch latch = new CountDownLatch(1);
            runner.submit("bench-wall", rule, ctx, steps -> latch.countDown());
            awaitLatch(latch);
            blackhole += sink.callCount;
        }
        SceneTimer.sink += blackhole;

        // 测量：每轮 submit 一次，等 trace callback 信号后计时
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
        SceneTimer.sink += blackhole;

        runner.shutdown();
        return Percentiles.of(samplesMs);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                LOG.warning("[bench-script] run timed out after 10s (runner SES never fired trace callback; result may be inaccurate)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 构造含 actionCount 个 SetVariable 动作的规则（用作脚本执行的"有效载荷"）。 */
    private static ScriptRule buildRule(String wallId, int actionCount) {
        List<Action> actions = new ArrayList<>(actionCount);
        for (int i = 0; i < actionCount; i++) {
            actions.add(new Action.SetVariable("user/" + wallId + "/bench-var", String.valueOf(i)));
        }
        return new ScriptRule(null, null, true, "bench-rule",
                new Trigger.WallReady(), actions, "{}");
    }

    // ---------- fake 基础设施 ----------

    /** 动作执行 fake：O(1) 返回 ok，记录调用次数（blackhole 用）。 */
    private static final class FakeActionSink implements ActionSink {
        volatile long callCount = 0L;

        @Override
        public TraceStep execute(String wallId, String blockId, Action action) {
            callCount++;
            return TraceStep.ok(blockId, "action", "bench-noop");
        }
    }
}
