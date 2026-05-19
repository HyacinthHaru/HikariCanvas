package moe.hikari.canvas.variable.provider;

import moe.hikari.canvas.HikariCanvasConfig;
import moe.hikari.canvas.schedule.ScheduleEntry;
import moe.hikari.canvas.schedule.WallSchedule;
import moe.hikari.canvas.storage.UserVariableDao;
import moe.hikari.canvas.variable.VarType;
import moe.hikari.canvas.variable.Variable;
import moe.hikari.canvas.variable.VariableStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.4.0-P3-L：{@link ManualScheduleProvider} 单测。
 *
 * <p>用 {@link FakeDataSource} 注入测试 entries + LocalTime，避免依赖 SQLite / Bukkit。覆盖：
 * <ul>
 *   <li>initialize 注册所有 wall_schedules 已存在的 wall</li>
 *   <li>refresh 计算 next_departure / next_destination / eta_minutes / is_arriving</li>
 *   <li>per-wall 隔离（多 wall 同时跑互不影响）</li>
 *   <li>ensureWallRegistered 幂等（重复调不抛 + 不重复 create）</li>
 *   <li>edge case：空 entries、过零点、单 entry 已过</li>
 *   <li>is_arriving / arrival_status 阈值（0.4.0 bugfix Bug 3：config 化默认 60s）</li>
 *   <li>precision 秒精度（0.4.0 bugfix Bug 4：支持 HH:mm:ss）</li>
 *   <li>unregisterWall 清干净（7 变量）</li>
 * </ul>
 */
class ManualScheduleProviderTest {

    private FakeUserVariableDao fakeDao;
    private VariableStore store;
    private FakeDataSource dataSource;
    private ManualScheduleProvider provider;

    @BeforeEach
    void setUp() {
        fakeDao = new FakeUserVariableDao();
        store = new VariableStore(fakeDao, w -> { });
        dataSource = new FakeDataSource();
        provider = new ManualScheduleProvider(store, dataSource);
    }

    // ──────────────────────────────────────────────────────────
    //  declaredKeys / metadata
    // ──────────────────────────────────────────────────────────

    @Test
    void namespace_isScheduleAlias() {
        assertEquals("schedule", provider.namespace());
    }

    @Test
    void declaredKeys_returnsSeven() {
        // 0.4.0 bugfix（Bug 3+4）：扩展到 7 个 key
        List<DeclaredKey> keys = provider.declaredKeys();
        assertEquals(7, keys.size());
        assertTrue(keys.stream().anyMatch(k -> k.key().equals("next_departure")));
        assertTrue(keys.stream().anyMatch(k -> k.key().equals("next_destination")));
        assertTrue(keys.stream().anyMatch(k -> k.key().equals("eta_minutes")));
        assertTrue(keys.stream().anyMatch(k -> k.key().equals("eta_seconds")));
        assertTrue(keys.stream().anyMatch(k -> k.key().equals("is_arriving")));
        assertTrue(keys.stream().anyMatch(k -> k.key().equals("arrival_status")));
        assertTrue(keys.stream().anyMatch(k -> k.key().equals("precision")));
    }

    @Test
    void isDynamic_returnsFalseByDefault() {
        assertFalse(provider.isDynamic());
    }

    // ──────────────────────────────────────────────────────────
    //  initialize + refresh
    // ──────────────────────────────────────────────────────────

    @Test
    void initialize_registersAllWallsFromDao() {
        dataSource.now = LocalTime.of(7, 30);
        dataSource.allSchedules.add(new WallSchedule("w-1", "中央站", 0L,
                WallSchedule.PRECISION_MINUTE,
                List.of(new ScheduleEntry(1, "w-1", "08:00", "Beijing", 0))));
        dataSource.allSchedules.add(new WallSchedule("w-2", null, 0L,
                WallSchedule.PRECISION_MINUTE,
                List.of(new ScheduleEntry(2, "w-2", "09:30", "Shanghai", 0))));

        provider.initialize();

        assertTrue(provider.registeredWallsSnapshot().contains("w-1"));
        assertTrue(provider.registeredWallsSnapshot().contains("w-2"));
        // 启动后立即 refresh 一次，next_departure 已填
        assertEquals("08:00", currentValueOrNull(store, "schedule:w-1/next_departure"));
        assertEquals("Beijing", currentValueOrNull(store, "schedule:w-1/next_destination"));
        assertEquals("09:30", currentValueOrNull(store, "schedule:w-2/next_departure"));
    }

    @Test
    void refresh_computesEtaMinutesCorrectly() {
        dataSource.now = LocalTime.of(8, 0);
        provider.initialize();
        dataSource.entriesByWall.put("w-1", List.of(
                new ScheduleEntry(1, "w-1", "08:30", "终点A", 0)));
        provider.ensureWallRegistered("w-1");

        // 08:00 → 08:30 = 30min
        assertEquals("30", currentValueOrNull(store, "schedule:w-1/eta_minutes"));
        assertEquals("08:30", currentValueOrNull(store, "schedule:w-1/next_departure"));
        assertEquals("终点A", currentValueOrNull(store, "schedule:w-1/next_destination"));
        assertEquals("false", currentValueOrNull(store, "schedule:w-1/is_arriving"));
    }

    @Test
    void refresh_isArrivingTrueWithinThresholdSeconds() {
        // 0.4.0 bugfix（Bug 3）：默认阈值 60s，3min ETA 不再算 arriving
        dataSource.now = LocalTime.of(8, 29, 30); // 30s 后到 08:30
        dataSource.entriesByWall.put("w-1", List.of(
                new ScheduleEntry(1, "w-1", "08:30", "Beijing", 0)));
        provider.ensureWallRegistered("w-1");

        // 08:29:30 → 08:30 = 30s ≤ 60 → true
        assertEquals("0", currentValueOrNull(store, "schedule:w-1/eta_minutes"));
        assertEquals("30", currentValueOrNull(store, "schedule:w-1/eta_seconds"));
        assertEquals("true", currentValueOrNull(store, "schedule:w-1/is_arriving"));
        assertEquals("进站中", currentValueOrNull(store, "schedule:w-1/arrival_status"));
    }

    @Test
    void refresh_arrivalStatusUsesIdleTextWhenNotArriving() {
        // 默认 idleText = ""
        dataSource.now = LocalTime.of(8, 0);
        dataSource.entriesByWall.put("w-1", List.of(
                new ScheduleEntry(1, "w-1", "09:00", "Beijing", 0)));
        provider.ensureWallRegistered("w-1");

        assertEquals("false", currentValueOrNull(store, "schedule:w-1/is_arriving"));
        assertEquals("", currentValueOrNull(store, "schedule:w-1/arrival_status"));
    }

    @Test
    void refresh_customConfigChangesArrivalText() {
        // 0.4.0 bugfix（Bug 3）：自定义阈值 + 文案
        provider = new ManualScheduleProvider(store, dataSource,
                new HikariCanvasConfig.ScheduleConfig(300L, "Arriving", "Standby"));
        dataSource.now = LocalTime.of(8, 25);
        dataSource.entriesByWall.put("w-2", List.of(
                new ScheduleEntry(1, "w-2", "08:28", "Tokyo", 0)));
        provider.ensureWallRegistered("w-2");

        // 3min = 180s ≤ 300 → arriving
        assertEquals("true", currentValueOrNull(store, "schedule:w-2/is_arriving"));
        assertEquals("Arriving", currentValueOrNull(store, "schedule:w-2/arrival_status"));
    }

    @Test
    void refresh_overMidnight_etaIncludesRolloverMinutes() {
        // 当前 23:50，所有 entry 已过（07:00 / 09:00）→ 取第一条次日 07:00
        dataSource.now = LocalTime.of(23, 50);
        dataSource.entriesByWall.put("w-1", List.of(
                new ScheduleEntry(1, "w-1", "07:00", "Morning", 0),
                new ScheduleEntry(2, "w-1", "09:00", "Mid", 0)));
        provider.ensureWallRegistered("w-1");

        // 23:50 → 24:00 = 10min；24:00 → 07:00 = 7h = 420min；合计 430min
        assertEquals("07:00", currentValueOrNull(store, "schedule:w-1/next_departure"));
        assertEquals("Morning", currentValueOrNull(store, "schedule:w-1/next_destination"));
        assertEquals("430", currentValueOrNull(store, "schedule:w-1/eta_minutes"));
        assertEquals("false", currentValueOrNull(store, "schedule:w-1/is_arriving"));
    }

    @Test
    void refresh_emptyEntries_pushEmptyValues() {
        dataSource.now = LocalTime.of(12, 0);
        dataSource.entriesByWall.put("w-empty", List.of());
        provider.ensureWallRegistered("w-empty");

        assertEquals("", currentValueOrNull(store, "schedule:w-empty/next_departure"));
        assertEquals("", currentValueOrNull(store, "schedule:w-empty/next_destination"));
        assertEquals("", currentValueOrNull(store, "schedule:w-empty/eta_minutes"));
        assertEquals("false", currentValueOrNull(store, "schedule:w-empty/is_arriving"));
    }

    @Test
    void perWall_isolation_walls_independent() {
        dataSource.now = LocalTime.of(8, 0);
        dataSource.entriesByWall.put("w-1", List.of(
                new ScheduleEntry(1, "w-1", "09:00", "A", 0)));
        dataSource.entriesByWall.put("w-2", List.of(
                new ScheduleEntry(2, "w-2", "10:30", "B", 0)));
        provider.ensureWallRegistered("w-1");
        provider.ensureWallRegistered("w-2");

        assertEquals("09:00", currentValueOrNull(store, "schedule:w-1/next_departure"));
        assertEquals("A", currentValueOrNull(store, "schedule:w-1/next_destination"));
        assertEquals("10:30", currentValueOrNull(store, "schedule:w-2/next_departure"));
        assertEquals("B", currentValueOrNull(store, "schedule:w-2/next_destination"));
    }

    @Test
    void ensureWallRegistered_idempotent() {
        dataSource.now = LocalTime.of(8, 0);
        dataSource.entriesByWall.put("w-1", List.of(
                new ScheduleEntry(1, "w-1", "08:30", "A", 0)));
        provider.ensureWallRegistered("w-1");
        provider.ensureWallRegistered("w-1"); // 重复
        provider.ensureWallRegistered("w-1");

        assertEquals(1, provider.registeredWallsSnapshot().size());
        assertNotNull(store.get("schedule:w-1/next_departure").orElse(null));
    }

    @Test
    void refreshWall_updatesEntriesSnapshot() {
        dataSource.now = LocalTime.of(8, 0);
        dataSource.entriesByWall.put("w-1", List.of(
                new ScheduleEntry(1, "w-1", "08:30", "Old", 0)));
        provider.ensureWallRegistered("w-1");
        assertEquals("Old", currentValueOrNull(store, "schedule:w-1/next_destination"));

        // 更新 entries（玩家添加新条目）
        dataSource.entriesByWall.put("w-1", List.of(
                new ScheduleEntry(1, "w-1", "08:10", "New", 0),
                new ScheduleEntry(2, "w-1", "08:30", "Old", 0)));
        provider.refreshWall("w-1");
        // 08:10 < 08:30，next 应是 08:10/New
        assertEquals("New", currentValueOrNull(store, "schedule:w-1/next_destination"));
        assertEquals("08:10", currentValueOrNull(store, "schedule:w-1/next_departure"));
    }

    @Test
    void unregisterWall_removesAllVariables() {
        // 0.4.0 bugfix（Bug 3+4）：扩展到 7 变量，unregister 全部清掉
        dataSource.now = LocalTime.of(8, 0);
        dataSource.entriesByWall.put("w-1", List.of(
                new ScheduleEntry(1, "w-1", "08:30", "A", 0)));
        provider.ensureWallRegistered("w-1");
        assertTrue(store.get("schedule:w-1/next_departure").isPresent());

        provider.unregisterWall("w-1");

        assertFalse(store.get("schedule:w-1/next_departure").isPresent());
        assertFalse(store.get("schedule:w-1/next_destination").isPresent());
        assertFalse(store.get("schedule:w-1/eta_minutes").isPresent());
        assertFalse(store.get("schedule:w-1/eta_seconds").isPresent());
        assertFalse(store.get("schedule:w-1/is_arriving").isPresent());
        assertFalse(store.get("schedule:w-1/arrival_status").isPresent());
        assertFalse(store.get("schedule:w-1/precision").isPresent());
        assertFalse(provider.registeredWallsSnapshot().contains("w-1"));
    }

    @Test
    void unregisterWall_idempotent() {
        // 不抛
        provider.unregisterWall("nonexistent");
        provider.unregisterWall(null);
        provider.unregisterWall("");
    }

    @Test
    void refresh_publishesPrecisionVariable() {
        // 0.4.0 bugfix（Bug 4）：precision 字段也作为变量暴露
        dataSource.now = LocalTime.of(8, 0);
        dataSource.precisionByWall.put("w-prec", WallSchedule.PRECISION_SECOND);
        dataSource.entriesByWall.put("w-prec", List.of(
                new ScheduleEntry(1, "w-prec", "08:30:00", "Tokyo", 0)));
        provider.ensureWallRegistered("w-prec");
        assertEquals("second", currentValueOrNull(store, "schedule:w-prec/precision"));
    }

    @Test
    void setConfig_hotReload() {
        // 0.4.0 bugfix（Bug 3）：reload config 后阈值生效
        dataSource.now = LocalTime.of(8, 0);
        dataSource.entriesByWall.put("w-1", List.of(
                new ScheduleEntry(1, "w-1", "08:02", "T", 0)));
        provider.ensureWallRegistered("w-1");
        assertEquals("false", currentValueOrNull(store, "schedule:w-1/is_arriving"));

        provider.setConfig(new HikariCanvasConfig.ScheduleConfig(300L, "X", "Y"));
        provider.refreshWall("w-1");
        // 2min = 120s ≤ 300 → arriving
        assertEquals("true", currentValueOrNull(store, "schedule:w-1/is_arriving"));
        assertEquals("X", currentValueOrNull(store, "schedule:w-1/arrival_status"));
    }

    @Test
    void shutdown_clearsState() {
        provider.ensureWallRegistered("w-1");
        provider.shutdown();
        assertTrue(provider.registeredWallsSnapshot().isEmpty());
    }

    // ──────────────────────────────────────────────────────────
    //  computeNext 纯函数
    // ──────────────────────────────────────────────────────────

    @Test
    void computeNext_picksFirstAfterNow() {
        List<ScheduleEntry> entries = List.of(
                new ScheduleEntry(1, "w", "07:00", "Early", 0),
                new ScheduleEntry(2, "w", "09:00", "Mid", 0),
                new ScheduleEntry(3, "w", "12:00", "Noon", 0));
        var c = ManualScheduleProvider.computeNext(entries, LocalTime.of(8, 30), 60L);
        assertEquals("09:00", c.nextDeparture());
        assertEquals("Mid", c.nextDestination());
        assertEquals(30, (int) c.etaMinutes());
        assertEquals(1800, (int) c.etaSeconds());
        assertFalse(c.isArriving());
    }

    @Test
    void computeNext_emptyEntries_returnsNulls() {
        var c = ManualScheduleProvider.computeNext(List.of(), LocalTime.of(8, 0), 60L);
        assertNull(c.nextDeparture());
        assertNull(c.nextDestination());
        assertNull(c.etaMinutes());
        assertNull(c.etaSeconds());
        assertFalse(c.isArriving());
    }

    @Test
    void computeNext_invalidTimeFormat_treatedAsMidnight() {
        // 非 HH:mm → LocalTime.MIDNIGHT；当前 08:00 时 midnight 已过 → 选第二条
        List<ScheduleEntry> entries = List.of(
                new ScheduleEntry(1, "w", "garbage", "Bad", 0),
                new ScheduleEntry(2, "w", "09:00", "Good", 0));
        var c = ManualScheduleProvider.computeNext(entries, LocalTime.of(8, 0), 60L);
        assertEquals("09:00", c.nextDeparture());
        assertEquals("Good", c.nextDestination());
    }

    @Test
    void computeNext_secondGranularity_HHmmss() {
        // 0.4.0 bugfix（Bug 4）：支持 HH:mm:ss 格式
        List<ScheduleEntry> entries = List.of(
                new ScheduleEntry(1, "w", "08:30:45", "PreciseTrain", 0));
        var c = ManualScheduleProvider.computeNext(entries, LocalTime.of(8, 30, 0), 60L);
        assertEquals("08:30:45", c.nextDeparture());
        assertEquals(45, (int) c.etaSeconds());
        assertEquals(0, (int) c.etaMinutes());
        assertTrue(c.isArriving());
    }

    // ──────────────────────────────────────────────────────────
    //  0.4.0 bugfix3（Bug B）：ChangeListener 集成
    //  Provider 写值时 listener 应被触发——这是前端 Schedule Manager preview
    //  实时显值的关键路径。
    // ──────────────────────────────────────────────────────────

    @Test
    void ensureWallRegistered_firesChangeListenerForCreateAndSetValue() {
        dataSource.now = LocalTime.of(8, 0);
        dataSource.entriesByWall.put("w-1", List.of(
                new ScheduleEntry(1, "w-1", "08:30", "终点A", 0)));

        java.util.List<VariableStore.VariableChangeEvent> events =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        store.registerChangeListener(events::add);

        provider.ensureWallRegistered("w-1");

        // 应至少有 7 个 CREATED 事件（schedule:w-1/<7 keys>）+ 7 个 VALUE_SET（pushValues 写所有 7 个）
        long createdCount = events.stream()
                .filter(e -> e.type() == VariableStore.ChangeType.CREATED)
                .filter(e -> e.fullName().startsWith("schedule:w-1/"))
                .count();
        long valueSetCount = events.stream()
                .filter(e -> e.type() == VariableStore.ChangeType.VALUE_SET)
                .filter(e -> e.fullName().startsWith("schedule:w-1/"))
                .count();
        assertEquals(7, createdCount,
                "ensureWallRegistered 应让 listener 接收 7 个 CREATED（7 schedule 变量）");
        assertEquals(7, valueSetCount,
                "ensureWallRegistered 后立即 pushValues 应让 listener 接收 7 个 VALUE_SET");

        // 验证 VALUE_SET 事件携带的 variable 含 currentValue
        var etaSecondsEvent = events.stream()
                .filter(e -> e.type() == VariableStore.ChangeType.VALUE_SET)
                .filter(e -> e.fullName().equals("schedule:w-1/eta_seconds"))
                .findFirst().orElseThrow();
        assertNotNull(etaSecondsEvent.variable());
        // 08:00 → 08:30 = 1800s
        assertEquals("1800", etaSecondsEvent.variable().currentValue());
    }

    @Test
    void unregisterWall_firesChangeListenerForDeleted() {
        dataSource.now = LocalTime.of(8, 0);
        dataSource.entriesByWall.put("w-1", List.of(
                new ScheduleEntry(1, "w-1", "08:30", "终点", 0)));
        provider.ensureWallRegistered("w-1");

        java.util.List<VariableStore.VariableChangeEvent> events =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        store.registerChangeListener(events::add);

        provider.unregisterWall("w-1");

        long deletedCount = events.stream()
                .filter(e -> e.type() == VariableStore.ChangeType.DELETED)
                .filter(e -> e.fullName().startsWith("schedule:w-1/"))
                .count();
        assertEquals(7, deletedCount,
                "unregisterWall 应让 listener 接收 7 个 DELETED");
        // DELETED 事件 variable=null
        events.stream()
                .filter(e -> e.type() == VariableStore.ChangeType.DELETED)
                .forEach(e -> assertNull(e.variable()));
    }

    @Test
    void computeNext_thresholdSeconds_isConfigurable() {
        List<ScheduleEntry> entries = List.of(
                new ScheduleEntry(1, "w", "08:05", "T", 0));
        // 300s 阈值
        var c1 = ManualScheduleProvider.computeNext(entries, LocalTime.of(8, 0), 300L);
        assertTrue(c1.isArriving()); // 300s ≥ 300
        // 60s 阈值
        var c2 = ManualScheduleProvider.computeNext(entries, LocalTime.of(8, 0), 60L);
        assertFalse(c2.isArriving()); // 300s > 60
    }

    // ──────────────────────────────────────────────────────────
    //  Helpers + Fakes
    // ──────────────────────────────────────────────────────────

    private static String currentValueOrNull(VariableStore store, String fullName) {
        return store.get(fullName).map(Variable::currentValue).orElse(null);
    }

    /** Mock DataSource：测试控制 wall list / entries / time。 */
    private static final class FakeDataSource implements ManualScheduleProvider.DataSource {
        final List<WallSchedule> allSchedules = new ArrayList<>();
        final Map<String, List<ScheduleEntry>> entriesByWall = new HashMap<>();
        final Map<String, String> precisionByWall = new HashMap<>();
        LocalTime now = LocalTime.of(8, 0);

        @Override
        public List<WallSchedule> loadAllSchedules() {
            return new ArrayList<>(allSchedules);
        }

        @Override
        public List<ScheduleEntry> loadEntries(String wallId) {
            // 先看 entriesByWall（per-wall fixture），fallback 到 allSchedules
            List<ScheduleEntry> e = entriesByWall.get(wallId);
            if (e != null) return e;
            for (WallSchedule ws : allSchedules) {
                if (ws.wallId().equals(wallId)) return ws.entries();
            }
            return List.of();
        }

        @Override
        public LocalTime currentLocalTime() {
            return now;
        }

        @Override
        public String loadByWallPrecision(String wallId) {
            String p = precisionByWall.get(wallId);
            if (p != null) return p;
            for (WallSchedule ws : allSchedules) {
                if (ws.wallId().equals(wallId)) return ws.precision();
            }
            return WallSchedule.PRECISION_MINUTE;
        }
    }

    /** Fake UserVariableDao - 不持久化。 */
    private static final class FakeUserVariableDao extends UserVariableDao {
        FakeUserVariableDao() {
            super(Logger.getLogger("test"), null);
        }
        @Override
        public void upsert(String wallId, String name, VarType type,
                           String defaultValue, String currentValue, String boundTo,
                           long createdAt, long updatedAt) {}
        @Override public void delete(String wallId, String name) {}
        @Override public List<Row> loadAll() { return new ArrayList<>(); }
    }
}
