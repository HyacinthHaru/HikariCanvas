package ac.haru.hikaricanvas.script.engine;

import ac.haru.hikaricanvas.HikariCanvasConfig;
import ac.haru.hikaricanvas.HikariCanvasConfig.CommandTemplate;
import ac.haru.hikaricanvas.HikariCanvasConfig.ParamSpec;
import ac.haru.hikaricanvas.render.AnimationTicker;
import ac.haru.hikaricanvas.script.Action;
import ac.haru.hikaricanvas.script.ScriptRule;
import ac.haru.hikaricanvas.script.Trigger;
import ac.haru.hikaricanvas.session.WallKey;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.storage.AuditLog;
import ac.haru.hikaricanvas.storage.Database;
import ac.haru.hikaricanvas.storage.MigrationRunner;
import ac.haru.hikaricanvas.storage.UserGlobalVariableDao;
import ac.haru.hikaricanvas.storage.WallRepo;
import ac.haru.hikaricanvas.variable.NoopUserVariableDao;
import ac.haru.hikaricanvas.variable.VarType;
import ac.haru.hikaricanvas.variable.VariableStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ActionExecutor} 动作执行、命令审计与变量越权守卫单元测试。
 */
class ActionExecutorTest {

    private static final String WALL = "w-1";

    private Logger log;
    private List<LogRecord> logRecords;
    private VariableStore store;
    private ElementPropertyApplierTest.FakeTicker ticker;

    @BeforeEach
    void setUp() {
        log = Logger.getLogger("ActionExecutorTest-" + System.nanoTime());
        log.setUseParentHandlers(false);
        log.setLevel(Level.ALL);
        logRecords = new ArrayList<>();
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logRecords.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        store = new VariableStore(new NoopUserVariableDao(), w -> {
        });
        ticker = new ElementPropertyApplierTest.FakeTicker();
    }

    private ActionExecutor executor() {
        return new ActionExecutor(store, ticker, null, null, null, log);
    }

    private ActionExecutor bare() {
        return new ActionExecutor(null, null, null, null, null, log);
    }

    /** 注入确定性随机源（setRandomVariable 测试用）。 */
    private ActionExecutor executorWithRng(RandomGenerator rng) {
        ActionExecutor ex = new ActionExecutor(store, ticker, null, null, null, log);
        ex.setRngForTest(rng);
        return ex;
    }

    private String valueOf(String fullName) {
        return store.get(fullName).orElseThrow().currentValue();
    }

    // ---------- setVariable ----------

    @Test
    void setVariable_setsValue_withWallNamespaceInjection() {
        store.create("user:w-1", "score", VarType.NUMBER, "0", "manual");
        TraceStep step = executor().execute(WALL, "b",
                new Action.SetVariable("user/score", "5"));
        assertEquals("ok", step.result(), () -> String.valueOf(step.detail()));
        assertEquals("5", valueOf("user:w-1/score"));
    }

    @Test
    void setVariable_interpolatesPlaceholders() {
        store.create("user:w-1", "name", VarType.STRING, null, "manual");
        store.setValue("user:w-1/name", "Bob", null);
        store.create("user:w-1", "greet", VarType.STRING, null, "manual");
        TraceStep step = executor().execute(WALL, "b",
                new Action.SetVariable("user/greet", "hi ${var:user/name}"));
        assertEquals("ok", step.result());
        assertEquals("hi Bob", valueOf("user:w-1/greet"), "value 过 ${var:X} 插值（与 Compositor 同源）");
    }

    @Test
    void setVariable_missingVariable_errorStep() {
        TraceStep step = executor().execute(WALL, "b",
                new Action.SetVariable("user/nope", "1"));
        assertEquals("error", step.result());
    }

    @Test
    void setVariable_storeMissing_errorStep() {
        TraceStep step = bare().execute(WALL, "b", new Action.SetVariable("user/x", "1"));
        assertEquals("error", step.result());
    }

    // ---------- incrementVariable ----------

    @Test
    void increment_integerOutput_noDecimalPoint() {
        store.create("user:w-1", "score", VarType.NUMBER, null, "manual");
        store.setValue("user:w-1/score", "41", null);
        TraceStep step = executor().execute(WALL, "b",
                new Action.IncrementVariable("user/score", 1.0));
        assertEquals("ok", step.result());
        assertEquals("42", valueOf("user:w-1/score"), "整数值输出无小数点（不是 \"42.0\"）");
    }

    @Test
    void increment_fractionalDelta_keepsDecimal() {
        store.create("user:w-1", "score", VarType.NUMBER, null, "manual");
        store.setValue("user:w-1/score", "42", null);
        executor().execute(WALL, "b", new Action.IncrementVariable("user/score", 0.5));
        assertEquals("42.5", valueOf("user:w-1/score"));
    }

    @Test
    void increment_nonNumericCurrent_startsFromZero() {
        store.create("user:w-1", "label", VarType.STRING, null, "manual");
        store.setValue("user:w-1/label", "abc", null);
        executor().execute(WALL, "b", new Action.IncrementVariable("user/label", 2.0));
        assertEquals("2", valueOf("user:w-1/label"), "非数值按 0 起算（§2.3）");
    }

    @Test
    void increment_emptyCurrent_usesDefaultThenZero() {
        store.create("user:w-1", "n", VarType.NUMBER, "10", "manual");
        executor().execute(WALL, "b", new Action.IncrementVariable("user/n", 1.0));
        assertEquals("11", valueOf("user:w-1/n"),
                "currentValue 空 → defaultValue 起算（storeLookup fallback 链）");
    }

    @Test
    void increment_missingVariable_errorStep() {
        TraceStep step = executor().execute(WALL, "b",
                new Action.IncrementVariable("user/nope", 1.0));
        assertEquals("error", step.result());
    }

    @Test
    void formatNumber_edges() {
        assertEquals("42", ActionExecutor.formatNumber(42.0));
        assertEquals("0", ActionExecutor.formatNumber(0.0));
        assertEquals("-7", ActionExecutor.formatNumber(-7.0));
        assertEquals("42.5", ActionExecutor.formatNumber(42.5));
        assertEquals("1.0E16", ActionExecutor.formatNumber(1.0E16),
                "超 2^53 整数精确域不收整");
    }

    @Test
    void formatNumber_nonFinite_returnsZero() {
        assertEquals("0", ActionExecutor.formatNumber(Double.POSITIVE_INFINITY),
                "Infinity → \"0\"，避免污染变量库");
        assertEquals("0", ActionExecutor.formatNumber(Double.NEGATIVE_INFINITY),
                "-Infinity → \"0\"");
        assertEquals("0", ActionExecutor.formatNumber(Double.NaN),
                "NaN → \"0\"");
    }

    // ---------- setElementProperty（委托；双路径细节见 ElementPropertyApplierTest） ----------

    @Test
    void setElementProperty_withoutApplier_errorStep() {
        TraceStep step = executor().execute(WALL, "b",
                new Action.SetElementProperty("e-1", "text", "x"));
        assertEquals("error", step.result());
    }

    @Test
    void setElementProperty_delegatesToApplier() {
        List<String> seen = new ArrayList<>();
        ElementPropertyApplier applier = new ElementPropertyApplier((w, e, p) -> {
            seen.add(w + "/" + e + "/" + p);
            return ElementPropertyApplier.SessionOutcome.applied();
        }, null, null, log);
        ActionExecutor ex = new ActionExecutor(store, ticker, applier, null, null, log);
        TraceStep step = ex.execute(WALL, "b",
                new Action.SetElementProperty("e-1", "x", "10"));
        assertEquals("ok", step.result());
        assertEquals(1, seen.size());
        assertTrue(seen.get(0).startsWith("w-1/e-1/{x=10"), seen.get(0));
    }

    // ---------- playTimeline ----------

    @Test
    void playTimeline_play_ok() {
        TraceStep step = executor().execute(WALL, "b",
                new Action.PlayTimeline("tl-1", "play", null));
        assertEquals("ok", step.result());
        assertEquals(List.of("w-1:tl-1"), ticker.plays);
    }

    @Test
    void playTimeline_play_resultFailure_errorStep() {
        ticker.playResult = AnimationTicker.Result.TIMELINE_NOT_FOUND;
        TraceStep step = executor().execute(WALL, "b",
                new Action.PlayTimeline("tl-x", "play", null));
        assertEquals("error", step.result());
        assertTrue(step.detail().contains("TIMELINE_NOT_FOUND"), step.detail());
    }

    @Test
    void playTimeline_pause_ok() {
        TraceStep step = executor().execute(WALL, "b",
                new Action.PlayTimeline(null, "pause", null));
        assertEquals("ok", step.result());
        assertEquals(List.of("w-1"), ticker.pauses);
    }

    @Test
    void playTimeline_seek_usesSeekMs() {
        TraceStep step = executor().execute(WALL, "b",
                new Action.PlayTimeline("tl-1", "seek", 750L));
        assertEquals("ok", step.result());
        assertEquals(List.of(750L), ticker.seeks);
    }

    @Test
    void playTimeline_seek_missingSeekMs_errorStep() {
        TraceStep step = executor().execute(WALL, "b",
                new Action.PlayTimeline("tl-1", "seek", null));
        assertEquals("error", step.result());
    }

    @Test
    void playTimeline_tickerMissing_errorStep() {
        TraceStep step = bare().execute(WALL, "b",
                new Action.PlayTimeline("tl-1", "play", null));
        assertEquals("error", step.result());
    }

    // ---------- playSound（拒绝分支覆盖） ----------

    @Test
    void playSound_badScope_errorStep() {
        TraceStep step = executor().execute(WALL, "b",
                new Action.PlaySound("block.note_block.pling", 1.0, 1.0, "galaxy"));
        assertEquals("error", step.result());
        assertTrue(step.detail().contains("scope"), step.detail());
    }

    @Test
    void playSound_repoMissing_errorStep() {
        TraceStep step = executor().execute(WALL, "b",
                new Action.PlaySound("block.note_block.pling", 1.0, 1.0, "all"));
        assertEquals("error", step.result());
    }

    @Test
    void playSound_wallMissing_errorStep() {
        // null-jdbi WallRepo：loadById 内部吞异常返 empty
        WallRepo repo = new WallRepo(log, null);
        ActionExecutor ex = new ActionExecutor(store, ticker, null, repo, null, log);
        TraceStep step = ex.execute(WALL, "b",
                new Action.PlaySound("block.note_block.pling", 1.0, 1.0, "near"));
        assertEquals("error", step.result());
        assertTrue(step.detail().contains("wall"), step.detail());
    }

    // ---------- runCommand（命令模板系统接线） ----------

    @Test
    void runCommand_withoutTemplates_blockedStep() {
        // 6 参构造（templates supplier 缺）→ 模板恒查无 → blocked
        TraceStep step = executor().execute(WALL, "b",
                new Action.RunCommand("give-reward", Map.of("player", "Bob")));
        assertEquals("blocked", step.result());
        assertTrue(step.detail().contains("give-reward"), step.detail());
    }

    @Test
    void runCommand_renderError_errorStep() {
        var tpls = Map.of("announce", new CommandTemplate(
                "say {msg}", Map.of("msg", ParamSpec.defaults())));
        ActionExecutor ex = new ActionExecutor(null, null, null, null, null,
                () -> tpls, List::of, null, log);
        // 参数缺失 → error step（不 blocked——模板在，是规则方填错）
        TraceStep step = ex.execute(WALL, "b",
                new Action.RunCommand("announce", Map.of()));
        assertEquals("error", step.result());
        assertTrue(step.detail().contains("msg"), step.detail());
    }

    @Test
    void runCommand_renderOk_okStep_andDispatchFailureContained() {
        var tpls = Map.of("announce", new CommandTemplate(
                "say {msg}", Map.of("msg", ParamSpec.defaults())));
        ActionExecutor ex = new ActionExecutor(null, null, null, null, null,
                () -> tpls, List::of, null, log);
        // plugin=null 直跑：Bukkit.dispatchCommand 在单测无 server 环境抛 → work 内自吞，step 仍 ok
        TraceStep step = ex.execute(WALL, "b",
                new Action.RunCommand("announce", Map.of("msg", "hello")));
        assertEquals("ok", step.result());
        assertTrue(step.detail().contains("announce"), step.detail());
        boolean warned = logRecords.stream().anyMatch(r ->
                r.getLevel() == Level.WARNING
                        && r.getMessage().contains("runCommand failed"));
        assertTrue(warned, "无 Bukkit server 环境 dispatch 失败应留 WARNING 不上抛");
    }

    // ---------- log ----------

    @Test
    void log_outputsToPluginLogger_withInterpolation_noAudit() {
        store.create("user:w-1", "n", VarType.NUMBER, null, "manual");
        store.setValue("user:w-1/n", "7", null);
        TraceStep step = executor().execute(WALL, "b",
                new Action.Log("count=${var:user/n}"));
        assertEquals("ok", step.result());
        boolean found = logRecords.stream().anyMatch(r ->
                r.getLevel() == Level.FINE && r.getMessage().equals("[script w-1] count=7"));
        assertTrue(found, "log.fine 含 wallId 前缀 + 插值后消息");
    }

    @Test
    void log_withoutStore_rawMessage() {
        TraceStep step = bare().execute(WALL, "b", new Action.Log("raw ${var:user/n}"));
        assertEquals("ok", step.result());
        assertTrue(logRecords.stream().anyMatch(r ->
                r.getMessage().equals("[script w-1] raw ${var:user/n}")));
    }

    // ---------- setElementProperties / nudge（委托 applier） ----------

    @Test
    void setElementProperties_delegatesToApplyMany() {
        // 真 ElementPropertyApplier + 记录型 SessionPatchApplier（applyMany 经路径 A 走 apply(patch)）
        List<Map<String, Object>> seenPatch = new ArrayList<>();
        ElementPropertyApplier applier = new ElementPropertyApplier((w, e, p) -> {
            seenPatch.add(p);
            return ElementPropertyApplier.SessionOutcome.applied();
        }, null, null, log);
        ActionExecutor ex = new ActionExecutor(store, ticker, applier, null, null, log);
        TraceStep step = ex.execute(WALL, "b", new Action.SetElementProperties(
                "e-1", Map.of("x", "128", "y", "64"), "moveTo"));
        assertEquals("ok", step.result());
        assertEquals(1, seenPatch.size());
        assertEquals(128, seenPatch.get(0).get("x"), "kind 透传忽略，patch 合并 x/y");
        assertEquals(64, seenPatch.get(0).get("y"));
    }

    @Test
    void setElementProperties_withoutApplier_errorStep() {
        TraceStep step = executor().execute(WALL, "b",
                new Action.SetElementProperties("e-1", Map.of("x", "1"), "moveTo"));
        assertEquals("error", step.result());
    }

    @Test
    void nudge_delegatesToApplyNudge() {
        // 真 ElementPropertyApplier + 记录型 nudge seam（applyNudge 经路径 A 走 nudge(dx,dy)）
        List<int[]> seen = new ArrayList<>();
        ElementPropertyApplier applier = new ElementPropertyApplier(
                new ElementPropertyApplier.SessionPatchApplier() {
                    @Override
                    public ElementPropertyApplier.SessionOutcome apply(
                            String w, String e, Map<String, Object> p) {
                        return ElementPropertyApplier.SessionOutcome.noSession();
                    }

                    @Override
                    public ElementPropertyApplier.SessionOutcome nudge(
                            String w, String e, int dx, int dy) {
                        seen.add(new int[]{dx, dy});
                        return ElementPropertyApplier.SessionOutcome.applied();
                    }
                }, null, null, log);
        ActionExecutor ex = new ActionExecutor(store, ticker, applier, null, null, log);
        TraceStep step = ex.execute(WALL, "b", new Action.NudgeElement("e-1", 5.0, -3.0));
        assertEquals("ok", step.result());
        assertEquals(1, seen.size());
        assertEquals(5, seen.get(0)[0], "dx 透传 round");
        assertEquals(-3, seen.get(0)[1], "dy 透传 round");
    }

    @Test
    void nudge_withoutApplier_errorStep() {
        TraceStep step = executor().execute(WALL, "b", new Action.NudgeElement("e-1", 1, 1));
        assertEquals("error", step.result());
    }

    // ---------- sendMessage / showTitle ----------

    @Test
    void sendMessage_noTriggerPlayer_okSkip() {
        ScriptRunner.TRIGGER_PLAYER.remove();
        TraceStep step = executor().execute(WALL, "b",
                new Action.SendMessage("hi", "chat", "trigger"));
        assertEquals("ok", step.result());
        assertTrue(step.detail().contains("no trigger player"), step.detail());
    }

    @Test
    void sendMessage_badChannel_errorStep() {
        TraceStep step = executor().execute(WALL, "b",
                new Action.SendMessage("hi", "hologram", "trigger"));
        assertEquals("error", step.result());
        assertTrue(step.detail().contains("channel"), step.detail());
    }

    @Test
    void sendMessage_withTriggerPlayer_okStep_dispatchContained() {
        ScriptRunner.TRIGGER_PLAYER.set("Alice");
        try {
            TraceStep step = executor().execute(WALL, "b",
                    new Action.SendMessage("hi", "chat", "trigger"));
            assertEquals("ok", step.result());
            assertTrue(step.detail().contains("Alice"), step.detail());
        } finally {
            ScriptRunner.TRIGGER_PLAYER.remove();
        }
    }

    /**
     * sendMessage / showTitle 的 target=trigger 只读 {@link ScriptRunner#currentTriggerPlayer()}，
     * 不把 {@link TriggerContext#detail()}（如 {@code Victim→Killer}）当作玩家名。
     */
    @Test
    void triggerTarget_detailIsNotUsedAsPlayerName() {
        ScriptRunner runner = new ScriptRunner(
                new ConditionEvaluator(log, store),
                executor(),
                new ScriptBudget(HikariCanvasConfig.ScriptsConfig.defaults()),
                null,
                log,
                new ScriptRunner.TaskScheduler() {
                    @Override public void execute(Runnable task) { task.run(); }
                    @Override public void schedule(Runnable task, long delayMs) {}
                    @Override public void shutdown() {}
                });
        ScriptRule rule = new ScriptRule("r-1", WALL, true, "test",
                new Trigger.PlayerKill(),
                List.of(
                        new Action.SendMessage("hi", "chat", "trigger"),
                        new Action.ShowTitle("Hi", "Sub", 200, 1000, 200, "trigger")),
                "{}");
        List<List<TraceStep>> traces = new ArrayList<>();
        runner.submit(WALL, rule,
                new TriggerContext(TriggerContext.Source.PLAYER_KILL, 0, "Victim→Killer", null),
                traces::add);

        assertEquals(1, traces.size());
        List<TraceStep> steps = traces.get(0);
        assertEquals(3, steps.size());
        assertTrue(steps.get(1).detail().contains("no trigger player"), steps.get(1).detail());
        assertFalse(steps.get(1).detail().contains("→"), steps.get(1).detail());
        assertTrue(steps.get(2).detail().contains("no trigger player"), steps.get(2).detail());
        assertFalse(steps.get(2).detail().contains("→"), steps.get(2).detail());
    }

    @Test
    void sendMessage_targetAll_takesBroadcastBranch_notTriggerSkip() {
        ScriptRunner.TRIGGER_PLAYER.remove();
        TraceStep step = executor().execute(WALL, "b",
                new Action.SendMessage("大家好", "chat", "all"));
        assertEquals("ok", step.result());
        assertFalse(step.detail().contains("no trigger player"),
                "target=all 应走广播分支，不应因无触发玩家而跳过: " + step.detail());
    }

    @Test
    void sendMessage_targetAll_withTriggerPlayer_stillBroadcast() {
        ScriptRunner.TRIGGER_PLAYER.set("Alice");
        try {
            TraceStep step = executor().execute(WALL, "b",
                    new Action.SendMessage("大家好", "actionbar", "all"));
            assertEquals("ok", step.result());
            assertFalse(step.detail().contains("Alice"),
                    "target=all 广播 detail 不应标触发玩家名: " + step.detail());
        } finally {
            ScriptRunner.TRIGGER_PLAYER.remove();
        }
    }

    @Test
    void showTitle_targetAll_takesBroadcastBranch() {
        ScriptRunner.TRIGGER_PLAYER.remove();
        TraceStep step = executor().execute(WALL, "a/0",
                new Action.ShowTitle("Big News", "Sub", 500, 2000, 500, "all"));
        assertEquals("ok", step.result(), step::detail);
        assertTrue(step.detail().contains("broadcast"), step.detail());
    }

    @Test
    void showTitle_targetTrigger_noTriggerPlayer_skipOk() {
        ScriptRunner.TRIGGER_PLAYER.remove();
        TraceStep step = executor().execute(WALL, "a/0",
                new Action.ShowTitle("Hi", "there", 200, 3000, 200, "trigger"));
        assertEquals("ok", step.result(), step::detail);
        assertTrue(step.detail().contains("skipped"), step.detail());
    }

    @Test
    void showTitle_targetTrigger_withTriggerPlayer_routesToTriggerPlayer() {
        ScriptRunner.TRIGGER_PLAYER.set("Alice");
        try {
            TraceStep step = executor().execute(WALL, "a/0",
                    new Action.ShowTitle("Hi", "there", 200, 3000, 200, "trigger"));
            assertEquals("ok", step.result(), step::detail);
            assertEquals("title -> Alice", step.detail());
        } finally {
            ScriptRunner.TRIGGER_PLAYER.remove();
        }
    }

    @Test
    void showTitleTo_dispatchesAdventureShowTitleWithMillisDurations() {
        List<Title> shown = new ArrayList<>();
        Player proxyPlayer = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    if ("showTitle".equals(method.getName()) && args != null && args.length == 1
                            && args[0] instanceof Title t) {
                        shown.add(t);
                        return null;
                    }
                    if ("sendTitle".equals(method.getName())) {
                        throw new AssertionError("不应调用已弃用的 Bukkit sendTitle");
                    }
                    return null;
                });

        ActionExecutor.showTitleTo(proxyPlayer, "Big News", "Subtitle", 350, 2500, 750);

        assertEquals(1, shown.size());
        Title t = shown.get(0);
        assertEquals(Component.text("Big News"), t.title());
        assertEquals(Component.text("Subtitle"), t.subtitle());
        assertNotNull(t.times());
        assertEquals(Duration.ofMillis(350), t.times().fadeIn());
        assertEquals(Duration.ofMillis(2500), t.times().stay());
        assertEquals(Duration.ofMillis(750), t.times().fadeOut());
    }

    // ---------- setRandomVariable ----------

    @Test
    void setRandomVariable_seeded_deterministic() {
        store.create("user:w-1", "roll", VarType.NUMBER, "0", "manual");
        ActionExecutor ex = executorWithRng(new Random(42));
        TraceStep step = ex.execute(WALL, "b",
                new Action.SetRandomVariable("user/roll", 1.0, 1.0));
        assertEquals("ok", step.result());
        assertEquals("1", valueOf("user:w-1/roll"), "min==max==1 → 恒 1（整数 roll 含 max）");
    }

    @Test
    void setRandomVariable_integerRange_inBounds() {
        store.create("user:w-1", "roll", VarType.NUMBER, "0", "manual");
        ActionExecutor ex = executorWithRng(new Random(7));
        for (int i = 0; i < 50; i++) {
            ex.execute(WALL, "b", new Action.SetRandomVariable("user/roll", 0.0, 10.0));
            int v = Integer.parseInt(valueOf("user:w-1/roll"));
            assertTrue(v >= 0 && v <= 10, "结果在 [0,10]: " + v);
        }
    }

    @Test
    void setRandomVariable_minGreaterThanMax_errorStep() {
        store.create("user:w-1", "roll", VarType.NUMBER, "0", "manual");
        TraceStep step = executor().execute(WALL, "b",
                new Action.SetRandomVariable("user/roll", 6.0, 1.0));
        assertEquals("error", step.result());
    }

    @Test
    void setRandomVariable_storeMissing_errorStep() {
        TraceStep step = bare().execute(WALL, "b",
                new Action.SetRandomVariable("user/x", 1.0, 2.0));
        assertEquals("error", step.result());
    }

    // ---------- scaleVariable ----------

    @Test
    void scaleVariable_multiply() {
        store.create("user:w-1", "score", VarType.NUMBER, "10", "manual");
        TraceStep step = executor().execute(WALL, "b",
                new Action.ScaleVariable("user/score", "multiply", 2.0));
        assertEquals("ok", step.result());
        assertEquals("20", valueOf("user:w-1/score"));
    }

    @Test
    void scaleVariable_divide() {
        store.create("user:w-1", "score", VarType.NUMBER, "10", "manual");
        TraceStep step = executor().execute(WALL, "b",
                new Action.ScaleVariable("user/score", "divide", 2.0));
        assertEquals("ok", step.result());
        assertEquals("5", valueOf("user:w-1/score"));
    }

    @Test
    void scaleVariable_divideByZero_errorStep() {
        store.create("user:w-1", "score", VarType.NUMBER, "10", "manual");
        store.setValue("user:w-1/score", "10", null); // 写 currentValue（create 仅设 default）
        TraceStep step = executor().execute(WALL, "b",
                new Action.ScaleVariable("user/score", "divide", 0.0));
        assertEquals("error", step.result());
        assertEquals("10", valueOf("user:w-1/score"), "除零拒绝，值不变");
    }

    @Test
    void scaleVariable_nonNumericCurrent_treatedAsZero() {
        store.create("user:w-1", "label", VarType.STRING, "abc", "manual");
        TraceStep step = executor().execute(WALL, "b",
                new Action.ScaleVariable("user/label", "multiply", 5.0));
        assertEquals("ok", step.result());
        assertEquals("0", valueOf("user:w-1/label"), "非数值按 0 → 0*5=0（StrictNumber 链）");
    }

    @Test
    void scaleVariable_badOp_errorStep() {
        store.create("user:w-1", "score", VarType.NUMBER, "10", "manual");
        TraceStep step = executor().execute(WALL, "b",
                new Action.ScaleVariable("user/score", "modulo", 2.0));
        assertEquals("error", step.result());
    }

    // ---------- playTimelineAwait（execute 做 play；挂起由 Runner） ----------

    @Test
    void playTimelineAwait_play_ok() {
        TraceStep step = executor().execute(WALL, "b",
                new Action.PlayTimelineAwait("tl-1"));
        assertEquals("ok", step.result());
        assertEquals(List.of("w-1:tl-1"), ticker.plays);
    }

    @Test
    void playTimelineAwait_playFailure_errorStep() {
        ticker.playResult = AnimationTicker.Result.TIMELINE_NOT_FOUND;
        TraceStep step = executor().execute(WALL, "b",
                new Action.PlayTimelineAwait("tl-x"));
        assertEquals("error", step.result());
        assertTrue(step.detail().contains("TIMELINE_NOT_FOUND"), step.detail());
    }

    @Test
    void playTimelineAwait_blankId_errorStep() {
        TraceStep step = executor().execute(WALL, "b", new Action.PlayTimelineAwait("  "));
        assertEquals("error", step.result());
    }

    @Test
    void playTimelineAwait_tickerMissing_errorStep() {
        TraceStep step = bare().execute(WALL, "b", new Action.PlayTimelineAwait("tl-1"));
        assertEquals("error", step.result());
    }

    // ---------- timelineDurationMs ----------

    @Test
    void timelineDurationMs_noWallRepo_returnsZero() {
        assertEquals(0L, executor().timelineDurationMs(WALL, "tl-1"),
                "wallRepo 缺 → 0（Runner 据此不挂起）");
    }

    // ---------- 防御 + 三层隔离 ----------

    @Test
    void runnerOwnedActions_reachingExecutor_defensiveErrorStep() {
        assertEquals("error", executor().execute(WALL, "b", new Action.Wait(100)).result());
        assertEquals("error", executor().execute(WALL, "b",
                new Action.If("1 == 1", List.of(), List.of())).result());
        TraceStep ru = executor().execute(WALL, "b",
                new Action.RepeatUntil("1 == 1", 3, List.of(new Action.Log("x"))));
        assertEquals("error", ru.result());
        assertEquals("repeatUntil is handled by ScriptRunner", ru.detail());
        TraceStep tb = executor().execute(WALL, "b",
                new Action.TweenBlock(500L, null, List.of()));
        assertEquals("error", tb.result());
        assertEquals("tweenBlock is handled by ScriptRunner", tb.detail());
    }

    // ---------- playParticle / stopScript / waitUntil ----------

    @Test
    void playParticle_repoMissing_errorStep() {
        TraceStep step = executor().execute(WALL, "b",
                new Action.PlayParticle("minecraft:flame", 10, 0, 0, 0));
        assertEquals("error", step.result());
    }

    @Test
    void playParticle_unknownWall_errorStep() {
        // null-jdbi WallRepo：loadById 内部吞异常返 empty → wall 不存在 → error
        WallRepo repo = new WallRepo(log, null);
        ActionExecutor ex = new ActionExecutor(store, ticker, null, repo, null, log);
        TraceStep step = ex.execute(WALL, "b",
                new Action.PlayParticle("minecraft:flame", 10, 0, 0, 0));
        assertEquals("error", step.result());
        assertTrue(step.detail().contains("wall"), step.detail());
    }

    @Test
    void stopScript_inExecutor_returnsError_handledByRunner() {
        // StopScript 应由 ScriptRunner 处理；Executor 收到返 error（同 Wait/If/Repeat 防御）
        TraceStep step = executor().execute(WALL, "b", new Action.StopScript());
        assertEquals("error", step.result());
    }

    @Test
    void waitUntil_inExecutor_returnsError_handledByRunner() {
        TraceStep step = executor().execute(WALL, "b",
                new Action.WaitUntil("x>0", 5000));
        assertEquals("error", step.result());
    }

    // ---------- copyVariable / appendVariable ----------

    @Test
    void copyVariable_copiesSourceToTarget() {
        store.create("user:w-1", "src", VarType.NUMBER, null, "manual");
        store.setValue("user:w-1/src", "42", null);
        store.create("user:w-1", "dst", VarType.NUMBER, null, "manual");
        store.setValue("user:w-1/dst", "0", null);
        TraceStep step = executor().execute(WALL, "actions/0",
                new Action.CopyVariable("user/dst", "user/src"));
        assertEquals("ok", step.result(), () -> String.valueOf(step.detail()));
        assertEquals("42", valueOf("user:w-1/dst"));
    }

    @Test
    void copyVariable_usesDefaultWhenCurrentNull() {
        store.create("user:w-1", "src", VarType.STRING, "fromDefault", "manual");
        store.create("user:w-1", "dst", VarType.STRING, null, "manual");
        TraceStep step = executor().execute(WALL, "actions/0",
                new Action.CopyVariable("user/dst", "user/src"));
        assertEquals("ok", step.result());
        assertEquals("fromDefault", valueOf("user:w-1/dst"),
                "currentValue 空 → 用 defaultValue");
    }

    @Test
    void copyVariable_missingSource_error() {
        store.create("user:w-1", "dst", VarType.STRING, null, "manual");
        TraceStep step = executor().execute(WALL, "actions/0",
                new Action.CopyVariable("user/dst", "user/nope"));
        assertEquals("error", step.result());
    }

    @Test
    void copyVariable_storeMissing_error() {
        TraceStep step = bare().execute(WALL, "actions/0",
                new Action.CopyVariable("user/dst", "user/src"));
        assertEquals("error", step.result());
    }

    @Test
    void appendVariable_appendsInterpolatedText() {
        store.create("user:w-1", "log", VarType.STRING, null, "manual");
        store.setValue("user:w-1/log", "[", null);
        store.create("user:w-1", "x", VarType.NUMBER, null, "manual");
        store.setValue("user:w-1/x", "9", null);
        TraceStep step = executor().execute(WALL, "actions/0",
                new Action.AppendVariable("user/log", "x=${var:user/x}"));
        assertEquals("ok", step.result());
        assertEquals("[x=9", valueOf("user:w-1/log"));
    }

    @Test
    void appendVariable_missingTargetCurrent_appendsFromEmpty() {
        store.create("user:w-1", "log", VarType.STRING, null, "manual");
        TraceStep step = executor().execute(WALL, "actions/0",
                new Action.AppendVariable("user/log", "abc"));
        assertEquals("ok", step.result());
        assertEquals("abc", valueOf("user:w-1/log"), "currentValue 空 → 从空串拼");
    }

    @Test
    void appendVariable_storeMissing_error() {
        TraceStep step = bare().execute(WALL, "actions/0",
                new Action.AppendVariable("user/log", "x"));
        assertEquals("error", step.result());
    }

    // ---------- cloneElement / deleteElement / setElementLayer（委托 ElementPropertyApplier） ----------

    @Test
    void cloneElement_noApplier_error() {
        // executor() 的 applier 为 null → error step（未装配）
        TraceStep step = executor().execute(WALL, "actions/0",
                new Action.CloneElement("e-1", 10, 10));
        assertEquals("error", step.result());
        assertTrue(step.detail().contains("ElementPropertyApplier"), step.detail());
    }

    @Test
    void deleteElement_noApplier_error() {
        TraceStep step = executor().execute(WALL, "actions/0",
                new Action.DeleteElement("e-1"));
        assertEquals("error", step.result());
        assertTrue(step.detail().contains("ElementPropertyApplier"), step.detail());
    }

    @Test
    void cloneElement_dispatchesToApplier_clone() {
        // 真 ElementPropertyApplier + 记录调用的 SessionPatchApplier seam（APPLIED → 不落 headless）
        List<String> cloneCalls = new ArrayList<>();
        ElementPropertyApplier applier = new ElementPropertyApplier(
                new ElementPropertyApplier.SessionPatchApplier() {
                    @Override
                    public ElementPropertyApplier.SessionOutcome apply(
                            String w, String e, Map<String, Object> p) {
                        throw new AssertionError("clone 不应走 apply()");
                    }

                    @Override
                    public ElementPropertyApplier.SessionOutcome clone(
                            String w, String e, int ox, int oy) {
                        cloneCalls.add(e + ":" + ox + "," + oy);
                        return ElementPropertyApplier.SessionOutcome.applied();
                    }
                }, null, ticker, log);
        ActionExecutor ex = new ActionExecutor(store, ticker, applier, null, null, log);
        TraceStep step = ex.execute(WALL, "actions/0", new Action.CloneElement("e-7", 3, 4));
        assertEquals("ok", step.result(), () -> String.valueOf(step.detail()));
        assertEquals(List.of("e-7:3,4"), cloneCalls, "applyClone 经 seam 落到 clone()");
    }

    @Test
    void deleteElement_dispatchesToApplier_delete() {
        List<String> deleteCalls = new ArrayList<>();
        ElementPropertyApplier applier = new ElementPropertyApplier(
                new ElementPropertyApplier.SessionPatchApplier() {
                    @Override
                    public ElementPropertyApplier.SessionOutcome apply(
                            String w, String e, Map<String, Object> p) {
                        throw new AssertionError("delete 不应走 apply()");
                    }

                    @Override
                    public ElementPropertyApplier.SessionOutcome delete(String w, String e) {
                        deleteCalls.add(e);
                        return ElementPropertyApplier.SessionOutcome.applied();
                    }
                }, null, ticker, log);
        ActionExecutor ex = new ActionExecutor(store, ticker, applier, null, null, log);
        TraceStep step = ex.execute(WALL, "actions/0", new Action.DeleteElement("e-9"));
        assertEquals("ok", step.result(), () -> String.valueOf(step.detail()));
        assertEquals(List.of("e-9"), deleteCalls, "applyDelete 经 seam 落到 delete()");
    }

    @Test
    void setElementLayer_noApplier_error() {
        TraceStep step = executor().execute(WALL, "actions/0",
                new Action.SetElementLayer("el-1", "front"));
        assertEquals("error", step.result());
        assertTrue(step.detail().contains("ElementPropertyApplier"), step.detail());
    }

    @Test
    void setElementLayer_frontAndBack_dispatchToReorderToEdge() {
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
                }, null, null, log);
        ActionExecutor ex = new ActionExecutor(store, ticker, applier, null, null, log);
        TraceStep s1 = ex.execute(WALL, "actions/0", new Action.SetElementLayer("el-3", "front"));
        TraceStep s2 = ex.execute(WALL, "actions/1", new Action.SetElementLayer("el-5", "back"));
        assertEquals("ok", s1.result(), s1::detail);
        assertEquals("ok", s2.result(), s2::detail);
        assertEquals(List.of("el-3:front", "el-5:back"), calls);
    }

    // ---------- roundVariable ----------

    @Test
    void roundVariable_floor_ok() {
        store.create("user:w-1", "v", VarType.NUMBER, null, "manual");
        store.setValue("user:w-1/v", "3.7", null);
        TraceStep step = executor().execute(WALL, "b",
                new Action.RoundVariable("user/v", "floor"));
        assertEquals("ok", step.result(), () -> String.valueOf(step.detail()));
        assertEquals("3", valueOf("user:w-1/v"), "floor(3.7) = 3");
    }

    @Test
    void roundVariable_negative_floor() {
        store.create("user:w-1", "neg", VarType.NUMBER, "-2.7", "manual");
        TraceStep step = executor().execute(WALL, "b",
                new Action.RoundVariable("user/neg", "floor"));
        assertEquals("ok", step.result(), step::detail);
        assertEquals("-3", valueOf("user:w-1/neg"));
    }

    @Test
    void roundVariable_ceil_ok() {
        store.create("user:w-1", "v", VarType.NUMBER, null, "manual");
        store.setValue("user:w-1/v", "3.2", null);
        TraceStep step = executor().execute(WALL, "b",
                new Action.RoundVariable("user/v", "ceil"));
        assertEquals("ok", step.result());
        assertEquals("4", valueOf("user:w-1/v"), "ceil(3.2) = 4");
    }

    @Test
    void roundVariable_round_half_up() {
        store.create("user:w-1", "v", VarType.NUMBER, null, "manual");
        store.setValue("user:w-1/v", "2.5", null);
        TraceStep step = executor().execute(WALL, "b",
                new Action.RoundVariable("user/v", "round"));
        assertEquals("ok", step.result());
        assertEquals("3", valueOf("user:w-1/v"), "round(2.5) = 3（half-up）");
    }

    @Test
    void roundVariable_round_negative_half_asymmetric() {
        store.create("user:w-1", "v", VarType.NUMBER, null, "manual");
        store.setValue("user:w-1/v", "-2.5", null);
        TraceStep step = executor().execute(WALL, "b",
                new Action.RoundVariable("user/v", "round"));
        assertEquals("ok", step.result());
        assertEquals("-2", valueOf("user:w-1/v"), "round(-2.5) = -2（Java Math.round 语义）");
    }

    @Test
    void roundVariable_nonNumeric_error() {
        store.create("user:w-1", "text", VarType.STRING, "abc", "manual");
        TraceStep step = executor().execute(WALL, "b",
                new Action.RoundVariable("user/text", "round"));
        assertEquals("error", step.result());
        assertTrue(step.detail().contains("not numeric"), step.detail());
    }

    @Test
    void roundVariable_nanString_error() {
        store.create("user:w-1", "v", VarType.NUMBER, null, "manual");
        store.setValue("user:w-1/v", "NaN", null);
        TraceStep step = executor().execute(WALL, "b",
                new Action.RoundVariable("user/v", "round"));
        assertEquals("error", step.result(), () -> String.valueOf(step.detail()));
        assertTrue(step.detail().contains("not numeric"), step.detail());
        assertEquals("NaN", valueOf("user:w-1/v"), "error 不写回，变量值保持原样");
    }

    @Test
    void roundVariable_infinityString_error() {
        store.create("user:w-1", "v", VarType.NUMBER, null, "manual");
        store.setValue("user:w-1/v", "Infinity", null);
        TraceStep step = executor().execute(WALL, "b",
                new Action.RoundVariable("user/v", "floor"));
        assertEquals("error", step.result());
        assertEquals("Infinity", valueOf("user:w-1/v"), "error 不写回");
    }

    @Test
    void roundVariable_hexString_error() {
        store.create("user:w-1", "v", VarType.NUMBER, null, "manual");
        store.setValue("user:w-1/v", "0x1p4", null);
        TraceStep step = executor().execute(WALL, "b",
                new Action.RoundVariable("user/v", "round"));
        assertEquals("error", step.result());
        assertEquals("0x1p4", valueOf("user:w-1/v"), "error 不写回");
    }

    @Test
    void roundVariable_trailingSuffix_error() {
        store.create("user:w-1", "v", VarType.NUMBER, null, "manual");
        store.setValue("user:w-1/v", "5d", null);
        TraceStep step = executor().execute(WALL, "b",
                new Action.RoundVariable("user/v", "ceil"));
        assertEquals("error", step.result());
        assertEquals("5d", valueOf("user:w-1/v"), "error 不写回");
    }

    @Test
    void roundVariable_storeMissing_errorStep() {
        TraceStep step = bare().execute(WALL, "b",
                new Action.RoundVariable("user/v", "round"));
        assertEquals("error", step.result());
    }

    @Test
    void actionThrow_isolatedToErrorStep_withWarning() {
        ticker.throwOnPlay = true;
        TraceStep step = executor().execute(WALL, "b",
                new Action.PlayTimeline("tl-1", "play", null));
        assertEquals("error", step.result());
        assertTrue(step.detail().contains("ticker boom"), step.detail());
        assertTrue(logRecords.stream().anyMatch(r -> r.getLevel() == Level.WARNING),
                "异常进 WARNING log（三层隔离）");
    }

    // ---------- runCommand SCRIPT_COMMAND_EXECUTED 审计 ----------

    @Nested
    class CommandAuditTests {

        private final Logger auditLogger = Logger.getLogger("ActionExecutorTest.CommandAuditTests");
        private Path tmpDir;
        private Database database;
        private AuditLog auditLog;

        @BeforeEach
        void setUpAuditDb() throws Exception {
            auditLogger.setUseParentHandlers(false);
            auditLogger.setLevel(Level.OFF);
            tmpDir = Files.createTempDirectory("hikari-cmd-audit-test-");
            database = new Database(auditLogger, tmpDir.resolve("data.db"));
            new MigrationRunner(database.jdbi(), auditLogger).run();
            auditLog = new AuditLog(database.jdbi(), auditLogger);
        }

        @AfterEach
        void tearDownAuditDb() throws Exception {
            ScriptRunner.RULE_KEY.remove();
            if (database != null) database.close();
            if (tmpDir != null && Files.exists(tmpDir)) {
                try (var s = Files.walk(tmpDir)) {
                    s.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
                }
            }
        }

        private List<Map<String, Object>> auditRows() {
            return database.jdbi().withHandle(h ->
                    h.createQuery("SELECT event, details FROM audit_log ORDER BY ts, rowid")
                            .mapToMap()
                            .list());
        }

        @Test
        void commandExecuted_auditedWithTemplateIdFullCommandAndRuleKey() {
            Map<String, CommandTemplate> tpls = Map.of(
                    "announce", new CommandTemplate("say [招牌] {msg}",
                            Map.of("msg", ParamSpec.defaults())));
            ActionExecutor ex = new ActionExecutor(null, null, null, null, null,
                    () -> tpls, List::of, auditLog, auditLogger);

            ScriptRunner.RULE_KEY.set("w-1:sr-abc");
            TraceStep step = ex.execute("w-1", "actions/0",
                    new Action.RunCommand("announce", Map.of("msg", "开张了")));
            assertEquals("ok", step.result());

            List<Map<String, Object>> rows = auditRows();
            assertEquals(1, rows.size(), "恰一条 SCRIPT_COMMAND_EXECUTED");
            assertEquals("SCRIPT_COMMAND_EXECUTED", rows.get(0).get("event"));
            String details = String.valueOf(rows.get(0).get("details"));
            assertTrue(details.contains("\"template_id\":\"announce\""), details);
            assertTrue(details.contains("say [招牌] 开张了"), details);
            assertTrue(details.contains("\"rule_key\":\"w-1:sr-abc\""), details);
            assertTrue(details.contains("\"wall_id\":\"w-1\""), details);
        }

        @Test
        void blockedAndErrorPaths_noAudit() {
            Map<String, CommandTemplate> tpls = Map.of(
                    "announce", new CommandTemplate("say {msg}",
                            Map.of("msg", ParamSpec.defaults())));
            ActionExecutor ex = new ActionExecutor(null, null, null, null, null,
                    () -> tpls, List::of, auditLog, auditLogger);

            assertEquals("blocked", ex.execute("w-1", "b",
                    new Action.RunCommand("nope", Map.of())).result());
            assertEquals("error", ex.execute("w-1", "b",
                    new Action.RunCommand("announce", Map.of())).result());
            assertTrue(auditRows().isEmpty(), "未执行的命令不留 SCRIPT_COMMAND_EXECUTED");
        }
    }

    // ---------- 变量族积木越权守卫（security.md §13.8） ----------

    @Nested
    class VariableAclTests {

        private final Logger aclLogger = Logger.getLogger("ActionExecutorTest.VariableAclTests");
        private final UUID wallOwner = UUID.randomUUID();
        private final UUID someoneElse = UUID.randomUUID();

        private Path tmpDir;
        private Database database;
        private WallRepo wallRepo;
        private VariableStore aclStore;
        private ActionExecutor aclExecutor;
        private String wallId;
        private String otherWallId;

        @BeforeEach
        void setUpAclDb() throws Exception {
            tmpDir = Files.createTempDirectory("hikari-script-acl-");
            database = new Database(aclLogger, tmpDir.resolve("data.db"));
            new MigrationRunner(database.jdbi(), aclLogger).run();
            wallRepo = new WallRepo(aclLogger, database.jdbi());
            wallId = wallRepo.createWithMapIds(new WallKey("world", 0, 64, 0, BlockFace.NORTH),
                    new ProjectState(1, 1), List.of(0), 1, 1, wallOwner, "Owner");
            otherWallId = wallRepo.createWithMapIds(new WallKey("world", 10, 64, 0, BlockFace.NORTH),
                    new ProjectState(1, 1), List.of(1), 1, 1, someoneElse, "Other");

            aclStore = new VariableStore(new NoopUserVariableDao(), w -> { });
            aclStore.configureUserGlobal(new NoopGlobalDao(aclLogger), 100, 1000);
            aclExecutor = new ActionExecutor(aclStore, null, null, wallRepo, null, aclLogger);
        }

        @AfterEach
        void tearDownAclDb() throws Exception {
            if (database != null) database.close();
            if (tmpDir != null && Files.exists(tmpDir)) {
                try (var s = Files.walk(tmpDir)) {
                    s.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
                }
            }
        }

        private String aclValue(String fullName) {
            return aclStore.get(fullName).map(v -> v.currentValue()).orElse(null);
        }

        @Test
        void setVariable_crossWallUserVariable_denied() {
            aclStore.create("user:" + otherWallId, "score", VarType.NUMBER, "0", "manual");
            aclStore.setValue("user:" + otherWallId + "/score", "1", null);

            TraceStep step = aclExecutor.execute(wallId, "b",
                    new Action.SetVariable("user:" + otherWallId + "/score", "9999"));

            assertEquals("error", step.result(), step::detail);
            assertEquals("1", aclValue("user:" + otherWallId + "/score"), "别的墙的变量不许被改");
        }

        @Test
        void setVariable_ownWallUserVariable_allowed() {
            aclStore.create("user:" + wallId, "score", VarType.NUMBER, "0", "manual");

            TraceStep step = aclExecutor.execute(wallId, "b",
                    new Action.SetVariable("user/score", "42"));

            assertEquals("ok", step.result(), step::detail);
            assertEquals("42", aclValue("user:" + wallId + "/score"));
        }

        @Test
        void copyVariable_crossWallSource_denied() {
            aclStore.create("user:" + otherWallId, "secret", VarType.STRING, null, "manual");
            aclStore.setValue("user:" + otherWallId + "/secret", "机密", null);
            aclStore.create("user:" + wallId, "leak", VarType.STRING, null, "manual");

            TraceStep step = aclExecutor.execute(wallId, "b",
                    new Action.CopyVariable("user:" + otherWallId + "/secret", "user/leak"));

            assertEquals("error", step.result(), step::detail);
            assertNull(aclValue("user:" + wallId + "/leak"), "跨墙内容不许被抄过来");
        }

        @Test
        void setVariable_userGlobalOwnedByOtherPlayer_denied() {
            aclStore.createGlobal(someoneElse, "Other", "server_notice", VarType.STRING, null);
            aclStore.setValue("userglobal/server_notice", "原文案", null);

            TraceStep step = aclExecutor.execute(wallId, "b",
                    new Action.SetVariable("userglobal/server_notice", "被篡改"));

            assertEquals("error", step.result(), step::detail);
            assertEquals("原文案", aclValue("userglobal/server_notice"));
        }

        @Test
        void setVariable_userGlobalOwnedByWallOwner_allowed() {
            aclStore.createGlobal(wallOwner, "Owner", "my_counter", VarType.NUMBER, "0");

            TraceStep step = aclExecutor.execute(wallId, "b",
                    new Action.SetVariable("userglobal/my_counter", "7"));

            assertEquals("ok", step.result(), step::detail);
            assertEquals("7", aclValue("userglobal/my_counter"));
        }

        @Test
        void setVariable_userGlobal_unknownWall_denied() {
            aclStore.createGlobal(wallOwner, "Owner", "my_counter", VarType.NUMBER, "0");

            TraceStep step = aclExecutor.execute("w-deadbeef", "b",
                    new Action.SetVariable("userglobal/my_counter", "7"));

            assertEquals("error", step.result(), step::detail);
            assertNull(aclValue("userglobal/my_counter"));
        }

        @Test
        void setVariable_providerOwnedNamespaces_denied() {
            aclStore.create("system:" + wallId, "wall.alias", VarType.STRING, null, "system");
            aclStore.setValue("system:" + wallId + "/wall.alias", "真别名", null);
            aclStore.create("scoreboard", "kills.Steve", VarType.NUMBER, null, "scoreboard-provider");
            aclStore.setValue("scoreboard/kills.Steve", "3", null);
            aclStore.create("schedule:" + wallId, "next_departure", VarType.STRING, null, "schedule");
            aclStore.setValue("schedule:" + wallId + "/next_departure", "08:00", null);

            assertEquals("error", aclExecutor.execute(wallId, "b",
                    new Action.SetVariable("system:" + wallId + "/wall.alias", "假别名")).result());
            assertEquals("error", aclExecutor.execute(wallId, "b",
                    new Action.SetVariable("scoreboard/kills.Steve", "9999")).result());
            assertEquals("error", aclExecutor.execute(wallId, "b",
                    new Action.SetVariable("schedule/next_departure", "23:59")).result());

            assertEquals("真别名", aclValue("system:" + wallId + "/wall.alias"));
            assertEquals("3", aclValue("scoreboard/kills.Steve"));
            assertEquals("08:00", aclValue("schedule:" + wallId + "/next_departure"));
        }

        @Test
        void setVariable_pluginNamespace_stillAllowed() {
            aclStore.create("bedwars", "score", VarType.NUMBER, "0", "bedwars");

            TraceStep step = aclExecutor.execute(wallId, "b",
                    new Action.SetVariable("bedwars/score", "5"));

            assertEquals("ok", step.result(), step::detail);
            assertEquals("5", aclValue("bedwars/score"));
        }

        @Test
        void everyVariableActionIsGuarded() {
            String foreign = "user:" + otherWallId + "/score";
            aclStore.create("user:" + otherWallId, "score", VarType.NUMBER, "0", "manual");
            aclStore.setValue(foreign, "1", null);
            aclStore.create("user:" + wallId, "mine", VarType.STRING, null, "manual");

            List<Action> actions = List.of(
                    new Action.SetVariable(foreign, "9"),
                    new Action.IncrementVariable(foreign, 1.0),
                    new Action.CopyVariable("user/mine", foreign),
                    new Action.AppendVariable(foreign, "x"),
                    new Action.SetRandomVariable(foreign, 1.0, 6.0),
                    new Action.ScaleVariable(foreign, "multiply", 2.0),
                    new Action.RoundVariable(foreign, "round"));

            for (Action a : actions) {
                TraceStep step = aclExecutor.execute(wallId, "b", a);
                assertEquals("error", step.result(),
                        () -> a.wireType() + " 也必须拦跨墙写: " + step.detail());
                assertTrue(step.detail().contains(otherWallId), step.detail());
            }
            assertEquals("1", aclValue(foreign), "7 个积木一个都没写进去");
        }
    }

    private static final class NoopGlobalDao extends UserGlobalVariableDao {
        NoopGlobalDao(Logger logger) {
            super(logger, null);
        }

        @Override
        public void upsert(String name, UUID ownerUuid, String ownerName, VarType type,
                           String defaultValue, String currentValue, String boundTo,
                           long createdAt, long updatedAt) {
        }

        @Override
        public void delete(String name) {
        }

        @Override
        public int countByOwner(UUID ownerUuid) {
            return 0;
        }

        @Override
        public int countTotal() {
            return 0;
        }
    }
}
