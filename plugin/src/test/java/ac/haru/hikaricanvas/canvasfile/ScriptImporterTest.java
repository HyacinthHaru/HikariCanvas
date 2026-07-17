package ac.haru.hikaricanvas.canvasfile;

import ac.haru.hikaricanvas.HikariCanvasConfig.CommandTemplate;
import ac.haru.hikaricanvas.HikariCanvasConfig.ParamSpec;
import ac.haru.hikaricanvas.script.ScriptRule;
import ac.haru.hikaricanvas.script.ScriptStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.8-A4 Task 18：{@link ScriptImporter} —— scripts.json 解析 + wallId 重绑 + 全量重校验 + 落库。
 *
 * <p>装配纯内存 {@link ScriptStore}（{@code dao = null}，配额可控，无须 temp DB / FK），
 * 目标墙固定 {@code w-new}。命令模板表由各测试按需传入（缺模板 → {@code script-command-blocked}）。
 * 每条规则带至少一个动作（{@link ac.haru.hikaricanvas.script.ScriptRuleValidator} 拒空动作列表）。</p>
 */
class ScriptImporterTest {

    private static final Logger LOG = Logger.getLogger("script-importer-test");
    private static final String TARGET = "w-new";

    /** 纯内存 store（dao=null）+ 模板表 → importer。max = 单墙配额上限。 */
    private static ScriptImporter newImporter(Map<String, CommandTemplate> templates, int max) {
        ScriptStore store = new ScriptStore(LOG, null, max);
        return new ScriptImporter(store, templates == null ? Map::of : () -> templates);
    }

    private static ScriptStore storeOf(ScriptImporter importer) {
        return importer.store();
    }

    @Test
    void importScripts_rebindsWallIdAndPersists() {
        ScriptImporter importer = newImporter(Map.of(), 16);
        // wallId="w-old" + id="sr-old" 都应被服务端权威覆写（wallId→w-new，id→新生成）。
        String scriptsJson = "[{\"id\":\"sr-old\",\"wallId\":\"w-old\",\"enabled\":true,\"name\":\"r\","
                + "\"trigger\":{\"type\":\"wallReady\"},"
                + "\"actions\":[{\"type\":\"log\",\"message\":\"hi\"}],\"blockLayout\":\"{}\"}]";

        List<ImportWarning> warns = importer.importScripts(scriptsJson.getBytes(), TARGET);

        List<ScriptRule> stored = storeOf(importer).listByWall(TARGET);
        assertEquals(1, stored.size());
        assertEquals(TARGET, stored.get(0).wallId(), "wallId 已重绑到目标墙");
        assertNotEquals("sr-old", stored.get(0).id(), "ruleId 必须重新生成");
        assertTrue(warns.isEmpty(), "干净规则不应产生 warning");
    }

    @Test
    void importScripts_invalidTrigger_skippedWithWarning() {
        ScriptImporter importer = newImporter(Map.of(), 16);
        // 未知 trigger type → 解析期单条失败（多态反序列化抛）→ 跳过 + warning。
        String bad = "[{\"id\":\"x\",\"wallId\":\"w\",\"enabled\":true,\"name\":\"r\","
                + "\"trigger\":{\"type\":\"NOT_A_TRIGGER\"},"
                + "\"actions\":[{\"type\":\"log\",\"message\":\"hi\"}],\"blockLayout\":\"{}\"}]";

        List<ImportWarning> warns = importer.importScripts(bad.getBytes(), TARGET);

        assertEquals(0, storeOf(importer).listByWall(TARGET).size(), "非法规则不落库");
        assertFalse(warns.isEmpty(), "非法规则应产生 warning");
        assertEquals("script-invalid", warns.get(0).kind());
    }

    @Test
    void importScripts_invalidStructure_skippedWithWarning() {
        ScriptImporter importer = newImporter(Map.of(), 16);
        // 合法 trigger 但空动作列表 → ScriptRuleValidator.validate 拒（“动作列表不能为空”）。
        String bad = "[{\"id\":\"x\",\"wallId\":\"w\",\"enabled\":true,\"name\":\"r\","
                + "\"trigger\":{\"type\":\"wallReady\"},\"actions\":[],\"blockLayout\":\"{}\"}]";

        List<ImportWarning> warns = importer.importScripts(bad.getBytes(), TARGET);

        assertEquals(0, storeOf(importer).listByWall(TARGET).size());
        assertEquals(1, warns.size());
        assertEquals("script-invalid", warns.get(0).kind());
    }

    @Test
    void importScripts_missingCommandTemplate_warnsButStillPersists() {
        // runCommand 引用的 templateId 本服模板表里没有 → script-command-blocked，但规则照常落库。
        ScriptImporter importer = newImporter(Map.of(), 16);
        String json = "[{\"id\":\"x\",\"wallId\":\"w\",\"enabled\":true,\"name\":\"r\","
                + "\"trigger\":{\"type\":\"wallReady\"},"
                + "\"actions\":[{\"type\":\"runCommand\",\"templateId\":\"give-diamond\",\"params\":{}}],"
                + "\"blockLayout\":\"{}\"}]";

        List<ImportWarning> warns = importer.importScripts(json.getBytes(), TARGET);

        assertEquals(1, storeOf(importer).listByWall(TARGET).size(), "缺模板规则仍落库");
        List<ImportWarning> blocked = warns.stream()
                .filter(w -> "script-command-blocked".equals(w.kind())).toList();
        assertEquals(1, blocked.size());
        assertEquals("give-diamond", blocked.get(0).detail());
    }

    @Test
    void importScripts_presentCommandTemplate_noBlockedWarning() {
        // 模板表里有 give-diamond → 不应产生 script-command-blocked。
        Map<String, CommandTemplate> templates = Map.of(
                "give-diamond", new CommandTemplate("give {player} diamond",
                        Map.of("player", ParamSpec.defaults())));
        ScriptImporter importer = newImporter(templates, 16);
        String json = "[{\"id\":\"x\",\"wallId\":\"w\",\"enabled\":true,\"name\":\"r\","
                + "\"trigger\":{\"type\":\"wallReady\"},"
                + "\"actions\":[{\"type\":\"runCommand\",\"templateId\":\"give-diamond\","
                + "\"params\":{\"player\":\"Steve\"}}],\"blockLayout\":\"{}\"}]";

        List<ImportWarning> warns = importer.importScripts(json.getBytes(), TARGET);

        assertEquals(1, storeOf(importer).listByWall(TARGET).size());
        assertTrue(warns.isEmpty(), "模板存在时无 warning");
    }

    @Test
    void importScripts_overQuota_warnsAndStops() {
        // 配额 = 1：第一条落库，第二条触发 QuotaExceededException → script-quota + 停止后续。
        ScriptImporter importer = newImporter(Map.of(), 1);
        String json = "["
                + "{\"id\":\"a\",\"wallId\":\"w\",\"enabled\":true,\"name\":\"r1\","
                + "\"trigger\":{\"type\":\"wallReady\"},\"actions\":[{\"type\":\"log\",\"message\":\"a\"}],\"blockLayout\":\"{}\"},"
                + "{\"id\":\"b\",\"wallId\":\"w\",\"enabled\":true,\"name\":\"r2\","
                + "\"trigger\":{\"type\":\"wallReady\"},\"actions\":[{\"type\":\"log\",\"message\":\"b\"}],\"blockLayout\":\"{}\"}"
                + "]";

        List<ImportWarning> warns = importer.importScripts(json.getBytes(), TARGET);

        assertEquals(1, storeOf(importer).listByWall(TARGET).size(), "只落得下配额内的第一条");
        assertEquals(1, warns.size());
        assertEquals("script-quota", warns.get(0).kind());
    }

    @Test
    void importScripts_malformedJson_singleWarning() {
        ScriptImporter importer = newImporter(Map.of(), 16);
        List<ImportWarning> warns = importer.importScripts("not json".getBytes(), TARGET);
        assertEquals(0, storeOf(importer).listByWall(TARGET).size());
        assertEquals(1, warns.size());
        assertEquals("script-invalid", warns.get(0).kind());
    }

    @Test
    void importScripts_emptyArray_noScriptsNoWarnings() {
        ScriptImporter importer = newImporter(Map.of(), 16);
        List<ImportWarning> warns = importer.importScripts("[]".getBytes(), TARGET);
        assertEquals(0, storeOf(importer).listByWall(TARGET).size());
        assertTrue(warns.isEmpty());
    }
}
