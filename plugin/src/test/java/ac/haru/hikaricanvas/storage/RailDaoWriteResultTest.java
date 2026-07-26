package ac.haru.hikaricanvas.storage;

import ac.haru.hikaricanvas.rail.RailLine;
import ac.haru.hikaricanvas.rail.RailRun;
import ac.haru.hikaricanvas.rail.RailStation;
import ac.haru.hikaricanvas.rail.RailTimetableEntry;
import ac.haru.hikaricanvas.rail.WallRailBinding;
import ac.haru.hikaricanvas.session.WallKey;
import ac.haru.hikaricanvas.state.Fill;
import ac.haru.hikaricanvas.state.ProjectState;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 写方法要如实报告成功与否（真 SQLite + 真外键）。
 *
 * <p>写方法以前一律 void，异常被吞在 DAO 里，dispatcher 无从判断 —— 绑定因为外键失败被吞掉、
 * 前端却收到 ack 成功，此后这面墙被当成"已绑铁路"，站牌变量全空到重启。</p>
 */
class RailDaoWriteResultTest {

    private static final Logger LOG = Logger.getLogger("test");

    private Path tmpDir;
    private Database database;
    private RailDao dao;
    private WallRepo wallRepo;

    @BeforeEach
    void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("hikari-raildao-test-");
        database = new Database(LOG, tmpDir.resolve("data.db"));
        new MigrationRunner(database.jdbi(), LOG).run();
        dao = new RailDao(LOG, database.jdbi());
        wallRepo = new WallRepo(LOG, database.jdbi());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (database != null) database.close();
        if (tmpDir != null && Files.exists(tmpDir)) {
            try (var s = Files.walk(tmpDir)) {
                s.sorted(Comparator.reverseOrder()).map(Path::toFile)
                        .forEach(java.io.File::delete);
            }
        }
    }

    // ---------- fixtures ----------

    private RailLine line(String id) {
        long now = System.currentTimeMillis();
        return new RailLine(id, "1 号线", "L1", "#E4002B",
                UUID.randomUUID(), "tester", now, now);
    }

    private RailStation station(String id, String lineId) {
        return new RailStation(id, lineId, "人民广场", "RMGC", 0, false,
                System.currentTimeMillis());
    }

    private RailRun run(String id, String lineId, String runNumber) {
        long now = System.currentTimeMillis();
        return new RailRun(id, lineId, runNumber, RailRun.DIRECTION_UP, "local",
                6, null, null, null, now, now);
    }

    private String createWall() {
        ProjectState state = new ProjectState(1L,
                new ProjectState.Canvas(1, 1, Fill.solid("#FFFFFF")),
                null, new ArrayList<>(), null,
                new ProjectState.History(0, 0), null, null, null);
        WallKey key = new WallKey("world", 1, 64, 0, BlockFace.NORTH);
        return wallRepo.createWithMapIds(key, state, List.of(0), 1, 1,
                UUID.randomUUID(), "tester");
    }

    // ---------- 成功路径 ----------

    @Test
    void happyPath_allWritesReturnTrue() {
        assertTrue(dao.upsertLine(line("line-1")));
        assertTrue(dao.upsertStation(station("stn-1", "line-1")));
        assertTrue(dao.upsertRun(run("run-1", "line-1", "1001")));
        assertTrue(dao.replaceTimetable("run-1",
                List.of(new RailTimetableEntry("run-1", "stn-1", "08:00:00", "08:00:30", true))));
        String wallId = createWall();
        assertTrue(dao.upsertBinding(new WallRailBinding(wallId, "line-1", "stn-1",
                WallRailBinding.DIRECTION_BOTH, System.currentTimeMillis())));
    }

    // ---------- 失败路径 ----------

    @Test
    void binding_toUnknownLine_returnsFalse() {
        String wallId = createWall();
        boolean ok = dao.upsertBinding(new WallRailBinding(wallId, "line-does-not-exist", null,
                WallRailBinding.DIRECTION_BOTH, System.currentTimeMillis()));
        assertFalse(ok, "外键失败必须报 false，不能装作写成功");
        assertTrue(dao.findBinding(wallId).isEmpty(), "确实没写进去");
    }

    @Test
    void binding_toUnknownStation_returnsFalse() {
        dao.upsertLine(line("line-1"));
        String wallId = createWall();
        assertFalse(dao.upsertBinding(new WallRailBinding(wallId, "line-1", "stn-nope",
                WallRailBinding.DIRECTION_BOTH, System.currentTimeMillis())));
    }

    @Test
    void run_duplicateRunNumberOnSameLine_returnsFalse() {
        dao.upsertLine(line("line-1"));
        assertTrue(dao.upsertRun(run("run-1", "line-1", "1001")));
        // 同线路同车次号、不同 id → 撞 UNIQUE(line_id, run_number)
        assertFalse(dao.upsertRun(run("run-2", "line-1", "1001")));
        assertEquals(1, dao.listRunsByLine("line-1").size(), "第二条不该落库");
    }

    @Test
    void station_onUnknownLine_returnsFalse() {
        assertFalse(dao.upsertStation(station("stn-1", "line-nope")));
    }

    @Test
    void timetable_withUnknownStation_rollsBackAndReturnsFalse() {
        dao.upsertLine(line("line-1"));
        dao.upsertStation(station("stn-1", "line-1"));
        dao.upsertRun(run("run-1", "line-1", "1001"));
        assertTrue(dao.replaceTimetable("run-1",
                List.of(new RailTimetableEntry("run-1", "stn-1", "08:00:00", "08:00:30", true))));

        boolean ok = dao.replaceTimetable("run-1", List.of(
                new RailTimetableEntry("run-1", "stn-1", "09:00:00", "09:00:30", true),
                new RailTimetableEntry("run-1", "stn-nope", "09:10:00", "09:10:30", true)));
        assertFalse(ok);
        // 事务整体回滚：旧时刻表还在，不会被删一半
        List<RailTimetableEntry> left = dao.listTimetableByRun("run-1");
        assertEquals(1, left.size());
        assertEquals("08:00:00", left.get(0).arrivalTime());
    }
}
