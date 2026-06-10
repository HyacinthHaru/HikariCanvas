package moe.hikari.canvas.script.engine;

import moe.hikari.canvas.HikariCanvasConfig;
import moe.hikari.canvas.script.Action;
import moe.hikari.canvas.script.ScriptRule;
import moe.hikari.canvas.script.Trigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.7.0-P2-3：{@link ScriptRunner} 执行管线。全 fake（同步调度替身 + 记录型
 * {@link ActionSink} + clock 注入 budget），无线程 sleep / 无 Bukkit。
 */
class ScriptRunnerTest {

    private static final Logger LOG = Logger.getLogger("test");
    private static final String WALL = "w-test";

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private InlineScheduler scheduler;
    private RecordingSink sink;
    private RecordingBudget budget;
    private ConditionEvaluator conditions;

    @BeforeEach
    void setUp() {
        scheduler = new InlineScheduler();
        sink = new RecordingSink();
        budget = new RecordingBudget(HikariCanvasConfig.ScriptsConfig.defaults(), clock);
        // 条件 lookup：score=10 固定值
        conditions = new ConditionEvaluator(LOG,
                fullName -> fullName.endsWith("/score") ? "10" : null);
    }

    private ScriptRunner runner() {
        return new ScriptRunner(conditions, sink, budget, null, LOG, scheduler);
    }

    private ScriptRunner runner(ScriptBudget b) {
        return new ScriptRunner(conditions, sink, b, null, LOG, scheduler);
    }

    private static ScriptRule rule(String id, List<Action> actions) {
        return new ScriptRule(id, WALL, true, "r-" + id, new Trigger.Timer(30), actions, "{}");
    }

    private static TriggerContext ctx(int depth) {
        return new TriggerContext(TriggerContext.Source.TEST, depth, "unit");
    }

    // ---------- 基本执行 + trace 形态 ----------

    @Test
    void simpleRun_executesAllActions_inOrder() {
        ScriptRunner r = runner();
        r.submit(WALL, rule("r1", List.of(
                new Action.Log("a"), new Action.Log("b"))), ctx(0));
        assertEquals(List.of("actions/0", "actions/1"), sink.blockIds);
        assertEquals(WALL, sink.wallIds.get(0));
    }

    @Test
    void trace_blockId_isTreePath_throughIfBranches() {
        ScriptRunner r = runner();
        // if(true) → then 分支；blockId 形如 actions/1/then/0
        Action.If iff = new Action.If("var(\"user/score\") == 10",
                List.of(new Action.Log("t0"), new Action.Log("t1")),
                List.of(new Action.Log("e0")));
        r.submit(WALL, rule("r1", List.of(new Action.Log("a"), iff,
                new Action.Log("tail"))), ctx(0));
        assertEquals(List.of("actions/0", "actions/1/then/0", "actions/1/then/1", "actions/2"),
                sink.blockIds, "then 分支树路径 + 外层后续动作按序执行；else 不执行");
    }

    @Test
    void if_falseBranch_takesElsePath() {
        ScriptRunner r = runner();
        Action.If iff = new Action.If("var(\"user/score\") == 999",
                List.of(new Action.Log("t0")),
                List.of(new Action.Log("e0")));
        r.submit(WALL, rule("r1", List.of(iff)), ctx(0));
        assertEquals(List.of("actions/0/else/0"), sink.blockIds);
    }

    @Test
    void if_badCondition_evaluatesFalse_chainContinues() {
        ScriptRunner r = runner();
        Action.If iff = new Action.If("((((", List.of(new Action.Log("t")), List.of());
        r.submit(WALL, rule("r1", List.of(iff, new Action.Log("after"))), ctx(0));
        assertEquals(List.of("actions/1"), sink.blockIds, "坏条件 → false 空 else，后续继续");
    }

    // ---------- runs/s 窗口 ----------

    @Test
    void runsPerSecond_9_10_pass_11_blocked() {
        ScriptRunner r = runner();
        ScriptRule ru = rule("r1", List.of(new Action.Log("x")));
        for (int i = 0; i < 11; i++) {
            r.submit(WALL, ru, ctx(0));
        }
        assertEquals(10, sink.blockIds.size(), "默认 10/s：前 10 次执行，第 11 次丢弃");
        clock.addAndGet(1000L);
        r.submit(WALL, ru, ctx(0));
        assertEquals(11, sink.blockIds.size(), "新窗放行");
    }

    // ---------- ABA 链深 ----------

    @Test
    void chainDepth_8_blocked_7_passes() {
        ScriptRunner r = runner();
        ScriptRule ru = rule("r1", List.of(new Action.Log("x")));
        r.submit(WALL, ru, ctx(8));
        assertEquals(0, sink.blockIds.size(), "depth=8 ≥ max(8) 整 run 掐断");
        r.submit(WALL, ru, ctx(7));
        assertEquals(1, sink.blockIds.size(), "depth=7 放行");
    }

    @Test
    void chainDepth_threadLocal_setDuringRun_clearedAfter() {
        ScriptRunner r = runner();
        List<Integer> seen = new ArrayList<>();
        sink.onExecute = () -> seen.add(ScriptRunner.CHAIN_DEPTH.get());
        r.submit(WALL, rule("r1", List.of(new Action.Log("x"))), ctx(3));
        assertEquals(List.of(3), seen, "执行期 CHAIN_DEPTH = ctx.chainDepth（K1）");
        assertNull(ScriptRunner.CHAIN_DEPTH.get(), "run 结束 finally 清 ThreadLocal");
        assertEquals(0, ScriptRunner.currentChainDepth(), "无上下文时 Router 读到 0");
    }

    // ---------- actions 上限 ----------

    @Test
    void actions_51_blocked_50_passes() {
        ScriptRunner r = runner();
        List<Action> fifty = new ArrayList<>();
        for (int i = 0; i < 50; i++) fifty.add(new Action.Log("x"));
        r.submit(WALL, rule("r1", fifty), ctx(0));
        assertEquals(50, sink.blockIds.size(), "恰 50 个动作全执行");

        sink.reset();
        clock.addAndGet(1000L);
        List<Action> fiftyOne = new ArrayList<>(fifty);
        fiftyOne.add(new Action.Log("x"));
        r.submit(WALL, rule("r2", fiftyOne), ctx(0));
        assertEquals(50, sink.blockIds.size(), "第 51 个动作被掐断");
    }

    @Test
    void actions_nestedIf_countsIfBlockItself() {
        // max-actions=3：log(1) + if(2) + then 里第一个 log(3) 执行，then 第二个(4)掐断
        ScriptBudget small = new ScriptBudget(
                new HikariCanvasConfig.ScriptsConfig(16, 3, 10, 8), clock::get);
        ScriptRunner r = runner(small);
        Action.If iff = new Action.If("var(\"user/score\") == 10",
                List.of(new Action.Log("t0"), new Action.Log("t1")), List.of());
        r.submit(WALL, rule("r1", List.of(new Action.Log("a"), iff)), ctx(0));
        assertEquals(List.of("actions/0", "actions/1/then/0"), sink.blockIds,
                "if 自身计 1：log + if + then[0] = 3 个，then[1] 第 4 个被掐断");
    }

    // ---------- wait 续接 ----------

    @Test
    void wait_schedulesContinuation_remainingActionsRun() {
        ScriptRunner r = runner();
        Action.If iff = new Action.If("var(\"user/score\") == 10",
                List.of(new Action.Log("t0"), new Action.Wait(200), new Action.Log("t1")),
                List.of());
        r.submit(WALL, rule("r1", List.of(iff, new Action.Log("tail"))), ctx(0));
        // wait 前只执行了 then[0]
        assertEquals(List.of("actions/0/then/0"), sink.blockIds);
        assertEquals(List.of(200L), scheduler.scheduledDelays, "wait 200ms 进调度");
        // 跑 continuation：剩余 then[1] + 外层 tail 都执行（外层 if 后续不丢）
        scheduler.runPending();
        assertEquals(List.of("actions/0/then/0", "actions/0/then/2", "actions/1"),
                sink.blockIds, "续接含外层 if 后续动作");
    }

    @Test
    void wait_continuation_doesNotReacquireRunsPerSecond() {
        ScriptBudget one = new ScriptBudget(
                new HikariCanvasConfig.ScriptsConfig(16, 50, 1, 8), clock::get);
        ScriptRunner r = runner(one);
        r.submit(WALL, rule("r1", List.of(
                new Action.Log("a"), new Action.Wait(100), new Action.Log("b"))), ctx(0));
        assertEquals(1, sink.blockIds.size());
        scheduler.runPending();
        assertEquals(2, sink.blockIds.size(), "runs/s=1 时续接不再过闸，剩余动作照常执行");
    }

    @Test
    void wait_continuation_actionCountAccumulates() {
        // max-actions=3：log(1) + wait(2) + 续接 log(3) 执行，第 4 个掐断
        ScriptBudget small = new ScriptBudget(
                new HikariCanvasConfig.ScriptsConfig(16, 3, 10, 8), clock::get);
        ScriptRunner r = runner(small);
        r.submit(WALL, rule("r1", List.of(
                new Action.Log("a"), new Action.Wait(100),
                new Action.Log("b"), new Action.Log("c"))), ctx(0));
        scheduler.runPending();
        assertEquals(List.of("actions/0", "actions/2"), sink.blockIds,
                "计数跨 wait 续接累计：第 4 个动作（actions/3）被掐断");
    }

    @Test
    void wait_continuation_restoresChainDepthThreadLocal() {
        ScriptRunner r = runner();
        List<Integer> seen = new ArrayList<>();
        sink.onExecute = () -> seen.add(ScriptRunner.CHAIN_DEPTH.get());
        r.submit(WALL, rule("r1", List.of(
                new Action.Log("a"), new Action.Wait(100), new Action.Log("b"))), ctx(5));
        scheduler.runPending();
        assertEquals(List.of(5, 5), seen, "续接段 CHAIN_DEPTH 延续");
        assertNull(ScriptRunner.CHAIN_DEPTH.get());
    }

    // ---------- sink 异常兜底 ----------

    @Test
    void sinkThrow_isIsolated_chainContinues() {
        ScriptRunner r = runner();
        sink.throwOn = "actions/0";
        r.submit(WALL, rule("r1", List.of(new Action.Log("a"), new Action.Log("b"))), ctx(0));
        assertEquals(List.of("actions/0", "actions/1"), sink.blockIds,
                "第一个动作抛异常 → error step，链不断");
    }

    // ---------- audit 限频（经 RecordingBudget 侧证） ----------

    @Test
    void blockedRun_withoutAudit_skipsRateLimitProbe() {
        ScriptRunner r = runner();
        ScriptRule ru = rule("r1", List.of(new Action.Log("x")));
        for (int i = 0; i < 13; i++) {
            r.submit(WALL, ru, ctx(0)); // 前 10 过，后 3 rate-blocked
        }
        assertEquals(10, sink.blockIds.size());
        assertEquals(0, budget.shouldAuditCalls, "audit 未装配 → 不做限频判定");
    }

    @Test
    void blockedRun_withAudit_rateLimitedTo1Per10s() {
        // AuditLog(null jdbi)：record() 内 NPE 被自吞走 SEVERE fallback（0.4.10 P3-4 既有纪律），
        // 这里只侧证 K5 限频判定被走到且 10s 窗内只放行 1 次。
        moe.hikari.canvas.storage.AuditLog audit =
                new moe.hikari.canvas.storage.AuditLog(null, LOG);
        ScriptRunner r = new ScriptRunner(conditions, sink, budget, audit, LOG, scheduler);
        ScriptRule ru = rule("r1", List.of(new Action.Log("x")));
        for (int i = 0; i < 13; i++) {
            r.submit(WALL, ru, ctx(0)); // 11、12、13 次被 rate 掐断 → 3 次 audit 尝试
        }
        assertEquals(3, budget.shouldAuditCalls, "每次掐断都做限频判定");
        assertEquals(1, budget.shouldAuditAllowed, "10s 窗内只放行 1 条 SCRIPT_RUN_BLOCKED");
        clock.addAndGet(10_000L);
        for (int i = 0; i < 12; i++) {
            r.submit(WALL, ru, ctx(0));
        }
        assertEquals(2, budget.shouldAuditAllowed, "新 10s 窗放行第二条");
    }

    @Test
    void chainBlocked_doesNotExecute_andDoesNotAcquireRunWindow() {
        ScriptBudget one = new ScriptBudget(
                new HikariCanvasConfig.ScriptsConfig(16, 50, 1, 8), clock::get);
        ScriptRunner r = runner(one);
        ScriptRule ru = rule("r1", List.of(new Action.Log("x")));
        r.submit(WALL, ru, ctx(9)); // chain 掐断（在 rate 闸之前）
        r.submit(WALL, ru, ctx(0));
        assertEquals(1, sink.blockIds.size(), "chain 掐断不占用 runs/s 窗额度");
    }

    // ---------- 0.7.0-P3 A2（K11）：trace callback 恰一次 ----------

    @Test
    void traceCallback_normalRun_firesExactlyOnce_withSteps() {
        ScriptRunner r = runner();
        List<List<TraceStep>> received = new ArrayList<>();
        r.submit(WALL, rule("r1", List.of(new Action.Log("a"), new Action.Log("b"))),
                ctx(0), received::add);
        assertEquals(1, received.size(), "正常结束恰一次回调");
        List<TraceStep> steps = received.get(0);
        assertEquals(3, steps.size(), "trigger + 2 action");
        assertEquals("trigger", steps.get(0).blockId());
        assertEquals("actions/0", steps.get(1).blockId());
        assertEquals("actions/1", steps.get(2).blockId());
    }

    @Test
    void traceCallback_waitContinuation_firesOnceAtFinalSegment() {
        ScriptRunner r = runner();
        List<List<TraceStep>> received = new ArrayList<>();
        r.submit(WALL, rule("r1", List.of(
                new Action.Log("a"), new Action.Wait(100), new Action.Log("b"))),
                ctx(0), received::add);
        assertEquals(0, received.size(), "wait 续接挂起期间不回调（最终段才回）");
        scheduler.runPending();
        assertEquals(1, received.size(), "续接段结束恰一次回调");
        // trigger + log + wait + log = 4 step（跨段 trace 经 RunState 延续）
        assertEquals(4, received.get(0).size());
        assertEquals("actions/2", received.get(0).get(3).blockId());
    }

    @Test
    void traceCallback_rateBlocked_firesOnceWithBlockedTriggerStep() {
        ScriptBudget one = new ScriptBudget(
                new HikariCanvasConfig.ScriptsConfig(16, 50, 1, 8), clock::get);
        ScriptRunner r = runner(one);
        ScriptRule ru = rule("r1", List.of(new Action.Log("x")));
        r.submit(WALL, ru, ctx(0)); // 占满 1/s 窗（无 callback）
        List<List<TraceStep>> received = new ArrayList<>();
        r.submit(WALL, ru, ctx(0), received::add);
        assertEquals(1, received.size(), "rate 闸拒也恰一次回调");
        assertEquals(1, received.get(0).size());
        TraceStep step = received.get(0).get(0);
        assertEquals("trigger", step.blockId());
        assertEquals("blocked", step.result());
        assertTrue(step.detail().contains("频率"), step.detail());
    }

    @Test
    void traceCallback_chainBlocked_firesOnceWithBlockedTriggerStep() {
        ScriptRunner r = runner();
        List<List<TraceStep>> received = new ArrayList<>();
        r.submit(WALL, rule("r1", List.of(new Action.Log("x"))), ctx(8), received::add);
        assertEquals(1, received.size());
        assertEquals("blocked", received.get(0).get(0).result());
        assertTrue(received.get(0).get(0).detail().contains("链深"),
                received.get(0).get(0).detail());
    }

    @Test
    void traceCallback_actionsExceeded_firesOnceWithBlockedStep() {
        ScriptBudget small = new ScriptBudget(
                new HikariCanvasConfig.ScriptsConfig(16, 1, 10, 8), clock::get);
        ScriptRunner r = runner(small);
        List<List<TraceStep>> received = new ArrayList<>();
        r.submit(WALL, rule("r1", List.of(new Action.Log("a"), new Action.Log("b"))),
                ctx(0), received::add);
        assertEquals(1, received.size(), "actions 掐断恰一次回调");
        List<TraceStep> steps = received.get(0);
        assertEquals("blocked", steps.get(steps.size() - 1).result(), "末步是掐断 blocked");
    }

    @Test
    void traceCallback_throwing_isSwallowed_runnerSurvives() {
        ScriptRunner r = runner();
        r.submit(WALL, rule("r1", List.of(new Action.Log("a"))), ctx(0),
                steps -> { throw new IllegalStateException("cb boom"); });
        // callback 抛被吞——runner 还能继续接活
        clock.addAndGet(1000L);
        List<List<TraceStep>> received = new ArrayList<>();
        r.submit(WALL, rule("r2", List.of(new Action.Log("b"))), ctx(0), received::add);
        assertEquals(1, received.size(), "上一个 callback 抛异常不影响后续 run");
    }

    // ---------- shutdown ----------

    @Test
    void shutdown_idempotent_submitAfterIsNoop() {
        ScriptRunner r = runner();
        r.shutdown();
        assertDoesNotThrow(r::shutdown, "幂等");
        assertEquals(1, scheduler.shutdownCount, "底层调度只关一次");
        r.submit(WALL, rule("r1", List.of(new Action.Log("x"))), ctx(0));
        assertEquals(0, sink.blockIds.size(), "关停后 submit no-op");
    }

    @Test
    void shutdown_pendingWaitContinuation_runsSafely() {
        ScriptRunner r = runner();
        r.submit(WALL, rule("r1", List.of(
                new Action.Log("a"), new Action.Wait(100), new Action.Log("b"))), ctx(0));
        r.shutdown();
        // continuation 已入队（shutdown 在 schedule 之后）；生产 SES shutdown 后不再跑，
        // 替身仍跑——验证关停态下补跑无异常、状态安全
        assertDoesNotThrow(scheduler::runPending);
        assertTrue(sink.blockIds.size() >= 1);
    }

    // ---------- fakes ----------

    /** 同步直跑调度替身：execute 立即跑；schedule 暂存（runPending 触发），记录 delay。 */
    private static final class InlineScheduler implements ScriptRunner.TaskScheduler {
        final List<Long> scheduledDelays = new ArrayList<>();
        final Deque<Runnable> pending = new ArrayDeque<>();
        int shutdownCount = 0;

        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public void schedule(Runnable task, long delayMs) {
            scheduledDelays.add(delayMs);
            pending.add(task);
        }

        void runPending() {
            while (!pending.isEmpty()) {
                pending.poll().run();
            }
        }

        @Override
        public void shutdown() {
            shutdownCount++;
        }
    }

    /** 记录调用序的 ActionSink；可指定某 blockId 抛异常（兜底路径测试）。 */
    private static final class RecordingSink implements ActionSink {
        final List<String> blockIds = new ArrayList<>();
        final List<String> wallIds = new ArrayList<>();
        Runnable onExecute;
        String throwOn;

        @Override
        public TraceStep execute(String wallId, String blockId, Action action) {
            blockIds.add(blockId);
            wallIds.add(wallId);
            if (onExecute != null) onExecute.run();
            if (blockId.equals(throwOn)) {
                throw new IllegalStateException("boom");
            }
            return TraceStep.ok(blockId, "action", null);
        }

        void reset() {
            blockIds.clear();
            wallIds.clear();
        }
    }

    /** 记录 shouldAuditBlock 调用/放行次数（AuditLog final 无接口，只能在 budget 层侧证）。 */
    private static final class RecordingBudget extends ScriptBudget {
        int shouldAuditCalls = 0;
        int shouldAuditAllowed = 0;

        RecordingBudget(HikariCanvasConfig.ScriptsConfig config, AtomicLong clock) {
            super(config, clock::get);
        }

        @Override
        public boolean shouldAuditBlock(String ruleKey) {
            shouldAuditCalls++;
            boolean allowed = super.shouldAuditBlock(ruleKey);
            if (allowed) shouldAuditAllowed++;
            return allowed;
        }
    }
}
