package ac.haru.hikaricanvas.storage;

import ac.haru.hikaricanvas.variable.VariableAlias;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.4.2：{@link VariableAliasDao} CRUD + 级联删测试。
 *
 * <p>真实 SQLite + MigrationRunner 跑 V001..V014；FK CASCADE 由 schema 触发。每个测试新建临时
 * dir + data.db，互相隔离。</p>
 */
class VariableAliasDaoTest {

    private Path tmpDir;
    private Database database;
    private VariableAliasDao dao;

    @BeforeEach
    void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("hikari-alias-test-");
        Logger log = Logger.getLogger("test");
        database = new Database(log, tmpDir.resolve("data.db"));
        new MigrationRunner(database.jdbi(), log).run();
        dao = new VariableAliasDao(log, database.jdbi());
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

    // ──────────────────────────────────────────────────────────
    //  upsert / loadByWall
    // ──────────────────────────────────────────────────────────

    @Test
    void loadByWall_emptyDb_returnsEmptyMap() {
        assertTrue(dao.loadByWall("nonexistent").isEmpty());
        assertTrue(dao.loadByWall("w-test-1").isEmpty());
    }

    @Test
    void upsert_createsThenUpdates() {
        long now = System.currentTimeMillis();
        dao.upsert("w-test-1", "user:w-test-1/red_score", "红队", now);
        Map<String, String> first = dao.loadByWall("w-test-1");
        assertEquals(1, first.size());
        assertEquals("红队", first.get("user:w-test-1/red_score"));

        dao.upsert("w-test-1", "user:w-test-1/red_score", "Red", now + 1000);
        Map<String, String> second = dao.loadByWall("w-test-1");
        assertEquals(1, second.size());
        assertEquals("Red", second.get("user:w-test-1/red_score"));
    }

    @Test
    void upsert_supportsCrossNamespace() {
        long now = System.currentTimeMillis();
        dao.upsert("w-test-1", "user:w-test-1/red", "红队", now);
        dao.upsert("w-test-1", "schedule:w-test-1/eta_seconds", "ETA秒", now);
        dao.upsert("w-test-1", "system/server.time", "服务器时间", now);
        Map<String, String> all = dao.loadByWall("w-test-1");
        assertEquals(3, all.size());
        assertEquals("红队", all.get("user:w-test-1/red"));
        assertEquals("ETA秒", all.get("schedule:w-test-1/eta_seconds"));
        assertEquals("服务器时间", all.get("system/server.time"));
    }

    // ──────────────────────────────────────────────────────────
    //  per-wall 隔离
    // ──────────────────────────────────────────────────────────

    @Test
    void perWall_isolation() {
        insertWall("w-test-2");
        long now = System.currentTimeMillis();
        // 不同 wall 给同一 fullName 起不同别名
        dao.upsert("w-test-1", "system/server.time", "时间A", now);
        dao.upsert("w-test-2", "system/server.time", "时间B", now);

        assertEquals("时间A", dao.loadByWall("w-test-1").get("system/server.time"));
        assertEquals("时间B", dao.loadByWall("w-test-2").get("system/server.time"));
    }

    // ──────────────────────────────────────────────────────────
    //  delete
    // ──────────────────────────────────────────────────────────

    @Test
    void delete_existing_returnsOne() {
        dao.upsert("w-test-1", "user:w-test-1/red", "红队", 0L);
        assertEquals(1, dao.delete("w-test-1", "user:w-test-1/red"));
        assertTrue(dao.loadByWall("w-test-1").isEmpty());
    }

    @Test
    void delete_nonexistent_returnsZero() {
        assertEquals(0, dao.delete("w-test-1", "user:w-test-1/ghost"));
    }

    @Test
    void delete_doesNotAffectOtherEntries() {
        long now = 0L;
        dao.upsert("w-test-1", "user:w-test-1/a", "Alpha", now);
        dao.upsert("w-test-1", "user:w-test-1/b", "Beta", now);
        dao.delete("w-test-1", "user:w-test-1/a");
        Map<String, String> remaining = dao.loadByWall("w-test-1");
        assertEquals(1, remaining.size());
        assertEquals("Beta", remaining.get("user:w-test-1/b"));
    }

    // ──────────────────────────────────────────────────────────
    //  deleteByWall + 级联
    // ──────────────────────────────────────────────────────────

    @Test
    void deleteByWall_clearsAllForThatWall() {
        insertWall("w-test-2");
        long now = 0L;
        dao.upsert("w-test-1", "user:w-test-1/a", "A", now);
        dao.upsert("w-test-1", "user:w-test-1/b", "B", now);
        dao.upsert("w-test-2", "user:w-test-2/c", "C", now);

        int affected = dao.deleteByWall("w-test-1");
        assertEquals(2, affected);
        assertTrue(dao.loadByWall("w-test-1").isEmpty());
        assertEquals(1, dao.loadByWall("w-test-2").size());
    }

    @Test
    void cascade_onWallDelete_clearsAliases() {
        long now = 0L;
        dao.upsert("w-test-1", "user:w-test-1/red", "红队", now);
        dao.upsert("w-test-1", "system/server.time", "时间", now);

        // 删 walls 行 → FK CASCADE 应清掉 variable_aliases
        database.jdbi().useHandle(h -> h.createUpdate("DELETE FROM walls WHERE wall_id = :id")
                .bind("id", "w-test-1")
                .execute());

        assertTrue(dao.loadByWall("w-test-1").isEmpty());
        int count = database.jdbi().withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM variable_aliases WHERE wall_id = :w")
                .bind("w", "w-test-1")
                .mapTo(Integer.class)
                .one());
        assertEquals(0, count);
    }

    // ──────────────────────────────────────────────────────────
    //  loadAll
    // ──────────────────────────────────────────────────────────

    @Test
    void loadAll_returnsAllAliases() {
        insertWall("w-test-2");
        long now = System.currentTimeMillis();
        dao.upsert("w-test-1", "user:w-test-1/a", "A1", now);
        dao.upsert("w-test-2", "user:w-test-2/b", "B2", now);

        List<VariableAlias> all = dao.loadAll();
        assertEquals(2, all.size());
        VariableAlias a1 = all.stream().filter(x -> "A1".equals(x.alias())).findFirst().orElseThrow();
        assertEquals("w-test-1", a1.wallId());
        assertEquals("user:w-test-1/a", a1.fullName());
        assertNotNull(a1.createdAt());
        assertEquals(a1.createdAt(), a1.updatedAt());
    }

    @Test
    void loadAll_emptyDb_returnsEmpty() {
        assertTrue(dao.loadAll().isEmpty());
    }

    // ──────────────────────────────────────────────────────────
    //  timestamp 行为
    // ──────────────────────────────────────────────────────────

    @Test
    void upsert_existingRow_updatesUpdatedAtButKeepsCreatedAt() {
        long t1 = 1000L;
        long t2 = 2000L;
        dao.upsert("w-test-1", "user:w-test-1/a", "A", t1);
        dao.upsert("w-test-1", "user:w-test-1/a", "Renamed", t2);

        List<VariableAlias> all = dao.loadAll();
        assertEquals(1, all.size());
        VariableAlias row = all.get(0);
        assertEquals("Renamed", row.alias());
        // created_at 不变；updated_at 刷新到 t2（SQL ON CONFLICT 只更新 alias + updated_at）
        assertEquals(t1, row.createdAt());
        assertEquals(t2, row.updatedAt());
    }

    // ──────────────────────────────────────────────────────────
    //  loadByWall 排序稳定（按 full_name）
    // ──────────────────────────────────────────────────────────

    @Test
    void loadByWall_returnsMapSortedByFullName() {
        long now = 0L;
        dao.upsert("w-test-1", "zeta/key", "Z", now);
        dao.upsert("w-test-1", "alpha/key", "A", now);
        dao.upsert("w-test-1", "mid/key", "M", now);

        Map<String, String> map = dao.loadByWall("w-test-1");
        // LinkedHashMap 应按 SQL ORDER BY full_name 顺序
        List<String> keys = List.copyOf(map.keySet());
        assertEquals(List.of("alpha/key", "mid/key", "zeta/key"), keys);
    }

    @Test
    void delete_emptyAliasFromDeletedWall_safe() {
        // 不存在的 wall_id 调用应不抛异常
        assertEquals(0, dao.deleteByWall("nonexistent-wall"));
        assertFalse(dao.loadByWall("nonexistent-wall").containsKey("any"));
        assertNull(dao.loadByWall("nonexistent-wall").get("any"));
    }
}
