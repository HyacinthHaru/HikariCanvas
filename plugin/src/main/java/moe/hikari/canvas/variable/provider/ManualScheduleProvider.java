package moe.hikari.canvas.variable.provider;

import moe.hikari.canvas.HikariCanvasConfig;
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
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
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
 * 本 Provider 在主线程线程外定期算 next_* / eta_* / arrival_status / is_arriving 并 push 进
 * {@link VariableStore}，渲染期 Compositor 替换占位符。</p>
 *
 * <h2>per-wall namespace</h2>
 *
 * <p>namespace = {@code "schedule:<wallId>"}（与 {@link SystemVariableProvider} per-wall
 * 同款）。7 key（0.4.0 bugfix 后扩展）：</p>
 * <ul>
 *   <li>{@code next_departure} (STRING) — 下一班车出发时间 {@code HH:mm} 或 {@code HH:mm:ss}
 *       （按 wall.precision；无 entry 时空字符串）</li>
 *   <li>{@code next_destination} (STRING) — 下一班车终点</li>
 *   <li>{@code eta_minutes} (NUMBER) — 距下一班车几分钟（向下兼容；过零点时计算明天）</li>
 *   <li>{@code eta_seconds} (NUMBER) — 距下一班车几秒（秒精度新增）</li>
 *   <li>{@code is_arriving} (BOOLEAN) — eta ≤ 阈值（config 默认 60s）为 {@code "true"}</li>
 *   <li>{@code arrival_status} (STRING) — 进站中文案 / 空闲文案（config 可改）</li>
 *   <li>{@code precision} (STRING) — wall 当前精度 {@code "minute"} / {@code "second"}</li>
 * </ul>
 *
 * <h2>注册时机</h2>
 *
 * <p>{@link #initialize()} 启动期遍历 {@code dao.loadAll()} 注册所有"已有 wall_schedules 元数据
 * 行"的 wall（即玩家曾经打开过 modal）；首次玩家添加 entry 时由 EditSession 调
 * {@link #ensureWallRegistered(String)} 显式注册（避免对所有 wall 都注册无意义的空 schedule
 * 变量）。删除 wall 时由 SessionManager.wallDeleteHook 联动 {@link #unregisterWall(String)}。</p>
 *
 * <h2>线程模型 / 节流</h2>
 *
 * <p>0.4.0 bugfix（Bug 4）：{@link #refresh()} 在 daemon 线程跑（1s 周期，最小粒度），内部按
 * 每个 wall 的 precision 节流：</p>
 * <ul>
 *   <li>{@code precision="second"} → 每 1s push 一次</li>
 *   <li>{@code precision="minute"} → 每 30s push 一次（默认；与 0.4.0 原行为一致）</li>
 * </ul>
 *
 * <p>{@link #refresh()} 自身轻量（O(W) 内存表 + LocalTime 计算），不切主线程。store.setValue
 * 自身线程安全。</p>
 *
 * @see /Users/haru/.../docs/dynamic-data.md §7.3
 */
public final class ManualScheduleProvider implements VariableProvider {

    private static final Logger log =
            Logger.getLogger(ManualScheduleProvider.class.getName());

    /** namespace 前缀；实际 namespace = {@code "schedule:" + wallId}。 */
    public static final String NAMESPACE_PREFIX = "schedule";
    /**
     * Daemon refresh interval = 1s（最小粒度）。每 wall 实际 push 间隔由 precision 控制：
     * minute → 30s / second → 1s（按 lastPushAt 节流）。
     */
    public static final long REFRESH_INTERVAL_MS = 1_000L;
    /** minute 精度 wall 的 push 间隔。 */
    public static final long MINUTE_INTERVAL_MS = 30_000L;
    /** second 精度 wall 的 push 间隔（= REFRESH_INTERVAL_MS）。 */
    public static final long SECOND_INTERVAL_MS = 1_000L;

    /** "HH:mm" / "HH:mm:ss" 解析器：支持可选秒。 */
    private static final DateTimeFormatter TIME_FMT = new DateTimeFormatterBuilder()
            .appendValue(ChronoField.HOUR_OF_DAY, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .optionalStart()
            .appendLiteral(':')
            .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
            .optionalEnd()
            .toFormatter(Locale.ROOT);

    private final VariableStore store;
    private final DataSource dataSource;
    /** 0.4.0 bugfix（Bug 3）：阈值 / 文案配置（不可变快照；reload config 后整体替换）。 */
    private volatile HikariCanvasConfig.ScheduleConfig config;

    /** 已注册的 wall 集合（值 = 该 wall 当前 entries 快照）。 */
    private final ConcurrentHashMap<String, List<ScheduleEntry>> registeredWalls =
            new ConcurrentHashMap<>();
    /** 0.4.0 bugfix（Bug 4）：每 wall 上次 push 时间戳（用于按 precision 节流）。 */
    private final ConcurrentHashMap<String, Long> lastPushAt = new ConcurrentHashMap<>();

    /** 生产构造：DAO 注入；默认 config。 */
    public ManualScheduleProvider(VariableStore store, JavaPlugin plugin, ScheduleDao dao) {
        this(store, new DaoDataSource(dao), HikariCanvasConfig.ScheduleConfig.defaults());
    }

    /** 生产构造：DAO + 显式 config。 */
    public ManualScheduleProvider(VariableStore store, JavaPlugin plugin, ScheduleDao dao,
                                  HikariCanvasConfig.ScheduleConfig config) {
        this(store, new DaoDataSource(dao), config);
    }

    /** 测试构造：注入 mock {@link DataSource}；默认 config。 */
    public ManualScheduleProvider(VariableStore store, DataSource dataSource) {
        this(store, dataSource, HikariCanvasConfig.ScheduleConfig.defaults());
    }

    /** 测试构造：注入 mock {@link DataSource} + 显式 config。 */
    public ManualScheduleProvider(VariableStore store, DataSource dataSource,
                                  HikariCanvasConfig.ScheduleConfig config) {
        this.store = Objects.requireNonNull(store, "store");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.config = config == null ? HikariCanvasConfig.ScheduleConfig.defaults() : config;
    }

    /** 0.4.0 bugfix（Bug 3）：reload config 后热替换。 */
    public void setConfig(HikariCanvasConfig.ScheduleConfig newConfig) {
        if (newConfig == null) return;
        this.config = newConfig;
    }

    @Override public String namespace() { return NAMESPACE_PREFIX; }
    @Override public String displayName() { return "Manual Schedule"; }
    @Override public Duration refreshInterval() { return Duration.ofMillis(REFRESH_INTERVAL_MS); }

    /** 静态 schema（key 描述），但实际 namespace 是 per-wall。 */
    @Override
    public List<DeclaredKey> declaredKeys() {
        return List.of(
                new DeclaredKey("next_departure", VarType.STRING,
                        "下一班车出发时间 HH:mm 或 HH:mm:ss（按 wall precision）", REFRESH_INTERVAL_MS),
                new DeclaredKey("next_destination", VarType.STRING,
                        "下一班车终点站（per-wall）", REFRESH_INTERVAL_MS),
                new DeclaredKey("eta_minutes", VarType.NUMBER,
                        "距下一班车多少分钟（向下兼容）", REFRESH_INTERVAL_MS),
                new DeclaredKey("eta_seconds", VarType.NUMBER,
                        "距下一班车多少秒（秒精度）", REFRESH_INTERVAL_MS),
                new DeclaredKey("is_arriving", VarType.BOOLEAN,
                        "eta ≤ 阈值时为 true（阈值见 config，默认 60s）", REFRESH_INTERVAL_MS),
                new DeclaredKey("arrival_status", VarType.STRING,
                        "进站文案（eta ≤ 阈值时）/ 空闲文案，config 可改", REFRESH_INTERVAL_MS),
                new DeclaredKey("precision", VarType.STRING,
                        "wall 当前精度（minute / second）", REFRESH_INTERVAL_MS));
    }

    @Override
    public void initialize() {
        // 启动期遍历已有 wall_schedules 元数据行（即玩家已配置过 schedule 的 wall）注册
        for (WallSchedule ws : dataSource.loadAllSchedules()) {
            registerWallInternal(ws.wallId(), ws.entries());
        }
        // 立即 refresh 一遍让首次渲染有值（不走节流，全部 push）
        forceRefreshAll();
    }

    @Override
    public boolean refresh() {
        if (registeredWalls.isEmpty()) return false;
        long now = System.currentTimeMillis();
        LocalTime localNow = dataSource.currentLocalTime();
        boolean any = false;
        for (java.util.Map.Entry<String, List<ScheduleEntry>> entry : registeredWalls.entrySet()) {
            String wallId = entry.getKey();
            try {
                // 0.4.0 bugfix（Bug 4）：按 wall.precision 节流
                String precision = currentPrecision(wallId);
                long interval = WallSchedule.PRECISION_SECOND.equals(precision)
                        ? SECOND_INTERVAL_MS : MINUTE_INTERVAL_MS;
                Long last = lastPushAt.get(wallId);
                if (last != null && now - last < interval) continue;
                lastPushAt.put(wallId, now);
                pushValues(wallId, entry.getValue(), localNow, precision);
                any = true;
            } catch (Exception e) {
                log.log(Level.WARNING,
                        "schedule refresh failed: wallId=" + wallId + " — " + e.getMessage(), e);
            }
        }
        return any;
    }

    /** 不经过节流强制 refresh 所有 wall（initialize / ensureWallRegistered / refreshWall 用）。 */
    private void forceRefreshAll() {
        if (registeredWalls.isEmpty()) return;
        LocalTime localNow = dataSource.currentLocalTime();
        long now = System.currentTimeMillis();
        for (java.util.Map.Entry<String, List<ScheduleEntry>> entry : registeredWalls.entrySet()) {
            String wallId = entry.getKey();
            try {
                pushValues(wallId, entry.getValue(), localNow, currentPrecision(wallId));
                lastPushAt.put(wallId, now);
            } catch (Exception e) {
                log.log(Level.WARNING,
                        "schedule initial push failed: wallId=" + wallId, e);
            }
        }
    }

    @Override
    public void shutdown() {
        registeredWalls.clear();
        lastPushAt.clear();
    }

    /**
     * 业务侧调用：确保该 wall 的 schedule 变量已注册到 store。首次添加 entry 时由
     * EditSession 触发；重复调用幂等。会从 DAO 拉最新 entries 快照填入。
     */
    public void ensureWallRegistered(String wallId) {
        if (wallId == null || wallId.isEmpty()) return;
        // 拿最新 entries 快照
        List<ScheduleEntry> entries = dataSource.loadEntries(wallId);
        registerWallInternal(wallId, entries);
        // 注册后立即算一次（不等下个 refresh tick）
        try {
            String precision = currentPrecision(wallId);
            pushValues(wallId, entries, dataSource.currentLocalTime(), precision);
            lastPushAt.put(wallId, System.currentTimeMillis());
        } catch (Exception e) {
            log.log(Level.WARNING,
                    "schedule initial push failed: wallId=" + wallId, e);
        }
    }

    /**
     * 当某 wall 的 entries 改变时由 EditSession 调（add / update / delete entry 后）；
     * 刷新内存快照 + 触发一次立即计算（绕过节流）。
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
            String precision = currentPrecision(wallId);
            pushValues(wallId, entries, dataSource.currentLocalTime(), precision);
            lastPushAt.put(wallId, System.currentTimeMillis());
        } catch (Exception e) {
            log.log(Level.WARNING,
                    "schedule refresh push failed: wallId=" + wallId, e);
        }
    }

    /** 注销 wall（删 schedule 变量）。同 wall 重复 / 未注册幂等。 */
    public void unregisterWall(String wallId) {
        if (wallId == null || wallId.isEmpty()) return;
        if (registeredWalls.remove(wallId) == null) return;
        lastPushAt.remove(wallId);
        String ns = NAMESPACE_PREFIX + ":" + wallId;
        for (String key : new String[] {
                "next_departure", "next_destination",
                "eta_minutes", "eta_seconds",
                "is_arriving", "arrival_status",
                "precision"}) {
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

    /** 当前阈值配置（测试用 / 调试用）。 */
    public HikariCanvasConfig.ScheduleConfig configSnapshot() {
        return config;
    }

    private void registerWallInternal(String wallId, List<ScheduleEntry> entries) {
        // entries 走深拷贝（dao 返不可变列表也无所谓）；放进 map 让 refresh 直接读
        List<ScheduleEntry> snapshot = entries == null
                ? List.of() : List.copyOf(entries);
        registeredWalls.put(wallId, snapshot);
        String ns = NAMESPACE_PREFIX + ":" + wallId;
        // 0.4.0 bugfix（Bug 3）：扩展 7 个变量；旧 4 个保留向下兼容
        tryCreate(ns, "next_departure", VarType.STRING);
        tryCreate(ns, "next_destination", VarType.STRING);
        tryCreate(ns, "eta_minutes", VarType.NUMBER);
        tryCreate(ns, "eta_seconds", VarType.NUMBER);
        tryCreate(ns, "is_arriving", VarType.BOOLEAN);
        tryCreate(ns, "arrival_status", VarType.STRING);
        tryCreate(ns, "precision", VarType.STRING);
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

    /**
     * 算 next_* + eta_* + is_arriving + arrival_status + precision，写回 store。
     */
    private void pushValues(String wallId, List<ScheduleEntry> entries, LocalTime now,
                            String precision) {
        String ns = NAMESPACE_PREFIX + ":" + wallId;
        HikariCanvasConfig.ScheduleConfig cfg = config; // volatile snapshot
        Computed c = computeNext(entries, now, cfg.arrivingThresholdSeconds());

        Duration ttl = Duration.ofMillis(MINUTE_INTERVAL_MS);
        tryWrite(ns + "/next_departure",
                c.nextDeparture == null ? "" : c.nextDeparture, ttl);
        tryWrite(ns + "/next_destination",
                c.nextDestination == null ? "" : c.nextDestination, ttl);
        tryWrite(ns + "/eta_minutes",
                c.etaMinutes == null ? "" : c.etaMinutes.toString(), ttl);
        tryWrite(ns + "/eta_seconds",
                c.etaSeconds == null ? "" : c.etaSeconds.toString(), ttl);
        tryWrite(ns + "/is_arriving", Boolean.toString(c.isArriving), ttl);
        tryWrite(ns + "/arrival_status",
                c.isArriving ? cfg.arrivingText() : cfg.idleText(), ttl);
        tryWrite(ns + "/precision",
                precision == null ? WallSchedule.PRECISION_MINUTE : precision, ttl);
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

    /** 拿 wall 当前 precision；DAO 失败 / 行不存在时返默认 minute。 */
    private String currentPrecision(String wallId) {
        try {
            return dataSource.loadByWallPrecision(wallId);
        } catch (Exception e) {
            return WallSchedule.PRECISION_MINUTE;
        }
    }

    /**
     * 计算下一班车信息（秒粒度）。entries 已按 sort_order / departure_time 排序；本方法二次
     * 稳定排序（按 departureTime）以防输入未排好。过零点逻辑：所有 entry 都已过 → next 是
     * 第一个 entry（明天），eta = (24h - now + nextTime).
     *
     * @param thresholdSeconds is_arriving 阈值（秒）
     */
    static Computed computeNext(List<ScheduleEntry> entries, LocalTime now, long thresholdSeconds) {
        if (entries == null || entries.isEmpty()) {
            return new Computed(null, null, null, null, false);
        }
        // 二次稳定排序：按 departure_time ASC（同 time 保留输入顺序，等价 sortOrder）
        List<ScheduleEntry> sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> {
            int cmp = safeParseTime(a.departureTime()).compareTo(
                    safeParseTime(b.departureTime()));
            if (cmp != 0) return cmp;
            return Integer.compare(a.sortOrder(), b.sortOrder());
        });

        // 找第一个 departure_time > now 的 entry（秒级比较）
        ScheduleEntry next = null;
        for (ScheduleEntry e : sorted) {
            LocalTime t = safeParseTime(e.departureTime());
            if (t.isAfter(now)) {
                next = e;
                break;
            }
        }
        long etaSeconds;
        if (next != null) {
            LocalTime t = safeParseTime(next.departureTime());
            etaSeconds = Duration.between(now, t).getSeconds();
        } else {
            // 所有 entry 都已过 → 用第一个 entry（明天）
            next = sorted.get(0);
            LocalTime t = safeParseTime(next.departureTime());
            // 24h - now + t（秒）
            long secondsToMidnight = Duration.between(now, LocalTime.MAX).getSeconds() + 1;
            long secondsAfterMidnight = Duration.between(LocalTime.MIDNIGHT, t).getSeconds();
            etaSeconds = secondsToMidnight + secondsAfterMidnight;
        }
        // 防误差：etaSeconds 负数兜底 0；超过 86400 截到 86400
        if (etaSeconds < 0) etaSeconds = 0;
        if (etaSeconds > 86_400L) etaSeconds = 86_400L;
        long etaMinutes = etaSeconds / 60L;
        if (etaMinutes > 1440L) etaMinutes = 1440L;
        boolean arriving = etaSeconds <= Math.max(0L, thresholdSeconds);
        return new Computed(next.departureTime(), next.destination(),
                (int) etaMinutes, (int) etaSeconds, arriving);
    }

    /**
     * 容错时间解析；非法格式返 {@link LocalTime#MIDNIGHT}（让该 entry 排前 + 不爆栈）。
     * 支持 HH:mm 和 HH:mm:ss 两种格式（0.4.0 bugfix Bug 4）。
     */
    private static LocalTime safeParseTime(String hhmm) {
        if (hhmm == null) return LocalTime.MIDNIGHT;
        try {
            return LocalTime.parse(hhmm.trim(), TIME_FMT);
        } catch (DateTimeParseException | NullPointerException e) {
            return LocalTime.MIDNIGHT;
        }
    }

    /** 计算结果。 */
    public record Computed(
            @Nullable String nextDeparture,
            @Nullable String nextDestination,
            @Nullable Integer etaMinutes,
            @Nullable Integer etaSeconds,
            boolean isArriving
    ) {}

    /**
     * DAO + 时钟抽象。生产用 {@link DaoDataSource}；测试注入 mock 控时间 / 控 entries。
     */
    public interface DataSource {
        List<WallSchedule> loadAllSchedules();
        List<ScheduleEntry> loadEntries(String wallId);
        LocalTime currentLocalTime();
        /** 0.4.0 bugfix（Bug 4）：拿该 wall 当前 precision。空 / 失败默认 "minute"。 */
        default String loadByWallPrecision(String wallId) {
            return WallSchedule.PRECISION_MINUTE;
        }
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

        @Override
        public String loadByWallPrecision(String wallId) {
            try {
                return dao.loadByWall(wallId)
                        .map(WallSchedule::precision)
                        .orElse(WallSchedule.PRECISION_MINUTE);
            } catch (Exception e) {
                return WallSchedule.PRECISION_MINUTE;
            }
        }
    }
}
