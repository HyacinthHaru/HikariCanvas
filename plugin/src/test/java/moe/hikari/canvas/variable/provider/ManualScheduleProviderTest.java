package moe.hikari.canvas.variable.provider;

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
 *   <li>is_arriving 阈值（5min）</li>
 *   <li>unregisterWall 清干净</li>
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
    void declaredKeys_returns4() {
        List<DeclaredKey> keys = provider.declaredKeys();
        assertEquals(4, keys.size());
        assertTrue(keys.stream().anyMatch(k -> k.key().equals("next_departure")));
        assertTrue(keys.stream().anyMatch(k -> k.key().equals("next_destination")));
        assertTrue(keys.stream().anyMatch(k -> k.key().equals("eta_minutes")));
        assertTrue(keys.stream().anyMatch(k -> k.key().equals("is_arriving")));
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
                List.of(new ScheduleEntry(1, "w-1", "08:00", "Beijing", 0))));
        dataSource.allSchedules.add(new WallSchedule("w-2", null, 0L,
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
    void refresh_isArrivingTrueWithinFiveMinutes() {
        dataSource.now = LocalTime.of(8, 27);
        dataSource.entriesByWall.put("w-1", List.of(
                new ScheduleEntry(1, "w-1", "08:30", "Beijing", 0)));
        provider.ensureWallRegistered("w-1");

        // 08:27 → 08:30 = 3min ≤ 5 → true
        assertEquals("3", currentValueOrNull(store, "schedule:w-1/eta_minutes"));
        assertEquals("true", currentValueOrNull(store, "schedule:w-1/is_arriving"));
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
    void unregisterWall_removesAllFourVariables() {
        dataSource.now = LocalTime.of(8, 0);
        dataSource.entriesByWall.put("w-1", List.of(
                new ScheduleEntry(1, "w-1", "08:30", "A", 0)));
        provider.ensureWallRegistered("w-1");
        assertTrue(store.get("schedule:w-1/next_departure").isPresent());

        provider.unregisterWall("w-1");

        assertFalse(store.get("schedule:w-1/next_departure").isPresent());
        assertFalse(store.get("schedule:w-1/next_destination").isPresent());
        assertFalse(store.get("schedule:w-1/eta_minutes").isPresent());
        assertFalse(store.get("schedule:w-1/is_arriving").isPresent());
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
        var c = ManualScheduleProvider.computeNext(entries, LocalTime.of(8, 30));
        assertEquals("09:00", c.nextDeparture());
        assertEquals("Mid", c.nextDestination());
        assertEquals(30, (int) c.etaMinutes());
        assertFalse(c.isArriving());
    }

    @Test
    void computeNext_emptyEntries_returnsNulls() {
        var c = ManualScheduleProvider.computeNext(List.of(), LocalTime.of(8, 0));
        assertNull(c.nextDeparture());
        assertNull(c.nextDestination());
        assertNull(c.etaMinutes());
        assertFalse(c.isArriving());
    }

    @Test
    void computeNext_invalidTimeFormat_treatedAsMidnight() {
        // 非 HH:mm → LocalTime.MIDNIGHT；当前 08:00 时 midnight 已过 → 选第二条
        List<ScheduleEntry> entries = List.of(
                new ScheduleEntry(1, "w", "garbage", "Bad", 0),
                new ScheduleEntry(2, "w", "09:00", "Good", 0));
        var c = ManualScheduleProvider.computeNext(entries, LocalTime.of(8, 0));
        assertEquals("09:00", c.nextDeparture());
        assertEquals("Good", c.nextDestination());
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
