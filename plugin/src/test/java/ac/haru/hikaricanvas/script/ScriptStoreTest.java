package ac.haru.hikaricanvas.script;

import ac.haru.hikaricanvas.storage.Database;
import ac.haru.hikaricanvas.storage.MigrationRunner;
import ac.haru.hikaricanvas.storage.ScriptDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.7.0-P1-5：{@link ScriptStore} 内存镜像 + 真 Dao + tmpdir SQLite 装配链测试。
 *
 * <p>用真实 Dao（非 mock）防内存与 DB 漂移——create/delete/setEnabled 后用<b>新 Dao 实例</b>
 * 直读 DB 验证持久化；loadFromDb 验证重启恢复。装配照 {@code ScriptDaoTest}。</p>
 */
class ScriptStoreTest {

    private Path tmpDir;
    private Database database;
    private ScriptDao dao;
    private ScriptStore store;
    private Logger log;

    @BeforeEach
    void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("hikari-scriptstore-test-");
        log = Logger.getLogger("test");
        database = new Database(log, tmpDir.resolve("data.db"));
        new MigrationRunner(database.jdbi(), log).run();
        dao = new ScriptDao(log, database.jdbi());
        store = new ScriptStore(log, dao, 16);
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

    /** incoming 规则:id/wallId 故意填假值,create 应忽略并重新生成。 */
    private static ScriptRule incoming(String name) {
        return new ScriptRule("FAKE-ID", "FAKE-WALL", true, name,
                new Trigger.Timer(30),
                List.of(new Action.Log("hello")),
                "{}");
    }

    // ──────────────────────────────────────────────────────────
    //  create
    // ──────────────────────────────────────────────────────────

    @Test
    void create_assigns_id_and_persists() {
        ScriptRule created = store.create("w-test-1", incoming("规则一"));
        assertTrue(created.id().matches("sr-[0-9a-f]{8}"), "id 格式: " + created.id());
        assertEquals("w-test-1", created.wallId());
        assertEquals("规则一", created.name());

        // 新 Dao 实例直读 DB 能看到(装配链:先落库再换内存)
        ScriptDao freshDao = new ScriptDao(log, database.jdbi());
        List<ScriptRule> fromDb = freshDao.loadByWall("w-test-1");
        assertEquals(1, fromDb.size());
        assertEquals(created, fromDb.get(0));
    }

    @Test
    void create_quota_exceeded() {
        ScriptStore small = new ScriptStore(log, dao, 2);
        small.create("w-test-1", incoming("a"));
        small.create("w-test-1", incoming("b"));
        assertThrows(ScriptStore.QuotaExceededException.class,
                () -> small.create("w-test-1", incoming("c")));
        // 配额拒绝后内存 / DB 都只有 2 条
        assertEquals(2, small.listByWall("w-test-1").size());
        assertEquals(2, dao.loadByWall("w-test-1").size());
    }

    /**
     * 核心契约:DB 失败 → 内存零污染。对不存在的 wall create,FK violation 从
     * compute 传播;之后内存快照 / 反查索引都不该留任何痕迹(孤儿)。
     */
    @Test
    void dao_failure_leaves_memory_untouched() {
        // walls 表无 w-nonexistent 行 → wall_scripts FK violation,异常从 compute 传播
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> store.create("w-nonexistent", incoming("孤儿候选")));
        // 不是 Store 自己的业务异常(配额 / NotFound),而是 Dao 层 DB 异常
        assertFalse(thrown instanceof ScriptStore.QuotaExceededException, "应为 DB 异常: " + thrown);
        assertFalse(thrown instanceof ScriptStore.NotFoundException, "应为 DB 异常: " + thrown);

        // ① byWall 快照不变:该墙仍为空
        assertTrue(store.listByWall("w-nonexistent").isEmpty());
        // ② wallByRule 反查索引没留孤儿:用 incoming 的 id 反查 → NotFound
        assertThrows(ScriptStore.NotFoundException.class,
                () -> store.update("FAKE-ID", incoming("x")));
        // DB 同样无残留
        assertTrue(new ScriptDao(log, database.jdbi()).loadByWall("w-nonexistent").isEmpty());
    }

    // ──────────────────────────────────────────────────────────
    //  update / delete / setEnabled
    // ──────────────────────────────────────────────────────────

    @Test
    void update_unknown_id_throws() {
        assertThrows(ScriptStore.NotFoundException.class,
                () -> store.update("sr-deadbeef", incoming("x")));
    }

    @Test
    void update_keeps_id_and_wall() {
        ScriptRule created = store.create("w-test-1", incoming("旧名"));
        ScriptRule updated = store.update(created.id(),
                new ScriptRule("IGNORED", "IGNORED", false, "新名",
                        new Trigger.PlayerJoin(), List.of(), null));
        assertEquals(created.id(), updated.id());
        assertEquals("w-test-1", updated.wallId());
        assertEquals("新名", updated.name());
        assertFalse(updated.enabled());
        // 内存与 DB 一致
        assertEquals(List.of(updated), store.listByWall("w-test-1"));
        assertEquals(List.of(updated), new ScriptDao(log, database.jdbi()).loadByWall("w-test-1"));
    }

    @Test
    void delete_removes_memory_and_db() {
        ScriptRule created = store.create("w-test-1", incoming("待删"));
        store.delete("w-test-1", created.id());
        assertTrue(store.listByWall("w-test-1").isEmpty());
        assertTrue(new ScriptDao(log, database.jdbi()).loadByWall("w-test-1").isEmpty());
        // 再删 → NotFound
        assertThrows(ScriptStore.NotFoundException.class,
                () -> store.delete("w-test-1", created.id()));
    }

    @Test
    void setEnabled_reflected_in_listByWall() {
        ScriptRule created = store.create("w-test-1", incoming("开关"));
        assertTrue(created.enabled());
        ScriptRule flipped = store.setEnabled("w-test-1", created.id(), false);
        assertFalse(flipped.enabled());
        assertFalse(store.listByWall("w-test-1").get(0).enabled());
        // DB 列也翻了
        assertFalse(new ScriptDao(log, database.jdbi())
                .loadByWall("w-test-1").get(0).enabled());
        // 不存在 → NotFound
        assertThrows(ScriptStore.NotFoundException.class,
                () -> store.setEnabled("w-test-1", "sr-deadbeef", true));
    }

    // ──────────────────────────────────────────────────────────
    //  loadFromDb / clearWall / 不可变
    // ──────────────────────────────────────────────────────────

    @Test
    void loadFromDb_restores_after_restart() {
        store.create("w-test-1", incoming("a"));
        store.create("w-test-1", incoming("b"));
        List<ScriptRule> before = store.listByWall("w-test-1");

        // 模拟重启:新 Store 实例 + loadFromDb
        ScriptStore restarted = new ScriptStore(log, new ScriptDao(log, database.jdbi()), 16);
        restarted.loadFromDb();
        assertEquals(before, restarted.listByWall("w-test-1"));
        // 反查索引也恢复了(setEnabled 能找到)
        ScriptRule first = before.get(0);
        assertFalse(restarted.setEnabled("w-test-1", first.id(), false).enabled());
    }

    @Test
    void clearWall_removes_all() {
        ScriptRule created = store.create("w-test-1", incoming("a"));
        store.create("w-test-1", incoming("b"));
        store.clearWall("w-test-1");
        assertTrue(store.listByWall("w-test-1").isEmpty());
        // 反查索引同步清:update 找不到
        assertThrows(ScriptStore.NotFoundException.class,
                () -> store.update(created.id(), incoming("x")));
    }

    @Test
    void listByWall_returns_immutable() {
        store.create("w-test-1", incoming("a"));
        List<ScriptRule> list = store.listByWall("w-test-1");
        assertThrows(UnsupportedOperationException.class,
                () -> list.add(incoming("b")));
        // 无墙也是不可变空 list
        List<ScriptRule> empty = store.listByWall("w-nonexistent");
        assertTrue(empty.isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> empty.add(incoming("c")));
    }

    @Test
    void find_returns_rule_or_empty() {
        ScriptRule created = store.create("w-test-1", incoming("a"));
        assertEquals(created, store.find("w-test-1", created.id()).orElseThrow());
        assertTrue(store.find("w-test-1", "sr-deadbeef").isEmpty());
        assertTrue(store.find("w-nonexistent", created.id()).isEmpty());
    }

    // ──────────────────────────────────────────────────────────
    //  0.7.0-P2-1:snapshotAll + Listener
    // ──────────────────────────────────────────────────────────

    @Test
    void snapshotAll_contains_all_walls_and_is_immutable() {
        insertWall("w-test-2");
        ScriptRule a = store.create("w-test-1", incoming("a"));
        ScriptRule b = store.create("w-test-2", incoming("b"));

        java.util.Map<String, List<ScriptRule>> snap = store.snapshotAll();
        assertEquals(2, snap.size());
        assertEquals(List.of(a), snap.get("w-test-1"));
        assertEquals(List.of(b), snap.get("w-test-2"));
        // 外层 map 不可变
        assertThrows(UnsupportedOperationException.class,
                () -> snap.put("w-evil", List.of()));
        // 值 list 不可变
        assertThrows(UnsupportedOperationException.class,
                () -> snap.get("w-test-1").add(incoming("c")));
        // 快照语义:snapshot 后再 create 不影响已取快照
        store.create("w-test-1", incoming("c"));
        assertEquals(1, snap.get("w-test-1").size());
    }

    @Test
    void listener_notified_on_all_five_mutations() {
        List<String> events = new java.util.ArrayList<>();
        store.addListener(events::add);

        ScriptRule created = store.create("w-test-1", incoming("a"));        // ① create
        store.update(created.id(), incoming("a改"));                          // ② update
        store.setEnabled("w-test-1", created.id(), false);                   // ③ setEnabled
        store.delete("w-test-1", created.id());                              // ④ delete
        ScriptRule again = store.create("w-test-1", incoming("b"));
        store.clearWall("w-test-1");                                         // ⑤ clearWall
        assertEquals(List.of("w-test-1", "w-test-1", "w-test-1", "w-test-1",
                "w-test-1", "w-test-1"), events,
                "5 种 mutation(+1 次重建 create)应各通知一次: " + events + " again=" + again.id());
    }

    @Test
    void listener_notified_per_wall_after_loadFromDb() {
        insertWall("w-test-2");
        store.create("w-test-1", incoming("a"));
        store.create("w-test-2", incoming("b"));

        ScriptStore restarted = new ScriptStore(log, new ScriptDao(log, database.jdbi()), 16);
        java.util.Set<String> notified = new java.util.HashSet<>();
        restarted.addListener(notified::add);
        restarted.loadFromDb();
        assertEquals(java.util.Set.of("w-test-1", "w-test-2"), notified,
                "loadFromDb 应对加载到的每面墙各通知一次");
    }

    @Test
    void listener_failure_does_not_break_store_or_other_listeners() {
        List<String> survived = new java.util.ArrayList<>();
        store.addListener(wallId -> { throw new IllegalStateException("listener 故意炸"); });
        store.addListener(survived::add);

        // 抛异常的 listener 不影响 store 操作结果,也不影响后续 listener
        ScriptRule created = store.create("w-test-1", incoming("a"));
        assertEquals(1, store.listByWall("w-test-1").size());
        assertEquals(List.of("w-test-1"), survived);

        store.delete("w-test-1", created.id());
        assertTrue(store.listByWall("w-test-1").isEmpty());
        assertEquals(List.of("w-test-1", "w-test-1"), survived);
    }

    @Test
    void listener_not_notified_when_mutation_fails() {
        List<String> events = new java.util.ArrayList<>();
        ScriptStore small = new ScriptStore(log, dao, 1);
        small.addListener(events::add);
        small.create("w-test-1", incoming("a"));
        assertEquals(1, events.size());
        // 配额拒绝 → 状态未变,不应通知
        assertThrows(ScriptStore.QuotaExceededException.class,
                () -> small.create("w-test-1", incoming("b")));
        assertEquals(1, events.size(), "失败的 mutation 不应触发通知");
        // 无规则墙 clearWall 是 no-op,不通知
        small.clearWall("w-nonexistent");
        assertEquals(1, events.size());
    }
}
