package ac.haru.hikaricanvas.script.engine;

import ac.haru.hikaricanvas.render.AnimationTicker;
import ac.haru.hikaricanvas.script.Action;
import ac.haru.hikaricanvas.storage.UserVariableDao;
import ac.haru.hikaricanvas.storage.WallRepo;
import ac.haru.hikaricanvas.variable.VarType;
import ac.haru.hikaricanvas.variable.VariableStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.7.0-P2-4：{@link ActionExecutor} 8 动作（fake store/ticker；plugin=null 直跑路径）。
 *
 * <p>playSound 的"声音存在 + 在线玩家收包"真路径需要 Bukkit server，留批次 3 MVP
 * 游戏内验证；这里覆盖 scope / 依赖缺失 / wall 缺失 各拒绝分支。</p>
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
        store = new VariableStore(new NoopDao(), w -> {
        });
        ticker = new ElementPropertyApplierTest.FakeTicker();
    }

    private ActionExecutor executor() {
        return new ActionExecutor(store, ticker, null, null, null, log);
    }

    private ActionExecutor bare() {
        return new ActionExecutor(null, null, null, null, null, log);
    }

    /** 0.7.1：注入确定性随机源（setRandomVariable 测试用）。 */
    private ActionExecutor executorWithRng(java.util.random.RandomGenerator rng) {
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

    // ---------- 0.7.3-ultrareview：formatNumber 非有限 → "0"（变量库污染修复）----------

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

    // ---------- playSound（拒绝分支；真路径留批次 3 游戏内 MVP） ----------

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
        // null-jdbi WallRepo：loadById 内部吞异常返 empty（既有防御纪律）
        WallRepo repo = new WallRepo(log, null);
        ActionExecutor ex = new ActionExecutor(store, ticker, null, repo, null, log);
        TraceStep step = ex.execute(WALL, "b",
                new Action.PlaySound("block.note_block.pling", 1.0, 1.0, "near"));
        assertEquals("error", step.result());
        assertTrue(step.detail().contains("wall"), step.detail());
    }

    // ---------- runCommand（0.7.0-P3 A1：命令模板系统接线） ----------

    @Test
    void runCommand_withoutTemplates_blockedStep() {
        // 旧 6 参构造（templates supplier 缺）→ 模板恒查无 → blocked
        TraceStep step = executor().execute(WALL, "b",
                new Action.RunCommand("give-reward", Map.of("player", "Bob")));
        assertEquals("blocked", step.result());
        assertTrue(step.detail().contains("give-reward"), step.detail());
    }

    @Test
    void runCommand_renderError_errorStep() {
        var tpls = Map.of("announce", new ac.haru.hikaricanvas.HikariCanvasConfig.CommandTemplate(
                "say {msg}", Map.of("msg",
                        ac.haru.hikaricanvas.HikariCanvasConfig.ParamSpec.defaults())));
        ActionExecutor ex = new ActionExecutor(null, null, null, null, null,
                () -> tpls, java.util.List::of, null, log);
        // 参数缺失 → error step（不 blocked——模板在，是规则方填错）
        TraceStep step = ex.execute(WALL, "b",
                new Action.RunCommand("announce", Map.of()));
        assertEquals("error", step.result());
        assertTrue(step.detail().contains("msg"), step.detail());
    }

    @Test
    void runCommand_renderOk_okStep_andDispatchFailureContained() {
        var tpls = Map.of("announce", new ac.haru.hikaricanvas.HikariCanvasConfig.CommandTemplate(
                "say {msg}", Map.of("msg",
                        ac.haru.hikaricanvas.HikariCanvasConfig.ParamSpec.defaults())));
        ActionExecutor ex = new ActionExecutor(null, null, null, null, null,
                () -> tpls, java.util.List::of, null, log);
        // plugin=null 直跑：Bukkit.dispatchCommand 在单测无 server 环境抛 → work 内自吞
        // （三层隔离），step 仍 ok（提交语义；同 playSound 范式）
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

    // ---------- 0.7.1：setElementProperties / nudge（委托 applier） ----------

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

    // ---------- 0.7.1：sendMessage ----------

    @Test
    void sendMessage_noTriggerPlayer_okSkip() {
        ScriptRunner.TRIGGER_DETAIL.remove(); // 无触发玩家
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
        // 触发玩家有值，plugin=null 直跑：Bukkit.getPlayerExact 无 server 抛 → work 内自吞（三层隔离）
        ScriptRunner.TRIGGER_DETAIL.set("Alice");
        try {
            TraceStep step = executor().execute(WALL, "b",
                    new Action.SendMessage("hi", "chat", "trigger"));
            assertEquals("ok", step.result());
            assertTrue(step.detail().contains("Alice"), step.detail());
        } finally {
            ScriptRunner.TRIGGER_DETAIL.remove();
        }
    }

    // ---------- 0.7.2-P3：sendMessage target 分流（trigger / all 广播）----------

    /**
     * target=all：不读 TRIGGER_DETAIL（即使无触发玩家也不"跳过"），走 {@code
     * Bukkit.getOnlinePlayers()} 广播分支。plugin=null 直跑：无 server 时
     * getOnlinePlayers 抛 → work 内自吞（三层隔离）→ 仍返 ok（detail 标 broadcast/all）。
     * 结构性断言：detail <b>不含</b>"无触发玩家"，证明走了广播分支而非 trigger-skip。
     */
    @Test
    void sendMessage_targetAll_takesBroadcastBranch_notTriggerSkip() {
        ScriptRunner.TRIGGER_DETAIL.remove(); // 无触发玩家
        TraceStep step = executor().execute(WALL, "b",
                new Action.SendMessage("大家好", "chat", "all"));
        assertEquals("ok", step.result());
        assertFalse(step.detail().contains("no trigger player"),
                "target=all 应走广播分支，不应因无触发玩家而跳过: " + step.detail());
    }

    /** target=all 即使有触发玩家也走广播分支（不只发触发者）。 */
    @Test
    void sendMessage_targetAll_withTriggerPlayer_stillBroadcast() {
        ScriptRunner.TRIGGER_DETAIL.set("Alice");
        try {
            TraceStep step = executor().execute(WALL, "b",
                    new Action.SendMessage("大家好", "actionbar", "all"));
            assertEquals("ok", step.result());
            // 广播 detail 不应是 trigger 玩家专属的 "msg→Alice"
            assertFalse(step.detail().contains("Alice"),
                    "target=all 广播 detail 不应标触发玩家名: " + step.detail());
        } finally {
            ScriptRunner.TRIGGER_DETAIL.remove();
        }
    }

    /** target=trigger 无触发玩家仍按现有逻辑跳过（向后兼容回归）。 */
    @Test
    void sendMessage_targetTrigger_noPlayer_okSkip() {
        ScriptRunner.TRIGGER_DETAIL.remove();
        TraceStep step = executor().execute(WALL, "b",
                new Action.SendMessage("hi", "chat", "trigger"));
        assertEquals("ok", step.result());
        assertTrue(step.detail().contains("no trigger player"), step.detail());
    }

    // ---------- 0.7.1：setRandomVariable ----------

    @Test
    void setRandomVariable_seeded_deterministic() {
        store.create("user:w-1", "roll", VarType.NUMBER, "0", "manual");
        ActionExecutor ex = executorWithRng(new java.util.Random(42));
        TraceStep step = ex.execute(WALL, "b",
                new Action.SetRandomVariable("user/roll", 1.0, 1.0));
        assertEquals("ok", step.result());
        assertEquals("1", valueOf("user:w-1/roll"), "min==max==1 → 恒 1（整数 roll 含 max）");
    }

    @Test
    void setRandomVariable_integerRange_inBounds() {
        store.create("user:w-1", "roll", VarType.NUMBER, "0", "manual");
        ActionExecutor ex = executorWithRng(new java.util.Random(7));
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

    // ---------- 0.7.1：scaleVariable ----------

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

    // ---------- 0.7.1：playTimelineAwait（execute 做 play；挂起由 Runner） ----------

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

    // ---------- 0.7.1：timelineDurationMs ----------

    @Test
    void timelineDurationMs_noWallRepo_returnsZero() {
        assertEquals(0L, executor().timelineDurationMs(WALL, "tl-1"),
                "wallRepo 缺 → 0（Runner 据此不挂起）");
    }

    // ---------- 防御 + 三层隔离 ----------

    @Test
    void waitAndIf_reachingExecutor_defensiveErrorStep() {
        assertEquals("error", executor().execute(WALL, "b", new Action.Wait(100)).result());
        assertEquals("error", executor().execute(WALL, "b",
                new Action.If("1 == 1", List.of(), List.of())).result());
    }

    // ---------- 0.7.1-P5：playParticle / stopScript / waitUntil ----------

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

    // ---------- 0.7.2-P2：copyVariable / appendVariable（变量积木真实现） ----------

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

    // ---------- 0.7.2-P2：cloneElement / deleteElement（dispatch 到 ElementPropertyApplier） ----------

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

    // ---------- 0.7.3-ultrareview：roundVariable StrictNumber 对齐 ----------

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
        // Java Math.round(2.5) = 3（half-up，非 half-even）
        assertEquals("3", valueOf("user:w-1/v"), "round(2.5) = 3（half-up）");
    }

    @Test
    void roundVariable_round_negative_half_asymmetric() {
        store.create("user:w-1", "v", VarType.NUMBER, null, "manual");
        store.setValue("user:w-1/v", "-2.5", null);
        TraceStep step = executor().execute(WALL, "b",
                new Action.RoundVariable("user/v", "round"));
        assertEquals("ok", step.result());
        // Java Math.round(-2.5) = -2（向正无穷的 half-up 非对称）
        assertEquals("-2", valueOf("user:w-1/v"), "round(-2.5) = -2（Java Math.round 语义）");
    }

    @Test
    void roundVariable_nanString_error() {
        // "NaN" 不符合 StrictNumber 文法 → error step（不写回，变量值不变）。
        // 旧 Double.parseDouble("NaN") 会写脏 "NaN"；静默按 0 又吞错——取整非数值一律 error。
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
        // "Infinity" 不符合 StrictNumber 文法 → error step（不写回）
        store.create("user:w-1", "v", VarType.NUMBER, null, "manual");
        store.setValue("user:w-1/v", "Infinity", null);
        TraceStep step = executor().execute(WALL, "b",
                new Action.RoundVariable("user/v", "floor"));
        assertEquals("error", step.result());
        assertEquals("Infinity", valueOf("user:w-1/v"), "error 不写回");
    }

    @Test
    void roundVariable_hexString_error() {
        // "0x1p4"（Double.parseDouble 接受但 StrictNumber 拒）→ error step
        store.create("user:w-1", "v", VarType.NUMBER, null, "manual");
        store.setValue("user:w-1/v", "0x1p4", null);
        TraceStep step = executor().execute(WALL, "b",
                new Action.RoundVariable("user/v", "round"));
        assertEquals("error", step.result());
        assertEquals("0x1p4", valueOf("user:w-1/v"), "error 不写回");
    }

    @Test
    void roundVariable_trailingSuffix_error() {
        // "5d"（Double.parseDouble 旧版接受但 StrictNumber 拒）→ error step
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

    // ---------- fakes ----------

    /** no-op 持久化 Dao（照 VariableStoreTest.FakeUserVariableDao 范式，内存即弃）。 */
    private static final class NoopDao extends UserVariableDao {
        NoopDao() {
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
    }
}
