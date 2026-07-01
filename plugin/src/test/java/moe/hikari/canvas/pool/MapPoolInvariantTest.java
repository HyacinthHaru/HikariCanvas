package moe.hikari.canvas.pool;

import moe.hikari.canvas.render.HikariCanvasRenderer;
import moe.hikari.canvas.storage.AuditLog;
import moe.hikari.canvas.storage.Database;
import moe.hikari.canvas.storage.MigrationRunner;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.6：MapPool 核心不变式守卫。策略——真 SQLite（tmpdir Database + MigrationRunner）+
 * {@link FakeMapBackend}（不可用 MockBukkit）+ JDK Proxy 造 fake {@link World}，直驱真实
 * initialize / reserveForWall / bindToWall / releaseWall / releaseToFree。
 * 断言 {@link FakeMapBackend#createMapCalls}——"FREE 够就绝不 createMap"是不让 idcounts.dat
 * 膨胀的命根子。
 */
class MapPoolInvariantTest {

    private static final Logger LOG = Logger.getLogger("test.mappool.invariant");

    private Path tmpDir;
    private Database database;
    private AuditLog auditLog;
    private FakeMapBackend backend;
    private MapPool pool;
    private World worldA;
    private World worldB;

    private static World fakeWorld(String name, UUID uid) {
        return (World) Proxy.newProxyInstance(
                MapPoolInvariantTest.class.getClassLoader(),
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
        return new MapPool(LOG, database.jdbi(), auditLog, new HikariCanvasRenderer(), initial, max, backend);
    }

    @BeforeEach
    void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("hikari-mappool-inv-");
        database = new Database(LOG, tmpDir.resolve("data.db"));
        new MigrationRunner(database.jdbi(), LOG).run();
        auditLog = new AuditLog(database.jdbi(), LOG);
        backend = new FakeMapBackend();
        worldA = fakeWorld("world_a", new UUID(1, 1));
        worldB = fakeWorld("world_b", new UUID(2, 2));
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

    // ── 1. initialize 用 backend 铸满 initialSize ──
    @Test
    void initialize_createsInitialSize_viaBackend() {
        pool = newPool(5, 20);
        pool.initialize(worldA, Map.of());
        assertEquals(5, backend.createMapCalls, "initialize 应铸 initialSize 张");
        assertEquals(5, pool.stats().free(), "全部进 FREE");
        assertEquals(0, pool.stats().reserved());
    }

    // ── 2. 核心：reserve 时 FREE 够 → 0 次 createMap（复用）──
    @Test
    void reserve_reusesFree_withZeroCreateMap() {
        pool = newPool(5, 20);
        pool.initialize(worldA, Map.of());
        int afterInit = backend.createMapCalls; // 5
        List<Integer> ids = pool.reserveForWall("w1", 3, worldA);
        assertEquals(3, ids.size());
        assertEquals(afterInit, backend.createMapCalls, "FREE 够时必须 0 次新 createMap（复用）");
        assertEquals(2, pool.stats().free());
        assertEquals(3, pool.stats().reserved());
    }

    // ── 3. FREE 不够 → 只铸 shortfall 张 ──
    @Test
    void reserve_growsExactlyShortfall() {
        pool = newPool(2, 20);
        pool.initialize(worldA, Map.of());   // createMapCalls=2
        pool.reserveForWall("w1", 5, worldA); // 缺 3 → 再铸 3
        assertEquals(5, backend.createMapCalls, "initial 2 + shortfall 3 = 5");
        assertEquals(0, pool.stats().free());
        assertEquals(5, pool.stats().reserved());
    }

    // ── 4. 超 max → PoolExhaustedException ──
    @Test
    void reserve_beyondMax_throws() {
        pool = newPool(2, 3);
        pool.initialize(worldA, Map.of());
        assertThrows(PoolExhaustedException.class,
                () -> pool.reserveForWall("w1", 5, worldA));
        assertTrue(backend.createMapCalls <= 3, "不应铸超过 max 的 map");
    }

    // ── 5. releaseWall → 回 FREE → 下次 reserve 复用（0 新铸）──
    @Test
    void releaseWall_returnsToFree_thenReused() {
        pool = newPool(3, 20);
        pool.initialize(worldA, Map.of());
        pool.reserveForWall("w1", 3, worldA);
        int afterReserve = backend.createMapCalls; // 3
        pool.releaseWall("w1");
        assertEquals(3, pool.stats().free(), "release 后回 FREE");
        pool.reserveForWall("w2", 3, worldA);
        assertEquals(afterReserve, backend.createMapCalls, "复用回收的 map，不新铸");
    }

    // ── 6. releaseToFree 单张 ──
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

    // ── 7. bindToWall 跨世界拒绝（M16 P2.4）──
    @Test
    void bindToWall_crossWorld_throws() {
        pool = newPool(2, 20);
        pool.initialize(worldA, Map.of());              // 池里 2 张都在 worldA（FREE，在 byId）
        List<Integer> freeIds = pool.reserveForWall("tmp", 2, worldA);
        pool.releaseWall("tmp");                         // 变回 FREE，仍在 byId、world=worldA
        assertThrows(IllegalStateException.class,
                () -> pool.bindToWall("w1", freeIds, worldB),
                "map 在 worldA 却按 worldB 绑定 → 抛");
    }

    // ── 8. bindToWall FREE→RESERVED 正常 ──
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

    // ── 9. per-world 分桶：worldB reserve 不吃 worldA 的 FREE ──
    @Test
    void reserve_perWorldBucketing() {
        pool = newPool(3, 20);
        pool.initialize(worldA, Map.of());   // 3 张全在 worldA
        int afterInit = backend.createMapCalls; // 3
        pool.reserveForWall("wB", 2, worldB); // worldB 无 FREE → 新铸 2
        assertEquals(afterInit + 2, backend.createMapCalls, "worldB 另铸，不复用 worldA");
        // worldA 的 3 张 FREE 一张都不能被 worldB 的 reserve 消耗掉。
        assertEquals(3, pool.byWorldStats().getOrDefault("world_a", 0),
                "worldA 的 3 张 FREE 未被 worldB 消耗");
    }
}
