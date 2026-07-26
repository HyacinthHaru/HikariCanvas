package ac.haru.hikaricanvas.pool;

import ac.haru.hikaricanvas.render.HikariCanvasRenderer;
import ac.haru.hikaricanvas.storage.AuditLog;
import ac.haru.hikaricanvas.storage.Database;
import ac.haru.hikaricanvas.storage.MigrationRunner;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.17：MapPool 的两条防呆守卫（必修 Bug #3 / #5）。
 *
 * <p>装配照 {@link MapPoolInvariantTest}：真 SQLite + {@link FakeMapBackend} + JDK Proxy
 * 假 World，直驱真实 {@code initialize} / {@code detectLeaks}。</p>
 */
class MapPoolLeakGuardTest {

    private static final Logger LOG = Logger.getLogger(MapPoolLeakGuardTest.class.getName());

    private Path tmpDir;
    private Database database;
    private AuditLog auditLog;
    private FakeMapBackend backend;
    private World worldA;

    private static World fakeWorld(String name, UUID uid) {
        return (World) Proxy.newProxyInstance(
                MapPoolLeakGuardTest.class.getClassLoader(),
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

    private MapPool newPool(int initial, int max) {
        return new MapPool(LOG, database.jdbi(), auditLog, new HikariCanvasRenderer(),
                initial, max, backend);
    }

    @BeforeEach
    void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("hikari-mappool-leakguard-");
        database = new Database(LOG, tmpDir.resolve("data.db"));
        new MigrationRunner(database.jdbi(), LOG).run();
        auditLog = new AuditLog(database.jdbi(), LOG);
        backend = new FakeMapBackend();
        worldA = fakeWorld("world_a", new UUID(1, 1));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (database != null) database.close();
        if (tmpDir != null) {
            try (var s = Files.walk(tmpDir)) {
                s.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            }
        }
    }

    // ---------- #3：detectLeaks 空 live 集防呆 ----------

    /**
     * 本批最重要的一条守卫。`WallRepo.loadAll()` 原先对任意异常返回空表（单行
     * project_json 损坏或 facing 非法即可触发），周期任务把它当 live wall <b>全集</b>
     * 传进来 → 全服在用地图被判泄漏、强制 FREE 并落盘 → 被新墙复用后跨墙串台。
     */
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

    /** 真的一面墙都没有（池里也没 RESERVED）→ 空集合合法，不误报也不炸。 */
    @Test
    void detectLeaks_emptyLiveSetWithNoReservedMaps_isFine() {
        MapPool pool = newPool(3, 20);
        pool.initialize(worldA, Map.of());
        assertEquals(0, pool.stats().reserved());

        assertEquals(0, pool.detectLeaks(Set.of()), "无 RESERVED 时空集合不触发任何释放");
    }

    /** 对照组：live 集非空且确实缺了某 wall → 正常回收，防呆不能把真泄漏也挡掉。 */
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

    // ---------- #51：walls 表快照与检测之间的竞态 ----------

    /**
     * 快照是在池锁外拍的：`/canvas confirm` 走 reserve(pending) → INSERT walls → bind(真 id)，
     * 只要快照拍在 INSERT 之前、检测跑在 bind 之后，新墙的 id 就既不在快照里、owner 也已不是
     * pending-*，于是刚借出去的地图被判泄漏、强制归还。下一次 confirm 把同一张地图借给别的墙，
     * 两面墙共用一张图互相覆盖像素，原墙的 map_ids 永远指向被抢走的地图。
     */
    @Test
    void detectLeaks_mapsReservedAfterSnapshot_areNotReclaimed() {
        MapPool pool = newPool(4, 20);
        pool.initialize(worldA, Map.of());

        long snapshotAt = System.currentTimeMillis();      // 先拍快照
        pool.reserveForWall("w-brand-new", 2, worldA);     // 之后才借出（快照里当然没有它）

        int freed = pool.detectLeaks(Set.of("w-existing"), snapshotAt);

        assertEquals(0, freed, "快照之后才借出的地图不能据旧快照判泄漏");
        assertEquals(2, pool.stats().reserved(), "刚借出的地图必须原封不动");
    }

    /** 对照组：借出发生在快照之前 + wall 确实不在快照里 → 是真泄漏，照常回收。 */
    @Test
    void detectLeaks_mapsReservedBeforeSnapshot_stillReclaimed() {
        MapPool pool = newPool(4, 20);
        pool.initialize(worldA, Map.of());
        pool.reserveForWall("w-gone", 2, worldA);

        long snapshotAt = System.currentTimeMillis() + 1000;   // 快照晚于借出

        assertEquals(2, pool.detectLeaks(Set.of("w-alive"), snapshotAt),
                "借出早于快照 + wall 已不在表里 → 真泄漏，必须回收");
        assertEquals(0, pool.stats().reserved());
    }

    // ---------- #79：pending 豁免要有期限 ----------

    /**
     * confirm 的 reserve→bind 窗口只有几毫秒。进程在窗口里被 kill -9，这批 owner 为
     * {@code wall:pending-*} 的行就永远留在库里：没有对应 walls 行，泄漏检测又无条件豁免
     * pending 前缀 —— 没有任何路径能回收，地图 ID 永久泄漏。
     */
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

        assertEquals(2, freed, "超期的 pending 预留 = 上次建墙被中断的残留，必须回收");
        assertEquals(0, pool.stats().reserved());
    }

    /**
     * 启动期收敛：pending 预留是 confirm 内部的中间态，活不过一次重启。
     * 能从库里读到它，只说明上次 confirm 被硬中断了。
     */
    @Test
    void initialize_unfinishedWallCreation_returnsMapsToFreePool() {
        MapPool first = newPool(3, 20);
        first.initialize(worldA, Map.of());
        List<Integer> ids = first.reserveForWall(
                MapPool.PENDING_WALL_PREFIX + UUID.randomUUID(), 2, worldA);
        assertEquals(2, ids.size());

        // 模拟 kill -9 后重启：同一份 DB、同一批 MapView，什么收尾都没做过
        int mintedBeforeRestart = backend.createMapCalls;
        MapPool second = newPool(1, 20);
        second.initialize(worldA, Map.of());

        assertEquals(0, second.stats().reserved(),
                "被中断的建墙不能继续占着地图（没人认得它，也没人会来回收）");
        assertEquals(3, second.stats().free(), "三张地图应全部回到 FREE 池");
        for (int id : ids) {
            assertEquals("FREE", stateOf(id), "DB 里也要落成 FREE");
        }
        assertEquals(mintedBeforeRestart, backend.createMapCalls,
                "第二次启动不该为此再铸新地图（那正是 idcounts.dat 膨胀的来源）");
    }

    // ---------- #5：世界未加载 ≠ MapView 丢失 ----------

    /**
     * {@code installRenderer} 返 null 有两种含义。原实现一律 DELETE pool 行，于是由
     * Multiverse 等插件管理、onEnable 时尚未加载的世界，其池地图全部被永久删除
     * （mapId 从簿记消失 → 不再复用 → 重新 createMap → idcounts.dat 膨胀）。
     */
    @Test
    void initialize_mapViewPresentButWorldUnloaded_keepsRow() {
        // 先建一个池并预留，产生 pool_maps 行
        MapPool first = newPool(3, 20);
        first.initialize(worldA, Map.of());
        List<Integer> ids = first.reserveForWall("w-1", 2, worldA);
        assertEquals(2, ids.size());
        assertEquals(2, rowsFor(ids));

        // 模拟重启：MapView 都还在，但 world 尚未加载（MapView.getWorld() 返 null）
        FakeMapBackend restarted = new FakeMapBackend();
        for (int id : ids) restarted.preexistingWithUnloadedWorld(id);
        MapPool second = new MapPool(LOG, database.jdbi(), auditLog, new HikariCanvasRenderer(),
                1, 20, restarted);
        second.initialize(worldA, Map.of());

        assertEquals(2, rowsFor(ids),
                "MapView 在、只是 world 未加载 → 这两张的 pool_maps 行不能被 DELETE"
                        + "（删了 mapId 就从簿记永久消失 → 重新 createMap → idcounts.dat 膨胀）");
        assertEquals(2, second.stats().reserved(),
                "两张 RESERVED 必须登记回 byId，否则该世界的 wall 永久打不开");
    }

    /** 对照组：MapView 真的不存在 → 仍按孤儿删除（防呆不能让真孤儿留下）。 */
    @Test
    void initialize_mapViewGone_stillDeletesOrphanRow() {
        MapPool first = newPool(3, 20);
        first.initialize(worldA, Map.of());
        List<Integer> originalIds = allPooledIds();
        assertEquals(3, originalIds.size());

        // 重启：一张 MapView 都不存在。把新 backend 的号段推开，避免补齐时铸出的新 id
        // 撞上原 id 让"原行是否被删"的断言失真。
        FakeMapBackend restarted = new FakeMapBackend();
        restarted.seedNextId(90_000);
        MapPool second = new MapPool(LOG, database.jdbi(), auditLog, new HikariCanvasRenderer(),
                1, 20, restarted);
        second.initialize(worldA, Map.of());

        assertEquals(0, rowsFor(originalIds), "MapView 真的没了 → 孤儿行仍应删除");
    }

    /** 指定 mapId 集合在 pool_maps 里还剩几行。 */
    private int rowsFor(List<Integer> ids) {
        int n = 0;
        for (int id : ids) {
            n += database.jdbi().withHandle(h -> h
                    .createQuery("SELECT COUNT(*) FROM pool_maps WHERE map_id = :id")
                    .bind("id", id).mapTo(Integer.class).one());
        }
        return n;
    }

    /** 某 mapId 在 pool_maps 里落的 state。 */
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
