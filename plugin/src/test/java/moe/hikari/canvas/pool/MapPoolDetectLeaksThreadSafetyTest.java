package moe.hikari.canvas.pool;

import moe.hikari.canvas.render.HikariCanvasRenderer;
import moe.hikari.canvas.storage.AuditLog;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlite3.SQLitePlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * E1·P2-10：{@link MapPool#detectLeaks} 异步线程安全性回归测试。
 *
 * <p><b>场景重现</b>：服务器重启，某 world 在本进程会话内从未有 FREE map
 * 经过（所有 maps 都 RESERVED，initialize 不调 offerFree），导致
 * {@link MapPool#worldNameToUid} 缓存无该 world 条目。此时某 wall 被删
 * （liveWallIds 不含其 wallId），detectLeaks 把对应 map 标记为泄漏，
 * 调用 {@code offerFreeByName(id, worldName, false)}——<b>修复前</b>
 * 此处会退化到 {@link org.bukkit.Bukkit#getWorld(String)}（Bukkit API
 * 主线程专用，异步下 NPE / 读不一致）；<b>修复后</b>直接落 unknown-world 桶。</p>
 *
 * <p><b>测试策略</b>：
 * <ol>
 *   <li>通过反射直接向 {@code byId} 注入 RESERVED 条目，<b>不调 {@code initialize}</b>
 *       （initialize 需要 Bukkit.getMap，测试环境 Bukkit server 为 null 会 NPE）。</li>
 *   <li>{@code worldNameToUid} 保持空——模拟"本会话从未 offerFree 过该 world"。</li>
 *   <li>验证 {@code detectLeaks} 在无 Bukkit server 的纯 JVM 环境下不抛异常：
 *       因为修复后走 {@code allowBukkitFallback=false}，缓存 miss 直接进
 *       unknown-world 桶，不碰 {@link org.bukkit.Bukkit#getWorld}。</li>
 *   <li>验证泄漏 map 归还到 unknown-world 桶（{@link UUID#(long,long)} 零 UUID），
 *       通过 {@code stats()} 确认 FREE 计数增加。</li>
 * </ol>
 * </p>
 *
 * <p>对比：主线程路径 {@code releaseWall} 调 {@code offerFreeByName(..., true)}，
 * 在 Bukkit server 非 null 时走 Bukkit.getWorld 合法；本测试不验证该路径（需 MockBukkit），
 * 仅守护异步路径不调 Bukkit。</p>
 *
 * <p>Jdbi 用 SQLite in-file（tmpdir），满足 {@code persist()} 落盘不依赖 Bukkit。
 * 只建 {@code pool_maps} 一张表（audit_log 用 null jdbi 吞异常兜底）。</p>
 */
class MapPoolDetectLeaksThreadSafetyTest {

    @TempDir
    Path tmpDir;

    private HikariDataSource dataSource;
    private Jdbi jdbi;
    private MapPool pool;

    private static final Logger LOG = Logger.getLogger("test.mappool");

    @BeforeEach
    void setUp() {
        Path dbFile = tmpDir.resolve("pool-test.db");
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        cfg.setMaximumPoolSize(2);
        cfg.setPoolName("test-pool");
        cfg.addDataSourceProperty("journal_mode", "WAL");
        cfg.addDataSourceProperty("foreign_keys", "false");
        cfg.addDataSourceProperty("busy_timeout", "5000");
        dataSource = new HikariDataSource(cfg);
        jdbi = Jdbi.create(dataSource).installPlugin(new SQLitePlugin());

        // pool_maps 表（只建本测试需要的列；sign_id 可选所以省略不影响 persist SQL）
        jdbi.useHandle(h -> h.execute(
                "CREATE TABLE IF NOT EXISTS pool_maps ("
                        + "map_id INTEGER PRIMARY KEY, "
                        + "state TEXT NOT NULL, "
                        + "reserved_by TEXT, "
                        + "sign_id TEXT, "
                        + "world TEXT, "
                        + "created_at INTEGER NOT NULL, "
                        + "last_used_at INTEGER NOT NULL"
                        + ")"));

        // AuditLog 用 null jdbi：audit_log 表不存在，record() 失败吞日志不影响断言
        AuditLog auditLog = new AuditLog(null, LOG);

        // HikariCanvasRenderer 构造不需要 Bukkit（只是 extends MapRenderer，无 Bukkit 调用）
        HikariCanvasRenderer renderer = new HikariCanvasRenderer();

        pool = new MapPool(LOG, jdbi, auditLog, renderer, 5, 20);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null && !dataSource.isClosed()) dataSource.close();
    }

    // -----------------------------------------------------------------------
    // 主测试：detectLeaks 缓存 miss 不调 Bukkit（E1·P2-10 核心）
    // -----------------------------------------------------------------------

    /**
     * 核心回归：detectLeaks 在 worldNameToUid 缓存 miss 时不调 Bukkit.getWorld，
     * 泄漏 map 归入 unknown-world 桶，池 FREE 计数从 0 升到 1。
     */
    @Test
    void detectLeaks_cacheMiss_noNullPointerFromBukkit() throws Exception {
        // 注入一条 RESERVED 条目到 byId（模拟重启后加载的持久化池记录）
        int mapId = 42;
        String worldName = "world_nether"; // 该 world 未在 worldNameToUid 缓存里
        long now = System.currentTimeMillis();
        PooledMap reserved = new PooledMap(mapId, PoolState.RESERVED,
                "wall:w-deadbeef", worldName, now, now);

        injectByIdEntry(pool, mapId, reserved);
        // persist 到 DB（让 detectLeaks 内部的 persist 不 conflict）
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO pool_maps (map_id, state, reserved_by, world, created_at, last_used_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                mapId, "RESERVED", "wall:w-deadbeef", worldName, now, now));

        // 前置断言：缓存确实为空（worldNameToUid 没有 worldName 条目）
        assertEquals(0, worldNameToUidSize(pool),
                "worldNameToUid 应为空（未经过 offerFree 路径）");

        // stats 初始：0 free, 1 reserved
        MapPool.Stats before = pool.stats();
        assertEquals(0, before.free(), "初始 FREE 计数应为 0");
        assertEquals(1, before.reserved(), "初始 RESERVED 计数应为 1");

        // detectLeaks 调用：wallId "w-deadbeef" 不在 liveWallIds → 判定为泄漏
        // 修复前：offerFreeByName 缓存 miss → Bukkit.getWorld → NPE（无 Bukkit server）
        // 修复后：offerFreeByName(id, worldName, false) → 直接进 unknown-world 桶，不碰 Bukkit
        int leaked = assertDoesNotThrow(
                () -> pool.detectLeaks(Set.of() /* 无存活 wall */),
                "detectLeaks 缓存 miss 时不应抛异常（Bukkit.getWorld 已被禁止）");

        assertEquals(1, leaked, "应检测到 1 个泄漏 map");

        // 归还后 FREE+1，RESERVED-1
        MapPool.Stats after = pool.stats();
        assertEquals(1, after.free(), "泄漏 map 归还后 FREE 计数应为 1");
        assertEquals(0, after.reserved(), "泄漏 map 归还后 RESERVED 计数应为 0");

        // 验证 DB 持久化：map 状态已改为 FREE
        String stateInDb = jdbi.withHandle(h ->
                h.createQuery("SELECT state FROM pool_maps WHERE map_id = :id")
                        .bind("id", mapId)
                        .mapTo(String.class)
                        .one());
        assertEquals("FREE", stateInDb, "DB 中 map 状态应已持久化为 FREE");
    }

    /**
     * 缓存命中路径：worldNameToUid 已有条目时 detectLeaks 走 UUID 桶，
     * 不进入 Bukkit 分支，归还后进正确 UUID 桶（非 unknown-world 桶）。
     */
    @Test
    void detectLeaks_cacheHit_placesInCorrectUuidBucket() throws Exception {
        int mapId = 99;
        UUID worldUid = UUID.randomUUID();
        String worldName = "world_end";
        long now = System.currentTimeMillis();
        PooledMap reserved = new PooledMap(mapId, PoolState.RESERVED,
                "wall:w-cafebabe", worldName, now, now);

        injectByIdEntry(pool, mapId, reserved);
        // 手动填充缓存（模拟本会话之前曾 offerFree 过该 world）
        injectWorldNameToUid(pool, worldName, worldUid);

        jdbi.useHandle(h -> h.execute(
                "INSERT INTO pool_maps (map_id, state, reserved_by, world, created_at, last_used_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                mapId, "RESERVED", "wall:w-cafebabe", worldName, now, now));

        // 缓存已预热
        assertEquals(1, worldNameToUidSize(pool), "worldNameToUid 应有 1 个条目");

        int leaked = assertDoesNotThrow(
                () -> pool.detectLeaks(Set.of()),
                "缓存命中路径也不应抛异常");

        assertEquals(1, leaked);

        // 归还后 map 应在 worldUid 桶（非 unknown-world 桶 zero UUID）
        int unknownBucketSize = freeCountInBucket(pool, new UUID(0L, 0L));
        assertEquals(0, unknownBucketSize,
                "缓存命中时归还应进 worldUid 桶，not unknown-world 桶");

        int worldBucketSize = freeCountInBucket(pool, worldUid);
        assertEquals(1, worldBucketSize,
                "归还后 map 应在 worldUid 对应的 FREE 桶里");
    }

    /**
     * pending-wall 前缀豁免：detectLeaks 不回收 "wall:pending-xxx" 的 map，
     * 避免 confirm() reserve→bind 窗口期 race（P2-18 保护）。
     */
    @Test
    void detectLeaks_pendingWallOwner_isExempted() throws Exception {
        int mapId = 77;
        long now = System.currentTimeMillis();
        PooledMap pending = new PooledMap(mapId, PoolState.RESERVED,
                "wall:pending-" + UUID.randomUUID(), "world", now, now);

        injectByIdEntry(pool, mapId, pending);
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO pool_maps (map_id, state, reserved_by, world, created_at, last_used_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                mapId, "RESERVED", pending.reservedBy(), "world", now, now));

        // pending-* wallId 不在 liveWallIds，但按 P2-18 应跳过
        int leaked = pool.detectLeaks(Set.of());
        assertEquals(0, leaked, "pending- 前缀的 RESERVED map 不应被判为泄漏");

        MapPool.Stats s = pool.stats();
        assertEquals(1, s.reserved(), "pending map 应保持 RESERVED");
    }

    /**
     * 旧版残留（非 wall: 前缀）：detectLeaks 直接回收，不查 liveWallIds，
     * 且同样走 allowBukkitFallback=false（不调 Bukkit）。
     */
    @Test
    void detectLeaks_legacyOwner_reclaimedWithoutBukkit() throws Exception {
        int mapId = 55;
        long now = System.currentTimeMillis();
        PooledMap legacy = new PooledMap(mapId, PoolState.RESERVED,
                "session:old-legacy", "world", now, now);

        injectByIdEntry(pool, mapId, legacy);
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO pool_maps (map_id, state, reserved_by, world, created_at, last_used_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                mapId, "RESERVED", "session:old-legacy", "world", now, now));

        // 旧前缀直接回收，worldNameToUid 为空 → 应不抛 NPE
        int leaked = assertDoesNotThrow(
                () -> pool.detectLeaks(Set.of()),
                "旧版 owner 前缀的 map 回收不应调 Bukkit");
        assertEquals(1, leaked);
        assertEquals(1, pool.stats().free());
    }

    // -----------------------------------------------------------------------
    // 反射工具
    // -----------------------------------------------------------------------

    private static void injectByIdEntry(MapPool pool, int mapId, PooledMap entry)
            throws Exception {
        Field f = MapPool.class.getDeclaredField("byId");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, PooledMap> byId = (Map<Integer, PooledMap>) f.get(pool);
        byId.put(mapId, entry);
    }

    private static void injectWorldNameToUid(MapPool pool, String worldName, UUID uid)
            throws Exception {
        Field f = MapPool.class.getDeclaredField("worldNameToUid");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, UUID> cache = (Map<String, UUID>) f.get(pool);
        cache.put(worldName, uid);
    }

    private static int worldNameToUidSize(MapPool pool) throws Exception {
        Field f = MapPool.class.getDeclaredField("worldNameToUid");
        f.setAccessible(true);
        return ((Map<?, ?>) f.get(pool)).size();
    }

    private static int freeCountInBucket(MapPool pool, UUID worldUid) throws Exception {
        Field f = MapPool.class.getDeclaredField("freeByWorld");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, java.util.Deque<Integer>> freeByWorld =
                (Map<UUID, java.util.Deque<Integer>>) f.get(pool);
        java.util.Deque<Integer> bucket = freeByWorld.get(worldUid);
        return bucket == null ? 0 : bucket.size();
    }
}
