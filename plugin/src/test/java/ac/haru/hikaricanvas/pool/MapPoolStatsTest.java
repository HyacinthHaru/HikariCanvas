package ac.haru.hikaricanvas.pool;

import ac.haru.hikaricanvas.render.HikariCanvasRenderer;
import ac.haru.hikaricanvas.storage.AuditLog;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.2 可观测性：{@link MapPool#maxSize()} / {@link MapPool#byWorldStats()} 只读 accessor 单测。
 *
 * <p><b>测试策略</b>（照 {@link MapPoolDetectLeaksThreadSafetyTest} 范式）：纯 JVM 无 Bukkit
 * server，{@link MapPool#assertMainThread} 在 server 为 null 时早返不抛；不调
 * {@code initialize}/{@code reserveForWall}（需 {@code Bukkit.createMap}/{@code Bukkit.getMap}），
 * 改为反射直接向 {@code byId} / {@code freeByWorld} 注入 {@link PooledMap} 条目模拟池状态。
 * 这些 accessor 是纯内存读，不碰 Bukkit / DB（jdbi 传 null 也安全）。</p>
 */
class MapPoolStatsTest {

    private static final Logger LOG = Logger.getLogger("test.mappool.stats");

    /** 构造一个不依赖 Bukkit/DB 的 MapPool（accessor 不读 jdbi/audit）。 */
    private static MapPool newPool(int initial, int max) {
        AuditLog auditLog = new AuditLog(null, LOG);
        HikariCanvasRenderer renderer = new HikariCanvasRenderer();
        return new MapPool(LOG, null, auditLog, renderer, initial, max);
    }

    @Test
    void maxSize_returnsConstructorValue() {
        MapPool pool = newPool(5, 20);
        assertEquals(20, pool.maxSize(), "maxSize() 应返回构造期注入的 max");
    }

    @Test
    void byWorldStats_emptyPool_returnsEmptyMap() {
        MapPool pool = newPool(5, 20);
        assertTrue(pool.byWorldStats().isEmpty(), "空池 byWorldStats 应为空");
        MapPool.Stats s = pool.stats();
        assertEquals(0, s.total());
        assertEquals(0, s.free());
        assertEquals(0, s.reserved());
    }

    @Test
    void byWorldStats_multiWorld_countsFreePerWorld() throws Exception {
        MapPool pool = newPool(5, 50);
        UUID overUid = UUID.randomUUID();
        UUID netherUid = UUID.randomUUID();

        // overworld: 3 FREE; nether: 1 FREE
        injectFree(pool, overUid, "world", 10, 11, 12);
        injectFree(pool, netherUid, "world_nether", 20);

        Map<String, Integer> byWorld = pool.byWorldStats();
        assertEquals(2, byWorld.size(), "应有两个 world 桶");
        assertEquals(3, byWorld.get("world").intValue());
        assertEquals(1, byWorld.get("world_nether").intValue());

        // stats() 与 byWorldStats() 自洽：total = free = 4，reserved = 0
        MapPool.Stats s = pool.stats();
        assertEquals(4, s.total());
        assertEquals(4, s.free());
        assertEquals(0, s.reserved());
        assertEquals(s.free(), byWorld.values().stream().mapToInt(Integer::intValue).sum(),
                "byWorldStats FREE 之和应等于 stats().free()");
    }

    @Test
    void byWorldStats_unknownWorldBucket_labelledAngleUnknown() throws Exception {
        MapPool pool = newPool(5, 50);
        // zero-UUID 桶（world 卸载窗口期暂存）
        injectFree(pool, new UUID(0L, 0L), "world_phantom", 30, 31);

        Map<String, Integer> byWorld = pool.byWorldStats();
        assertEquals(1, byWorld.size());
        assertEquals(2, byWorld.get("<unknown>").intValue(),
                "zero-UUID 桶应固定标 <unknown>");
    }

    @Test
    void byWorldStats_emptyBucketSkipped() throws Exception {
        MapPool pool = newPool(5, 50);
        UUID overUid = UUID.randomUUID();
        // 空桶（曾有 map 后全借出）应被跳过
        injectEmptyBucket(pool, overUid);
        assertTrue(pool.byWorldStats().isEmpty(), "空桶应被 byWorldStats 跳过");
    }

    @Test
    void byWorldStats_reservedNotCounted() throws Exception {
        MapPool pool = newPool(5, 50);
        UUID overUid = UUID.randomUUID();
        injectFree(pool, overUid, "world", 40, 41);
        // 注入一条 RESERVED（不进 freeByWorld）— 不应出现在 byWorldStats
        injectByIdOnly(pool, new PooledMap(99, PoolState.RESERVED,
                "wall:w-deadbeef", "world", 0L, 0L));

        Map<String, Integer> byWorld = pool.byWorldStats();
        assertEquals(2, byWorld.get("world").intValue(),
                "RESERVED map 不计入 FREE 分世界统计");
        MapPool.Stats s = pool.stats();
        assertEquals(3, s.total());
        assertEquals(2, s.free());
        assertEquals(1, s.reserved());
    }

    // ----------------------------------------------------------------------
    // 反射工具
    // ----------------------------------------------------------------------

    /** 向 byId + freeByWorld 注入 FREE map（同一 world 桶）。 */
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

    /** 注入一个空 FREE 桶（无 mapId）。 */
    @SuppressWarnings("unchecked")
    private static void injectEmptyBucket(MapPool pool, UUID worldUid) throws Exception {
        Field freeF = MapPool.class.getDeclaredField("freeByWorld");
        freeF.setAccessible(true);
        Map<UUID, Deque<Integer>> freeByWorld = (Map<UUID, Deque<Integer>>) freeF.get(pool);
        freeByWorld.put(worldUid, new ArrayDeque<>());
    }

    /** 仅注入 byId（不进 freeByWorld）—— 用于 RESERVED 条目。 */
    @SuppressWarnings("unchecked")
    private static void injectByIdOnly(MapPool pool, PooledMap entry) throws Exception {
        Field byIdF = MapPool.class.getDeclaredField("byId");
        byIdF.setAccessible(true);
        Map<Integer, PooledMap> byId = (Map<Integer, PooledMap>) byIdF.get(pool);
        byId.put(entry.mapId(), entry);
    }
}
