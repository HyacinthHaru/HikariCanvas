package ac.haru.hikaricanvas.script.engine;

import ac.haru.hikaricanvas.HikariCanvasConfig;
import ac.haru.hikaricanvas.render.AnimationTicker;
import ac.haru.hikaricanvas.script.Action;
import ac.haru.hikaricanvas.script.ScriptRule;
import ac.haru.hikaricanvas.script.Trigger;
import ac.haru.hikaricanvas.state.BlendMode;
import ac.haru.hikaricanvas.state.Easing;
import ac.haru.hikaricanvas.state.Fill;
import ac.haru.hikaricanvas.state.Layer;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.state.RectElement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
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
        return new ScriptRunner(conditions, sink, budget, null, LOG, scheduler, clock::get);
    }

    private ScriptRunner runner(ScriptBudget b) {
        return new ScriptRunner(conditions, sink, b, null, LOG, scheduler, clock::get);
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

    // ---------- 0.7.1：playTimelineAwait 挂起续接 ----------

    @Test
    void playTimelineAwait_durationPositive_suspendsThenContinues() {
        sink.timelineDuration = 1000L;
        ScriptRunner r = runner();
        r.submit(WALL, rule("r1", List.of(
                new Action.PlayTimelineAwait("tl-1"), new Action.Log("after"))), ctx(0));
        // play 已执行（actions/0），但 after 还没——挂起 1000ms
        assertEquals(List.of("actions/0"), sink.blockIds, "play 执行后挂起，after 未执行");
        assertEquals(List.of(1000L), scheduler.scheduledDelays, "挂起 durationMs=1000");
        scheduler.runPending();
        assertEquals(List.of("actions/0", "actions/1"), sink.blockIds, "续接执行 after");
    }

    @Test
    void playTimelineAwait_durationZero_doesNotSuspend() {
        sink.timelineDuration = 0L; // 查无 timeline / 不支持 → 不挂起
        ScriptRunner r = runner();
        r.submit(WALL, rule("r1", List.of(
                new Action.PlayTimelineAwait("tl-x"), new Action.Log("after"))), ctx(0));
        assertEquals(List.of("actions/0", "actions/1"), sink.blockIds,
                "dur=0 不挂起，after 紧接执行");
        assertEquals(0, scheduler.scheduledDelays.size(), "无调度");
    }

    @Test
    void playTimelineAwait_playFailure_doesNotSuspend() {
        // play 失败 → execute 返 error → dur 不查（恒 0）→ 不挂起，after 继续
        sink.timelineDuration = 5000L;
        sink.errorOn = "actions/0";
        ScriptRunner r = runner();
        r.submit(WALL, rule("r1", List.of(
                new Action.PlayTimelineAwait("tl-1"), new Action.Log("after"))), ctx(0));
        assertEquals(List.of("actions/0", "actions/1"), sink.blockIds,
                "play 失败不挂起（dur 不查），链继续");
        assertEquals(0, scheduler.scheduledDelays.size());
    }

    // ---------- 0.7.1-P5：StopScript 中止 ----------

    @Test
    void stopScript_abortsRemainingActions() {
        ScriptRunner r = runner();
        r.submit(WALL, rule("r1", List.of(
                new Action.Log("a"), new Action.StopScript(), new Action.Log("b"))), ctx(0));
        // a 走 sink；stopScript 由 runner 拦截清栈；b 不执行
        assertEquals(List.of("actions/0"), sink.blockIds, "stopScript 后续动作 b 被中止");
    }

    // ---------- 0.7.1-P5：WaitUntil 轮询 ----------

    @Test
    void waitUntil_satisfiedImmediately_continues() {
        // setUp lookup: score=10 → "var(\"user/score\") > 5" 立即 true
        ScriptRunner r = runner();
        r.submit(WALL, rule("r1", List.of(
                new Action.WaitUntil("var(\"user/score\") > 5", 5000),
                new Action.Log("after"))), ctx(0));
        scheduler.runPending(); // WaitUntil 同步评估 true → schedule(runFrames,0)；after 在续接里
        assertTrue(sink.blockIds.contains("actions/1"), "条件立即满足，after 执行");
    }

    @Test
    void waitUntil_pollsUntilConditionTrue() {
        // 前 2 次评估 false（score=0），第 3 次起 true（score=10）——可变 lookup
        java.util.concurrent.atomic.AtomicInteger evals = new java.util.concurrent.atomic.AtomicInteger();
        ConditionEvaluator polling = new ConditionEvaluator(LOG,
                fullName -> fullName.endsWith("/score") ? (evals.incrementAndGet() >= 3 ? "10" : "0") : null);
        ScriptRunner r = new ScriptRunner(polling, sink, budget, null, LOG, scheduler, clock::get);
        r.submit(WALL, rule("r1", List.of(
                new Action.WaitUntil("var(\"user/score\") > 5", 30000),
                new Action.Log("after"))), ctx(0));
        // runPending drain：eval#1 false→schedule poll；poll eval#2 false→poll eval#3 true→续接 runFrames
        scheduler.runPending();
        assertTrue(sink.blockIds.contains("actions/1"), "轮询至条件满足后 after 执行");
    }

    @Test
    void waitUntil_timeout_continuesAnyway() {
        // 条件永 false（score=10, > 50）；timeoutMs=100；推进 clock 过 deadline → 超时续接
        ScriptRunner r = runner();
        r.submit(WALL, rule("r1", List.of(
                new Action.WaitUntil("var(\"user/score\") > 50", 100),
                new Action.Log("after"))), ctx(0));
        assertEquals(List.of(), sink.blockIds, "未超时前 after 不执行（WaitUntil 不走 sink）");
        clock.addAndGet(200); // 过 deadline 1_000_100
        scheduler.runPending(); // poll → false + 超时 → schedule runFrames → after
        assertTrue(sink.blockIds.contains("actions/1"), "超时后 after 照常执行");
    }

    @Test
    void waitUntil_pollingDoesNotExhaustBudget() {
        // 前 4 次 false 后 true；max-actions=3。WaitUntil(1)+after(2) ≤ 3。
        // 若轮询每次计费，5+ 次会超 3 → blocked；断言 after 执行证明轮询不计 Budget。
        java.util.concurrent.atomic.AtomicInteger evals = new java.util.concurrent.atomic.AtomicInteger();
        ConditionEvaluator polling = new ConditionEvaluator(LOG,
                fullName -> fullName.endsWith("/score") ? (evals.incrementAndGet() >= 5 ? "10" : "0") : null);
        ScriptBudget small = new ScriptBudget(
                new HikariCanvasConfig.ScriptsConfig(16, 3, 10, 8), clock::get);
        ScriptRunner r = new ScriptRunner(polling, sink, small, null, LOG, scheduler, clock::get);
        r.submit(WALL, rule("r1", List.of(
                new Action.WaitUntil("var(\"user/score\") > 5", 30000),
                new Action.Log("after"))), ctx(0));
        scheduler.runPending();
        assertTrue(sink.blockIds.contains("actions/1"), "轮询不计 Budget，after 未被掐断执行");
    }

    // ---------- 0.7.2-P3：RepeatUntil 动态循环 ----------

    @Test
    void repeatUntil_loopsUntilConditionTrue() {
        // 可变 lookup：前 2 次 score="0"（>5 false），第 3 次起 "10"（true）
        java.util.concurrent.atomic.AtomicInteger evals = new java.util.concurrent.atomic.AtomicInteger();
        ConditionEvaluator polling = new ConditionEvaluator(LOG,
                fullName -> fullName.endsWith("/score") ? (evals.incrementAndGet() >= 3 ? "10" : "0") : null);
        ScriptRunner r = new ScriptRunner(polling, sink, budget, null, LOG, scheduler, clock::get);
        r.submit(WALL, rule("r1", List.of(
                new Action.RepeatUntil("var(\"user/score\") > 5", 10, List.of(new Action.Log("body"))),
                new Action.Log("after"))), ctx(0));
        // while 语义：eval#1 false→body，eval#2 false→body，eval#3 true→跳出。body 2 轮。
        assertEquals(2, sink.blockIds.stream().filter("actions/0/body/0"::equals).count(), "body 执行 2 轮");
        assertTrue(sink.blockIds.contains("actions/1"), "跳出后 after 执行");
    }

    @Test
    void repeatUntil_stopsAtMaxIterations() {
        // condition 永 false（setUp score=10，> 50 false），maxIterations=3 → body 3 轮停
        ScriptRunner r = runner();
        r.submit(WALL, rule("r1", List.of(
                new Action.RepeatUntil("var(\"user/score\") > 50", 3, List.of(new Action.Log("body"))),
                new Action.Log("after"))), ctx(0));
        assertEquals(3, sink.blockIds.stream().filter("actions/0/body/0"::equals).count(), "body 3 轮(达 maxIter)");
        assertTrue(sink.blockIds.contains("actions/1"), "after 执行");
    }

    @Test
    void repeatUntil_conditionTrueImmediately_zeroBody() {
        // condition 一开始就 true（score=10 > 5），while 语义 → body 0 次
        ScriptRunner r = runner();
        r.submit(WALL, rule("r1", List.of(
                new Action.RepeatUntil("var(\"user/score\") > 5", 10, List.of(new Action.Log("body"))),
                new Action.Log("after"))), ctx(0));
        assertEquals(0, sink.blockIds.stream().filter("actions/0/body/0"::equals).count(), "条件立即满足 body 0 次");
        assertTrue(sink.blockIds.contains("actions/1"), "after 执行");
    }

    @Test
    void repeatUntil_bodyWithWait_continuesAcrossSegments() {
        // body 含 wait → 帧栈续接；condition 永 false，maxIter=2
        ScriptRunner r = runner();
        r.submit(WALL, rule("r1", List.of(
                new Action.RepeatUntil("var(\"user/score\") > 50", 2,
                        List.of(new Action.Log("a"), new Action.Wait(100), new Action.Log("b"))),
                new Action.Log("after"))), ctx(0));
        scheduler.runPending();
        assertEquals(2, sink.blockIds.stream().filter("actions/0/body/0"::equals).count(), "a 执行 2 轮");
        assertEquals(2, sink.blockIds.stream().filter("actions/0/body/2"::equals).count(), "b 执行 2 轮(跨 wait 续接)");
        assertTrue(sink.blockIds.contains("actions/1"), "after 执行");
    }

    @Test
    void repeatUntil_budgetCaps() {
        // condition 永 false + maxIterations=100 + max-actions=5 → Budget 熔断，远不到 100 轮
        ScriptBudget small = new ScriptBudget(
                new HikariCanvasConfig.ScriptsConfig(16, 5, 10, 8), clock::get);
        ScriptRunner r = runner(small);
        r.submit(WALL, rule("r1", List.of(
                new Action.RepeatUntil("var(\"user/score\") > 50", 100, List.of(new Action.Log("body"))))), ctx(0));
        assertTrue(sink.blockIds.stream().filter("actions/0/body/0"::equals).count() < 100, "Budget 熔断不到 100 轮");
    }

    // ---------- 0.7.1：Repeat 有界循环展开 ----------

    @Test
    void repeat_expandsBodyCountTimes_sameBlockIdPrefix() {
        ScriptRunner r = runner();
        // repeat 3x [log] → body 执行 3 次，blockId 都是 actions/0/body/0（守同构，不带 round）
        r.submit(WALL, rule("r1", List.of(
                new Action.Repeat(3, List.of(new Action.Log("loop"))))), ctx(0));
        assertEquals(List.of("actions/0/body/0", "actions/0/body/0", "actions/0/body/0"),
                sink.blockIds, "body 展开 3 次，blockId 同 prefix 不带 round（前后端同构）");
    }

    @Test
    void repeat_multiBody_preservesInnerOrderEachRound() {
        ScriptRunner r = runner();
        // repeat 2x [log, log] → 每轮 body[0] body[1] 按序；blockId body/0 body/1 重复 2 轮
        r.submit(WALL, rule("r1", List.of(
                new Action.Repeat(2, List.of(new Action.Log("a"), new Action.Log("b"))))), ctx(0));
        assertEquals(List.of(
                "actions/0/body/0", "actions/0/body/1",
                "actions/0/body/0", "actions/0/body/1"), sink.blockIds,
                "多动作 body 每轮内部按序，整体重复 count 次");
    }

    @Test
    void repeat_withTailAction_runsAfterAllRounds() {
        ScriptRunner r = runner();
        r.submit(WALL, rule("r1", List.of(
                new Action.Repeat(2, List.of(new Action.Log("loop"))),
                new Action.Log("tail"))), ctx(0));
        assertEquals(List.of("actions/0/body/0", "actions/0/body/0", "actions/1"),
                sink.blockIds, "循环展开后外层后续动作正常执行");
    }

    @Test
    void repeat_bodyWithWait_continuesAcrossSegments() {
        ScriptRunner r = runner();
        // repeat 2x [log, wait, log] → 第一轮 log 后遇 wait 挂起；续接跑完所有轮 + 续接段
        r.submit(WALL, rule("r1", List.of(
                new Action.Repeat(2, List.of(
                        new Action.Log("a"), new Action.Wait(100), new Action.Log("b"))))), ctx(0));
        // wait 前只执行了第一轮 body[0]
        assertEquals(List.of("actions/0/body/0"), sink.blockIds, "首轮 wait 前 body[0]");
        assertEquals(List.of(100L), scheduler.scheduledDelays);
        scheduler.runPending();
        // 续接：第一轮 body[2] + 第二轮 body[0] body[2]（第二轮的 wait 也续接）
        assertTrue(sink.blockIds.contains("actions/0/body/2"), "wait 续接执行 body[2]");
        assertTrue(scheduler.scheduledDelays.size() >= 1, "第二轮 wait 也挂起");
    }

    @Test
    void repeat_nestedInIf_thenBranch() {
        ScriptRunner r = runner();
        Action.If iff = new Action.If("var(\"user/score\") == 10",
                List.of(new Action.Repeat(2, List.of(new Action.Log("x")))),
                List.of());
        r.submit(WALL, rule("r1", List.of(iff)), ctx(0));
        // if then 内 repeat：blockId actions/0/then/0/body/0 重复 2 次
        assertEquals(List.of("actions/0/then/0/body/0", "actions/0/then/0/body/0"),
                sink.blockIds, "if 分支内 repeat 树路径 + 展开");
    }

    @Test
    void repeat_100x2Actions_hitsMaxActionsBudget_blocked() {
        // 默认 max-actions-per-run=50。repeat 自身计 1 + 100×[2 动作] 展开 → 累计撞 50 熔断
        ScriptRunner r = runner();
        r.submit(WALL, rule("r1", List.of(
                new Action.Repeat(100, List.of(new Action.Log("a"), new Action.Log("b"))))), ctx(0));
        // repeat 自身占第 1 个动作额度，之后 body 动作累计到第 50 个掐断 → 执行 49 个 body 动作
        assertEquals(49, sink.blockIds.size(),
                "repeat(1) + 49 body 动作 = 50；第 50 个 body（总第 51）被掐断");
    }

    @Test
    void repeat_budgetCutoff_traceEndsWithBlocked() {
        ScriptRunner r = runner();
        List<List<TraceStep>> received = new ArrayList<>();
        r.submit(WALL, rule("r1", List.of(
                new Action.Repeat(100, List.of(new Action.Log("a"), new Action.Log("b"))))),
                ctx(0), received::add);
        assertEquals(1, received.size());
        List<TraceStep> steps = received.get(0);
        assertEquals("blocked", steps.get(steps.size() - 1).result(),
                "展开撞 max-actions → 末步 blocked（试跑 trace 可见）");
    }

    // ---------- 0.7.1：TRIGGER_DETAIL 透传 ----------

    @Test
    void triggerDetail_setDuringRun_clearedAfter() {
        ScriptRunner r = runner();
        List<String> seen = new ArrayList<>();
        sink.onExecute = () -> seen.add(ScriptRunner.TRIGGER_DETAIL.get());
        ScriptRule ru = new ScriptRule("r1", WALL, true, "r-1",
                new Trigger.PlayerJoin(), List.of(new Action.Log("x")), "{}");
        r.submit(WALL, ru, new TriggerContext(TriggerContext.Source.PLAYER_JOIN, 0, "Alice"));
        assertEquals(List.of("Alice"), seen, "执行期 TRIGGER_DETAIL = ctx.detail");
        assertNull(ScriptRunner.TRIGGER_DETAIL.get(), "run 结束 finally 清 ThreadLocal");
        assertNull(ScriptRunner.currentTriggerDetail(), "无上下文时读到 null");
    }

    /** 触发玩家走独立 ThreadLocal（与 detail 分离），run 结束一并清。 */
    @Test
    void triggerPlayer_setDuringRun_clearedAfter() {
        ScriptRunner r = runner();
        List<String> seen = new ArrayList<>();
        sink.onExecute = () -> seen.add(ScriptRunner.TRIGGER_PLAYER.get());
        ScriptRule ru = new ScriptRule("r1", WALL, true, "r-1",
                new Trigger.PlayerKill(), List.of(new Action.Log("x")), "{}");
        // playerKill 形态：detail 是 victim→killer 拼接串，triggerPlayer 单独给击杀者
        r.submit(WALL, ru, new TriggerContext(TriggerContext.Source.PLAYER_KILL, 0,
                "Victim→Killer", "Killer"));
        assertEquals(List.of("Killer"), seen, "执行期 TRIGGER_PLAYER = ctx.triggerPlayer");
        assertNull(ScriptRunner.TRIGGER_PLAYER.get(), "run 结束 finally 清 ThreadLocal");
        assertNull(ScriptRunner.currentTriggerPlayer(), "无上下文时读到 null");
    }

    /** 没有触发玩家的来源（变量变化 / timer / wallReady）→ TRIGGER_PLAYER 恒 null。 */
    @Test
    void triggerPlayer_absentForNonPlayerSources() {
        ScriptRunner r = runner();
        List<String> seen = new ArrayList<>();
        seen.add("sentinel");
        sink.onExecute = () -> seen.add(ScriptRunner.TRIGGER_PLAYER.get());
        r.submit(WALL, rule("r1", List.of(new Action.Log("a"))),
                new TriggerContext(TriggerContext.Source.VARIABLE, 0, "user:w-1/score"));
        assertEquals(java.util.Arrays.asList("sentinel", null), seen,
                "变量触发没有触发玩家");
    }

    @Test
    void triggerDetail_waitContinuation_restored() {
        ScriptRunner r = runner();
        List<String> seen = new ArrayList<>();
        sink.onExecute = () -> seen.add(ScriptRunner.TRIGGER_DETAIL.get());
        r.submit(WALL, rule("r1", List.of(
                new Action.Log("a"), new Action.Wait(100), new Action.Log("b"))),
                new TriggerContext(TriggerContext.Source.PLAYER_JOIN, 0, "Bob"));
        scheduler.runPending();
        assertEquals(List.of("Bob", "Bob"), seen, "续接段 TRIGGER_DETAIL 延续");
        assertNull(ScriptRunner.TRIGGER_DETAIL.get());
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
        ac.haru.hikaricanvas.storage.AuditLog audit =
                new ac.haru.hikaricanvas.storage.AuditLog(null, LOG);
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
        assertTrue(step.detail().contains("rate"), step.detail());
    }

    @Test
    void traceCallback_chainBlocked_firesOnceWithBlockedTriggerStep() {
        ScriptRunner r = runner();
        List<List<TraceStep>> received = new ArrayList<>();
        r.submit(WALL, rule("r1", List.of(new Action.Log("x"))), ctx(8), received::add);
        assertEquals(1, received.size());
        assertEquals("blocked", received.get(0).get(0).result());
        assertTrue(received.get(0).get(0).detail().contains("chain-depth"),
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

    // ---------- E1：TweenBlock 续接早于落盘修复（ScriptRunner × TweenScheduler 集成） ----------

    /** 构建含一个 RectElement（x=0, y=0, w=100, h=50）的 ProjectState（补间 base）。 */
    private static ProjectState tweenBaseState(String elementId) {
        RectElement rect = new RectElement(elementId, 0, 0, 100, 50, 0,
                false, true, Fill.solid("#FF0000"), null, null, BlendMode.NORMAL, null);
        Layer layer = new Layer("l-1", "L", true, false, 1.0f, BlendMode.NORMAL, null,
                List.of(rect));
        return new ProjectState(1L,
                new ProjectState.Canvas(2, 2, Fill.solid("#FFFFFF")),
                null, List.of(layer), "l-1",
                new ProjectState.History(0, 0), null, null, null);
    }

    /** no-op TickerControl（renderStatic / clearStaticDiff 静默；isWallAnimating=false 走静态墙路径）。 */
    private static TickerControl noopTicker() {
        return new TickerControl() {
            @Override public AnimationTicker.Result play(String w, String t) { return AnimationTicker.Result.OK; }
            @Override public void pause(String w) {}
            @Override public AnimationTicker.Result seek(String w, String t, long ms) { return AnimationTicker.Result.OK; }
            @Override public boolean isWallAnimating(String w) { return false; }
            @Override public void invalidate(String w) {}
            @Override public void refreshAutoPlay(String w) {}
            @Override public void renderStatic(String wallId, ProjectState frame) {}
            @Override public void clearStaticDiff(String wallId) {}
        };
    }

    /**
     * E1 顺序承诺（端到端）：TweenBlock 移动元素 x（0→100），其后一个读 x 的动作（{@code Log}）必须在
     * 补间<b>末帧落盘之后</b>续接——证明续接不再早于落盘。用共享 {@code events} 记录「落盘 WRITE」与
     * 「续接动作 AFTER」的先后，断言顺序为 [WRITE, AFTER]，且 AFTER 落盘值是目标 x=100（非起始 0）。
     */
    @Test
    void e1_tweenBlock_continuationRunsAfterFinalApplyMany() {
        final List<String> events = new ArrayList<>();
        final AtomicLong tweenClock = new AtomicLong(0L);
        final ProjectState base = tweenBaseState("e-rect");

        // 真实 TweenScheduler（fake clock/applyFn/ticker/wallLoader）；applyFn 记 WRITE + 目标值
        TweenScheduler tween = new TweenScheduler(
                (wallId, blockId, elementId, rawPatch) -> {
                    events.add("WRITE:" + rawPatch);
                    return TraceStep.ok(blockId, "action", "ok");
                },
                noopTicker(), wallId -> base, tweenClock::get, 4, 60, LOG);

        // sink：续接动作执行时记 AFTER（TweenBlock 由 runner 拦截，不过 sink；唯一 sink execute 是 after）
        sink.onExecute = () -> events.add("AFTER");
        ScriptRunner r = runner();
        r.setTweenScheduler(tween);

        Action.SetElementProperties move = new Action.SetElementProperties(
                "e-rect", Map.of("x", "100"), "moveTo");
        Action.TweenBlock tb = new Action.TweenBlock(500L, Easing.LINEAR, List.of(move));
        r.submit(WALL, rule("r1", List.of(tb, new Action.Log("after"))), ctx(0));

        // 补间已注册 + 脚本挂起：after 还没执行，runner SES 也没排续接（等 TweenScheduler 回调）
        assertEquals(List.of(), sink.blockIds, "TweenBlock 挂起：after 未执行");
        assertEquals(0, scheduler.scheduledDelays.size(),
                "续接不再由 runner 自按 durationMs 定时（无 schedule）");
        assertTrue(events.isEmpty(), "尚未落盘 / 未续接");

        // 推进补间到末帧 → applyFn 落盘（WRITE）→ TweenScheduler 触发续接回调 → schedule(0) 到 runner SES
        tweenClock.set(500L);
        tween.tickForTest();
        assertEquals(List.of("WRITE:{x=100}"), events, "末帧先落盘（目标 x=100）");
        assertEquals(1, scheduler.scheduledDelays.size(), "续接回调把 runFrames schedule 到 runner SES");
        assertEquals(0L, scheduler.scheduledDelays.get(0), "续接以 0 延迟投递（已在末帧之后）");

        // 跑 runner SES 续接 → after 执行
        scheduler.runPending();
        assertEquals(List.of("WRITE:{x=100}", "AFTER"), events,
                "顺序承诺：落盘 WRITE 必先于续接动作 AFTER");
        assertEquals(List.of("actions/1"), sink.blockIds, "续接执行 after（actions/1）");
    }

    /**
     * E1：补间末帧 tick 异常（renderStatic 抛）时脚本<b>不永久挂起</b>——TweenScheduler 异常清理路径
     * 仍触发续接回调，after 照常执行。
     */
    @Test
    void e1_tweenBlock_tickException_doesNotHangScript() {
        final AtomicLong tweenClock = new AtomicLong(0L);
        final ProjectState base = tweenBaseState("e-rect");

        // 静态墙末帧会调 renderStatic → 抛 → 落到 tick() 的 Throwable 清理路径
        TickerControl throwingTicker = new TickerControl() {
            @Override public AnimationTicker.Result play(String w, String t) { return AnimationTicker.Result.OK; }
            @Override public void pause(String w) {}
            @Override public AnimationTicker.Result seek(String w, String t, long ms) { return AnimationTicker.Result.OK; }
            @Override public boolean isWallAnimating(String w) { return false; }
            @Override public void invalidate(String w) {}
            @Override public void refreshAutoPlay(String w) {}
            @Override public void renderStatic(String wallId, ProjectState frame) {
                throw new RuntimeException("boom render");
            }
            @Override public void clearStaticDiff(String wallId) {}
        };
        TweenScheduler tween = new TweenScheduler(
                (wallId, blockId, elementId, rawPatch) -> TraceStep.ok(blockId, "action", "ok"),
                throwingTicker, wallId -> base, tweenClock::get, 4, 60, LOG);

        ScriptRunner r = runner();
        r.setTweenScheduler(tween);
        Action.SetElementProperties move = new Action.SetElementProperties(
                "e-rect", Map.of("x", "100"), "moveTo");
        Action.TweenBlock tb = new Action.TweenBlock(500L, Easing.LINEAR, List.of(move));
        r.submit(WALL, rule("r1", List.of(tb, new Action.Log("after"))), ctx(0));
        assertEquals(List.of(), sink.blockIds, "挂起：after 未执行");

        tweenClock.set(500L);
        tween.tickForTest();   // renderStatic 抛 → 异常清理 → 触发续接
        scheduler.runPending();
        assertEquals(List.of("actions/1"), sink.blockIds, "补间异常也续接（脚本不永久挂起）");
    }

    /**
     * E1：续接回调恰一次——TweenBlock 后接续接动作只跑一次（不因末帧 + 潜在重复触发而双发）。
     */
    @Test
    void e1_tweenBlock_continuationFiresExactlyOnce() {
        final AtomicLong tweenClock = new AtomicLong(0L);
        final ProjectState base = tweenBaseState("e-rect");
        TweenScheduler tween = new TweenScheduler(
                (wallId, blockId, elementId, rawPatch) -> TraceStep.ok(blockId, "action", "ok"),
                noopTicker(), wallId -> base, tweenClock::get, 4, 60, LOG);

        ScriptRunner r = runner();
        r.setTweenScheduler(tween);
        Action.SetElementProperties move = new Action.SetElementProperties(
                "e-rect", Map.of("x", "100"), "moveTo");
        Action.TweenBlock tb = new Action.TweenBlock(500L, Easing.LINEAR, List.of(move));
        r.submit(WALL, rule("r1", List.of(tb, new Action.Log("after"))), ctx(0));

        tweenClock.set(500L);
        tween.tickForTest();
        tweenClock.set(600L);
        tween.tickForTest();   // active 已空，no-op；不应再次触发续接
        scheduler.runPending();
        assertEquals(1, sink.blockIds.stream().filter("actions/1"::equals).count(),
                "续接动作 after 恰执行一次");
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

    /** 记录调用序的 ActionSink；可指定某 blockId 抛异常 / 返 error（兜底 + playTimelineAwait 路径测试）。 */
    private static final class RecordingSink implements ActionSink {
        final List<String> blockIds = new ArrayList<>();
        final List<String> wallIds = new ArrayList<>();
        Runnable onExecute;
        String throwOn;
        /** 0.7.1：playTimelineAwait 续接路径——某 blockId execute 返 error step（不抛）。 */
        String errorOn;
        /** 0.7.1：timelineDurationMs 返回值（>0 → Runner 挂起续接）。 */
        long timelineDuration = 0L;

        @Override
        public TraceStep execute(String wallId, String blockId, Action action) {
            blockIds.add(blockId);
            wallIds.add(wallId);
            if (onExecute != null) onExecute.run();
            if (blockId.equals(throwOn)) {
                throw new IllegalStateException("boom");
            }
            if (blockId.equals(errorOn)) {
                return TraceStep.error(blockId, "forced error");
            }
            return TraceStep.ok(blockId, "action", null);
        }

        @Override
        public long timelineDurationMs(String wallId, String timelineId) {
            return timelineDuration;
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
