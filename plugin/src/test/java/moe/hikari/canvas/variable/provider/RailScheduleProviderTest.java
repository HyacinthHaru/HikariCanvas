package moe.hikari.canvas.variable.provider;

import moe.hikari.canvas.HikariCanvasConfig;
import moe.hikari.canvas.rail.RailRun;
import moe.hikari.canvas.rail.RailStation;
import moe.hikari.canvas.rail.RailTimetableEntry;
import moe.hikari.canvas.rail.WallRailBinding;
import moe.hikari.canvas.storage.RailDao;
import moe.hikari.canvas.storage.UserVariableDao;
import moe.hikari.canvas.variable.VariableStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.4.4 P2：RailScheduleProvider 计算单测（mock DataSource）。
 */
class RailScheduleProviderTest {

    private static final String WALL = "w-rail";
    private static final UUID OWNER = UUID.randomUUID();

    private FakeDataSource ds;
    private VariableStore store;
    private RailScheduleProvider provider;

    @BeforeEach
    void setUp() {
        ds = new FakeDataSource();
        store = new VariableStore(new FakeUserVarDao(), wid -> {});
        provider = new RailScheduleProvider(store, ds,
                HikariCanvasConfig.ScheduleConfig.defaults(), Locale.SIMPLIFIED_CHINESE);
    }

    @Test
    void register_pushesAllKeys() {
        ds.bindings.add(binding(WALL, "L1", "s1", "up"));
        ds.stations.addAll(List.of(station("s1", "L1", "郑州", 0),
                station("s2", "L1", "二七", 1),
                station("s3", "L1", "紫荆", 2)));
        ds.runs.add(run("r1", "L1", "A01", "up", "express", 6, null, null, "末班车"));
        ds.timetable.add(new RailDao.TimetableJoinRow(
                new RailTimetableEntry("r1", "s1", null, "10:00:00", true),
                ds.runs.get(0)));
        ds.timetable.add(new RailDao.TimetableJoinRow(
                new RailTimetableEntry("r1", "s2", "10:02:30", "10:03:00", true),
                ds.runs.get(0)));
        ds.timetable.add(new RailDao.TimetableJoinRow(
                new RailTimetableEntry("r1", "s3", "10:05:00", null, true),
                ds.runs.get(0)));
        ds.now = LocalTime.of(9, 59, 0);

        provider.initialize();

        // 22 个 key 都应被注册到 store
        assertTrue(store.get("schedule:" + WALL + "/next_departure").isPresent());
        assertTrue(store.get("schedule:" + WALL + "/next_run_number").isPresent());
        assertTrue(store.get("schedule:" + WALL + "/next_service_type").isPresent());
        assertTrue(store.get("schedule:" + WALL + "/next_service_type_text").isPresent());
        assertTrue(store.get("schedule:" + WALL + "/next_terminus").isPresent());
        assertTrue(store.get("schedule:" + WALL + "/next_cars").isPresent());
        assertTrue(store.get("schedule:" + WALL + "/next_notes").isPresent());
        assertTrue(store.get("schedule:" + WALL + "/next_arrival").isPresent());
    }

    @Test
    void pushValues_correctServiceTypeAndCars() {
        ds.bindings.add(binding(WALL, "L1", "s1", "up"));
        ds.stations.add(station("s1", "L1", "郑州", 0));
        ds.runs.add(run("r1", "L1", "A01", "up", "express", 6, null, null, ""));
        ds.timetable.add(new RailDao.TimetableJoinRow(
                new RailTimetableEntry("r1", "s1", null, "10:00:00", true),
                ds.runs.get(0)));
        ds.now = LocalTime.of(9, 30, 0);
        provider.initialize();

        assertEquals("A01", store.get("schedule:" + WALL + "/next_run_number").orElseThrow().currentValue());
        assertEquals("express", store.get("schedule:" + WALL + "/next_service_type").orElseThrow().currentValue());
        assertEquals("大站快车",
                store.get("schedule:" + WALL + "/next_service_type_text").orElseThrow().currentValue());
        assertEquals("6", store.get("schedule:" + WALL + "/next_cars").orElseThrow().currentValue());
    }

    /**
     * 0.7.3 P2-13：next_arrival / next_departure 是两个独立字段。
     * 中间站 arrival_time ≠ departure_time → 两 key 分别等于两列且不相等。
     */
    @Test
    void middleStation_arrivalAndDepartureAreIndependent() {
        ds.bindings.add(binding(WALL, "L1", "s2", "up"));
        ds.stations.addAll(List.of(station("s1", "L1", "郑州", 0),
                station("s2", "L1", "二七", 1),
                station("s3", "L1", "紫荆", 2)));
        ds.runs.add(run("r1", "L1", "A01", "up", "express", 6, null, null, ""));
        // s2 是中间站：到达 10:02:30，发车 10:03:00（两列不同）
        ds.timetable.add(new RailDao.TimetableJoinRow(
                new RailTimetableEntry("r1", "s2", "10:02:30", "10:03:00", true),
                ds.runs.get(0)));
        ds.now = LocalTime.of(10, 0, 0);
        provider.initialize();

        String arrival = store.get("schedule:" + WALL + "/next_arrival").orElseThrow().currentValue();
        String departure = store.get("schedule:" + WALL + "/next_departure").orElseThrow().currentValue();
        assertEquals("10:02:30", arrival, "next_arrival 应取 arrival_time");
        assertEquals("10:03:00", departure, "next_departure 应取 departure_time");
        assertNotEquals(arrival, departure,
                "next_arrival 与 next_departure 是独立字段，中间站不应相等");
    }

    /**
     * 0.7.3 P2-13：始发站无 arrival → next_arrival fallback departure_time；
     * next_departure 取 departure_time（两者此时相等是预期的，因始发站只有发车时刻）。
     */
    @Test
    void firstStation_arrivalFallsBackToDeparture() {
        ds.bindings.add(binding(WALL, "L1", "s1", "up"));
        ds.stations.addAll(List.of(station("s1", "L1", "郑州", 0),
                station("s2", "L1", "二七", 1)));
        ds.runs.add(run("r1", "L1", "A01", "up", "express", 6, null, null, ""));
        // s1 始发站：无 arrival（null），仅 departure 10:00:00
        ds.timetable.add(new RailDao.TimetableJoinRow(
                new RailTimetableEntry("r1", "s1", null, "10:00:00", true),
                ds.runs.get(0)));
        ds.now = LocalTime.of(9, 50, 0);
        provider.initialize();

        assertEquals("10:00:00",
                store.get("schedule:" + WALL + "/next_arrival").orElseThrow().currentValue(),
                "始发站无 arrival → next_arrival fallback departure_time");
        assertEquals("10:00:00",
                store.get("schedule:" + WALL + "/next_departure").orElseThrow().currentValue(),
                "始发站 next_departure 取 departure_time");
    }

    /**
     * 0.7.3 P2-13：终到站无 departure → next_departure fallback arrival_time；
     * next_arrival 取 arrival_time。
     */
    @Test
    void terminalStation_departureFallsBackToArrival() {
        ds.bindings.add(binding(WALL, "L1", "s3", "up"));
        ds.stations.addAll(List.of(station("s1", "L1", "郑州", 0),
                station("s2", "L1", "二七", 1),
                station("s3", "L1", "紫荆", 2)));
        ds.runs.add(run("r1", "L1", "A01", "up", "express", 6, null, null, ""));
        // s3 终到站：到达 10:05:00，无 departure（null）
        ds.timetable.add(new RailDao.TimetableJoinRow(
                new RailTimetableEntry("r1", "s3", "10:05:00", null, true),
                ds.runs.get(0)));
        ds.now = LocalTime.of(10, 0, 0);
        provider.initialize();

        assertEquals("10:05:00",
                store.get("schedule:" + WALL + "/next_arrival").orElseThrow().currentValue(),
                "终到站 next_arrival 取 arrival_time");
        assertEquals("10:05:00",
                store.get("schedule:" + WALL + "/next_departure").orElseThrow().currentValue(),
                "终到站无 departure → next_departure fallback arrival_time");
    }

    /**
     * 0.7.3 P2-14（provider 侧契约）：RailOpDispatcher.handleStationDelete 删除被绑定站后
     * 对每个绑定 wall 调本方法。验证 unregisterWall 把 station-bound wall 的 rail 快照彻底释放——
     * registeredWalls 不再含该 wall（refresh 不再 push 旧 stationId 空串）+ hasWallBinding 转 false
     * （ManualSchedule 的 skipPredicate 不再跳过 → 可接管）。
     */
    @Test
    void unregisterWall_releasesStationBoundSnapshot_forStationDelete() {
        ds.bindings.add(binding(WALL, "L1", "s1", "up"));
        ds.stations.add(station("s1", "L1", "郑州", 0));
        ds.runs.add(run("r1", "L1", "A01", "up", "local", 6, null, null, ""));
        ds.timetable.add(new RailDao.TimetableJoinRow(
                new RailTimetableEntry("r1", "s1", null, "10:00:00", true),
                ds.runs.get(0)));
        provider.initialize();
        assertTrue(provider.hasWallBinding(WALL));
        assertTrue(provider.registeredWallsSnapshot().contains(WALL));

        // 模拟 handleStationDelete 在删站后对绑定 wall 的调用
        provider.unregisterWall(WALL);

        assertFalse(provider.hasWallBinding(WALL),
                "删被绑定站后 wall 应被释放，skipPredicate 不再跳过");
        assertFalse(provider.registeredWallsSnapshot().contains(WALL),
                "registeredWalls 不应再持旧 stationId 快照");
        assertFalse(store.get("schedule:" + WALL + "/next_run_number").isPresent(),
                "rail-only key 应被清，不残留旧值");
    }

    @Test
    void hasWallBinding_trueAfterRegister() {
        ds.bindings.add(binding(WALL, "L1", "s1", "up"));
        ds.stations.add(station("s1", "L1", "St", 0));
        provider.initialize();
        assertTrue(provider.hasWallBinding(WALL));
        assertFalse(provider.hasWallBinding("w-other"));
    }

    @Test
    void registerWallByBinding_nullLineId_unregisters() {
        ds.bindings.add(binding(WALL, "L1", "s1", "up"));
        ds.stations.add(station("s1", "L1", "St", 0));
        provider.initialize();
        assertTrue(provider.hasWallBinding(WALL));

        // 后续 bind line_id = null（用户清除绑定）
        provider.registerWallByBinding(WALL, new WallRailBinding(WALL, null, null, "both", 0L));
        assertFalse(provider.hasWallBinding(WALL));
    }

    @Test
    void unregisterWall_clearsAllKeys() {
        ds.bindings.add(binding(WALL, "L1", "s1", "up"));
        ds.stations.add(station("s1", "L1", "St", 0));
        ds.runs.add(run("r1", "L1", "A01", "up", "local", null, null, null, null));
        ds.timetable.add(new RailDao.TimetableJoinRow(
                new RailTimetableEntry("r1", "s1", null, "10:00:00", true),
                ds.runs.get(0)));
        provider.initialize();
        assertNotNull(store.get("schedule:" + WALL + "/next_run_number").orElse(null));

        provider.unregisterWall(WALL);
        assertFalse(store.get("schedule:" + WALL + "/next_run_number").isPresent());
        assertFalse(provider.hasWallBinding(WALL));
    }

    @Test
    void declaredKeys_contains14NewRailKeys() {
        var keys = provider.declaredKeys();
        assertEquals(14, keys.size());
        // 抽样校验
        assertTrue(keys.stream().anyMatch(k -> k.key().equals("next_run_number")));
        assertTrue(keys.stream().anyMatch(k -> k.key().equals("next2_run_number")));
        assertTrue(keys.stream().anyMatch(k -> k.key().equals("next_service_type_text")));
    }

    @Test
    void emptyTimetable_pushesEmptyValues() {
        // wall 绑定但 timetable 空（车次 / 站点未配齐）—— 应 push 空字符串，不抛
        ds.bindings.add(binding(WALL, "L1", "s1", "up"));
        ds.stations.add(station("s1", "L1", "St", 0));
        ds.now = LocalTime.of(10, 0, 0);
        provider.initialize();

        assertEquals("", store.get("schedule:" + WALL + "/next_run_number")
                .orElseThrow().currentValue());
        assertEquals("", store.get("schedule:" + WALL + "/next_terminus")
                .orElseThrow().currentValue());
    }

    @Test
    void skipPredicateIntegration_manualSkipsRailBoundWall() {
        // 集成式：ManualScheduleProvider 接 skipPredicate = rail.hasWallBinding
        ds.bindings.add(binding(WALL, "L1", "s1", "up"));
        ds.stations.add(station("s1", "L1", "St", 0));
        provider.initialize();

        java.util.function.Predicate<String> skip = provider::hasWallBinding;
        assertTrue(skip.test(WALL));
        assertFalse(skip.test("w-non-rail"));
    }

    // ────────────────────────────────────────────────────────────
    //  helpers
    // ────────────────────────────────────────────────────────────

    private static WallRailBinding binding(String wallId, String lineId,
                                           String stationId, String direction) {
        return new WallRailBinding(wallId, lineId, stationId, direction, 0L);
    }

    private static RailStation station(String id, String lineId, String name, int order) {
        return new RailStation(id, lineId, name, null, order, false, 0L);
    }

    private static RailRun run(String id, String lineId, String num, String dir, String type,
                               Integer cars, String startId, String endId, String notes) {
        return new RailRun(id, lineId, num, dir, type, cars, startId, endId, notes, 0L, 0L);
    }

    private static final class FakeDataSource implements RailScheduleProvider.DataSource {
        final List<WallRailBinding> bindings = new ArrayList<>();
        final List<RailStation> stations = new ArrayList<>();
        final List<RailRun> runs = new ArrayList<>();
        final List<RailDao.TimetableJoinRow> timetable = new ArrayList<>();
        LocalTime now = LocalTime.of(0, 0, 0);

        @Override public List<WallRailBinding> loadAllBindings() { return bindings; }

        @Override
        public List<RailDao.TimetableJoinRow> listStationStops(String stationId, String direction) {
            return timetable.stream()
                    .filter(r -> r.entry().stationId().equals(stationId))
                    .filter(r -> WallRailBinding.DIRECTION_BOTH.equals(direction)
                            || r.run().direction().equals(direction))
                    .toList();
        }

        @Override
        public RailStation findStation(String stationId) {
            return stations.stream()
                    .filter(s -> s.id().equals(stationId)).findFirst().orElse(null);
        }

        @Override
        public List<RailStation> listStationsByLine(String lineId) {
            return stations.stream()
                    .filter(s -> s.lineId().equals(lineId))
                    .sorted((a, b) -> Integer.compare(a.sortOrder(), b.sortOrder()))
                    .toList();
        }

        @Override public LocalTime currentLocalTime() { return now; }
    }

    /** 复用 VariableStore 的 dao 占位（不会被调用）。 */
    private static final class FakeUserVarDao extends UserVariableDao {
        FakeUserVarDao() { super(Logger.getLogger("test"), null); }
        @Override public void upsert(String w, String n, moe.hikari.canvas.variable.VarType t,
                String d, String c, String b, long ca, long ua) {}
        @Override public void delete(String w, String n) {}
        @Override public List<Row> loadAll() { return List.of(); }
    }
}
