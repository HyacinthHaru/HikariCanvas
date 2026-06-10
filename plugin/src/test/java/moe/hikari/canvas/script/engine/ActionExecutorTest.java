package moe.hikari.canvas.script.engine;

import moe.hikari.canvas.render.AnimationTicker;
import moe.hikari.canvas.script.Action;
import moe.hikari.canvas.storage.UserVariableDao;
import moe.hikari.canvas.storage.WallRepo;
import moe.hikari.canvas.variable.VarType;
import moe.hikari.canvas.variable.VariableStore;
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

    // ---------- runCommand（K4） ----------

    @Test
    void runCommand_blockedStep() {
        TraceStep step = executor().execute(WALL, "b",
                new Action.RunCommand("give-reward", Map.of("player", "Bob")));
        assertEquals("blocked", step.result());
        assertTrue(step.detail().contains("0.7.0-P3"), step.detail());
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
                r.getLevel() == Level.INFO && r.getMessage().equals("[脚本 w-1] count=7"));
        assertTrue(found, "log.info 含 wallId 前缀 + 插值后消息");
    }

    @Test
    void log_withoutStore_rawMessage() {
        TraceStep step = bare().execute(WALL, "b", new Action.Log("raw ${var:user/n}"));
        assertEquals("ok", step.result());
        assertTrue(logRecords.stream().anyMatch(r ->
                r.getMessage().equals("[脚本 w-1] raw ${var:user/n}")));
    }

    // ---------- 防御 + 三层隔离 ----------

    @Test
    void waitAndIf_reachingExecutor_defensiveErrorStep() {
        assertEquals("error", executor().execute(WALL, "b", new Action.Wait(100)).result());
        assertEquals("error", executor().execute(WALL, "b",
                new Action.If("1 == 1", List.of(), List.of())).result());
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
