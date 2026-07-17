package ac.haru.hikaricanvas.storage;

import ac.haru.hikaricanvas.script.Action;
import ac.haru.hikaricanvas.script.ScriptRule;
import ac.haru.hikaricanvas.script.Trigger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.7.0-P1-4：{@link ScriptDao} CRUD + 坏 blob 跳过 + enabled 列权威 测试。
 *
 * <p>真实 SQLite + MigrationRunner 跑 V001..V017；FK CASCADE 由 schema 触发。每个测试
 * 新建临时 dir + data.db，互相隔离（照 {@link ScheduleDaoTest} 装配）。</p>
 */
class ScriptDaoTest {

    private Path tmpDir;
    private Database database;
    private ScriptDao dao;

    @BeforeEach
    void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("hikari-script-test-");
        Logger log = Logger.getLogger("test");
        database = new Database(log, tmpDir.resolve("data.db"));
        new MigrationRunner(database.jdbi(), log).run();
        dao = new ScriptDao(log, database.jdbi());
        insertWall("w-test-1");
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

    /** AtomicInteger 给每次 insertWall 唯一 origin_x，绕开 walls UNIQUE 约束。 */
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

    /** 带嵌套 If 的样例规则——覆盖 Trigger / Action 多态 roundtrip。 */
    private static ScriptRule sampleRule(String id, String wallId, String name) {
        return new ScriptRule(id, wallId, true, name,
                new Trigger.Timer(30),
                List.of(
                        new Action.SetVariable("user/score", "1"),
                        new Action.If("${var:user/score} > 0",
                                List.of(new Action.Log("hi")),
                                List.of(new Action.Wait(500L)))),
                "{\"x\":1}");
    }

    // ──────────────────────────────────────────────────────────
    //  insert / loadByWall
    // ──────────────────────────────────────────────────────────

    @Test
    void insert_then_loadByWall() {
        ScriptRule r1 = sampleRule("sr-aaaa0001", "w-test-1", "规则一");
        ScriptRule r2 = new ScriptRule("sr-aaaa0002", "w-test-1", false, "规则二",
                new Trigger.PlayerNear(8),
                List.of(new Action.PlaySound("ding", 1.0, 1.2, "near")),
                null);
        // 故意倒序插入,验证 ORDER BY sort_order
        dao.insert(r2, 1, 1000L);
        dao.insert(r1, 0, 1000L);

        List<ScriptRule> loaded = dao.loadByWall("w-test-1");
        assertEquals(2, loaded.size());
        // 按 sort_order 升序:r1(#0) 在前
        assertEquals(r1, loaded.get(0));
        assertEquals(r2, loaded.get(1));
        // 字段逐一确认(record equals 已覆盖,这里额外点名 trigger/actions roundtrip)
        assertEquals(new Trigger.Timer(30), loaded.get(0).trigger());
        assertEquals(r1.actions(), loaded.get(0).actions());
        assertFalse(loaded.get(1).enabled());
    }

    // ──────────────────────────────────────────────────────────
    //  update
    // ──────────────────────────────────────────────────────────

    @Test
    void update_replaces_json_and_bumps_updated_at() {
        ScriptRule original = sampleRule("sr-bbbb0001", "w-test-1", "旧名");
        dao.insert(original, 0, 1000L);

        ScriptRule changed = new ScriptRule("sr-bbbb0001", "w-test-1", false, "新名",
                new Trigger.PlayerJoin(),
                List.of(new Action.Log("changed")),
                "{\"y\":2}");
        dao.update(changed, 2000L);

        List<ScriptRule> loaded = dao.loadByWall("w-test-1");
        assertEquals(1, loaded.size());
        assertEquals(changed, loaded.get(0));

        // 直接查列断言 updated_at 变化 + name 列同步
        Map<String, Object> row = database.jdbi().withHandle(h -> h.createQuery(
                "SELECT name, created_at, updated_at FROM wall_scripts WHERE id = :id")
                .bind("id", "sr-bbbb0001")
                .mapToMap()
                .one());
        assertEquals("新名", row.get("name"));
        assertEquals(1000L, ((Number) row.get("created_at")).longValue());
        assertEquals(2000L, ((Number) row.get("updated_at")).longValue());
    }

    // ──────────────────────────────────────────────────────────
    //  delete
    // ──────────────────────────────────────────────────────────

    @Test
    void delete_returns_rowcount() {
        dao.insert(sampleRule("sr-cccc0001", "w-test-1", "待删"), 0, 1000L);
        assertEquals(1, dao.delete("sr-cccc0001"));
        assertEquals(0, dao.delete("sr-cccc0001"));
        assertTrue(dao.loadByWall("w-test-1").isEmpty());
    }

    // ──────────────────────────────────────────────────────────
    //  setEnabled:列权威,不动 rule_json
    // ──────────────────────────────────────────────────────────

    @Test
    void setEnabled_only_flips_flag() {
        ScriptRule rule = sampleRule("sr-dddd0001", "w-test-1", "开关");
        assertTrue(rule.enabled());
        dao.insert(rule, 0, 1000L);

        dao.setEnabled("sr-dddd0001", false, 2000L);

        // load 出的 record 以列值为准
        List<ScriptRule> loaded = dao.loadByWall("w-test-1");
        assertFalse(loaded.get(0).enabled());
        // 其余字段不受影响
        assertEquals(rule.name(), loaded.get(0).name());
        assertEquals(rule.trigger(), loaded.get(0).trigger());
        assertEquals(rule.actions(), loaded.get(0).actions());

        // rule_json 列本身不动(内含 "enabled":true),证明 load 是列覆写而非重写 blob
        String json = database.jdbi().withHandle(h -> h.createQuery(
                "SELECT rule_json FROM wall_scripts WHERE id = :id")
                .bind("id", "sr-dddd0001")
                .mapTo(String.class)
                .one());
        assertTrue(json.contains("\"enabled\":true"));
    }

    // ──────────────────────────────────────────────────────────
    //  loadAll
    // ──────────────────────────────────────────────────────────

    @Test
    void loadAll_groups_by_wall() {
        insertWall("w-test-2");
        dao.insert(sampleRule("sr-eeee0001", "w-test-1", "墙一规则"), 0, 1000L);
        dao.insert(sampleRule("sr-eeee0002", "w-test-2", "墙二规则"), 0, 1000L);

        Map<String, List<ScriptRule>> all = dao.loadAll();
        assertEquals(2, all.size());
        assertEquals(1, all.get("w-test-1").size());
        assertEquals(1, all.get("w-test-2").size());
        assertEquals("墙一规则", all.get("w-test-1").get(0).name());
        assertEquals("墙二规则", all.get("w-test-2").get(0).name());
    }

    // ──────────────────────────────────────────────────────────
    //  FK CASCADE
    // ──────────────────────────────────────────────────────────

    @Test
    void fk_cascade_on_wall_delete() {
        dao.insert(sampleRule("sr-ffff0001", "w-test-1", "随墙删"), 0, 1000L);
        database.jdbi().useHandle(h -> h.createUpdate("DELETE FROM walls WHERE wall_id = :id")
                .bind("id", "w-test-1")
                .execute());
        assertTrue(dao.loadByWall("w-test-1").isEmpty());
        int count = database.jdbi().withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM wall_scripts WHERE wall_id = :w")
                .bind("w", "w-test-1")
                .mapTo(Integer.class)
                .one());
        assertEquals(0, count);
    }

    // ──────────────────────────────────────────────────────────
    //  坏 blob 跳过
    // ──────────────────────────────────────────────────────────

    @Test
    void bad_blob_skipped() {
        dao.insert(sampleRule("sr-0000good", "w-test-1", "好规则"), 0, 1000L);
        // 手工 INSERT 坏 JSON
        database.jdbi().useHandle(h -> h.createUpdate(
                "INSERT INTO wall_scripts(id, wall_id, enabled, name, rule_json, "
                        + "sort_order, created_at, updated_at) "
                        + "VALUES('sr-0000bad1', 'w-test-1', 1, 'broken', '{broken', 1, 0, 0)")
                .execute());

        List<ScriptRule> loaded = assertDoesNotThrow(() -> dao.loadByWall("w-test-1"));
        assertEquals(1, loaded.size());
        assertEquals("好规则", loaded.get(0).name());

        // loadAll 同样跳过
        Map<String, List<ScriptRule>> all = assertDoesNotThrow(() -> dao.loadAll());
        assertEquals(1, all.get("w-test-1").size());
    }

    // ──────────────────────────────────────────────────────────
    //  maxSortOrder
    // ──────────────────────────────────────────────────────────

    @Test
    void maxSortOrder_emptyWall_returnsMinusOne() {
        assertEquals(-1, dao.maxSortOrder("w-test-1"));
        dao.insert(sampleRule("sr-1111aaaa", "w-test-1", "a"), 0, 1000L);
        dao.insert(sampleRule("sr-1111bbbb", "w-test-1", "b"), 5, 1000L);
        assertEquals(5, dao.maxSortOrder("w-test-1"));
    }
}
