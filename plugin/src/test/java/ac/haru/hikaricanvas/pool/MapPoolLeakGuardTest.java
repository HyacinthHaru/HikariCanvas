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

    private List<Integer> allPooledIds() {
        return database.jdbi().withHandle(h ->
                h.createQuery("SELECT map_id FROM pool_maps ORDER BY map_id")
                        .mapTo(Integer.class).list());
    }
}
