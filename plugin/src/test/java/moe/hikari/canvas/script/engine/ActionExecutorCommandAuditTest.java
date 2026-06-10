package moe.hikari.canvas.script.engine;

import moe.hikari.canvas.HikariCanvasConfig.CommandTemplate;
import moe.hikari.canvas.HikariCanvasConfig.ParamSpec;
import moe.hikari.canvas.script.Action;
import moe.hikari.canvas.storage.AuditLog;
import moe.hikari.canvas.storage.Database;
import moe.hikari.canvas.storage.MigrationRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.7.0-P3 A1：runCommand 的 {@code SCRIPT_COMMAND_EXECUTED} audit（真 DB 直读断言，
 * 照 {@code ScriptOpPermissionDispatchTest} 范式）。
 */
class ActionExecutorCommandAuditTest {

    private static final Logger LOG =
            Logger.getLogger(ActionExecutorCommandAuditTest.class.getName());

    private Path tmpDir;
    private Database database;
    private AuditLog auditLog;

    @BeforeEach
    void setUp() throws Exception {
        LOG.setUseParentHandlers(false);
        LOG.setLevel(Level.OFF); // dispatch 失败 WARNING 噪音静音（无 Bukkit server 环境）
        tmpDir = Files.createTempDirectory("hikari-cmd-audit-test-");
        database = new Database(LOG, tmpDir.resolve("data.db"));
        new MigrationRunner(database.jdbi(), LOG).run();
        auditLog = new AuditLog(database.jdbi(), LOG);
    }

    @AfterEach
    void tearDown() throws Exception {
        ScriptRunner.RULE_KEY.remove();
        if (database != null) database.close();
        if (tmpDir != null && Files.exists(tmpDir)) {
            try (var s = Files.walk(tmpDir)) {
                s.sorted(Comparator.reverseOrder())
                        .map(Path::toFile).forEach(java.io.File::delete);
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
                () -> tpls, List::of, auditLog, LOG);

        // 模拟 runner 线程上下文（生产路径 RULE_KEY 由 ScriptRunner.runFrames 置位）
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
                () -> tpls, List::of, auditLog, LOG);

        // 模板查无 → blocked，不 audit
        assertEquals("blocked", ex.execute("w-1", "b",
                new Action.RunCommand("nope", Map.of())).result());
        // 参数缺失 → error，不 audit
        assertEquals("error", ex.execute("w-1", "b",
                new Action.RunCommand("announce", Map.of())).result());
        assertTrue(auditRows().isEmpty(), "未执行的命令不留 SCRIPT_COMMAND_EXECUTED");
    }
}
