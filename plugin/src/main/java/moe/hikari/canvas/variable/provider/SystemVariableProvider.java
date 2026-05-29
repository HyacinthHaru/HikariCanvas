package moe.hikari.canvas.variable.provider;

import moe.hikari.canvas.storage.WallRepo;
import moe.hikari.canvas.variable.VarType;
import moe.hikari.canvas.variable.VariableException;
import moe.hikari.canvas.variable.VariableStore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * 0.4.0-P3-J：系统变量 Provider（Tier 3）。
 *
 * <p>注册两轨：</p>
 * <ul>
 *   <li><b>全局 8 变量</b>（namespace {@code system}）：
 *       {@code server.time} / {@code server.real_time} / {@code server.tick} /
 *       {@code server.online} / {@code server.online_list} / {@code server.motd} /
 *       {@code server.tps} / {@code server.name}。
 *       各自 TTL 不同（{@code server.tick} 1s 起到 {@code server.motd} 1h）。</li>
 *   <li><b>per-wall 4 变量</b>（namespace {@code system:<wallId>}）：
 *       {@code wall.id} / {@code wall.alias} / {@code wall.owner} / {@code wall.owner_uuid}。
 *       由 {@link VariableInterpolator#resolveFullName} 注入：
 *       {@code ${var:wall.id}} + wallId="w-abc" → {@code system:w-abc/wall.id}。</li>
 * </ul>
 *
 * <h2>线程模型</h2>
 *
 * <p>{@link #refresh()} 在 daemon 线程跑（{@link VariableProviderDaemon}），最小公约
 * {@link #BASE_REFRESH_INTERVAL_MS} 1s。每次 refresh 内部维护 {@code nextRefreshAt} per-key
 * 时刻表，到期者切主线程 ({@link DataSource#scheduleMainThread(Runnable)}) 计算 + 写回 store
 * （多数 Bukkit getter 非 async safe，但计算极快，主线程不感）。</p>
 *
 * <p>per-wall {@code wall.*} 变量注册时机：</p>
 * <ol>
 *   <li>启动时 {@link #initialize()} 遍历 {@link DataSource#allWallIds()} 一次性注册；</li>
 *   <li><b>运行期新建的 wall</b>（如 {@code /canvas confirm} 全空新建分支）走 dynamic-lookup
 *       兜底：{@link #initialize()} 注册 {@link VariableStore#registerDynamicLookupHook} hook，
 *       interpolator 遇 {@code system:<wid>/wall.*} miss → {@link VariableStore#notifyDynamicLookup}
 *       → 本 Provider lazy {@link #registerWall(String)}（与 ScoreboardVariableProvider /
 *       PapiVariableBridge 同款）。首次渲染走 fallback {@code "???"}，下一 tick 起有数据；</li>
 *   <li>wall 删除时由 HikariCanvas#onEnable 挂的 wallDeleteHook 联动 {@link #unregisterWall(String)}
 *       清理变量本身（{@code system:<wid>/wall.*}）。</li>
 * </ol>
 *
 * @see docs/dynamic-data.md §7.1
 */
public final class SystemVariableProvider implements VariableProvider {

    private static final Logger log = Logger.getLogger(SystemVariableProvider.class.getName());

    /** 全局 namespace。 */
    public static final String NAMESPACE = "system";
    /** Daemon refresh interval（最小公约：server.tick = 1s）。 */
    public static final long BASE_REFRESH_INTERVAL_MS = 1000L;
    /** per-wall alias 刷新节流间隔（与 wall.alias TTL=5s 一致；P2-83）。 */
    public static final long PER_WALL_ALIAS_INTERVAL_MS = 5_000L;

    /** server.time 格式化器（24h HH:mm）。 */
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    /** 全局变量 declared keys（启动期 + declaredKeys() 共用）。 */
    private static final List<SystemKey> GLOBAL_KEYS = List.of(
            new SystemKey("server.time", VarType.STRING, "当前服务器本地时间 HH:mm", 60_000L),
            new SystemKey("server.real_time", VarType.STRING, "当前 ISO 时间戳", 60_000L),
            new SystemKey("server.tick", VarType.NUMBER, "服务器 tick 计数", 1_000L),
            new SystemKey("server.online", VarType.NUMBER, "当前在线玩家数", 30_000L),
            new SystemKey("server.online_list", VarType.STRING, "在线玩家名（逗号分隔）", 30_000L),
            new SystemKey("server.motd", VarType.STRING, "服务器 MOTD", 3_600_000L),
            new SystemKey("server.tps", VarType.NUMBER, "Paper TPS (1min avg)", 30_000L),
            new SystemKey("server.name", VarType.STRING, "服务器名（config/hostname）", 3_600_000L)
    );

    /** per-wall 变量 keys。 */
    private static final List<SystemKey> PER_WALL_KEYS = List.of(
            new SystemKey("wall.id", VarType.STRING, "wall 短 ID（w-<8hex>）", 0L),
            new SystemKey("wall.alias", VarType.STRING, "wall 玩家命名（可空）", 5_000L),
            new SystemKey("wall.owner", VarType.STRING, "wall 创建者玩家名", 0L),
            new SystemKey("wall.owner_uuid", VarType.STRING, "wall 创建者 UUID", 0L)
    );

    private final VariableStore store;
    private final DataSource dataSource;

    /** 每个全局 key 的下次刷新时刻（ms epoch）。 */
    private final Map<String, Long> nextRefreshAt = new java.util.concurrent.ConcurrentHashMap<>();
    /** 已注册的 per-wall（wallId set）。Concurrent 让 registerWall / unregisterWall / refresh 共用。 */
    private final java.util.Set<String> registeredWalls =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * P2-83：per-wall alias 上次 push 时刻（ms epoch）。refresh() 每 1s 跑一次（与 server.tick 同
     * 公约），但 wall.alias TTL=5s + 极少变 —— 用此表把每 wall 的 DB 查询/刷新收到 5s 一次，
     * 避免 N 个 wall × 1 SELECT/s 永续浪费 HikariCP 连接池。
     */
    private final Map<String, Long> lastAliasPushAt = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 生产构造：注入 plugin + WallRepo。内部组装 {@link DataSource} 默认 Bukkit 实现。
     */
    public SystemVariableProvider(VariableStore store, JavaPlugin plugin, WallRepo wallRepo) {
        this(store, new BukkitDataSource(plugin, wallRepo));
    }

    /**
     * 测试构造：注入 mock {@link DataSource}。
     */
    public SystemVariableProvider(VariableStore store, DataSource dataSource) {
        this.store = Objects.requireNonNull(store, "store");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public String namespace() {
        return NAMESPACE;
    }

    @Override
    public String displayName() {
        return "系统变量";
    }

    @Override
    public Duration refreshInterval() {
        return Duration.ofMillis(BASE_REFRESH_INTERVAL_MS);
    }

    @Override
    public boolean isDynamic() {
        return false;
    }

    @Override
    public List<DeclaredKey> declaredKeys() {
        List<DeclaredKey> all = new ArrayList<>(GLOBAL_KEYS.size() + PER_WALL_KEYS.size());
        for (SystemKey k : GLOBAL_KEYS) {
            all.add(new DeclaredKey(k.key, k.type, k.description, k.ttlMs));
        }
        for (SystemKey k : PER_WALL_KEYS) {
            all.add(new DeclaredKey(k.key, k.type, k.description + "（per-wall）", k.ttlMs));
        }
        return all;
    }

    @Override
    public void initialize() {
        long now = System.currentTimeMillis();
        // P2-32：运行期新建 wall 走 dynamic-lookup 兜底。interpolator 遇 system:<wid>/wall.* miss
        // → notifyDynamicLookup("system:<wid>/wall.X")（namespace 提取为 "system:<wid>"）→ 本 hook
        // 解析 wallId lazy registerWall。与 ScoreboardVariableProvider.handleDynamic 同款模式。
        store.registerDynamicLookupHook((fullName, namespace) -> {
            if (namespace != null && namespace.startsWith(NAMESPACE + ":")) {
                handleDynamicWall(namespace.substring(NAMESPACE.length() + 1));
            }
        });
        // 全局：注册 8 个 system/<key>，立即填一次 next refresh 时刻
        for (SystemKey k : GLOBAL_KEYS) {
            String fullName = NAMESPACE + "/" + k.key;
            try {
                store.create(NAMESPACE, k.key, k.type, null, "system");
            } catch (VariableException e) {
                if (e.code() != VariableException.Code.VARIABLE_EXISTS) {
                    log.log(Level.WARNING,
                            "system variable create failed: " + fullName + " — " + e.getMessage(), e);
                }
                // 已存在（重启 / re-register）→ 仅触发刷新
            }
            nextRefreshAt.put(k.key, now); // 立即刷新
        }
        // per-wall：遍历已存在 wall 注册
        for (String wallId : dataSource.allWallIds()) {
            registerWall(wallId);
        }
        // 启动期立刻刷新一次（不等 daemon 第一个 tick），让首次 paint 就有数据
        refresh();
    }

    @Override
    public boolean refresh() {
        long now = System.currentTimeMillis();
        boolean anyPushed = false;
        // 全局 keys
        for (SystemKey k : GLOBAL_KEYS) {
            Long next = nextRefreshAt.get(k.key);
            if (next == null || now < next) continue;
            // 把这个 key 排到下一轮：用 max(now+ttl, nextDue) 防止漂移；用 now+ttl 即可（最简）
            nextRefreshAt.put(k.key, now + k.ttlMs);
            try {
                pushGlobalValue(k);
                anyPushed = true;
            } catch (Exception e) {
                log.log(Level.WARNING,
                        "system var refresh failed: " + k.key + " — " + e.getMessage(), e);
            }
        }
        // per-wall keys：所有已注册 wall 的 wall.alias 每 5s 刷新即可（其他 3 个永久变量没必要）。
        // P2-83：用 lastAliasPushAt 节流——refresh() 本身 1s 一跑，但 alias TTL=5s + 极少变，
        // 不该每 tick 对每个 wall 发一条 SELECT * FROM walls；按 PER_WALL_ALIAS_INTERVAL_MS（5s）收敛。
        for (String wallId : registeredWalls) {
            Long last = lastAliasPushAt.get(wallId);
            if (last != null && now - last < PER_WALL_ALIAS_INTERVAL_MS) continue;
            lastAliasPushAt.put(wallId, now);
            try {
                pushPerWallAlias(wallId);
                anyPushed = true;
            } catch (Exception e) {
                log.log(Level.WARNING,
                        "system per-wall refresh failed: wallId=" + wallId
                                + " — " + e.getMessage(), e);
            }
        }
        return anyPushed;
    }

    @Override
    public void shutdown() {
        nextRefreshAt.clear();
        registeredWalls.clear();
        lastAliasPushAt.clear();
    }

    /**
     * P2-32：dynamic-lookup hook 上来的 per-wall 懒注册。namespace 形如 {@code system:<wallId>}，
     * 此处 wallId 已是去掉 {@code "system:"} 前缀后的部分。registerWall 自带 add 幂等 + 永久值填充。
     */
    void handleDynamicWall(String wallId) {
        if (wallId == null || wallId.isEmpty()) return;
        if (registeredWalls.contains(wallId)) return; // 已注册，跳过 DB 往返
        registerWall(wallId);
    }

    /**
     * 注册新 wall（4 个 wall.* 变量）。永久值（id / owner / owner_uuid）立刻写入；
     * alias 走 refresh 周期更新。同 wall 重复注册幂等。
     */
    public void registerWall(String wallId) {
        if (wallId == null || wallId.isEmpty()) return;
        if (!registeredWalls.add(wallId)) return; // 已注册
        String ns = NAMESPACE + ":" + wallId;
        for (SystemKey k : PER_WALL_KEYS) {
            try {
                store.create(ns, k.key, k.type, null, "system");
            } catch (VariableException e) {
                if (e.code() != VariableException.Code.VARIABLE_EXISTS) {
                    log.log(Level.WARNING,
                            "system per-wall create failed: " + ns + "/" + k.key, e);
                }
            }
        }
        // 立刻填永久 3 项 + alias
        try {
            WallMeta meta = dataSource.wallMeta(wallId);
            if (meta != null) {
                store.setValue(ns + "/wall.id", meta.wallId(), Duration.ZERO);
                if (meta.ownerName() != null) {
                    store.setValue(ns + "/wall.owner", meta.ownerName(), Duration.ZERO);
                }
                if (meta.ownerUuid() != null) {
                    store.setValue(ns + "/wall.owner_uuid", meta.ownerUuid(), Duration.ZERO);
                }
                if (meta.alias() != null) {
                    store.setValue(ns + "/wall.alias", meta.alias(),
                            Duration.ofMillis(5_000L));
                }
            }
        } catch (Exception e) {
            log.log(Level.WARNING,
                    "system per-wall initial fill failed: wallId=" + wallId, e);
        }
    }

    /** 注销 wall（删 4 个 wall.* 变量）。同 wall 重复或未注册幂等。 */
    public void unregisterWall(String wallId) {
        if (wallId == null || wallId.isEmpty()) return;
        if (!registeredWalls.remove(wallId)) return;
        lastAliasPushAt.remove(wallId);
        String ns = NAMESPACE + ":" + wallId;
        for (SystemKey k : PER_WALL_KEYS) {
            try {
                store.delete(ns + "/" + k.key);
            } catch (VariableException e) {
                if (e.code() != VariableException.Code.VARIABLE_NOT_FOUND) {
                    log.log(Level.WARNING,
                            "system per-wall delete failed: " + ns + "/" + k.key, e);
                }
            }
        }
    }

    private void pushGlobalValue(SystemKey k) {
        // 多数 Bukkit getter 不允许 async；切主线程算 + 写回 store（store 自身线程安全，
        // 写值不需要在主线程）。计算极快，主线程不感。
        dataSource.scheduleMainThread(() -> {
            String value;
            try {
                value = computeGlobalValue(k.key);
            } catch (Exception e) {
                log.log(Level.WARNING,
                        "system var compute failed: " + k.key, e);
                // 0.4.10 P3-45：compute 异常也回退 nextRefreshAt 让下个 tick 重试，而非等满 TTL
                nextRefreshAt.put(k.key, System.currentTimeMillis());
                return;
            }
            if (value == null) {
                // 0.4.10 P3-45：value 暂不可用（如 tick / tps 尚未就绪）。refresh() 已把
                // nextRefreshAt 推进到 now+ttl —— 若不回退，这个 key 要等满一个 TTL（最长 1h）
                // 才重试。回退到当前时刻 → 下个 1s tick 即重试。ConcurrentHashMap 幂等写。
                nextRefreshAt.put(k.key, System.currentTimeMillis());
                return;
            }
            try {
                store.setValue(NAMESPACE + "/" + k.key, value, Duration.ofMillis(k.ttlMs));
            } catch (VariableException e) {
                log.log(Level.WARNING,
                        "system var setValue failed: " + k.key + " — " + e.getMessage(), e);
            }
        });
    }

    private @Nullable String computeGlobalValue(String key) {
        switch (key) {
            case "server.time":
                return LocalTime.now().format(TIME_FMT);
            case "server.real_time":
                return Instant.now().toString();
            case "server.tick": {
                Integer t = dataSource.currentTick();
                return t == null ? null : t.toString();
            }
            case "server.online":
                return Integer.toString(dataSource.onlinePlayerCount());
            case "server.online_list":
                return dataSource.onlinePlayerNames().stream()
                        .collect(Collectors.joining(","));
            case "server.motd": {
                String motd = dataSource.motd();
                return motd == null ? "" : motd;
            }
            case "server.tps": {
                Double tps = dataSource.tps1m();
                if (tps == null) return null;
                return String.format(Locale.ROOT, "%.2f", tps);
            }
            case "server.name": {
                String n = dataSource.serverName();
                return n == null ? "" : n;
            }
            default:
                return null;
        }
    }

    private void pushPerWallAlias(String wallId) {
        WallMeta meta = dataSource.wallMeta(wallId);
        if (meta == null) return;
        String ns = NAMESPACE + ":" + wallId;
        if (meta.alias() != null) {
            try {
                store.setValue(ns + "/wall.alias", meta.alias(),
                        Duration.ofMillis(5_000L));
            } catch (VariableException ignored) {
                // 变量被外部删了 → 静默
            }
        }
    }

    /** 内部 record：声明 + ttl。 */
    private record SystemKey(String key, VarType type, String description, long ttlMs) {}

    /** wall 元数据简记。 */
    public record WallMeta(
            String wallId,
            @Nullable String alias,
            @Nullable String ownerName,
            @Nullable String ownerUuid
    ) {}

    /**
     * Bukkit / DB 访问抽象。生产用 {@link BukkitDataSource}；测试注入 mock。
     *
     * <p>主线程切换由 {@link #scheduleMainThread(Runnable)} 完成——生产实现走
     * {@code Bukkit.getScheduler().runTask(plugin, r)}；测试实现直接同步 run。</p>
     */
    public interface DataSource {
        Iterable<String> allWallIds();
        @Nullable WallMeta wallMeta(String wallId);
        void scheduleMainThread(Runnable r);
        int onlinePlayerCount();
        List<String> onlinePlayerNames();
        @Nullable Integer currentTick();
        @Nullable String motd();
        @Nullable Double tps1m();
        @Nullable String serverName();
    }

    /** 生产 Bukkit / WallRepo 实现。 */
    private static final class BukkitDataSource implements DataSource {
        private final JavaPlugin plugin;
        private final WallRepo wallRepo;

        BukkitDataSource(JavaPlugin plugin, WallRepo wallRepo) {
            this.plugin = plugin;
            this.wallRepo = wallRepo;
        }

        @Override
        public Iterable<String> allWallIds() {
            try {
                return wallRepo.loadAll().stream()
                        .map(WallRepo.Wall::wallId)
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.log(Level.WARNING, "allWallIds failed: " + e.getMessage(), e);
                return List.of();
            }
        }

        @Override
        public @Nullable WallMeta wallMeta(String wallId) {
            try {
                return wallRepo.loadById(wallId)
                        .map(w -> new WallMeta(
                                w.wallId(),
                                w.alias(),
                                w.ownerName(),
                                w.ownerUuid() == null ? null : w.ownerUuid().toString()))
                        .orElse(null);
            } catch (Exception e) {
                log.log(Level.WARNING, "wallMeta failed for " + wallId + ": " + e.getMessage(), e);
                return null;
            }
        }

        @Override
        public void scheduleMainThread(Runnable r) {
            // plugin disabled 后 scheduler 调用会抛；防御性 try-catch
            try {
                if (Bukkit.isPrimaryThread()) {
                    r.run();
                } else {
                    Bukkit.getScheduler().runTask(plugin, r);
                }
            } catch (Exception e) {
                log.log(Level.WARNING, "scheduleMainThread dispatch failed", e);
            }
        }

        @Override
        public int onlinePlayerCount() {
            try {
                return Bukkit.getOnlinePlayers().size();
            } catch (Exception e) {
                return 0;
            }
        }

        @Override
        public List<String> onlinePlayerNames() {
            try {
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    names.add(p.getName());
                }
                return names;
            } catch (Exception e) {
                return List.of();
            }
        }

        @Override
        public @Nullable Integer currentTick() {
            try {
                return Bukkit.getCurrentTick();
            } catch (NoSuchMethodError | Exception e) {
                return null;
            }
        }

        @Override
        public @Nullable String motd() {
            try {
                return Bukkit.getServer().getMotd();
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public @Nullable Double tps1m() {
            try {
                double[] tps = Bukkit.getServer().getTPS();
                if (tps == null || tps.length == 0) return null;
                return tps[0];
            } catch (NoSuchMethodError | Exception e) {
                return null;
            }
        }

        @Override
        public @Nullable String serverName() {
            try {
                String n = Bukkit.getServer().getName();
                if (n != null && !n.isEmpty()) return n;
                return Bukkit.getServer().getMotd();
            } catch (Exception e) {
                return null;
            }
        }
    }
}
