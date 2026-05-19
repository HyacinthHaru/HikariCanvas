package moe.hikari.canvas.storage;

import moe.hikari.canvas.schedule.ScheduleEntry;
import moe.hikari.canvas.schedule.WallSchedule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.4.0-P3-L：{@link ScheduleDao} CRUD + 级联删测试。
 *
 * <p>真实 SQLite + MigrationRunner 跑 V001..V012；FK CASCADE 由 schema 触发。每个测试新建临时
 * dir + data.db，互相隔离。</p>
 */
class ScheduleDaoTest {

    private Path tmpDir;
    private Database database;
    private ScheduleDao dao;

    @BeforeEach
    void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("hikari-schedule-test-");
        Logger log = Logger.getLogger("test");
        database = new Database(log, tmpDir.resolve("data.db"));
        new MigrationRunner(database.jdbi(), log).run();
        dao = new ScheduleDao(log, database.jdbi());
        // 插入一个 walls 行，让 FK 通过
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

    /** AtomicInteger 给每次 insertWall 一个唯一 origin_x，绕开 walls (world, origin_*, facing) UNIQUE 约束。 */
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
    //  loadByWall / upsertSchedule
    // ──────────────────────────────────────────────────────────

    @Test
    void loadByWall_emptyDb_returnsEmpty() {
        assertTrue(dao.loadByWall("nonexistent").isEmpty());
    }

    @Test
    void upsertSchedule_createsThenUpdates() {
        dao.upsertSchedule("w-test-1", "中央站");
        Optional<WallSchedule> first = dao.loadByWall("w-test-1");
        assertTrue(first.isPresent());
        assertEquals("中央站", first.get().stationName());

        dao.upsertSchedule("w-test-1", "南站");
        Optional<WallSchedule> second = dao.loadByWall("w-test-1");
        assertEquals("南站", second.get().stationName());
    }

    @Test
    void upsertSchedule_nullStationName_allowed() {
        dao.upsertSchedule("w-test-1", null);
        WallSchedule ws = dao.loadByWall("w-test-1").orElseThrow();
        assertEquals(null, ws.stationName());
    }

    // ──────────────────────────────────────────────────────────
    //  insertEntry / updateEntry / deleteEntry
    // ──────────────────────────────────────────────────────────

    @Test
    void insertEntry_returnsNewId_andLoadByWallShowsIt() {
        dao.upsertSchedule("w-test-1", null);
        long id1 = dao.insertEntry("w-test-1", "08:00", "Beijing", 0);
        long id2 = dao.insertEntry("w-test-1", "10:30", "Shanghai", 1);
        assertTrue(id1 > 0);
        assertTrue(id2 > id1);

        WallSchedule ws = dao.loadByWall("w-test-1").orElseThrow();
        assertEquals(2, ws.entries().size());
        assertEquals("08:00", ws.entries().get(0).departureTime());
        assertEquals("Beijing", ws.entries().get(0).destination());
        assertEquals("10:30", ws.entries().get(1).departureTime());
    }

    @Test
    void insertEntry_destinationNullableOk() {
        dao.upsertSchedule("w-test-1", null);
        long id = dao.insertEntry("w-test-1", "07:15", null, 0);
        assertTrue(id > 0);
        WallSchedule ws = dao.loadByWall("w-test-1").orElseThrow();
        assertEquals(null, ws.entries().get(0).destination());
    }

    @Test
    void loadByWall_entriesSortedBySortOrderThenDepartureTime() {
        dao.upsertSchedule("w-test-1", null);
        dao.insertEntry("w-test-1", "10:00", "Late", 5);
        dao.insertEntry("w-test-1", "08:00", "Early", 0);
        dao.insertEntry("w-test-1", "12:00", "Noon", 0);

        WallSchedule ws = dao.loadByWall("w-test-1").orElseThrow();
        // sort_order ASC, departure_time ASC → 08:00(#0), 12:00(#0), 10:00(#5)
        assertEquals("Early", ws.entries().get(0).destination());
        assertEquals("Noon", ws.entries().get(1).destination());
        assertEquals("Late", ws.entries().get(2).destination());
    }

    @Test
    void updateEntry_changesFields() {
        dao.upsertSchedule("w-test-1", null);
        long id = dao.insertEntry("w-test-1", "08:00", "Old", 0);
        int rows = dao.updateEntry(id, "09:00", "New", 3);
        assertEquals(1, rows);
        WallSchedule ws = dao.loadByWall("w-test-1").orElseThrow();
        assertEquals(1, ws.entries().size());
        ScheduleEntry e = ws.entries().get(0);
        assertEquals("09:00", e.departureTime());
        assertEquals("New", e.destination());
        assertEquals(3, e.sortOrder());
    }

    @Test
    void updateEntry_unknownId_returnsZero() {
        int rows = dao.updateEntry(999999L, "08:00", "x", 0);
        assertEquals(0, rows);
    }

    @Test
    void deleteEntry_removesRow() {
        dao.upsertSchedule("w-test-1", null);
        long id = dao.insertEntry("w-test-1", "08:00", "Beijing", 0);
        assertEquals(1, dao.deleteEntry(id));
        assertEquals(0, dao.loadByWall("w-test-1").orElseThrow().entries().size());
    }

    @Test
    void deleteEntry_unknownId_returnsZero() {
        assertEquals(0, dao.deleteEntry(999999L));
    }

    // ──────────────────────────────────────────────────────────
    //  deleteByWall + 级联
    // ──────────────────────────────────────────────────────────

    @Test
    void deleteByWall_clearsScheduleAndEntries() {
        dao.upsertSchedule("w-test-1", "Station");
        dao.insertEntry("w-test-1", "08:00", "A", 0);
        dao.insertEntry("w-test-1", "10:00", "B", 0);
        int affected = dao.deleteByWall("w-test-1");
        assertTrue(affected >= 3); // 2 entries + 1 meta

        assertTrue(dao.loadByWall("w-test-1").isEmpty());
    }

    @Test
    void cascade_onWallDelete_clearsScheduleData() {
        dao.upsertSchedule("w-test-1", "Station");
        dao.insertEntry("w-test-1", "08:00", "A", 0);

        // 删 walls 行 → FK CASCADE 应清掉 wall_schedules + schedule_entries
        database.jdbi().useHandle(h -> h.createUpdate("DELETE FROM walls WHERE wall_id = :id")
                .bind("id", "w-test-1")
                .execute());

        assertTrue(dao.loadByWall("w-test-1").isEmpty());
        // 直接查 schedule_entries 验证物理删除
        int count = database.jdbi().withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM schedule_entries WHERE wall_id = :w")
                .bind("w", "w-test-1")
                .mapTo(Integer.class)
                .one());
        assertEquals(0, count);
    }

    // ──────────────────────────────────────────────────────────
    //  loadAll
    // ──────────────────────────────────────────────────────────

    @Test
    void loadAll_returnsAllSchedulesWithEntries() {
        insertWall("w-test-2");
        dao.upsertSchedule("w-test-1", "S1");
        dao.insertEntry("w-test-1", "08:00", "A", 0);
        dao.upsertSchedule("w-test-2", "S2");
        dao.insertEntry("w-test-2", "09:30", "B", 0);
        dao.insertEntry("w-test-2", "10:00", "C", 1);

        List<WallSchedule> all = dao.loadAll();
        assertEquals(2, all.size());
        WallSchedule w1 = all.stream().filter(s -> s.wallId().equals("w-test-1")).findFirst().orElseThrow();
        WallSchedule w2 = all.stream().filter(s -> s.wallId().equals("w-test-2")).findFirst().orElseThrow();
        assertEquals(1, w1.entries().size());
        assertEquals(2, w2.entries().size());
        // w-test-2 entries 按 sort_order ASC：B(#0), C(#1)
        assertEquals("B", w2.entries().get(0).destination());
        assertEquals("C", w2.entries().get(1).destination());
    }

    @Test
    void loadAll_emptyDb_returnsEmpty() {
        assertTrue(dao.loadAll().isEmpty());
    }
}
