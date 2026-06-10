package moe.hikari.canvas.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import moe.hikari.canvas.script.Action;
import moe.hikari.canvas.script.ScriptRule;
import moe.hikari.canvas.script.ScriptStore;
import moe.hikari.canvas.script.Trigger;
import moe.hikari.canvas.storage.Database;
import moe.hikari.canvas.storage.MigrationRunner;
import moe.hikari.canvas.storage.ScriptDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.7.0 P1-7：验证 ready payload 携带 wall 脚本快照的 wire 行为（照
 * {@link ReadyPayloadAliasTest} 装配）。
 *
 * <p>WebServer.handleAuth 末尾：{@code payload.put("scripts", (scriptStore != null
 * && wallId != null) ? scriptStore.listByWall(wallId) : List.of())}。
 * 本测试不 boot 全栈，直接模拟同款逻辑断言序列化 JSON。</p>
 */
class ReadyPayloadScriptsTest {

    private Path tmpDir;
    private Database database;
    private ScriptStore store;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("hikari-ready-scripts-");
        Logger log = Logger.getLogger("test");
        database = new Database(log, tmpDir.resolve("data.db"));
        new MigrationRunner(database.jdbi(), log).run();
        store = new ScriptStore(log, new ScriptDao(log, database.jdbi()), 16);
        insertWall("w-aaaa1111");
        insertWall("w-bbbb2222");
        mapper = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (database != null) database.close();
        if (tmpDir != null && Files.exists(tmpDir)) {
            try (var s = Files.walk(tmpDir)) {
                s.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
            }
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger ORIGIN_COUNTER =
            new java.util.concurrent.atomic.AtomicInteger();

    private void insertWall(String wallId) {
        int ox = ORIGIN_COUNTER.incrementAndGet();
        database.jdbi().useHandle(h -> h.createUpdate(
                "INSERT INTO walls(wall_id, world, origin_x, origin_y, origin_z, facing, "
                        + "width_maps, height_maps, map_ids, project_json, "
                        + "owner_uuid, owner_name, alias, published_at, "
                        + "template_id, template_version, created_at, updated_at) "
                        + "VALUES(:id, 'world', :ox, 0, 0, 'NORTH', 1, 1, '', '{}', "
                        + "'00000000-0000-0000-0000-000000000001', 'Owner', NULL, NULL, "
                        + "NULL, NULL, 0, 0)")
                .bind("id", wallId)
                .bind("ox", ox)
                .execute());
    }

    /** 模拟 WebServer.handleAuth 同款注入逻辑（store 可为 null）。 */
    private Map<String, Object> buildReadyPayload(ScriptStore scriptStore, String wallId) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("wallId", wallId);
        payload.put("scripts", (scriptStore != null && wallId != null)
                ? scriptStore.listByWall(wallId) : List.of());
        return payload;
    }

    private ScriptRule incoming(String name) {
        return new ScriptRule(null, null, true, name,
                new Trigger.VariableChange("user/score"),
                List.of(new Action.Log("hi")), "{}");
    }

    /** 墙有 2 条规则 → scripts list size 2，首条含 id / name。 */
    @Test
    void readyContainsScriptsArrayWithRules() throws Exception {
        store.create("w-aaaa1111", incoming("规则一"));
        store.create("w-aaaa1111", incoming("规则二"));

        String json = mapper.writeValueAsString(buildReadyPayload(store, "w-aaaa1111"));
        JsonNode scripts = mapper.readTree(json).get("scripts");

        assertNotNull(scripts, "scripts must be present");
        assertTrue(scripts.isArray(), "scripts must serialize as array");
        assertEquals(2, scripts.size());
        JsonNode first = scripts.get(0);
        assertNotNull(first.get("id"), "rule entry must carry id");
        assertTrue(first.get("id").asText().startsWith("sr-"));
        assertEquals("规则一", first.get("name").asText());
        // wire 形态抽查：trigger 扁平 + type 判别
        assertEquals("variableChange", first.get("trigger").get("type").asText());
    }

    /** scriptStore 为 null → scripts 恒为空 list（字段不缺失）。 */
    @Test
    void nullStoreYieldsEmptyScriptsArray() throws Exception {
        String json = mapper.writeValueAsString(buildReadyPayload(null, "w-aaaa1111"));
        JsonNode scripts = mapper.readTree(json).get("scripts");
        assertNotNull(scripts, "scripts field must always exist");
        assertTrue(scripts.isArray());
        assertEquals(0, scripts.size());
    }

    /** wall 隔离：wallA 的 scripts 不含 wallB 的规则。 */
    @Test
    void scriptsIsolatedByWall() throws Exception {
        store.create("w-aaaa1111", incoming("A 墙规则"));
        store.create("w-bbbb2222", incoming("B 墙规则"));

        String jsonA = mapper.writeValueAsString(buildReadyPayload(store, "w-aaaa1111"));
        String jsonB = mapper.writeValueAsString(buildReadyPayload(store, "w-bbbb2222"));

        assertTrue(jsonA.contains("A 墙规则"));
        assertTrue(!jsonA.contains("B 墙规则"), "wall-A ready must not leak wall-B scripts");
        assertTrue(jsonB.contains("B 墙规则"));
        assertTrue(!jsonB.contains("A 墙规则"));
    }
}
