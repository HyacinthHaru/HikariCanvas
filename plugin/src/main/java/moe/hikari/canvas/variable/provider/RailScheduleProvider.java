package moe.hikari.canvas.variable.provider;

import moe.hikari.canvas.HikariCanvasConfig;
import moe.hikari.canvas.rail.RailRun;
import moe.hikari.canvas.rail.RailStation;
import moe.hikari.canvas.rail.RailTimetableEntry;
import moe.hikari.canvas.rail.ServiceType;
import moe.hikari.canvas.rail.WallRailBinding;
import moe.hikari.canvas.storage.RailDao;
import moe.hikari.canvas.variable.VarType;
import moe.hikari.canvas.variable.VariableException;
import moe.hikari.canvas.variable.VariableStore;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 0.4.4 P2：铁路网络时刻表 Provider。与 {@link ManualScheduleProvider} 共享
 * {@code schedule:<wallId>/*} namespace；仅对**已绑定铁路网络**（{@code wall_rail_bindings.line_id
 * IS NOT NULL}）的 wall 接管 push，未绑定的 wall 由 ManualScheduleProvider 继续走 0.4.0
 * 旧路径（fallback）。
 *
 * <p>{@link #refresh()} 对每个已注册 wall：</p>
 * <ol>
 *   <li>查 {@code wall_rail_bindings} 拿 line / station / direction</li>
 *   <li>{@link RailDao#listStationStops} 拉该站当前方向所有车次的 timetable JOIN run</li>
 *   <li>按 LocalTime 比较找下一班 / 下下班（过零点回首班）</li>
 *   <li>JOIN station 拿 terminus 名（end_station_id）</li>
 *   <li>push 22 key（兼容 ManualSchedule 15 + 新 7 车次语义 next_* + 7 next2_*；
 *       实际新增 7×2=14 写两次会覆盖；同名 key 复用）</li>
 * </ol>
 *
 * <p>线程模型 = 同 ManualScheduleProvider：daemon 线程 1s 周期，主线程外计算，
 * {@link VariableStore#setValue} 自身线程安全。</p>
 *
 * <h2>暴露变量（22 key 全套，与 ManualSchedule 兼容）</h2>
 *
 * <ul>
 *   <li>兼容旧：{@code next_departure / next_destination / eta_minutes / eta_seconds /
 *       eta_mmss / is_arriving / arrival_status / precision}</li>
 *   <li>0.4.4 新车次语义：{@code next_run_number / next_service_type /
 *       next_service_type_text / next_cars / next_terminus / next_notes / next_arrival}</li>
 *   <li>next2 同上（next_* / next2_* 配对，第二班车）</li>
 * </ul>
 */
public final class RailScheduleProvider implements VariableProvider {

    private static final Logger log = Logger.getLogger(RailScheduleProvider.class.getName());

    /**
     * 对外暴露给 wall 的 namespace 前缀 — 与 {@link ManualScheduleProvider#NAMESPACE_PREFIX}
     * 相同（"schedule"）让旧 wall 文本无感升级；rail-bound wall 的 ${var:schedule.X} 与
     * 未绑定 wall 的 ${var:schedule.X} 文本完全一致。
     */
    public static final String NAMESPACE_PREFIX = "schedule";

    /**
     * 0.4.6 hotfix：{@link VariableProvider#namespace()} 是 {@link VariableProviderDaemon}
     * 内部用作 provider 实例 map 的 key（必须全局唯一）；这与"对外暴露的 store namespace
     * 前缀"是两个独立概念。Rail 和 Manual 都暴露 {@code schedule:<wallId>/*} 给 wall（
     * NAMESPACE_PREFIX），但在 daemon 内部必须用不同 key 区分实例——否则
     * `daemon.register` 会抛 IllegalStateException("Provider for namespace 'schedule'
     * already registered")。
     *
     * <p>这个值仅 daemon 内部用，不参与 store 写入 / interpolator 解析。</p>
     */
    public static final String DAEMON_KEY = "schedule_rail";

    public static final long REFRESH_INTERVAL_MS = 1_000L;
    public static final long MINUTE_INTERVAL_MS = 30_000L;
    public static final long SECOND_INTERVAL_MS = 1_000L;

    /** "HH:mm:ss" 解析（rail_timetable 字段约定 + 兼容 "HH:mm"）。 */
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
    private volatile HikariCanvasConfig.ScheduleConfig config;
    /** owner locale（i18n service_type_text）；config / runtime 可改。 */
    private volatile Locale ownerLocale;

    /** 已注册铁路 wall（值 = 该 wall 当前 binding 快照）。 */
    private final ConcurrentHashMap<String, WallRailBinding> registeredWalls = new ConcurrentHashMap<>();
    /** 节流（同 ManualSchedule，secondary 精度走 1s / 默认 30s）。0.4.4 默认 second（地铁屏标配）。 */
    private final ConcurrentHashMap<String, Long> lastPushAt = new ConcurrentHashMap<>();

    /** 生产构造：注入 RailDao + config + locale。 */
    public RailScheduleProvider(VariableStore store, RailDao dao,
                                HikariCanvasConfig.ScheduleConfig config,
                                Locale ownerLocale) {
        this(store, new DaoDataSource(dao),
                config == null ? HikariCanvasConfig.ScheduleConfig.defaults() : config,
                ownerLocale == null ? Locale.SIMPLIFIED_CHINESE : ownerLocale);
    }

    /** 测试构造：注入 mock {@link DataSource}。 */
    public RailScheduleProvider(VariableStore store, DataSource dataSource,
                                HikariCanvasConfig.ScheduleConfig config,
                                Locale ownerLocale) {
        this.store = Objects.requireNonNull(store, "store");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.config = config == null ? HikariCanvasConfig.ScheduleConfig.defaults() : config;
        this.ownerLocale = ownerLocale == null ? Locale.SIMPLIFIED_CHINESE : ownerLocale;
    }

    public void setConfig(HikariCanvasConfig.ScheduleConfig newConfig) {
        if (newConfig != null) this.config = newConfig;
    }

    public void setOwnerLocale(Locale locale) {
        if (locale != null) this.ownerLocale = locale;
    }

    @Override public String namespace() { return DAEMON_KEY; }
    @Override public String displayName() { return "Rail Schedule"; }
    @Override public Duration refreshInterval() { return Duration.ofMillis(REFRESH_INTERVAL_MS); }

    /** Provider 静态 schema（与 ManualSchedule 一致 + 7 新 key + 7 next2_*）。 */
    @Override
    public List<DeclaredKey> declaredKeys() {
        List<DeclaredKey> out = new ArrayList<>(ManualScheduleProvider.ALL_KEYS.length + 14);
        // 复用 ManualSchedule 的 15 个 key（同名同类）；不要重复声明，让 ManualSchedule 的 declaredKeys 处理
        // 0.4.4 新车次语义：next + next2 配对，共 14
        out.add(new DeclaredKey("next_run_number", VarType.STRING,
                "下一班车次号（如 A01）", REFRESH_INTERVAL_MS));
        out.add(new DeclaredKey("next_service_type", VarType.STRING,
                "下一班服务类型 enum 值（local / express / section / limited / 自定义）",
                REFRESH_INTERVAL_MS));
        out.add(new DeclaredKey("next_service_type_text", VarType.STRING,
                "下一班服务类型 i18n 友好文本（按 owner locale）", REFRESH_INTERVAL_MS));
        out.add(new DeclaredKey("next_cars", VarType.STRING,
                "下一班编组节数（null → 空字符串）", REFRESH_INTERVAL_MS));
        out.add(new DeclaredKey("next_terminus", VarType.STRING,
                "下一班终点站（区间车显示区间终点）", REFRESH_INTERVAL_MS));
        out.add(new DeclaredKey("next_notes", VarType.STRING,
                "下一班备注（如末班车 / 加开）", REFRESH_INTERVAL_MS));
        out.add(new DeclaredKey("next_arrival", VarType.STRING,
                "该站精确到达时刻（HH:mm:ss，从 timetable 读，非估算）", REFRESH_INTERVAL_MS));
        // next2_*
        out.add(new DeclaredKey("next2_run_number", VarType.STRING,
                "下下班车次号", REFRESH_INTERVAL_MS));
        out.add(new DeclaredKey("next2_service_type", VarType.STRING,
                "下下班服务类型 enum", REFRESH_INTERVAL_MS));
        out.add(new DeclaredKey("next2_service_type_text", VarType.STRING,
                "下下班服务类型 i18n 文本", REFRESH_INTERVAL_MS));
        out.add(new DeclaredKey("next2_cars", VarType.STRING,
                "下下班编组节数", REFRESH_INTERVAL_MS));
        out.add(new DeclaredKey("next2_terminus", VarType.STRING,
                "下下班终点站", REFRESH_INTERVAL_MS));
        out.add(new DeclaredKey("next2_notes", VarType.STRING,
                "下下班备注", REFRESH_INTERVAL_MS));
        out.add(new DeclaredKey("next2_arrival", VarType.STRING,
                "下下班该站精确到达时刻", REFRESH_INTERVAL_MS));
        return out;
    }

    @Override
    public void initialize() {
        // 启动期遍历所有 wall_rail_bindings 把 lineId != null 的注册
        for (WallRailBinding b : dataSource.loadAllBindings()) {
            if (b.lineId() == null || b.stationId() == null) continue;
            registerWallInternal(b.wallId(), b);
        }
        forceRefreshAll();
    }

    @Override
    public boolean refresh() {
        if (registeredWalls.isEmpty()) return false;
        long now = System.currentTimeMillis();
        LocalTime localNow = dataSource.currentLocalTime();
        boolean any = false;
        for (java.util.Map.Entry<String, WallRailBinding> entry : registeredWalls.entrySet()) {
            String wallId = entry.getKey();
            try {
                // 节流：rail wall 默认 second 精度（地铁屏标配）；未来可加 per-wall 精度
                long interval = SECOND_INTERVAL_MS;
                Long last = lastPushAt.get(wallId);
                if (last != null && now - last < interval) continue;
                lastPushAt.put(wallId, now);
                pushValues(wallId, entry.getValue(), localNow);
                any = true;
            } catch (Exception e) {
                log.log(Level.WARNING,
                        "rail schedule refresh failed: wallId=" + wallId + " — " + e.getMessage(), e);
            }
        }
        return any;
    }

    private void forceRefreshAll() {
        if (registeredWalls.isEmpty()) return;
        LocalTime localNow = dataSource.currentLocalTime();
        long now = System.currentTimeMillis();
        for (var entry : registeredWalls.entrySet()) {
            String wallId = entry.getKey();
            try {
                pushValues(wallId, entry.getValue(), localNow);
                lastPushAt.put(wallId, now);
            } catch (Exception e) {
                log.log(Level.WARNING, "rail initial push failed: wallId=" + wallId, e);
            }
        }
    }

    @Override
    public void shutdown() {
        registeredWalls.clear();
        lastPushAt.clear();
    }

    /**
     * 业务侧（{@code rail.wall.bind} op 后）调用：注册 / 更新 wall 的 rail binding。
     * 调用方应在 DB upsert 之后立即调本方法让 push 立即生效，不等下个 refresh tick。
     */
    public void registerWallByBinding(String wallId, WallRailBinding binding) {
        if (wallId == null || binding == null) return;
        if (binding.lineId() == null || binding.stationId() == null) {
            // 退化为未绑定 → 取消注册
            unregisterWall(wallId);
            return;
        }
        registerWallInternal(wallId, binding);
        try {
            pushValues(wallId, binding, dataSource.currentLocalTime());
            lastPushAt.put(wallId, System.currentTimeMillis());
        } catch (Exception e) {
            log.log(Level.WARNING, "rail initial push failed: wallId=" + wallId, e);
        }
    }

    /**
     * 取消铁路接管（业务侧 unbind / DELETE 后调）。
     *
     * <p>0.4.10 U-2：仅删 {@link #RAIL_ONLY_KEYS}（14 个 0.4.4 专属 key）。
     * {@link #SHARED_BASE_KEYS}（15 个与 ManualSchedule 共享的 key）<b>保留</b>——unbind
     * 后该 wall 若仍有 manual schedule，ManualScheduleProvider 会继续写这些 key；删掉只会
     * 触发多余的 re-create + 在重建窗口闪占位符。manual 不再接管时这些 key 会随其
     * TTL 自然失效或由 SessionManager wallDeleteHook → ManualScheduleProvider.unregisterWall
     * 统一清理。</p>
     */
    public void unregisterWall(String wallId) {
        if (wallId == null) return;
        if (registeredWalls.remove(wallId) == null) return;
        lastPushAt.remove(wallId);
        String ns = NAMESPACE_PREFIX + ":" + wallId;
        for (String key : RAIL_ONLY_KEYS) {
            try {
                store.delete(ns + "/" + key);
            } catch (VariableException e) {
                if (e.code() != VariableException.Code.VARIABLE_NOT_FOUND) {
                    log.log(Level.WARNING,
                            "rail unregisterWall delete failed: " + ns + "/" + key, e);
                }
            }
        }
    }

    /** 当前已注册的 rail wall 集合快照（测试用）。 */
    public Set<String> registeredWallsSnapshot() {
        return Set.copyOf(registeredWalls.keySet());
    }

    /**
     * 0.4.4 P2 关键：判断 wall 是否被铁路路径接管（HikariCanvas 装配时把它作为
     * {@code ManualScheduleProvider.skipWallPredicate} 传入，让 manual 跳过这些 wall）。
     */
    public boolean hasWallBinding(String wallId) {
        if (wallId == null) return false;
        return registeredWalls.containsKey(wallId);
    }

    // ────────────────────────────────────────────────────────────
    //  内部
    // ────────────────────────────────────────────────────────────

    /**
     * 与 {@link ManualScheduleProvider} 共享的 15 个基础 key（8 base + 7 next2 base）。
     *
     * <p>0.4.10 U-2：这些 key 由 rail 和 manual 两个 provider 共同写。{@link #unregisterWall}
     * <b>不能</b>删它们——unbind 后该 wall 可能退回 ManualSchedule 路径，删掉会让 manual
     * 重新 create（多余）+ 在重建窗口内占位符闪 "???"。仅 register 时 create（幂等，
     * manual 已 create 则跳过），unregister 时保留交给 manual。</p>
     */
    static final String[] SHARED_BASE_KEYS = new String[] {
            // 基础 8
            "next_departure", "next_destination", "eta_minutes", "eta_seconds",
            "eta_mmss", "is_arriving", "arrival_status", "precision",
            // next2 基础 7
            "next2_departure", "next2_destination",
            "next2_eta_minutes", "next2_eta_seconds", "next2_eta_mmss",
            "next2_is_arriving", "next2_arrival_status"
    };

    /**
     * 0.4.4 引入的 14 个 rail 专属 key（7 next + 7 next2）。
     *
     * <p>这些 key 只有 rail provider 写。{@link #unregisterWall} 只删这 14 个——manual
     * 不认识它们，删掉是干净的。</p>
     */
    static final String[] RAIL_ONLY_KEYS = new String[] {
            // 0.4.4 新 next 7
            "next_run_number", "next_service_type", "next_service_type_text",
            "next_cars", "next_terminus", "next_notes", "next_arrival",
            // 0.4.4 新 next2 7
            "next2_run_number", "next2_service_type", "next2_service_type_text",
            "next2_cars", "next2_terminus", "next2_notes", "next2_arrival"
    };

    /** 完整 key 列表（注册共用）：15 shared base + 14 rail-only = 29。 */
    static final String[] ALL_RAIL_KEYS = concatKeys(SHARED_BASE_KEYS, RAIL_ONLY_KEYS);

    private static String[] concatKeys(String[] a, String[] b) {
        String[] out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private void registerWallInternal(String wallId, WallRailBinding binding) {
        registeredWalls.put(wallId, binding);
        String ns = NAMESPACE_PREFIX + ":" + wallId;
        // create 全部 29 key（幂等，shared base 若 manual 已 create 则跳过）
        for (String key : ALL_RAIL_KEYS) {
            tryCreate(ns, key, varTypeOf(key));
        }
    }

    private static VarType varTypeOf(String key) {
        return switch (key) {
            case "eta_minutes", "eta_seconds",
                 "next2_eta_minutes", "next2_eta_seconds" -> VarType.NUMBER;
            case "is_arriving", "next2_is_arriving" -> VarType.BOOLEAN;
            default -> VarType.STRING;
        };
    }

    private void tryCreate(String ns, String key, VarType type) {
        try {
            store.create(ns, key, type, null, "rail");
        } catch (VariableException e) {
            if (e.code() != VariableException.Code.VARIABLE_EXISTS) {
                log.log(Level.WARNING,
                        "rail create failed: " + ns + "/" + key + " — " + e.getMessage(), e);
            }
        }
    }

    private void pushValues(String wallId, WallRailBinding binding, LocalTime now) {
        String ns = NAMESPACE_PREFIX + ":" + wallId;
        HikariCanvasConfig.ScheduleConfig cfg = config;
        Locale locale = ownerLocale;

        List<RailDao.TimetableJoinRow> stopsRaw = dataSource.listStationStops(
                binding.stationId(),
                WallRailBinding.normalizeDirection(binding.direction()));
        // 拷贝到可变 list 再排序（DAO 可能返不可变 List；同时避免污染 dao 返回值）
        List<RailDao.TimetableJoinRow> stops = new ArrayList<>(stopsRaw);
        // 排序：按 arrival_time（fallback departure_time）的 LocalTime
        stops.sort((a, b) -> safeParseTime(a.entry().arrivalTime() != null
                ? a.entry().arrivalTime() : a.entry().departureTime())
                .compareTo(safeParseTime(b.entry().arrivalTime() != null
                        ? b.entry().arrivalTime() : b.entry().departureTime())));

        Computed c = computeNext(stops, now, cfg.arrivingThresholdSeconds(),
                dataSource, locale);

        Duration ttl = Duration.ofMillis(MINUTE_INTERVAL_MS);
        // ── 基础 8 key（兼容 ManualSchedule） ─────────────────────
        tryWrite(ns + "/next_departure", c.nextDeparture, ttl);
        tryWrite(ns + "/next_destination", c.nextTerminus /* same as terminus for rail */, ttl);
        tryWrite(ns + "/eta_minutes", c.etaMinutes == null ? "" : c.etaMinutes.toString(), ttl);
        tryWrite(ns + "/eta_seconds", c.etaSeconds == null ? "" : c.etaSeconds.toString(), ttl);
        tryWrite(ns + "/eta_mmss", c.etaMmss == null ? "" : c.etaMmss, ttl);
        tryWrite(ns + "/is_arriving", Boolean.toString(c.isArriving), ttl);
        tryWrite(ns + "/arrival_status",
                c.isArriving ? cfg.arrivingText() : cfg.idleText(), ttl);
        tryWrite(ns + "/precision", "second", ttl);  // rail 固定 second 精度
        // ── next 新 7 key ─────────────────────────────────────
        tryWrite(ns + "/next_run_number", c.nextRunNumber, ttl);
        tryWrite(ns + "/next_service_type", c.nextServiceType, ttl);
        tryWrite(ns + "/next_service_type_text", c.nextServiceTypeText, ttl);
        tryWrite(ns + "/next_cars", c.nextCars, ttl);
        tryWrite(ns + "/next_terminus", c.nextTerminus, ttl);
        tryWrite(ns + "/next_notes", c.nextNotes, ttl);
        tryWrite(ns + "/next_arrival", c.nextArrival, ttl);
        // ── next2 基础 7 key ─────────────────────────────────
        tryWrite(ns + "/next2_departure", c.next2Departure, ttl);
        tryWrite(ns + "/next2_destination", c.next2Terminus, ttl);
        tryWrite(ns + "/next2_eta_minutes",
                c.next2EtaMinutes == null ? "" : c.next2EtaMinutes.toString(), ttl);
        tryWrite(ns + "/next2_eta_seconds",
                c.next2EtaSeconds == null ? "" : c.next2EtaSeconds.toString(), ttl);
        tryWrite(ns + "/next2_eta_mmss", c.next2EtaMmss == null ? "" : c.next2EtaMmss, ttl);
        tryWrite(ns + "/next2_is_arriving", Boolean.toString(c.next2IsArriving), ttl);
        tryWrite(ns + "/next2_arrival_status",
                c.next2IsArriving ? cfg.arrivingText() : cfg.idleText(), ttl);
        // ── next2 新 7 key ─────────────────────────────────────
        tryWrite(ns + "/next2_run_number", c.next2RunNumber, ttl);
        tryWrite(ns + "/next2_service_type", c.next2ServiceType, ttl);
        tryWrite(ns + "/next2_service_type_text", c.next2ServiceTypeText, ttl);
        tryWrite(ns + "/next2_cars", c.next2Cars, ttl);
        tryWrite(ns + "/next2_terminus", c.next2Terminus, ttl);
        tryWrite(ns + "/next2_notes", c.next2Notes, ttl);
        tryWrite(ns + "/next2_arrival", c.next2Arrival, ttl);
    }

    private void tryWrite(String fullName, @Nullable String value, Duration ttl) {
        try {
            store.setValue(fullName, value == null ? "" : value, ttl);
        } catch (VariableException e) {
            if (e.code() != VariableException.Code.VARIABLE_NOT_FOUND) {
                log.log(Level.WARNING,
                        "rail setValue failed: " + fullName + " — " + e.getMessage(), e);
            }
        }
    }

    /**
     * 计算下一班 / 下下班的 22 字段结果。
     *
     * <p>stops 已按 arrival_time 排序、且仅含 stops_here = 1 + direction 匹配的车次行；
     * caller 走 DAO 拉时已做过过滤。</p>
     */
    static Computed computeNext(List<RailDao.TimetableJoinRow> sortedStops, LocalTime now,
                                long thresholdSeconds, DataSource ds, Locale locale) {
        if (sortedStops == null || sortedStops.isEmpty()) {
            return Computed.empty();
        }

        long secondsToMidnight = Duration.between(now, LocalTime.MAX).getSeconds() + 1;

        // 找前两个 arrival_time > now（用 arrival 作为参考点；首站无 arrival 则 fallback departure）
        int n1 = -1, n2 = -1;
        long eta1 = 0L, eta2 = 0L;
        for (int i = 0; i < sortedStops.size(); i++) {
            LocalTime t = referenceTime(sortedStops.get(i).entry());
            if (t.isAfter(now)) {
                if (n1 < 0) {
                    n1 = i;
                    eta1 = Duration.between(now, t).getSeconds();
                } else {
                    n2 = i;
                    eta2 = Duration.between(now, t).getSeconds();
                    break;
                }
            }
        }

        // 过零点处理
        if (n1 < 0) {
            n1 = 0;
            eta1 = secondsToMidnight + Duration.between(LocalTime.MIDNIGHT,
                    referenceTime(sortedStops.get(0).entry())).getSeconds();
            if (sortedStops.size() >= 2) {
                n2 = 1;
                eta2 = secondsToMidnight + Duration.between(LocalTime.MIDNIGHT,
                        referenceTime(sortedStops.get(1).entry())).getSeconds();
            } else {
                n2 = 0;
                eta2 = eta1 + 86_400L;
            }
        } else if (n2 < 0) {
            if (sortedStops.size() == 1) {
                n2 = 0;
                eta2 = eta1 + 86_400L;
            } else {
                n2 = (n1 == 0) ? 1 : 0;
                eta2 = secondsToMidnight + Duration.between(LocalTime.MIDNIGHT,
                        referenceTime(sortedStops.get(n2).entry())).getSeconds();
            }
        }

        if (eta1 < 0) eta1 = 0;
        if (eta1 > 86_400L) eta1 = 86_400L;
        if (eta2 < 0) eta2 = 0;
        if (eta2 > 172_800L) eta2 = 172_800L;

        long etaMin1 = Math.min(1440L, eta1 / 60L);
        long etaMin2 = Math.min(2880L, eta2 / 60L);
        boolean arr1 = eta1 <= Math.max(0L, thresholdSeconds);
        boolean arr2 = eta2 <= Math.max(0L, thresholdSeconds);

        return new Computed(
                snapshotFields(sortedStops.get(n1), ds, locale),
                arr1, eta1, eta2,
                snapshotFields(sortedStops.get(n2), ds, locale),
                arr2,
                (int) etaMin1, (int) etaMin2,
                ManualScheduleProvider.formatMmss(eta1),
                ManualScheduleProvider.formatMmss(eta2));
    }

    /** 单趟车的字段快照（departure / terminus / runNumber / serviceType / 等）。 */
    private static Snapshot snapshotFields(RailDao.TimetableJoinRow row,
                                           DataSource ds, Locale locale) {
        RailTimetableEntry e = row.entry();
        RailRun r = row.run();
        // departure: 首站显示 departure_time；其他显示 arrival_time（兼容旧 next_departure 字段语义）
        String departure = e.arrivalTime() != null ? e.arrivalTime() : e.departureTime();
        String arrival = e.arrivalTime();
        // terminus = run.end_station_id → station name；无则线路末站；都没则空
        String terminus = resolveTerminus(r, ds);
        return new Snapshot(
                departure == null ? "" : departure,
                arrival == null ? "" : arrival,
                terminus == null ? "" : terminus,
                r.runNumber() == null ? "" : r.runNumber(),
                r.serviceType() == null ? "" : r.serviceType(),
                ServiceType.displayText(r.serviceType(), locale),
                r.cars() == null ? "" : r.cars().toString(),
                r.notes() == null ? "" : r.notes());
    }

    /** 终点站名解析：run.end_station_id 优先；fallback 线路末站。 */
    @Nullable
    private static String resolveTerminus(RailRun r, DataSource ds) {
        String endId = r.endStationId();
        if (endId != null) {
            RailStation s = ds.findStation(endId);
            if (s != null) return s.name();
        }
        // fallback：按 direction 取线路末站
        List<RailStation> stations = ds.listStationsByLine(r.lineId());
        if (stations.isEmpty()) return null;
        if (RailRun.DIRECTION_DOWN.equals(r.direction())) {
            return stations.get(0).name();  // down 方向末站 = sort_order 最小
        }
        return stations.get(stations.size() - 1).name();
    }

    /** 单趟车的 next/next2 字段快照。 */
    record Snapshot(
            String departure,
            String arrival,
            String terminus,
            String runNumber,
            String serviceType,
            String serviceTypeText,
            String cars,
            String notes
    ) {
        static Snapshot empty() {
            return new Snapshot("", "", "", "", "", "", "", "");
        }
    }

    /** Provider push 用的完整快照。 */
    record Computed(
            String nextDeparture, String nextArrival, String nextTerminus,
            String nextRunNumber, String nextServiceType, String nextServiceTypeText,
            String nextCars, String nextNotes,
            boolean isArriving, long etaSecondsLong, long eta2SecondsLong,
            String next2Departure, String next2Arrival, String next2Terminus,
            String next2RunNumber, String next2ServiceType, String next2ServiceTypeText,
            String next2Cars, String next2Notes,
            boolean next2IsArriving,
            @Nullable Integer etaMinutes, @Nullable Integer etaSeconds,
            @Nullable Integer next2EtaMinutes, @Nullable Integer next2EtaSeconds,
            @Nullable String etaMmss, @Nullable String next2EtaMmss
    ) {
        static Computed empty() {
            return new Computed("", "", "", "", "", "", "", "",
                    false, 0L, 0L,
                    "", "", "", "", "", "", "", "",
                    false, null, null, null, null, null, null);
        }

        Computed(Snapshot s1, boolean arr1, long e1, long e2,
                 Snapshot s2, boolean arr2,
                 int etaMin1, int etaMin2,
                 String mmss1, String mmss2) {
            this(s1.departure(), s1.arrival(), s1.terminus(),
                    s1.runNumber(), s1.serviceType(), s1.serviceTypeText(),
                    s1.cars(), s1.notes(),
                    arr1, e1, e2,
                    s2.departure(), s2.arrival(), s2.terminus(),
                    s2.runNumber(), s2.serviceType(), s2.serviceTypeText(),
                    s2.cars(), s2.notes(),
                    arr2,
                    etaMin1, (int) e1,
                    etaMin2, (int) e2,
                    mmss1, mmss2);
        }
    }

    /** 拿"参考时间"（arrival 优先；首站无 arrival 时 fallback departure）。 */
    private static LocalTime referenceTime(RailTimetableEntry e) {
        String t = e.arrivalTime() != null ? e.arrivalTime() : e.departureTime();
        return safeParseTime(t);
    }

    private static LocalTime safeParseTime(String s) {
        if (s == null) return LocalTime.MIDNIGHT;
        try {
            return LocalTime.parse(s.trim(), TIME_FMT);
        } catch (DateTimeParseException | NullPointerException e) {
            return LocalTime.MIDNIGHT;
        }
    }

    // ────────────────────────────────────────────────────────────
    //  DataSource：DAO 抽象层（测试可注入 mock）
    // ────────────────────────────────────────────────────────────

    /** RailDao + 时钟抽象。 */
    public interface DataSource {
        List<WallRailBinding> loadAllBindings();
        List<RailDao.TimetableJoinRow> listStationStops(String stationId, String direction);
        @Nullable RailStation findStation(String stationId);
        List<RailStation> listStationsByLine(String lineId);
        LocalTime currentLocalTime();
    }

    /** 生产 DAO 实现：以系统本地时钟 + RailDao 拉数据。 */
    private static final class DaoDataSource implements DataSource {
        private final RailDao dao;

        DaoDataSource(RailDao dao) {
            this.dao = dao;
        }

        @Override
        public List<WallRailBinding> loadAllBindings() {
            try {
                return dao.listAllBindings();
            } catch (Exception e) {
                log.log(Level.WARNING, "loadAllBindings failed", e);
                return List.of();
            }
        }

        @Override
        public List<RailDao.TimetableJoinRow> listStationStops(String stationId, String direction) {
            try {
                return dao.listStationStops(stationId, direction);
            } catch (Exception e) {
                log.log(Level.WARNING, "listStationStops failed: " + stationId, e);
                return List.of();
            }
        }

        @Override
        @Nullable
        public RailStation findStation(String stationId) {
            try {
                return dao.findStation(stationId).orElse(null);
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public List<RailStation> listStationsByLine(String lineId) {
            try {
                return dao.listStationsByLine(lineId);
            } catch (Exception e) {
                return List.of();
            }
        }

        @Override
        public LocalTime currentLocalTime() {
            return LocalTime.now();
        }
    }
}
