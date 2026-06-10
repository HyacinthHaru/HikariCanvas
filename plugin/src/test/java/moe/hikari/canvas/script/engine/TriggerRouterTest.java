package moe.hikari.canvas.script.engine;

import moe.hikari.canvas.HikariCanvasConfig;
import moe.hikari.canvas.script.Action;
import moe.hikari.canvas.script.ScriptRule;
import moe.hikari.canvas.script.ScriptStore;
import moe.hikari.canvas.script.Trigger;
import moe.hikari.canvas.variable.VariableInterpolator;
import moe.hikari.canvas.variable.VariableStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.7.0-P2-5：{@link TriggerRouter} 单元测试（索引 / timer 调度 / 链深读取 / 关停）。
 * 全 fake：内存 ScriptStore（dao null）+ 真 ScriptRunner（同步替身调度）+ 记录型 sink +
 * 手动 tick timer 替身。全链贯通（含真 VariableStore 事件）在 {@link EndToEndScriptTest}。
 */
class TriggerRouterTest {

    private static final Logger LOG = Logger.getLogger("test");
    private static final String WALL = "w-rt";

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private ScriptStore scriptStore;
    private RecordingSink sink;
    private ScriptRunner runner;
    private FakeTimerScheduler fakeTimers;
    private TriggerRouter router;

    @BeforeEach
    void setUp() {
        scriptStore = new ScriptStore(LOG, null, 16);
        sink = new RecordingSink();
        ScriptBudget budget = new ScriptBudget(
                HikariCanvasConfig.ScriptsConfig.defaults(), clock::get);
        ConditionEvaluator conditions = new ConditionEvaluator(LOG,
                (java.util.function.Function<String, String>) null);
        runner = new ScriptRunner(conditions, sink, budget, null, LOG, new InlineScheduler());
        fakeTimers = new FakeTimerScheduler();
        router = new TriggerRouter(scriptStore, runner,
                VariableInterpolator::resolveFullName, LOG, fakeTimers);
    }

    private ScriptRule addRule(String wallId, boolean enabled, Trigger trigger) {
        return scriptStore.create(wallId, new ScriptRule(null, null, enabled,
                "r", trigger, List.of(new Action.Log("x")), "{}"));
    }

    private static VariableStore.VariableChangeEvent event(String fullName,
                                                           VariableStore.ChangeType type) {
        return new VariableStore.VariableChangeEvent(fullName, null, type, Set.of());
    }

    // ---------- variableChange 索引 ----------

    @Test
    void variableChange_indexedWithWallInjection_andFires() {
        addRule(WALL, true, new Trigger.VariableChange("user/score"));
        router.rebuildWall(WALL);
        // user/score 经 resolveFullName 注入 wallId → user:w-rt/score
        assertEquals(1, router.bindingCount("user:" + WALL + "/score"));
        router.onVariableChange(event("user:" + WALL + "/score",
                VariableStore.ChangeType.VALUE_SET));
        assertEquals(1, sink.blockIds.size(), "VALUE_SET 命中索引 → 投递执行");
        router.onVariableChange(event("user:" + WALL + "/other",
                VariableStore.ChangeType.VALUE_SET));
        assertEquals(1, sink.blockIds.size(), "无关 fullName 不触发");
    }

    @Test
    void changeTypeFilter_onlyValueSetUpdatedCreated() {
        addRule(WALL, true, new Trigger.VariableChange("user/score"));
        router.rebuildWall(WALL);
        String fn = "user:" + WALL + "/score";
        router.onVariableChange(event(fn, VariableStore.ChangeType.DELETED));
        router.onVariableChange(event(fn, VariableStore.ChangeType.BOUND));
        router.onVariableChange(event(fn, VariableStore.ChangeType.WALL_REFS_UPDATED));
        assertEquals(0, sink.blockIds.size(), "DELETED / BOUND / WALL_REFS_UPDATED 不触发");
        router.onVariableChange(event(fn, VariableStore.ChangeType.CREATED));
        router.onVariableChange(event(fn, VariableStore.ChangeType.UPDATED));
        assertEquals(2, sink.blockIds.size(), "CREATED / UPDATED 触发");
    }

    @Test
    void disabledRule_notIndexed_notScheduled() {
        addRule(WALL, false, new Trigger.VariableChange("user/score"));
        ScriptRule timer = addRule(WALL, false, new Trigger.Timer(30));
        router.rebuildWall(WALL);
        assertEquals(0, router.bindingCount("user:" + WALL + "/score"));
        assertFalse(router.hasTimer(WALL, timer.id()));
        assertEquals(0, fakeTimers.entries.size());
    }

    @Test
    void staleIndexEntry_ruleFetchedFromStore_disabledSkipped() {
        ScriptRule r = addRule(WALL, true, new Trigger.VariableChange("user/score"));
        router.rebuildWall(WALL);
        // 索引未重建（不经 listener），store 内已禁用 → 触发时刻 find 最新 → 跳过
        scriptStore.setEnabled(WALL, r.id(), false);
        router.onVariableChange(event("user:" + WALL + "/score",
                VariableStore.ChangeType.VALUE_SET));
        assertEquals(0, sink.blockIds.size(), "stale 索引条目不执行已禁用规则");
    }

    // ---------- 链深读取（K1） ----------

    @Test
    void chainDepth_nullThreadLocal_isDepth0_runnerThreadIsPlus1() {
        addRule(WALL, true, new Trigger.VariableChange("user/score"));
        router.rebuildWall(WALL);
        String fn = "user:" + WALL + "/score";
        // 非脚本来源：CHAIN_DEPTH null → depth 0 → maxChainDepth(8) 内放行
        router.onVariableChange(event(fn, VariableStore.ChangeType.VALUE_SET));
        assertEquals(1, sink.blockIds.size());
        // 模拟 runner 线程执行态（CHAIN_DEPTH=7）→ depth 8 ≥ max(8) → 掐断
        ScriptRunner.CHAIN_DEPTH.set(7);
        try {
            router.onVariableChange(event(fn, VariableStore.ChangeType.VALUE_SET));
            assertEquals(1, sink.blockIds.size(), "depth 7+1=8 被链深闸掐断");
            ScriptRunner.CHAIN_DEPTH.set(6);
            router.onVariableChange(event(fn, VariableStore.ChangeType.VALUE_SET));
            assertEquals(2, sink.blockIds.size(), "depth 6+1=7 放行");
        } finally {
            ScriptRunner.CHAIN_DEPTH.remove();
        }
    }

    // ---------- timer ----------

    @Test
    void timer_scheduledAtFixedRate_firstFireAfterPeriod() {
        ScriptRule r = addRule(WALL, true, new Trigger.Timer(30));
        router.rebuildWall(WALL);
        assertTrue(router.hasTimer(WALL, r.id()));
        assertEquals(1, fakeTimers.entries.size());
        FakeTimerScheduler.Entry e = fakeTimers.entries.get(0);
        assertEquals(30_000L, e.initialDelayMs, "首次不立即（initialDelay = period）");
        assertEquals(30_000L, e.periodMs);
        assertEquals(0, sink.blockIds.size(), "调度 ≠ 执行");
        e.task.run();
        assertEquals(1, sink.blockIds.size(), "周期到 → store.find 最新规则 → 投递");
    }

    @Test
    void timer_ruleDeletedInStore_cancelsSelfOnFire() {
        ScriptRule r = addRule(WALL, true, new Trigger.Timer(30));
        router.rebuildWall(WALL);
        FakeTimerScheduler.Entry e = fakeTimers.entries.get(0);
        scriptStore.delete(WALL, r.id());   // 不经 listener rebuild（测兜底自清路径）
        assertDoesNotThrow(e.task::run);
        assertEquals(0, sink.blockIds.size(), "规则没了 → 不投递");
        assertTrue(e.future.cancelled, "到期发现规则没了 → cancel 自己");
        assertFalse(router.hasTimer(WALL, r.id()));
    }

    @Test
    void rebuildWall_cancelsOldTimer_reschedulesNew() {
        ScriptRule r = addRule(WALL, true, new Trigger.Timer(30));
        router.rebuildWall(WALL);
        FakeTimerScheduler.Entry old = fakeTimers.entries.get(0);
        scriptStore.update(r.id(), new ScriptRule(null, null, true, "r",
                new Trigger.Timer(60), List.of(new Action.Log("x")), "{}"));
        router.rebuildWall(WALL);
        assertTrue(old.future.cancelled, "旧周期任务被 cancel");
        assertEquals(2, fakeTimers.entries.size());
        assertEquals(60_000L, fakeTimers.entries.get(1).periodMs, "新间隔重新调度");
    }

    // ---------- rebuild / removeWall 清理 ----------

    @Test
    void rebuildWall_replacesOldIndexContribution() {
        ScriptRule r = addRule(WALL, true, new Trigger.VariableChange("user/a"));
        router.rebuildWall(WALL);
        assertEquals(1, router.bindingCount("user:" + WALL + "/a"));
        scriptStore.update(r.id(), new ScriptRule(null, null, true, "r",
                new Trigger.VariableChange("user/b"),
                List.of(new Action.Log("x")), "{}"));
        router.rebuildWall(WALL);
        assertEquals(0, router.bindingCount("user:" + WALL + "/a"), "旧 fullName 条目清掉");
        assertEquals(1, router.bindingCount("user:" + WALL + "/b"));
    }

    @Test
    void removeWall_clearsIndexAndCancelsTimers() {
        addRule(WALL, true, new Trigger.VariableChange("user/a"));
        ScriptRule timer = addRule(WALL, true, new Trigger.Timer(30));
        addRule("w-other", true, new Trigger.VariableChange("user/a"));
        router.rebuildWall(WALL);
        router.rebuildWall("w-other");
        router.removeWall(WALL);
        assertEquals(0, router.bindingCount("user:" + WALL + "/a"));
        assertEquals(1, router.bindingCount("user:w-other/a"), "别的墙不受影响");
        assertFalse(router.hasTimer(WALL, timer.id()));
        assertTrue(fakeTimers.entries.get(0).future.cancelled);
    }

    // ---------- wallReady ----------

    @Test
    void fireWallReady_onlyEnabledWallReadyRules() {
        addRule(WALL, true, new Trigger.WallReady());
        addRule(WALL, false, new Trigger.WallReady());
        addRule(WALL, true, new Trigger.Timer(30));
        router.fireWallReady(WALL);
        assertEquals(1, sink.blockIds.size(), "仅 enabled 的 wallReady 规则投递一次");
        router.fireWallReadyAll(List.of(WALL, "w-none"));
        assertEquals(2, sink.blockIds.size(), "fireWallReadyAll 逐墙触发；无规则墙 no-op");
    }

    // ---------- shutdown ----------

    @Test
    void shutdown_idempotent_allEntrancesNoop() {
        addRule(WALL, true, new Trigger.VariableChange("user/a"));
        ScriptRule timer = addRule(WALL, true, new Trigger.Timer(30));
        addRule(WALL, true, new Trigger.WallReady());
        router.rebuildWall(WALL);
        router.shutdown();
        assertDoesNotThrow(router::shutdown, "幂等");
        assertEquals(1, fakeTimers.shutdownCount, "底层调度只关一次");
        assertTrue(fakeTimers.entries.get(0).future.cancelled);
        assertFalse(router.hasTimer(WALL, timer.id()));
        router.onVariableChange(event("user:" + WALL + "/a",
                VariableStore.ChangeType.VALUE_SET));
        router.fireWallReady(WALL);
        router.rebuildWall(WALL);
        assertEquals(0, sink.blockIds.size(), "关停后全入口 no-op");
        assertEquals(0, router.bindingCount("user:" + WALL + "/a"), "关停后 rebuild 不重建索引");
    }

    // ---------- fakes ----------

    /** 同步直跑调度替身（照 ScriptRunnerTest.InlineScheduler）。 */
    private static final class InlineScheduler implements ScriptRunner.TaskScheduler {
        final Deque<Runnable> pending = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public void schedule(Runnable task, long delayMs) {
            pending.add(task);
        }

        @Override
        public void shutdown() {
        }
    }

    /** 记录调用的 ActionSink。 */
    private static final class RecordingSink implements ActionSink {
        final List<String> blockIds = new ArrayList<>();

        @Override
        public TraceStep execute(String wallId, String blockId, Action action) {
            blockIds.add(blockId);
            return TraceStep.ok(blockId, "action", null);
        }
    }

    /** 手动 tick 的 timer 调度替身。 */
    static final class FakeTimerScheduler implements TriggerRouter.TimerScheduler {
        static final class Entry {
            final Runnable task;
            final long initialDelayMs;
            final long periodMs;
            final FakeFuture future = new FakeFuture();

            Entry(Runnable task, long initialDelayMs, long periodMs) {
                this.task = task;
                this.initialDelayMs = initialDelayMs;
                this.periodMs = periodMs;
            }
        }

        final List<Entry> entries = new ArrayList<>();
        int shutdownCount = 0;

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task,
                                                      long initialDelayMs, long periodMs) {
            Entry e = new Entry(task, initialDelayMs, periodMs);
            entries.add(e);
            return e.future;
        }

        @Override
        public void shutdown() {
            shutdownCount++;
        }
    }

    /** 只记录 cancel 的 ScheduledFuture 替身。 */
    static final class FakeFuture implements ScheduledFuture<Object> {
        volatile boolean cancelled;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled;
        }

        @Override
        public Object get() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed o) {
            return 0;
        }
    }
}
