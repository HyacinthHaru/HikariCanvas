package ac.haru.hikaricanvas.render;

import ac.haru.hikaricanvas.pool.MapBackend;
import ac.haru.hikaricanvas.pool.MapPool;
import ac.haru.hikaricanvas.session.WallKey;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.storage.AuditLog;
import ac.haru.hikaricanvas.storage.Database;
import ac.haru.hikaricanvas.storage.MigrationRunner;
import ac.haru.hikaricanvas.storage.WallRepo;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.map.MapRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.6：{@link WallRestorer} 启动期恢复守卫。核心是 <b>M16 P2.5 命根子</b>——某 wall
 * bind 到 {@link MapPool} 成功、但后续渲染（{@code renderer.update}）抛异常时，restoreOne
 * 的 catch 必须把这一轮已 bind 的 mapId 全部 {@link MapPool#releaseToFree} 回 FREE，否则它们
 * 卡在 RESERVED（owner=wall:&lt;id&gt;）而 wall 又没真正恢复 → 软泄漏 → idcounts.dat 膨胀。
 *
 * <p><b>测试策略</b>（照 {@code MapPoolInvariantTest} 范式）：真 SQLite（tmpdir Database +
 * MigrationRunner）+ {@link FakeMapBackendForRender}（不可用 MockBukkit）+ JDK Proxy 造 fake
 * {@link World} + 真 {@link WallRepo} seed 一面 pristine 白墙 + 真 {@link MapPool}。用
 * {@link WallRestorer} 的 {@code worldResolver} seam 注入 fake world 解析。case 2 用
 * {@link ThrowingRenderer} 子类 override {@code update} 抛异常，驱动失败回滚路径。</p>
 *
 * <p>断言直读 {@link MapPool#stats()} 的 free/reserved——最终不泄漏的唯一权威。</p>
 */
class WallRestorerTest {

    private static final Logger LOG = Logger.getLogger("test.wallrestorer");
    private static final String WORLD_NAME = "world_a";

    private Path tmpDir;
    private Database database;
    private AuditLog auditLog;
    private FakeMapBackend backend;
    private MapPool pool;
    private WallRepo wallRepo;
    private CanvasCompositor compositor;
    private PlaceholderRenderer placeholder;
    private World worldA;

    /** JDK Proxy fake World：只实现 getName / getUID / equals / hashCode / toString。 */
    private static World fakeWorld(String name, UUID uid) {
        return (World) Proxy.newProxyInstance(
                WallRestorerTest.class.getClassLoader(),
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

    @BeforeEach
    void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("hikari-wallrestorer-");
        database = new Database(LOG, tmpDir.resolve("data.db"));
        new MigrationRunner(database.jdbi(), LOG).run();
        auditLog = new AuditLog(database.jdbi(), LOG);
        backend = new FakeMapBackend();
        worldA = fakeWorld(WORLD_NAME, new UUID(1, 1));

        wallRepo = new WallRepo(LOG, database.jdbi());

        PaletteLut paletteLut = PaletteLut.loadFromClasspath("/palette.json");
        FontRegistry fontRegistry = new FontRegistry(LOG);
        fontRegistry.loadBuiltIn();
        compositor = new CanvasCompositor(paletteLut, fontRegistry, LOG);
        placeholder = new PlaceholderRenderer();
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

    // ──────────────────────────────────────────────────────────
    //  helpers
    // ──────────────────────────────────────────────────────────

    private MapPool newPool(int initial, int max) {
        return new MapPool(LOG, database.jdbi(), auditLog,
                new HikariCanvasRenderer(), initial, max, backend);
    }

    /**
     * 用真 MapPool 铸出 {@code count} 张 FREE map（走 FakeMapBackend，0 次真 Bukkit.createMap），
     * 拿到它们的 id，然后 seed 一面 pristine 白墙引用这些 id。返回 (wallId, mapIds)。
     *
     * <p>拿 FREE id 的技法：reserveForWall 借出 → releaseWall 归还，maps 变回 FREE 但 id 已知、
     * 仍在 byId、world=worldA（FakeMapBackend 认得）。这样 WallRestorer.bindToWall 能成功。</p>
     */
    private SeededWall seedPristineWall(int count) {
        pool = newPool(count, count + 8);
        pool.initialize(worldA, Map.of());            // 铸 count 张 FREE（FakeMapBackend）
        List<Integer> ids = pool.reserveForWall("tmp", count, worldA);
        pool.releaseWall("tmp");                       // 变回 FREE，id 已知，仍在 byId
        assertEquals(count, pool.stats().free(), "seed 前提：count 张全 FREE");
        assertEquals(0, pool.stats().reserved());

        UUID owner = UUID.randomUUID();
        String wallId = wallRepo.createWithMapIds(
                new WallKey(WORLD_NAME, 0, 64, 0, BlockFace.NORTH),
                new ProjectState(count, 1),            // pristine 白底、无元素 → placeholder 路径
                ids, count, 1, owner, "tester");
        return new SeededWall(wallId, ids);
    }

    private WallRestorer restorer(HikariCanvasRenderer renderer, Function<String, World> worldResolver) {
        return new WallRestorer(LOG, wallRepo, pool, renderer, compositor, placeholder, worldResolver);
    }

    private record SeededWall(String wallId, List<Integer> mapIds) {}

    // ──────────────────────────────────────────────────────────
    //  ① 成功：pristine 墙 → bind + placeholder 渲染 → maps RESERVED
    // ──────────────────────────────────────────────────────────

    @Test
    void restore_pristineWall_success() {
        SeededWall sw = seedPristineWall(3);
        int mapCount = sw.mapIds().size();

        WallRestorer restorer = restorer(new HikariCanvasRenderer(), name -> worldA);
        int restored = restorer.restore();

        assertEquals(1, restored, "pristine 墙应恢复成功");
        assertEquals(mapCount, pool.stats().reserved(), "全部 map bind 到 wall（RESERVED）");
        assertEquals(0, pool.stats().free(), "无残留 FREE");
        assertFalse(restorer.isRestorationFailed(sw.wallId()), "成功不进 failed 集合");
        assertTrue(restorer.failedRestoreWallIds().isEmpty());
    }

    // ──────────────────────────────────────────────────────────
    //  ② 命根子：bind 后渲染抛异常 → releaseToFree 全回滚，0 泄漏
    // ──────────────────────────────────────────────────────────

    @Test
    void restore_failureAfterBind_releasesAllMapsToFree() {
        SeededWall sw = seedPristineWall(3);
        int mapCount = sw.mapIds().size();

        // ThrowingRenderer.update 抛 → restoreOne bind 成功后 renderer.update 炸 → catch → releaseToFree
        WallRestorer restorer = restorer(new ThrowingRenderer(), name -> worldA);
        int restored = restorer.restore();

        assertEquals(0, restored, "渲染失败 → 该墙不算恢复");
        assertEquals(mapCount, pool.stats().free(),
                "命根子：bind 过的 map 全 releaseToFree 回 FREE，无泄漏");
        assertEquals(0, pool.stats().reserved(),
                "命根子：不能有 map 卡在 RESERVED（软泄漏 = idcounts.dat 膨胀）");
        assertTrue(restorer.isRestorationFailed(sw.wallId()),
                "失败墙进 failedRestoreWallIds");
    }

    // ──────────────────────────────────────────────────────────
    //  ③ 世界未加载（worldResolver 返 null）→ 跳过，不 bind，不泄漏
    // ──────────────────────────────────────────────────────────

    @Test
    void restore_worldNotLoaded_skips() {
        SeededWall sw = seedPristineWall(3);
        int mapCount = sw.mapIds().size();

        // worldResolver 返 null → restoreOne 早返（return false），根本不到 bind
        WallRestorer restorer = restorer(new HikariCanvasRenderer(), name -> null);
        int restored = restorer.restore();

        assertEquals(0, restored, "世界未加载 → 跳过恢复");
        assertEquals(mapCount, pool.stats().free(), "未 bind，FREE 数不变");
        assertEquals(0, pool.stats().reserved(), "未 bind，无 RESERVED");
        assertTrue(restorer.isRestorationFailed(sw.wallId()),
                "跳过的墙也进 failedRestoreWallIds");
    }

    // ──────────────────────────────────────────────────────────
    //  测试内子类：注入渲染失败
    // ──────────────────────────────────────────────────────────

    /** override update 无条件抛，模拟 bind 成功后 compose / IO 阶段炸。 */
    static final class ThrowingRenderer extends HikariCanvasRenderer {
        @Override
        public void update(int mapId, byte[] pixels) {
            throw new RuntimeException("injected render failure");
        }
    }

    /**
     * 内存 {@link MapBackend} fake（照 {@code pool.FakeMapBackend} 抄——那是 pool 包的
     * 包级类，render 测试包访问不到）。铸的 map id 从 1000 起，记住 world 让
     * {@link MapPool#bindToWall} 的 world 校验通过。
     */
    static final class FakeMapBackend implements MapBackend {
        private int nextId = 1000;
        private final Map<Integer, World> maps = new HashMap<>();
        private final Map<String, World> worlds = new HashMap<>();

        @Override
        public int createMap(World world, MapRenderer renderer) {
            int id = nextId++;
            maps.put(id, world);
            worlds.putIfAbsent(world.getName(), world);
            return id;
        }

        @Override public World installRenderer(int mapId, MapRenderer renderer) { return maps.get(mapId); }
        @Override public World mapWorld(int mapId) { return maps.get(mapId); }
        @Override public World worldByName(String name) { return worlds.get(name); }
    }
}
