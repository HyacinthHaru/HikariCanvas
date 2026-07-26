package ac.haru.hikaricanvas.script.engine;

import ac.haru.hikaricanvas.HikariCanvasConfig;
import ac.haru.hikaricanvas.script.Action;
import ac.haru.hikaricanvas.script.ScriptRule;
import ac.haru.hikaricanvas.script.Trigger;
import ac.haru.hikaricanvas.state.BlendMode;
import ac.haru.hikaricanvas.state.EditSession;
import ac.haru.hikaricanvas.state.Fill;
import ac.haru.hikaricanvas.state.Layer;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.state.RenderMode;
import ac.haru.hikaricanvas.state.TextElement;
import ac.haru.hikaricanvas.storage.UserVariableDao;
import ac.haru.hikaricanvas.variable.VarType;
import ac.haru.hikaricanvas.variable.VariableStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.7.3 四个新积木的行为测试：
 * - G1 RandomBranch：rng 注入确定性 + 两臂选择
 * - G2 SetElementLayer：置顶/置底经 session seam 落地 + EditSession reorder
 * - G3 RoundVariable：三种取整模式
 * - G4 ShowTitle：executor 分支（无 plugin 直跑路径）
 */
class Action073BehaviorTest {

    private static final String WALL = "w-1";
    private static final Logger LOG = Logger.getLogger("Action073BehaviorTest");

    private VariableStore store;

    @BeforeEach
    void setUp() {
        store = new VariableStore(new NoopDao(), w -> {});
        store.create("user:" + WALL, "score", VarType.NUMBER, "10", null);
        store.create("user:" + WALL, "flag", VarType.STRING, "", null);
    }

    private String valueOf(String fullName) {
        return store.get(fullName).orElseThrow().currentValue();
    }

    // ────────── G1 RandomBranch via ScriptRunner rng seam ──────────

    /**
     * rng 恒返 20 → 20 < 50 = true → then 分支执行。
     */
    @Test
    void randomBranch_thenPicked_whenRngBelowProbability() {
        ScriptRunner runner = buildRunner();
        runner.setRngForTest(() -> 20);   // 20 < 50 → then

        runRule(runner, new Action.RandomBranch(50,
                List.of(new Action.SetVariable("user/flag", "then")),
                List.of(new Action.SetVariable("user/flag", "else"))));

        assertEquals("then", valueOf("user:" + WALL + "/flag"));
    }

    /**
     * rng 恒返 80 → 80 < 50 = false → else 分支执行。
     */
    @Test
    void randomBranch_elsePicked_whenRngAboveProbability() {
        ScriptRunner runner = buildRunner();
        runner.setRngForTest(() -> 80);

        runRule(runner, new Action.RandomBranch(50,
                List.of(new Action.SetVariable("user/flag", "then")),
                List.of(new Action.SetVariable("user/flag", "else"))));

        assertEquals("else", valueOf("user:" + WALL + "/flag"));
    }

    /**
     * probability=100 → rng 任何值 (0..99) < 100 → 恒走 then。
     */
    @Test
    void randomBranch_alwaysThen_probability100() {
        ScriptRunner runner = buildRunner();
        runner.setRngForTest(() -> 99);   // 最大合法随机值

        runRule(runner, new Action.RandomBranch(100,
                List.of(new Action.SetVariable("user/flag", "certain")),
                List.of(new Action.SetVariable("user/flag", "never"))));

        assertEquals("certain", valueOf("user:" + WALL + "/flag"));
    }

    /**
     * probability=0 → rng 返 0，但 0 < 0 = false → 恒走 else。
     */
    @Test
    void randomBranch_neverThen_probability0() {
        ScriptRunner runner = buildRunner();
        runner.setRngForTest(() -> 0);    // 0 < 0 = false

        runRule(runner, new Action.RandomBranch(0,
                List.of(new Action.SetVariable("user/flag", "never")),
                List.of(new Action.SetVariable("user/flag", "always"))));

        assertEquals("always", valueOf("user:" + WALL + "/flag"));
    }

    // ────────── G2 SetElementLayer via ActionExecutor ──────────

    @Test
    void setElementLayer_noApplier_error() {
        ActionExecutor ex = new ActionExecutor(store, null, null, null, null, LOG);
        TraceStep step = ex.execute(WALL, "actions/0",
                new Action.SetElementLayer("el-1", "front"));
        assertEquals("error", step.result());
        assertTrue(step.detail().contains("ElementPropertyApplier"), step.detail());
    }

    @Test
    void setElementLayer_front_dispatchesToReorderToEdge() {
        List<String> calls = new ArrayList<>();
        ElementPropertyApplier applier = new ElementPropertyApplier(
                new ElementPropertyApplier.SessionPatchApplier() {
                    @Override
                    public ElementPropertyApplier.SessionOutcome apply(
                            String w, String e, Map<String, Object> p) {
                        throw new AssertionError("不应走 apply()");
                    }

                    @Override
                    public ElementPropertyApplier.SessionOutcome reorderToEdge(
                            String w, String e, String mode) {
                        calls.add(e + ":" + mode);
                        return ElementPropertyApplier.SessionOutcome.applied();
                    }
                }, null, null, LOG);
        ActionExecutor ex = new ActionExecutor(store, null, applier, null, null, LOG);
        TraceStep step = ex.execute(WALL, "actions/0",
                new Action.SetElementLayer("el-3", "front"));
        assertEquals("ok", step.result(), step::detail);
        assertEquals(List.of("el-3:front"), calls);
    }

    @Test
    void setElementLayer_back_dispatchesToReorderToEdge() {
        List<String> calls = new ArrayList<>();
        ElementPropertyApplier applier = new ElementPropertyApplier(
                new ElementPropertyApplier.SessionPatchApplier() {
                    @Override
                    public ElementPropertyApplier.SessionOutcome apply(
                            String w, String e, Map<String, Object> p) {
                        throw new AssertionError("不应走 apply()");
                    }

                    @Override
                    public ElementPropertyApplier.SessionOutcome reorderToEdge(
                            String w, String e, String mode) {
                        calls.add(e + ":" + mode);
                        return ElementPropertyApplier.SessionOutcome.applied();
                    }
                }, null, null, LOG);
        ActionExecutor ex = new ActionExecutor(store, null, applier, null, null, LOG);
        TraceStep step = ex.execute(WALL, "actions/0",
                new Action.SetElementLayer("el-5", "back"));
        assertEquals("ok", step.result(), step::detail);
        assertEquals(List.of("el-5:back"), calls);
    }

    // ────────── G2 EditSession.moveElementToFront/Back ──────────

    @Test
    void editSession_moveToFront_movesElementToEnd() {
        ProjectState state = stateWith3Elements();
        EditSession es = new EditSession(state);
        EditSession.OpResult r = es.moveElementToFront("el-0");
        assertInstanceOf(EditSession.OpResult.Ok.class, r);
        var layer = es.state().layers().get(0);
        assertEquals("el-0", layer.elements().get(2).id(), "el-0 应在末尾（index=2）");
        assertEquals("el-1", layer.elements().get(0).id());
        assertEquals("el-2", layer.elements().get(1).id());
    }

    @Test
    void editSession_moveToBack_movesElementToStart() {
        ProjectState state = stateWith3Elements();
        EditSession es = new EditSession(state);
        EditSession.OpResult r = es.moveElementToBack("el-2");
        assertInstanceOf(EditSession.OpResult.Ok.class, r);
        var layer = es.state().layers().get(0);
        assertEquals("el-2", layer.elements().get(0).id(), "el-2 应在开头（index=0）");
        assertEquals("el-0", layer.elements().get(1).id());
        assertEquals("el-1", layer.elements().get(2).id());
    }

    @Test
    void editSession_moveToFront_alreadyFront_idempotent() {
        ProjectState state = stateWith3Elements();
        EditSession es = new EditSession(state);
        EditSession.OpResult r = es.moveElementToFront("el-2");  // 已在末尾
        assertInstanceOf(EditSession.OpResult.Ok.class, r);
        var layer = es.state().layers().get(0);
        assertEquals("el-2", layer.elements().get(2).id(), "已在末尾，顺序不变");
    }

    @Test
    void editSession_moveToFront_missingElement_error() {
        EditSession es = new EditSession(stateWith3Elements());
        EditSession.OpResult r = es.moveElementToFront("no-such");
        assertInstanceOf(EditSession.OpResult.Error.class, r);
        assertEquals("INVALID_ELEMENT",
                ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void editSession_moveToBack_missingElement_error() {
        EditSession es = new EditSession(stateWith3Elements());
        EditSession.OpResult r = es.moveElementToBack("no-such");
        assertInstanceOf(EditSession.OpResult.Error.class, r);
        assertEquals("INVALID_ELEMENT",
                ((EditSession.OpResult.Error) r).code());
    }

    // ────────── G3 RoundVariable ──────────

    @Test
    void roundVariable_round() {
        store.create("user:" + WALL, "val", VarType.NUMBER, "3.7", null);
        ActionExecutor ex = new ActionExecutor(store, null, null, null, null, LOG);
        TraceStep step = ex.execute(WALL, "a/0",
                new Action.RoundVariable("user/val", "round"));
        assertEquals("ok", step.result(), step::detail);
        assertEquals("4", valueOf("user:" + WALL + "/val"));
    }

    @Test
    void roundVariable_floor() {
        store.create("user:" + WALL, "val2", VarType.NUMBER, "3.9", null);
        ActionExecutor ex = new ActionExecutor(store, null, null, null, null, LOG);
        TraceStep step = ex.execute(WALL, "a/0",
                new Action.RoundVariable("user/val2", "floor"));
        assertEquals("ok", step.result(), step::detail);
        assertEquals("3", valueOf("user:" + WALL + "/val2"));
    }

    @Test
    void roundVariable_ceil() {
        store.create("user:" + WALL, "val3", VarType.NUMBER, "3.1", null);
        ActionExecutor ex = new ActionExecutor(store, null, null, null, null, LOG);
        TraceStep step = ex.execute(WALL, "a/0",
                new Action.RoundVariable("user/val3", "ceil"));
        assertEquals("ok", step.result(), step::detail);
        assertEquals("4", valueOf("user:" + WALL + "/val3"));
    }

    @Test
    void roundVariable_negative_floor() {
        store.create("user:" + WALL, "neg", VarType.NUMBER, "-2.7", null);
        ActionExecutor ex = new ActionExecutor(store, null, null, null, null, LOG);
        ex.execute(WALL, "a/0", new Action.RoundVariable("user/neg", "floor"));
        assertEquals("-3", valueOf("user:" + WALL + "/neg"));
    }

    @Test
    void roundVariable_nonNumeric_error() {
        store.create("user:" + WALL, "text", VarType.STRING, "abc", null);
        ActionExecutor ex = new ActionExecutor(store, null, null, null, null, LOG);
        TraceStep step = ex.execute(WALL, "a/0",
                new Action.RoundVariable("user/text", "round"));
        assertEquals("error", step.result());
        assertTrue(step.detail().contains("not numeric"), step.detail());
    }

    @Test
    void roundVariable_noStore_error() {
        ActionExecutor ex = new ActionExecutor(null, null, null, null, null, LOG);
        TraceStep step = ex.execute(WALL, "a/0",
                new Action.RoundVariable("user/x", "round"));
        assertEquals("error", step.result());
    }

    // ────────── G4 ShowTitle executor（plugin=null 直跑路径）──────────

    /**
     * target=all：主线程 hop Runnable 直跑（plugin=null），Bukkit.getOnlinePlayers()
     * 在测试环境会抛，但被 catch Throwable 吞掉 → step 依然 ok。
     */
    @Test
    void showTitle_all_stepOk_evenIfBukkitThrows() {
        ActionExecutor ex = new ActionExecutor(store, null, null, null, null, LOG);
        TraceStep step = ex.execute(WALL, "a/0",
                new Action.ShowTitle("Big News", "Sub", 500, 2000, 500, "all"));
        assertEquals("ok", step.result(), step::detail);
        assertTrue(step.detail().contains("broadcast"), step.detail());
    }

    /**
     * target=trigger，无触发玩家（TRIGGER_PLAYER ThreadLocal 为空）→ 跳过，step ok。
     */
    @Test
    void showTitle_trigger_noTriggerPlayer_skipOk() {
        ActionExecutor ex = new ActionExecutor(store, null, null, null, null, LOG);
        TraceStep step = ex.execute(WALL, "a/0",
                new Action.ShowTitle("Hi", "there", 200, 3000, 200, "trigger"));
        assertEquals("ok", step.result(), step::detail);
        assertTrue(step.detail().contains("skipped"), step.detail());
    }

    // ────────── helpers ──────────

    /** 构建含 3 TextElement 的最简 ProjectState（照 ElementPropertyApplierTest.stateWithText 范式）。 */
    private static ProjectState stateWith3Elements() {
        TextElement e0 = new TextElement("el-0", 10, 10, 100, 50, 0, false, true,
                "A", "inter", 24, "#000000", "left", 0f, 1.2f, false, null,
                1.0f, BlendMode.NORMAL, RenderMode.CLEAN, null, null);
        TextElement e1 = new TextElement("el-1", 10, 70, 100, 50, 0, false, true,
                "B", "inter", 24, "#000000", "left", 0f, 1.2f, false, null,
                1.0f, BlendMode.NORMAL, RenderMode.CLEAN, null, null);
        TextElement e2 = new TextElement("el-2", 10, 130, 100, 50, 0, false, true,
                "C", "inter", 24, "#000000", "left", 0f, 1.2f, false, null,
                1.0f, BlendMode.NORMAL, RenderMode.CLEAN, null, null);
        Layer layer = new Layer("l-1", "L", true, false, 1.0f, BlendMode.NORMAL, null,
                new ArrayList<>(List.of(e0, e1, e2)));
        return new ProjectState(1L,
                new ProjectState.Canvas(2, 2, Fill.solid("#FFFFFF")),
                null, new ArrayList<>(List.of(layer)), "l-1",
                new ProjectState.History(0, 0), null, null, null);
    }

    /** 构建配置了 store 的 ScriptRunner（trampoline 调度，同步直跑）。 */
    private ScriptRunner buildRunner() {
        ActionExecutor executor = new ActionExecutor(store, null, null, null, null, LOG);
        ScriptBudget budget = new ScriptBudget(
                HikariCanvasConfig.ScriptsConfig.defaults(),
                new AtomicLong(1_000_000L)::get);
        ConditionEvaluator cond = new ConditionEvaluator(LOG, store);
        return new ScriptRunner(cond, executor, budget, null, LOG, new TrampolineScheduler());
    }

    /** 单条规则直接提交运行（trampoline → 同步完成）。 */
    private void runRule(ScriptRunner runner, Action action) {
        ScriptRule rule = new ScriptRule("r-1", WALL, true, "test",
                new Trigger.VariableChange("user/score"),
                List.of(action), "{}");
        runner.submit(WALL, rule, new TriggerContext(TriggerContext.Source.TEST, 0, null), null);
    }

    /** 队列式 trampoline 调度替身（照 EndToEndScriptTest 内部类逻辑，这里独立复制）。 */
    private static final class TrampolineScheduler implements ScriptRunner.TaskScheduler {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        private boolean draining;

        @Override
        public void execute(Runnable task) {
            queue.add(task);
            drain();
        }

        @Override
        public void schedule(Runnable task, long delayMs) {
            // wait 续接：本测试不涉及 wait，静默忽略
        }

        @Override
        public void shutdown() {}

        private void drain() {
            if (draining) return;
            draining = true;
            try {
                Runnable r;
                while ((r = queue.poll()) != null) r.run();
            } finally {
                draining = false;
            }
        }
    }

    /** no-op VariableDao（照 ActionExecutorTest.NoopDao 范式）。 */
    private static final class NoopDao extends UserVariableDao {
        NoopDao() {
            super(Logger.getLogger("test"), null);
        }

        @Override
        public void upsert(String wallId, String name, VarType type,
                           String defaultValue, String currentValue, String boundTo,
                           long createdAt, long updatedAt) {}

        @Override
        public void delete(String wallId, String name) {}
    }
}
