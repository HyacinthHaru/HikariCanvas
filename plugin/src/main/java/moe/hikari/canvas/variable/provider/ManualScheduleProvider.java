package moe.hikari.canvas.variable.provider;

import moe.hikari.canvas.schedule.ScheduleEntry;
import moe.hikari.canvas.schedule.WallSchedule;
import moe.hikari.canvas.storage.ScheduleDao;
import moe.hikari.canvas.variable.VarType;
import moe.hikari.canvas.variable.VariableException;
import moe.hikari.canvas.variable.VariableStore;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 0.4.0-P3-L：内置 Manual Schedule Provider（兜底列车 / 公交时刻表）。
 *
 * <p>零外部依赖。玩家通过编辑器 "Schedule Manager" modal 配 schedule 元数据 + entries，
 * 本 Provider 在主线程线程外定期算 next_* / eta_minutes / is_arriving 并 push 进
 * {@link VariableStore}，渲染期 Compositor 替换 {@code ${var:schedule.next_departure}} 等。</p>
 *
 * <h2>per-wall namespace</h2>
 *
 * <p>namespace = {@code "schedule:<wallId>"}（与 {@link SystemVariableProvider} per-wall
 * 同款）。4 key：</p>
 * <ul>
 *   <li>{@code next_departure} (STRING) — 下一班车出发时间 {@code HH:mm}（无 entry 时空字符串）</li>
 *   <li>{@code next_destination} (STRING) — 下一班车终点</li>
 *   <li>{@code eta_minutes} (NUMBER) — 距下一班车几分钟（&gt; 0 整数；过零点时计算明天）</li>
 *   <li>{@code is_arriving} (BOOLEAN) — eta ≤ 5min 时 {@code "true"}，否则 {@code "false"}</li>
 * </ul>
 *
 * <h2>注册时机</h2>
 *
 * <p>{@link #initialize()} 启动期遍历 {@code dao.loadAll()} 注册所有"已有 wall_schedules 元数据
 * 行"的 wall（即玩家曾经打开过 modal）；首次玩家添加 entry 时由 EditSession 调
 * {@link #ensureWallRegistered(String)} 显式注册（避免对所有 wall 都注册无意义的空 schedule
 * 变量）。删除 wall 时由 SessionManager.wallDeleteHook 联动 {@link #unregisterWall(String)}。</p>
 *
 * <h2>线程模型</h2>
 *
 * <p>{@link #refresh()} 在 daemon 线程跑（30s 周期），不切主线程——本 Provider 只读自己内存
 * map + 算 LocalTime，不碰 Bukkit / 数据库。store.setValue 自身线程安全。30s 周期足以让
 * eta_minutes 精度在 ±1min（实际玩家可见单位）。</p>
 *
 * @see /Users/haru/.../docs/dynamic-data.md §7.3
 */
public final class ManualScheduleProvider implements VariableProvider {

    private static final Logger log =
            Logger.getLogger(ManualScheduleProvider.class.getName());

    /** namespace 前缀；实际 namespace = {@code "schedule:" + wallId}。 */
    public static final String NAMESPACE_PREFIX = "schedule";
    /** Daemon refresh interval = 30s（eta_minutes 精度足够 + 不浪费）。 */
    public static final long REFRESH_INTERVAL_MS = 30_000L;
    /** is_arriving 阈值（分钟）。 */
    public static final int ARRIVING_THRESHOLD_MINUTES = 5;

    /** "HH:mm" 解析器。 */
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private final VariableStore store;
    private final DataSource dataSource;

    /** 已注册的 wall 集合（值 = 该 wall 当前 entries 快照）。Concurrent 让 refresh / register 共用。 */
    private final java.util.concurrent.ConcurrentHashMap<String, List<ScheduleEntry>> registeredWalls =
            new ConcurrentHashMap<>();

    /** 生产构造：DAO 注入。 */
    public ManualScheduleProvider(VariableStore store, JavaPlugin plugin, ScheduleDao dao) {
        this(store, new DaoDataSource(dao));
    }

    /** 测试构造：注入 mock {@link DataSource}。 */
    public ManualScheduleProvider(VariableStore store, DataSource dataSource) {
        this.store = Objects.requireNonNull(store, "store");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override public String namespace() { return NAMESPACE_PREFIX; }
    @Override public String displayName() { return "Manual Schedule"; }
    @Override public Duration refreshInterval() { return Duration.ofMillis(REFRESH_INTERVAL_MS); }

    /** 静态 schema（4 key 名称），但实际 namespace 是 per-wall；declaredKeys 返 4 key 描述。 */
    @Override
    public List<DeclaredKey> declaredKeys() {
        return List.of(
                new DeclaredKey("next_departure", VarType.STRING,
                        "下一班车出发时间 HH:mm（per-wall）", REFRESH_INTERVAL_MS),
                new DeclaredKey("next_destination", VarType.STRING,
                        "下一班车终点站（per-wall）", REFRESH_INTERVAL_MS),
                new DeclaredKey("eta_minutes", VarType.NUMBER,
                        "距下一班车多少分钟（per-wall）", REFRESH_INTERVAL_MS),
                new DeclaredKey("is_arriving", VarType.BOOLEAN,
                        "5min 内到站为 true（per-wall）", REFRESH_INTERVAL_MS));
    }

    @Override
    public void initialize() {
        // 启动期遍历已有 wall_schedules 元数据行（即玩家已配置过 schedule 的 wall）注册
        for (WallSchedule ws : dataSource.loadAllSchedules()) {
            registerWallInternal(ws.wallId(), ws.entries());
        }
        // 立即 refresh 一遍让首次渲染有值
        refresh();
    }

    @Override
    public boolean refresh() {
        if (registeredWalls.isEmpty()) return false;
        LocalTime now = dataSource.currentLocalTime();
        boolean any = false;
        for (java.util.Map.Entry<String, List<ScheduleEntry>> entry : registeredWalls.entrySet()) {
            String wallId = entry.getKey();
            List<ScheduleEntry> entries = entry.getValue();
            try {
                pushValues(wallId, entries, now);
                any = true;
            } catch (Exception e) {
                log.log(Level.WARNING,
                        "schedule refresh failed: wallId=" + wallId + " — " + e.getMessage(), e);
            }
        }
        return any;
    }

    @Override
    public void shutdown() {
        registeredWalls.clear();
    }

    /**
     * 业务侧调用：确保该 wall 的 4 个 schedule 变量已注册到 store。首次添加 entry 时由
     * EditSession 触发；重复调用幂等。会从 DAO 拉最新 entries 快照填入。
     */
    public void ensureWallRegistered(String wallId) {
        if (wallId == null || wallId.isEmpty()) return;
        // 拿最新 entries 快照
        List<ScheduleEntry> entries = dataSource.loadEntries(wallId);
        registerWallInternal(wallId, entries);
        // 注册后立即算一次（不等下个 refresh tick）
        try {
            pushValues(wallId, entries, dataSource.currentLocalTime());
        } catch (Exception e) {
            log.log(Level.WARNING,
                    "schedule initial push failed: wallId=" + wallId, e);
        }
    }

    /**
     * 当某 wall 的 entries 改变时由 EditSession 调（add / update / delete entry 后）；
     * 刷新内存快照 + 触发一次立即计算。
     */
    public void refreshWall(String wallId) {
        if (wallId == null || wallId.isEmpty()) return;
        if (!registeredWalls.containsKey(wallId)) {
            // 未注册：走 ensureWallRegistered 注册 + 立即算
            ensureWallRegistered(wallId);
            return;
        }
        List<ScheduleEntry> entries = dataSource.loadEntries(wallId);
        registeredWalls.put(wallId, entries);
        try {
            pushValues(wallId, entries, dataSource.currentLocalTime());
        } catch (Exception e) {
            log.log(Level.WARNING,
                    "schedule refresh push failed: wallId=" + wallId, e);
        }
    }

    /** 注销 wall（删 4 个 schedule 变量）。同 wall 重复 / 未注册幂等。 */
    public void unregisterWall(String wallId) {
        if (wallId == null || wallId.isEmpty()) return;
        if (registeredWalls.remove(wallId) == null) return;
        String ns = NAMESPACE_PREFIX + ":" + wallId;
        for (String key : new String[] {
                "next_departure", "next_destination", "eta_minutes", "is_arriving"}) {
            try {
                store.delete(ns + "/" + key);
            } catch (VariableException e) {
                if (e.code() != VariableException.Code.VARIABLE_NOT_FOUND) {
                    log.log(Level.WARNING,
                            "schedule unregisterWall delete failed: " + ns + "/" + key, e);
                }
            }
        }
    }

    /** 已注册的 wall 集合的不可变快照（测试 / 调试用）。 */
    public Set<String> registeredWallsSnapshot() {
        return Set.copyOf(registeredWalls.keySet());
    }

    private void registerWallInternal(String wallId, List<ScheduleEntry> entries) {
        // entries 走深拷贝（dao 返不可变列表也无所谓）；放进 map 让 refresh 直接读
        List<ScheduleEntry> snapshot = entries == null
                ? List.of() : List.copyOf(entries);
        registeredWalls.put(wallId, snapshot);
        String ns = NAMESPACE_PREFIX + ":" + wallId;
        // 4 个变量，create 时让 source = "schedule"（与 SystemProvider 风格一致）
        tryCreate(ns, "next_departure", VarType.STRING);
        tryCreate(ns, "next_destination", VarType.STRING);
        tryCreate(ns, "eta_minutes", VarType.NUMBER);
        tryCreate(ns, "is_arriving", VarType.BOOLEAN);
    }

    private void tryCreate(String ns, String key, VarType type) {
        try {
            store.create(ns, key, type, null, "schedule");
        } catch (VariableException e) {
            if (e.code() != VariableException.Code.VARIABLE_EXISTS) {
                log.log(Level.WARNING,
                        "schedule create failed: " + ns + "/" + key + " — " + e.getMessage(), e);
            }
        }
    }

    /** 算 next_* + eta + is_arriving，写回 store；非 push 路径（值 null 也写空字符串占位）。 */
    private void pushValues(String wallId, List<ScheduleEntry> entries, LocalTime now) {
        String ns = NAMESPACE_PREFIX + ":" + wallId;
        Computed c = computeNext(entries, now);

        Duration ttl = Duration.ofMillis(REFRESH_INTERVAL_MS);
        tryWrite(ns + "/next_departure",
                c.nextDeparture == null ? "" : c.nextDeparture, ttl);
        tryWrite(ns + "/next_destination",
                c.nextDestination == null ? "" : c.nextDestination, ttl);
        tryWrite(ns + "/eta_minutes",
                c.etaMinutes == null ? "" : c.etaMinutes.toString(), ttl);
        tryWrite(ns + "/is_arriving", Boolean.toString(c.isArriving), ttl);
    }

    private void tryWrite(String fullName, String value, Duration ttl) {
        try {
            store.setValue(fullName, value, ttl);
        } catch (VariableException e) {
            if (e.code() != VariableException.Code.VARIABLE_NOT_FOUND) {
                log.log(Level.WARNING,
                        "schedule setValue failed: " + fullName + " — " + e.getMessage(), e);
            }
            // VARIABLE_NOT_FOUND（被外部删了）→ 静默：refresh 下轮再处理 / unregisterWall 之后正常
        }
    }

    /**
     * 计算下一班车信息。entries 已按 sort_order / departure_time 排序；本方法二次稳定排序（按
     * departureTime）以防输入未排好。过零点逻辑：所有 entry 都已过 → next 是第一个 entry（明天），
     * eta = (24h - now + nextTime).
     */
    static Computed computeNext(List<ScheduleEntry> entries, LocalTime now) {
        if (entries == null || entries.isEmpty()) {
            return new Computed(null, null, null, false);
        }
        // 二次稳定排序：按 departure_time ASC（同 time 保留输入顺序，等价 sortOrder）
        List<ScheduleEntry> sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> {
            int cmp = safeParseTime(a.departureTime()).compareTo(
                    safeParseTime(b.departureTime()));
            if (cmp != 0) return cmp;
            return Integer.compare(a.sortOrder(), b.sortOrder());
        });

        // 找第一个 departure_time > now 的 entry
        ScheduleEntry next = null;
        for (ScheduleEntry e : sorted) {
            LocalTime t = safeParseTime(e.departureTime());
            if (t.isAfter(now)) {
                next = e;
                break;
            }
        }
        long etaMinutes;
        if (next != null) {
            LocalTime t = safeParseTime(next.departureTime());
            etaMinutes = Duration.between(now, t).toMinutes();
        } else {
            // 所有 entry 都已过 → 用第一个 entry（明天）
            next = sorted.get(0);
            LocalTime t = safeParseTime(next.departureTime());
            // 24h - now + t
            long minutesToMidnight = Duration.between(now, LocalTime.MAX).toMinutes() + 1;
            long minutesAfterMidnight = Duration.between(LocalTime.MIDNIGHT, t).toMinutes();
            etaMinutes = minutesToMidnight + minutesAfterMidnight;
        }
        // 防误差：etaMinutes 负数兜底 0；超过 1440 截到 1440
        if (etaMinutes < 0) etaMinutes = 0;
        if (etaMinutes > 1440) etaMinutes = 1440;
        boolean arriving = etaMinutes <= ARRIVING_THRESHOLD_MINUTES;
        return new Computed(next.departureTime(), next.destination(),
                (int) etaMinutes, arriving);
    }

    /** 容错时间解析；非法格式返 {@link LocalTime#MIDNIGHT}（让该 entry 排前 + 不爆栈）。 */
    private static LocalTime safeParseTime(String hhmm) {
        if (hhmm == null) return LocalTime.MIDNIGHT;
        try {
            return LocalTime.parse(hhmm.trim(), TIME_FMT);
        } catch (Exception e) {
            return LocalTime.MIDNIGHT;
        }
    }

    /** 计算结果。 */
    public record Computed(
            @Nullable String nextDeparture,
            @Nullable String nextDestination,
            @Nullable Integer etaMinutes,
            boolean isArriving
    ) {}

    /**
     * DAO + 时钟抽象。生产用 {@link DaoDataSource}；测试注入 mock 控时间 / 控 entries。
     */
    public interface DataSource {
        List<WallSchedule> loadAllSchedules();
        List<ScheduleEntry> loadEntries(String wallId);
        LocalTime currentLocalTime();
    }

    /** 生产 DAO 实现：以系统本地时钟 + ScheduleDao 拉数据。 */
    private static final class DaoDataSource implements DataSource {
        private final ScheduleDao dao;

        DaoDataSource(ScheduleDao dao) {
            this.dao = dao;
        }

        @Override
        public List<WallSchedule> loadAllSchedules() {
            try {
                return dao.loadAll();
            } catch (Exception e) {
                log.log(Level.WARNING, "loadAllSchedules failed", e);
                return List.of();
            }
        }

        @Override
        public List<ScheduleEntry> loadEntries(String wallId) {
            try {
                return dao.loadByWall(wallId)
                        .map(WallSchedule::entries)
                        .orElse(Collections.emptyList());
            } catch (Exception e) {
                log.log(Level.WARNING, "loadEntries failed: " + wallId, e);
                return List.of();
            }
        }

        @Override
        public LocalTime currentLocalTime() {
            return LocalTime.now();
        }
    }
}
