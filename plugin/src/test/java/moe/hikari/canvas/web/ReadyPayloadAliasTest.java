package moe.hikari.canvas.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import moe.hikari.canvas.storage.Database;
import moe.hikari.canvas.storage.MigrationRunner;
import moe.hikari.canvas.storage.VariableAliasDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.4.2：验证 ready payload 携带 wall 别名快照的 wire 行为。
 *
 * <p>WebServer.handleAuth 末尾：{@code payload.put("aliases", variableAliasDao.loadByWall(wallId))}。
 * 本测试不 boot 全栈，直接模拟同款逻辑断言序列化 JSON：</p>
 *
 * <ol>
 *   <li>aliases 字段在 ready payload 内为 Map / object（非 array）</li>
 *   <li>wall 隔离：wallA 的 aliases 不含 wallB</li>
 *   <li>空 store 序列化为 {@code {}}（不为 null / 缺失）</li>
 *   <li>跨 namespace 别名（user/schedule/system）都能携带</li>
 * </ol>
 */
class ReadyPayloadAliasTest {

    private Path tmpDir;
    private Database database;
    private VariableAliasDao dao;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("hikari-ready-alias-");
        Logger log = Logger.getLogger("test");
        database = new Database(log, tmpDir.resolve("data.db"));
        new MigrationRunner(database.jdbi(), log).run();
        dao = new VariableAliasDao(log, database.jdbi());
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

    private Map<String, Object> buildReadyPayload(String wallId) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("wallId", wallId);
        payload.put("aliases", dao.loadByWall(wallId));
        return payload;
    }

    @Test
    void readyContainsAliasesObjectForWall() throws Exception {
        long now = System.currentTimeMillis();
        dao.upsert("w-aaaa1111", "user:w-aaaa1111/red", "红队", now);
        dao.upsert("w-aaaa1111", "schedule:w-aaaa1111/eta_seconds", "ETA秒", now);
        dao.upsert("w-aaaa1111", "system/server.time", "服务器时间", now);

        String json = mapper.writeValueAsString(buildReadyPayload("w-aaaa1111"));
        JsonNode root = mapper.readTree(json);
        JsonNode aliases = root.get("aliases");

        assertNotNull(aliases, "aliases must be present");
        assertTrue(aliases.isObject(), "aliases must serialize as object (Map<fullName, alias>)");
        assertEquals(3, aliases.size());
        assertEquals("红队", aliases.get("user:w-aaaa1111/red").asText());
        assertEquals("ETA秒", aliases.get("schedule:w-aaaa1111/eta_seconds").asText());
        assertEquals("服务器时间", aliases.get("system/server.time").asText());
    }

    @Test
    void readyAliasesIsolatedByWall() throws Exception {
        long now = 0L;
        dao.upsert("w-aaaa1111", "user:w-aaaa1111/x", "X-A", now);
        dao.upsert("w-bbbb2222", "user:w-bbbb2222/y", "Y-B", now);

        String jsonA = mapper.writeValueAsString(buildReadyPayload("w-aaaa1111"));
        String jsonB = mapper.writeValueAsString(buildReadyPayload("w-bbbb2222"));

        assertTrue(jsonA.contains("\"X-A\""));
        assertFalse(jsonA.contains("Y-B"), "wall-A ready must not leak wall-B aliases: " + jsonA);
        assertTrue(jsonB.contains("\"Y-B\""));
        assertFalse(jsonB.contains("X-A"));
    }

    @Test
    void emptyStoreYieldsEmptyAliasesObject() throws Exception {
        String json = mapper.writeValueAsString(buildReadyPayload("w-aaaa1111"));
        JsonNode aliases = mapper.readTree(json).get("aliases");
        assertNotNull(aliases, "aliases field must always exist");
        assertTrue(aliases.isObject());
        assertEquals(0, aliases.size());
    }

    @Test
    void aliasUpsertReplacesPreviousValue_andStillSingleEntry() throws Exception {
        long now = 1000L;
        dao.upsert("w-aaaa1111", "user:w-aaaa1111/x", "First", now);
        dao.upsert("w-aaaa1111", "user:w-aaaa1111/x", "Second", now + 100);

        String json = mapper.writeValueAsString(buildReadyPayload("w-aaaa1111"));
        JsonNode aliases = mapper.readTree(json).get("aliases");
        assertEquals(1, aliases.size());
        assertEquals("Second", aliases.get("user:w-aaaa1111/x").asText());
        assertFalse(json.contains("\"First\""));
    }

    @Test
    void multipleNamespacesCoexist() throws Exception {
        long now = 0L;
        // 模拟所有支持的 namespace 形态
        dao.upsert("w-aaaa1111", "user:w-aaaa1111/u", "User", now);
        dao.upsert("w-aaaa1111", "papi/p", "Papi", now);
        dao.upsert("w-aaaa1111", "system/s", "Sys", now);
        dao.upsert("w-aaaa1111", "myplugin/x", "Plug", now);
        dao.upsert("w-aaaa1111", "scoreboard.kills.Steve", "击杀", now);

        Map<String, String> map = dao.loadByWall("w-aaaa1111");
        assertEquals(5, map.size());
        assertEquals("User", map.get("user:w-aaaa1111/u"));
        assertEquals("Papi", map.get("papi/p"));
        assertEquals("Sys", map.get("system/s"));
        assertEquals("Plug", map.get("myplugin/x"));
        assertEquals("击杀", map.get("scoreboard.kills.Steve"));
    }
}
