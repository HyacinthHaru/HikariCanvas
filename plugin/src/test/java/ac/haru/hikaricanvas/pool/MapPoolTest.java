package ac.haru.hikaricanvas.pool;

import ac.haru.hikaricanvas.render.HikariCanvasRenderer;
import ac.haru.hikaricanvas.storage.AuditLog;
import ac.haru.hikaricanvas.storage.Database;
import ac.haru.hikaricanvas.storage.MigrationRunner;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.World;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlite3.SQLitePlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MapPool} 核心不变式、泄漏回收防呆、异步线程安全与分世界统计单测。
 */
class MapPoolTest {

    private static final Logger LOG = Logger.getLogger("test.mappool");

    private static World fakeWorld(String name, UUID uid) {
        return (World) Proxy.newProxyInstance(
                MapPoolTest.class.getClassLoader(),
                new Class[]{World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getUID" -> uid;
                    case "hashCode" -> uid.hashCode();
                    case "equals" -> proxy == (args == null ? null : args[0]);
                    case "toString" -> "FakeWorld[" + name + "]";
                    default -> throw new UnsupportedOperationException("fakeWorld: " + method.getName());
                });
    }

    @Nested
    class CoreInvariants {

        @TempDir
        Path tmpDir;

        private Database database;
        private AuditLog auditLog;
        private FakeMapBackend backend;
        private MapPool pool;
        private World worldA;
        private World worldB;

        private MapPool newPool(int initial, int max) {
            return new MapPool(LOG, database.jdbi(), auditLog, new HikariCanvasRenderer(), initial, max, backend);
        }

        @BeforeEach
        void setUp() {
            database = new Database(LOG, tmpDir.resolve("data.db"));
            new MigrationRunner(database.jdbi(), LOG).run();
            auditLog = new AuditLog(database.jdbi(), LOG);
            backend = new FakeMapBackend();
            worldA = fakeWorld("world_a", new UUID(1, 1));
            worldB = fakeWorld("world_b", new UUID(2, 2));
        }

        @AfterEach
        void tearDown() {
            if (database != null) database.close();
        }

        @Test
        void initialize_createsInitialSize_viaBackend() {
            pool = newPool(5, 20);
            pool.initialize(worldA, Map.of());
            assertEquals(5, backend.createMapCalls, "initialize 应铸 initialSize 张");
            assertEquals(5, pool.stats().free(), "全部进 FREE");
            assertEquals(0, pool.stats().reserved());
        }

        @Test
        void reserve_reusesFree_withZeroCreateMap() {
            pool = newPool(5, 20);
            pool.initialize(worldA, Map.of());
            int afterInit = backend.createMapCalls;
            List<Integer> ids = pool.reserveForWall("w1", 3, worldA);
            assertEquals(3, ids.size());
            assertEquals(afterInit, backend.createMapCalls, "FREE 够时必须 0 次新 createMap（复用）");
            assertEquals(2, pool.stats().free());
            assertEquals(3, pool.stats().reserved());
        }

        @Test
        void reserve_growsExactlyShortfall() {
            pool = newPool(2, 20);
            pool.initialize(worldA, Map.of());
            pool.reserveForWall("w1", 5, worldA);
            assertEquals(5, backend.createMapCalls, "initial 2 + shortfall 3 = 5");
            assertEquals(0, pool.stats().free());
            assertEquals(5, pool.stats().reserved());
        }

        @Test
        void reserve_beyondMax_throws() {
            pool = newPool(2, 3);
            pool.initialize(worldA, Map.of());
            assertThrows(PoolExhaustedException.class,
                    () -> pool.reserveForWall("w1", 5, worldA));
            assertTrue(backend.createMapCalls <= 3, "不应铸超过 max 的 map");
        }

        @Test
        void releaseWall_returnsToFree_thenReused() {
            pool = newPool(3, 20);
            pool.initialize(worldA, Map.of());
            pool.reserveForWall("w1", 3, worldA);
            int afterReserve = backend.createMapCalls;
            pool.releaseWall("w1");
            assertEquals(3, pool.stats().free(), "release 后回 FREE");
            pool.reserveForWall("w2", 3, worldA);
            assertEquals(afterReserve, backend.createMapCalls, "复用回收的 map，不新铸");
        }

        @Test
        void releaseToFree_singleMap_reused() {
            pool = newPool(2, 20);
            pool.initialize(worldA, Map.of());
            List<Integer> ids = pool.reserveForWall("w1", 2, worldA);
            assertTrue(pool.releaseToFree(ids.get(0)), "释放已 RESERVED 的 map 返 true");
            assertEquals(1, pool.stats().free());
            int before = backend.createMapCalls;
            pool.reserveForWall("w2", 1, worldA);
            assertEquals(before, backend.createMapCalls, "复用刚 releaseToFree 的那张");
        }

        @Test
        void bindToWall_crossWorld_throws() {
            pool = newPool(2, 20);
            pool.initialize(worldA, Map.of());
            List<Integer> freeIds = pool.reserveForWall("tmp", 2, worldA);
            pool.releaseWall("tmp");
            assertThrows(IllegalStateException.class,
                    () -> pool.bindToWall("w1", freeIds, worldB),
                    "map 在 worldA 却按 worldB 绑定 → 抛");
        }

        @Test
        void bindToWall_freeToReserved_ok() {
            pool = newPool(2, 20);
            pool.initialize(worldA, Map.of());
            List<Integer> ids = pool.reserveForWall("tmp", 2, worldA);
            pool.releaseWall("tmp");
            boolean ok = pool.bindToWall("w1", ids, worldA);
            assertTrue(ok, "同世界 FREE map 绑定成功");
            assertEquals(0, pool.stats().free());
            assertEquals(2, pool.stats().reserved());
        }

        @Test
        void reserve_perWorldBucketing() {
            pool = newPool(3, 20);
            pool.initialize(worldA, Map.of());
            int afterInit = backend.createMapCalls;
            pool.reserveForWall("wB", 2, worldB);
            assertEquals(afterInit + 2, backend.createMapCalls, "worldB 另铸，不复用 worldA");
            assertEquals(3, pool.byWorldStats().getOrDefault("world_a", 0),
                    "worldA 的 3 张 FREE 未被 worldB 消耗");
        }

        @Test
        void initialize_recoversPersisted_withoutReminting() {
            long now = System.currentTimeMillis();
            int[] ids = {7001, 7002, 7003};
            for (int id : ids) {
                backend.preexisting(id, worldA);
                database.jdbi().useHandle(h -> h.execute(
                        "INSERT INTO pool_maps (map_id, state, reserved_by, world, created_at, last_used_at) "
                                + "VALUES (?, 'FREE', NULL, ?, ?, ?)",
                        id, "world_a", now, now));
            }
            pool = newPool(3, 20);
            pool.initialize(worldA, Map.of());
            assertEquals(0, backend.createMapCalls, "已恢复 3 张 FREE，重启不应重铸任何 map");
            assertEquals(3, pool.stats().free(), "3 张持久化 FREE 全部恢复");
            assertEquals(0, pool.stats().reserved());
        }
    }

    @Nested
    class LeakGuardsAndRecovery {

        @TempDir
        Path tmpDir;

        private Database database;
        private AuditLog auditLog;
        private FakeMapBackend backend;
        private World worldA;

        private MapPool newPool(int initial, int max) {
            return new MapPool(LOG, database.jdbi(), auditLog, new HikariCanvasRenderer(),
                    initial, max, backend);
        }

        @BeforeEach
        void setUp() {
            database = new Database(LOG, tmpDir.resolve("data.db"));
            new MigrationRunner(database.jdbi(), LOG).run();
            auditLog = new AuditLog(database.jdbi(), LOG);
            backend = new FakeMapBackend();
            worldA = fakeWorld("world_a", new UUID(1, 1));
        }

        @AfterEach
        void tearDown() {
            if (database != null) database.close();
        }

        @Test
        void detectLeaks_emptyLiveSetWithReservedMaps_refusesToRun() {
            MapPool pool = newPool(4, 20);
            pool.initialize(worldA, Map.of());
            List<Integer> reserved = pool.reserveForWall("w-alive", 3, worldA);
            assertEquals(3, reserved.size());
            assertEquals(3, pool.stats().reserved());

            int freed = pool.detectLeaks(Set.of());

            assertEquals(0, freed, "live 集为空 + 有 RESERVED → 必须拒绝执行，不能释放任何地图");
            assertEquals(3, pool.stats().reserved(), "在用地图必须原封不动");
        }

        @Test
        void detectLeaks_emptyLiveSetWithNoReservedMaps_isFine() {
            MapPool pool = newPool(3, 20);
            pool.initialize(worldA, Map.of());
            assertEquals(0, pool.stats().reserved());

            assertEquals(0, pool.detectLeaks(Set.of()), "无 RESERVED 时空集合不触发任何释放");
        }

        @Test
        void detectLeaks_nonEmptyLiveSet_stillReclaimsRealLeaks() {
            MapPool pool = newPool(6, 20);
            pool.initialize(worldA, Map.of());
            pool.reserveForWall("w-alive", 2, worldA);
            pool.reserveForWall("w-gone", 2, worldA);
            assertEquals(4, pool.stats().reserved());

            int freed = pool.detectLeaks(Set.of("w-alive"));

            assertEquals(2, freed, "w-gone 的 2 张应被回收");
            assertEquals(2, pool.stats().reserved(), "w-alive 的 2 张保留");
        }

        @Test
        void detectLeaks_mapsReservedAfterSnapshot_areNotReclaimed() {
            MapPool pool = newPool(4, 20);
            pool.initialize(worldA, Map.of());

            long snapshotAt = System.currentTimeMillis();
            pool.reserveForWall("w-brand-new", 2, worldA);

            int freed = pool.detectLeaks(Set.of("w-existing"), snapshotAt);

            assertEquals(0, freed, "快照之后才借出的地图不能据旧快照判泄漏");
            assertEquals(2, pool.stats().reserved(), "刚借出的地图必须原封不动");
        }

        @Test
        void detectLeaks_mapsReservedBeforeSnapshot_stillReclaimed() {
            MapPool pool = newPool(4, 20);
            pool.initialize(worldA, Map.of());
            pool.reserveForWall("w-gone", 2, worldA);

            long snapshotAt = System.currentTimeMillis() + 1000;

            assertEquals(2, pool.detectLeaks(Set.of("w-alive"), snapshotAt),
                    "借出早于快照 + wall 已不在表里 → 真泄漏，必须回收");
            assertEquals(0, pool.stats().reserved());
        }

        @Test
        void detectLeaks_pendingReservation_exemptWithinTtlReclaimedAfter() {
            MapPool pool = newPool(4, 20);
            pool.initialize(worldA, Map.of());
            pool.reserveForWall(MapPool.PENDING_WALL_PREFIX + UUID.randomUUID(), 2, worldA);
            long now = System.currentTimeMillis();

            assertEquals(0, pool.detectLeaks(Set.of("w-alive"), now, now),
                    "窗口内的 pending 预留是正常中间态，不能碰");
            assertEquals(2, pool.stats().reserved());

            int freed = pool.detectLeaks(Set.of("w-alive"), now,
                    now + MapPool.PENDING_RESERVE_TTL_MS + 1);

            assertEquals(2, freed, "超期的 pending 预留必须回收");
            assertEquals(0, pool.stats().reserved());
        }

        @Test
        void initialize_unfinishedWallCreation_returnsMapsToFreePool() {
            MapPool first = newPool(3, 20);
            first.initialize(worldA, Map.of());
            List<Integer> ids = first.reserveForWall(
                    MapPool.PENDING_WALL_PREFIX + UUID.randomUUID(), 2, worldA);
            assertEquals(2, ids.size());

            int mintedBeforeRestart = backend.createMapCalls;
            MapPool second = newPool(1, 20);
            second.initialize(worldA, Map.of());

            assertEquals(0, second.stats().reserved(),
                    "被中断的建墙不能继续占着地图");
            assertEquals(3, second.stats().free(), "三张地图应全部回到 FREE 池");
            for (int id : ids) {
                assertEquals("FREE", stateOf(id), "DB 里也要落成 FREE");
            }
            assertEquals(mintedBeforeRestart, backend.createMapCalls,
                    "第二次启动不该为此再铸新地图");
        }

        @Test
        void initialize_mapViewPresentButWorldUnloaded_keepsRow() {
            MapPool first = newPool(3, 20);
            first.initialize(worldA, Map.of());
            List<Integer> ids = first.reserveForWall("w-1", 2, worldA);
            assertEquals(2, ids.size());
            assertEquals(2, rowsFor(ids));

            FakeMapBackend restarted = new FakeMapBackend();
            for (int id : ids) restarted.preexistingWithUnloadedWorld(id);
            MapPool second = new MapPool(LOG, database.jdbi(), auditLog, new HikariCanvasRenderer(),
                    1, 20, restarted);
            second.initialize(worldA, Map.of());

            assertEquals(2, rowsFor(ids),
                    "MapView 在、只是 world 未加载 → pool_maps 行不能被 DELETE");
            assertEquals(2, second.stats().reserved(),
                    "两张 RESERVED 必须登记回 byId");
        }

        @Test
        void initialize_mapViewGone_stillDeletesOrphanRow() {
            MapPool first = newPool(3, 20);
            first.initialize(worldA, Map.of());
            List<Integer> originalIds = allPooledIds();
            assertEquals(3, originalIds.size());

            FakeMapBackend restarted = new FakeMapBackend();
            restarted.seedNextId(90_000);
            MapPool second = new MapPool(LOG, database.jdbi(), auditLog, new HikariCanvasRenderer(),
                    1, 20, restarted);
            second.initialize(worldA, Map.of());

            assertEquals(0, rowsFor(originalIds), "MapView 真的没了 → 孤儿行仍应删除");
        }

        private int rowsFor(List<Integer> ids) {
            int n = 0;
            for (int id : ids) {
                n += database.jdbi().withHandle(h -> h
                        .createQuery("SELECT COUNT(*) FROM pool_maps WHERE map_id = :id")
                        .bind("id", id).mapTo(Integer.class).one());
            }
            return n;
        }

        private String stateOf(int id) {
            return database.jdbi().withHandle(h -> h
                    .createQuery("SELECT state FROM pool_maps WHERE map_id = :id")
                    .bind("id", id).mapTo(String.class).one());
        }

        private List<Integer> allPooledIds() {
            return database.jdbi().withHandle(h ->
                    h.createQuery("SELECT map_id FROM pool_maps ORDER BY map_id")
                            .mapTo(Integer.class).list());
        }
    }

    @Nested
    class AsyncDetectLeaksThreadSafety {

        @TempDir
        Path tmpDir;

        private HikariDataSource dataSource;
        private Jdbi jdbi;
        private MapPool pool;

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

            AuditLog auditLog = new AuditLog(null, LOG);
            HikariCanvasRenderer renderer = new HikariCanvasRenderer();
            pool = new MapPool(LOG, jdbi, auditLog, renderer, 5, 20);
        }

        @AfterEach
        void tearDown() {
            if (dataSource != null && !dataSource.isClosed()) dataSource.close();
        }

        @Test
        void detectLeaks_cacheMiss_noNullPointerFromBukkit() throws Exception {
            int mapId = 42;
            String worldName = "world_nether";
            long now = System.currentTimeMillis();
            PooledMap reserved = new PooledMap(mapId, PoolState.RESERVED,
                    "wall:w-deadbeef", worldName, now, now);

            injectByIdEntry(pool, mapId, reserved);
            jdbi.useHandle(h -> h.execute(
                    "INSERT INTO pool_maps (map_id, state, reserved_by, world, created_at, last_used_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    mapId, "RESERVED", "wall:w-deadbeef", worldName, now, now));

            assertEquals(0, worldNameToUidSize(pool),
                    "worldNameToUid 应为空（未经过 offerFree 路径）");

            MapPool.Stats before = pool.stats();
            assertEquals(0, before.free(), "初始 FREE 计数应为 0");
            assertEquals(1, before.reserved(), "初始 RESERVED 计数应为 1");

            int leaked = assertDoesNotThrow(
                    () -> pool.detectLeaks(Set.of("w-some-other-live-wall")),
                    "detectLeaks 缓存 miss 时不应调用 Bukkit.getWorld 抛异常");

            assertEquals(1, leaked, "应检测到 1 个泄漏 map");

            MapPool.Stats after = pool.stats();
            assertEquals(1, after.free(), "泄漏 map 归还后 FREE 计数应为 1");
            assertEquals(0, after.reserved(), "泄漏 map 归还后 RESERVED 计数应为 0");

            String stateInDb = jdbi.withHandle(h ->
                    h.createQuery("SELECT state FROM pool_maps WHERE map_id = :id")
                            .bind("id", mapId)
                            .mapTo(String.class)
                            .one());
            assertEquals("FREE", stateInDb, "DB 中 map 状态应已持久化为 FREE");
        }

        @Test
        void detectLeaks_cacheHit_placesInCorrectUuidBucket() throws Exception {
            int mapId = 99;
            UUID worldUid = UUID.randomUUID();
            String worldName = "world_end";
            long now = System.currentTimeMillis();
            PooledMap reserved = new PooledMap(mapId, PoolState.RESERVED,
                    "wall:w-cafebabe", worldName, now, now);

            injectByIdEntry(pool, mapId, reserved);
            injectWorldNameToUid(pool, worldName, worldUid);

            jdbi.useHandle(h -> h.execute(
                    "INSERT INTO pool_maps (map_id, state, reserved_by, world, created_at, last_used_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    mapId, "RESERVED", "wall:w-cafebabe", worldName, now, now));

            assertEquals(1, worldNameToUidSize(pool), "worldNameToUid 应有 1 个条目");

            int leaked = assertDoesNotThrow(
                    () -> pool.detectLeaks(Set.of("w-some-other-live-wall")),
                    "缓存命中路径不应抛异常");

            assertEquals(1, leaked);
            assertEquals(0, freeCountInBucket(pool, new UUID(0L, 0L)),
                    "缓存命中时归还应进 worldUid 桶，而非 unknown-world 桶");
            assertEquals(1, freeCountInBucket(pool, worldUid),
                    "归还后 map 应在 worldUid 对应的 FREE 桶里");
        }

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

            int leaked = pool.detectLeaks(Set.of());
            assertEquals(0, leaked, "pending- 前缀的 RESERVED map 不应被判为泄漏");
            assertEquals(1, pool.stats().reserved(), "pending map 应保持 RESERVED");
        }

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

            int leaked = assertDoesNotThrow(
                    () -> pool.detectLeaks(Set.of()),
                    "非 wall: 前缀的 map 回收不应调 Bukkit");
            assertEquals(1, leaked);
            assertEquals(1, pool.stats().free());
        }
    }

    @Nested
    class ObservabilityStats {

        private MapPool newMemoryPool(int initial, int max) {
            return new MapPool(LOG, null, new AuditLog(null, LOG), new HikariCanvasRenderer(), initial, max);
        }

        @Test
        void byWorldStats_emptyPool_returnsEmptyMap() {
            MapPool pool = newMemoryPool(5, 20);
            assertTrue(pool.byWorldStats().isEmpty(), "空池 byWorldStats 应为空");
            MapPool.Stats s = pool.stats();
            assertEquals(0, s.total());
            assertEquals(0, s.free());
            assertEquals(0, s.reserved());
        }

        @Test
        void byWorldStats_multiWorld_countsFreePerWorld() throws Exception {
            MapPool pool = newMemoryPool(5, 50);
            UUID overUid = UUID.randomUUID();
            UUID netherUid = UUID.randomUUID();

            injectFree(pool, overUid, "world", 10, 11, 12);
            injectFree(pool, netherUid, "world_nether", 20);

            Map<String, Integer> byWorld = pool.byWorldStats();
            assertEquals(2, byWorld.size(), "应有两个 world 桶");
            assertEquals(3, byWorld.get("world").intValue());
            assertEquals(1, byWorld.get("world_nether").intValue());

            MapPool.Stats s = pool.stats();
            assertEquals(4, s.total());
            assertEquals(4, s.free());
            assertEquals(0, s.reserved());
            assertEquals(s.free(), byWorld.values().stream().mapToInt(Integer::intValue).sum(),
                    "byWorldStats FREE 之和应等于 stats().free()");
        }

        @Test
        void byWorldStats_unknownWorldBucket_labelledAngleUnknown() throws Exception {
            MapPool pool = newMemoryPool(5, 50);
            injectFree(pool, new UUID(0L, 0L), "world_phantom", 30, 31);

            Map<String, Integer> byWorld = pool.byWorldStats();
            assertEquals(1, byWorld.size());
            assertEquals(2, byWorld.get("<unknown>").intValue(),
                    "zero-UUID 桶应固定标 <unknown>");
        }

        @Test
        void byWorldStats_emptyBucketSkipped() throws Exception {
            MapPool pool = newMemoryPool(5, 50);
            injectEmptyBucket(pool, UUID.randomUUID());
            assertTrue(pool.byWorldStats().isEmpty(), "空桶应被 byWorldStats 跳过");
        }

        @Test
        void byWorldStats_reservedNotCounted() throws Exception {
            MapPool pool = newMemoryPool(5, 50);
            injectFree(pool, UUID.randomUUID(), "world", 40, 41);
            injectByIdEntry(pool, 99, new PooledMap(99, PoolState.RESERVED,
                    "wall:w-deadbeef", "world", 0L, 0L));

            Map<String, Integer> byWorld = pool.byWorldStats();
            assertEquals(2, byWorld.get("world").intValue(),
                    "RESERVED map 不计入 FREE 分世界统计");
            MapPool.Stats s = pool.stats();
            assertEquals(3, s.total());
            assertEquals(2, s.free());
            assertEquals(1, s.reserved());
        }
    }

    // ----------------------------------------------------------------------
    // Reflection helpers for memory-state injection
    // ----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void injectByIdEntry(MapPool pool, int mapId, PooledMap entry) throws Exception {
        Field f = MapPool.class.getDeclaredField("byId");
        f.setAccessible(true);
        Map<Integer, PooledMap> byId = (Map<Integer, PooledMap>) f.get(pool);
        byId.put(mapId, entry);
    }

    @SuppressWarnings("unchecked")
    private static void injectWorldNameToUid(MapPool pool, String worldName, UUID uid) throws Exception {
        Field f = MapPool.class.getDeclaredField("worldNameToUid");
        f.setAccessible(true);
        Map<String, UUID> cache = (Map<String, UUID>) f.get(pool);
        cache.put(worldName, uid);
    }

    private static int worldNameToUidSize(MapPool pool) throws Exception {
        Field f = MapPool.class.getDeclaredField("worldNameToUid");
        f.setAccessible(true);
        return ((Map<?, ?>) f.get(pool)).size();
    }

    @SuppressWarnings("unchecked")
    private static int freeCountInBucket(MapPool pool, UUID worldUid) throws Exception {
        Field f = MapPool.class.getDeclaredField("freeByWorld");
        f.setAccessible(true);
        Map<UUID, Deque<Integer>> freeByWorld = (Map<UUID, Deque<Integer>>) f.get(pool);
        Deque<Integer> bucket = freeByWorld.get(worldUid);
        return bucket == null ? 0 : bucket.size();
    }

    @SuppressWarnings("unchecked")
    private static void injectFree(MapPool pool, UUID worldUid, String worldName, int... mapIds)
            throws Exception {
        Field byIdF = MapPool.class.getDeclaredField("byId");
        byIdF.setAccessible(true);
        Map<Integer, PooledMap> byId = (Map<Integer, PooledMap>) byIdF.get(pool);

        Field freeF = MapPool.class.getDeclaredField("freeByWorld");
        freeF.setAccessible(true);
        Map<UUID, Deque<Integer>> freeByWorld = (Map<UUID, Deque<Integer>>) freeF.get(pool);

        Deque<Integer> q = freeByWorld.computeIfAbsent(worldUid, k -> new ArrayDeque<>());
        for (int id : mapIds) {
            byId.put(id, new PooledMap(id, PoolState.FREE, null, worldName, 0L, 0L));
            q.offer(id);
        }
    }

    @SuppressWarnings("unchecked")
    private static void injectEmptyBucket(MapPool pool, UUID worldUid) throws Exception {
        Field freeF = MapPool.class.getDeclaredField("freeByWorld");
        freeF.setAccessible(true);
        Map<UUID, Deque<Integer>> freeByWorld = (Map<UUID, Deque<Integer>>) freeF.get(pool);
        freeByWorld.put(worldUid, new ArrayDeque<>());
    }
}
