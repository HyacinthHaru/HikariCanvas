package moe.hikari.canvas.script.engine;

import moe.hikari.canvas.HikariCanvasConfig;
import moe.hikari.canvas.render.AnimationTicker;
import moe.hikari.canvas.script.Action;
import moe.hikari.canvas.script.ScriptRule;
import moe.hikari.canvas.script.ScriptStore;
import moe.hikari.canvas.script.Trigger;
import moe.hikari.canvas.storage.AuditLog;
import moe.hikari.canvas.storage.UserVariableDao;
import moe.hikari.canvas.variable.VarType;
import moe.hikari.canvas.variable.VariableInterpolator;
import moe.hikari.canvas.variable.VariableStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.7.0-P2-5：脚本引擎 MVP 端到端集成测试（照 {@code variable.EndToEndSmokeTest} 风格）。
 *
 * <p>全真内存装配链：真 VariableStore（fake dao）+ 真 ScriptStore（dao null）+ 真
 * ConditionEvaluator / ScriptRunner（trampoline 队列调度替身——submit 串行排队，与生产
 * 单线程 SES 同语义且防 ABA 同步递归爆栈）+ 真 ActionExecutor（fake TickerControl，
 * plugin null 直跑）+ 真 TriggerRouter（手动 tick timer 替身）。监听全接：
 * {@code variableStore.registerChangeListener(router::onVariableChange)} +
 * {@code scriptStore.addListener(router::rebuildWall)}。</p>
 *
 * <p>本测试防回归的是"装配链"——变量变化 → 路由 → 预算闸 → 条件 → 动作 → 再触发的
 * 完整闭环；个体模块 case 见各自单测。</p>
 */
class EndToEndScriptTest {

    private static final Logger LOG = Logger.getLogger("test");

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private VariableStore variableStore;
    private ScriptStore scriptStore;
    private FakeTickerControl ticker;
    private TrampolineScheduler scheduler;
    private RecordingBudget budget;
    private TriggerRouterTest.FakeTimerScheduler timers;
    private TriggerRouter router;

    @BeforeEach
    void setUp() {
        variableStore = new VariableStore(new FakeUserVariableDao(), w -> {});
        scriptStore = new ScriptStore(LOG, null, 16);
        ticker = new FakeTickerControl();
        scheduler = new TrampolineScheduler();
        budget = new RecordingBudget(HikariCanvasConfig.ScriptsConfig.defaults(), clock);
        ConditionEvaluator conditions = new ConditionEvaluator(LOG, variableStore);
        ActionExecutor executor = new ActionExecutor(variableStore, ticker,
                null, null, null, LOG);
        // AuditLog(null jdbi)：record() 内 NPE 自吞走 log fallback（0.4.10 P3-4 既有纪律）；
        // 装上它让 K5 限频判定路径被走到（经 RecordingBudget 侧证）。
        ScriptRunner runner = new ScriptRunner(conditions, executor, budget,
                new AuditLog(null, LOG), LOG, scheduler);
        timers = new TriggerRouterTest.FakeTimerScheduler();
        router = new TriggerRouter(scriptStore, runner,
                VariableInterpolator::resolveFullName, LOG, timers);
        // 全链监听接线（与 HikariCanvas.onEnable 装配一致）
        variableStore.registerChangeListener(router::onVariableChange);
        scriptStore.addListener(router::rebuildWall);
    }

    private ScriptRule addRule(String wallId, boolean enabled, Trigger trigger,
                               List<Action> actions) {
        // scriptStore.addListener 已接 rebuildWall → create 即自动建索引（全链）
        return scriptStore.create(wallId, new ScriptRule(null, null, enabled,
                "r", trigger, actions, "{}"));
    }

    private String value(String fullName) {
        return variableStore.get(fullName).map(v -> v.currentValue()).orElse(null);
    }

    // ── ① 变量变化 → 条件真 → setVariable + playTimeline ──────────────

    @Test
    void variableChange_conditionTrue_setsVariableAndPlaysTimeline() {
        variableStore.create("user:w-1", "score", VarType.NUMBER, "0", null);
        variableStore.create("user:w-1", "level", VarType.STRING, "", null);
        addRule("w-1", true, new Trigger.VariableChange("user/score"), List.of(
                new Action.If("var(\"user/score\") >= 10",
                        List.of(new Action.SetVariable("user/level", "up"),
                                new Action.PlayTimeline("tl-1", "play", null)),
                        List.of())));

        variableStore.setValue("user:w-1/score", "12", null);

        assertEquals("up", value("user:w-1/level"), "条件真 → setVariable 落地");
        assertEquals(List.of("w-1:tl-1"), ticker.played, "playTimeline 直达 ticker");
    }

    // ── ② 条件假 → 动作不执行 ────────────────────────────────────────

    @Test
    void conditionFalse_actionsSkipped() {
        variableStore.create("user:w-1", "score", VarType.NUMBER, "0", null);
        variableStore.create("user:w-1", "level", VarType.STRING, "", null);
        addRule("w-1", true, new Trigger.VariableChange("user/score"), List.of(
                new Action.If("var(\"user/score\") >= 10",
                        List.of(new Action.SetVariable("user/level", "up"),
                                new Action.PlayTimeline("tl-1", "play", null)),
                        List.of())));

        variableStore.setValue("user:w-1/score", "5", null);

        assertNull(value("user:w-1/level"), "条件假 → then 分支动作全跳过");
        assertTrue(ticker.played.isEmpty());
    }

    // ── ③ ABA 互触发链 → 链深掐断 + audit 侧证 ──────────────────────

    @Test
    void abaChain_cutAtMaxDepth_withAudit() {
        variableStore.create("user:w-1", "x", VarType.NUMBER, "0", null);
        variableStore.create("user:w-1", "y", VarType.NUMBER, "0", null);
        // 规则 A：x 变化 → 写 y；规则 B：y 变化 → 写 x —— 无限互触发环
        addRule("w-1", true, new Trigger.VariableChange("user/x"),
                List.of(new Action.SetVariable("user/y", "${var:user/x}")));
        addRule("w-1", true, new Trigger.VariableChange("user/y"),
                List.of(new Action.SetVariable("user/x", "${var:user/y}")));

        AtomicInteger xSets = new AtomicInteger();
        AtomicInteger ySets = new AtomicInteger();
        variableStore.registerChangeListener(e -> {
            if (e.type() != VariableStore.ChangeType.VALUE_SET) return;
            if (e.fullName().equals("user:w-1/x")) xSets.incrementAndGet();
            if (e.fullName().equals("user:w-1/y")) ySets.incrementAndGet();
        });

        variableStore.setValue("user:w-1/x", "1", null);   // 外部源：depth 0 起链

        // 链深演进：外部 set x(链外) → A@0 写 y → B@1 写 x → … → B@7 写 x →
        // 下一跳 depth 8 ≥ max(8) 被掐断。脚本 run 共 8 次（depth 0..7）。
        assertEquals(5, xSets.get(), "x = 外部 1 次 + B@depth1/3/5/7 共 4 次");
        assertEquals(4, ySets.get(), "y = A@depth0/2/4/6 共 4 次");
        assertEquals(1, budget.shouldAuditCalls, "链深掐断恰 1 次 → audit 限频判定走到");
        assertEquals(1, budget.shouldAuditAllowed, "SCRIPT_RUN_BLOCKED(chain) 放行 1 条");
    }

    // ── ④ timer 经调度器触发 ────────────────────────────────────────

    @Test
    void timerTrigger_firesViaScheduler() {
        variableStore.create("user:w-2", "ticks", VarType.NUMBER, "0", null);
        addRule("w-2", true, new Trigger.Timer(30),
                List.of(new Action.IncrementVariable("user/ticks", 1)));

        assertEquals(1, timers.entries.size(), "enabled timer 规则被调度");
        TriggerRouterTest.FakeTimerScheduler.Entry e = timers.entries.get(0);
        assertEquals(30_000L, e.initialDelayMs, "首次不立即（周期到才第一跑）");
        assertNull(value("user:w-2/ticks"), "调度 ≠ 执行");

        e.task.run();
        assertEquals("1", value("user:w-2/ticks"), "周期到 → increment 落地（default 0 起算）");
        e.task.run();
        assertEquals("2", value("user:w-2/ticks"));
    }

    // ── ⑤ disabled 规则不触发 ───────────────────────────────────────

    @Test
    void disabledRule_notTriggered() {
        variableStore.create("user:w-1", "score", VarType.NUMBER, "0", null);
        variableStore.create("user:w-1", "hits", VarType.NUMBER, "0", null);
        ScriptRule r = addRule("w-1", false, new Trigger.VariableChange("user/score"),
                List.of(new Action.IncrementVariable("user/hits", 1)));

        variableStore.setValue("user:w-1/score", "1", null);
        assertNull(value("user:w-1/hits"), "disabled → 不触发");

        // 启用后（store.setEnabled → listener → rebuildWall）即触发
        scriptStore.setEnabled("w-1", r.id(), true);
        variableStore.setValue("user:w-1/score", "2", null);
        assertEquals("1", value("user:w-1/hits"), "enable 经 listener 重建索引后触发");
    }

    // ── ⑥ wallReady 触发 enabled 规则 ───────────────────────────────

    @Test
    void wallReady_firesEnabledRules() {
        variableStore.create("user:w-3", "hits", VarType.NUMBER, "0", null);
        addRule("w-3", true, new Trigger.WallReady(),
                List.of(new Action.IncrementVariable("user/hits", 1)));
        addRule("w-3", false, new Trigger.WallReady(),
                List.of(new Action.IncrementVariable("user/hits", 1)));

        router.fireWallReady("w-3");
        assertEquals("1", value("user:w-3/hits"), "仅 enabled 的 wallReady 规则各跑一次");

        router.fireWallReadyAll(List.of("w-3", "w-none"));
        assertEquals("2", value("user:w-3/hits"), "fireWallReadyAll 逐墙触发；无规则墙 no-op");
    }

    // ── ⑦ 规则更新 → 索引跟随（store.addListener → rebuildWall 全链） ──

    @Test
    void ruleUpdated_indexFollows() {
        variableStore.create("user:w-1", "a", VarType.NUMBER, "0", null);
        variableStore.create("user:w-1", "b", VarType.NUMBER, "0", null);
        variableStore.create("user:w-1", "hits", VarType.NUMBER, "0", null);
        ScriptRule r = addRule("w-1", true, new Trigger.VariableChange("user/a"),
                List.of(new Action.IncrementVariable("user/hits", 1)));

        variableStore.setValue("user:w-1/a", "1", null);
        assertEquals("1", value("user:w-1/hits"));

        // 改 trigger fullName → store.update 通知 listener → rebuildWall 换索引
        scriptStore.update(r.id(), new ScriptRule(null, null, true, "r",
                new Trigger.VariableChange("user/b"),
                List.of(new Action.IncrementVariable("user/hits", 1)), "{}"));

        variableStore.setValue("user:w-1/a", "2", null);
        assertEquals("1", value("user:w-1/hits"), "旧 fullName 不再触发");
        variableStore.setValue("user:w-1/b", "1", null);
        assertEquals("2", value("user:w-1/hits"), "新 fullName 触发");
    }

    // ──────────────────────────────────────────────────────────
    //  fakes
    // ──────────────────────────────────────────────────────────

    /**
     * 队列式 trampoline 调度替身：submit 入队、最外层调用 drain 串行跑——与生产单线程
     * SES 同语义（脚本动作 setVariable 同步 fireChange 时下游 run 只入队不嵌套执行），
     * ABA 互触发链不会同步递归爆栈，且 CHAIN_DEPTH ThreadLocal 不被嵌套 run 的
     * finally 提前清掉。
     */
    private static final class TrampolineScheduler implements ScriptRunner.TaskScheduler {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        private final List<Runnable> delayed = new ArrayList<>();
        private boolean draining;

        @Override
        public void execute(Runnable task) {
            queue.add(task);
            drain();
        }

        @Override
        public void schedule(Runnable task, long delayMs) {
            delayed.add(task);
        }

        @Override
        public void shutdown() {
        }

        private void drain() {
            if (draining) return;
            draining = true;
            try {
                Runnable r;
                while ((r = queue.poll()) != null) {
                    r.run();
                }
            } finally {
                draining = false;
            }
        }
    }

    /** 记录 play 调用的 TickerControl 替身。 */
    private static final class FakeTickerControl implements TickerControl {
        final List<String> played = new ArrayList<>();

        @Override
        public AnimationTicker.Result play(String wallId, String timelineId) {
            played.add(wallId + ":" + timelineId);
            return AnimationTicker.Result.OK;
        }

        @Override
        public void pause(String wallId) {
        }

        @Override
        public AnimationTicker.Result seek(String wallId, String timelineId, long atMs) {
            return AnimationTicker.Result.OK;
        }

        @Override
        public boolean isWallAnimating(String wallId) {
            return false;
        }

        @Override
        public void invalidate(String wallId) {
        }

        @Override
        public void refreshAutoPlay(String wallId) {
        }
    }

    /** 记录 shouldAuditBlock 的 budget（照 ScriptRunnerTest.RecordingBudget）。 */
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

    /** 纯内存 dao 替身（照 variable.EndToEndSmokeTest.FakeUserVariableDao）。 */
    private static final class FakeUserVariableDao extends UserVariableDao {
        FakeUserVariableDao() {
            super(Logger.getLogger("test"), null);
        }

        @Override
        public void upsert(String wallId, String name, VarType type,
                           String defaultValue, String currentValue, String boundTo,
                           long createdAt, long updatedAt) {
        }

        @Override
        public void delete(String wallId, String name) {
        }

        @Override
        public List<Row> loadAll() {
            return new ArrayList<>();
        }
    }
}
