package ac.haru.hikaricanvas.variable.provider;

import ac.haru.hikaricanvas.storage.UserVariableDao;
import ac.haru.hikaricanvas.variable.Variable;
import ac.haru.hikaricanvas.variable.VariableStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.4.0-P3-J：{@link SystemVariableProvider} 单测。
 *
 * <p>用 {@link FakeDataSource} 注入测试值，避免依赖 Bukkit。覆盖：</p>
 * <ul>
 *   <li>initialize 注册全局 8 + per-wall (4 × N) 变量</li>
 *   <li>refresh push 全局值（time / online / tps / motd 等）</li>
 *   <li>registerWall / unregisterWall 幂等</li>
 *   <li>declaredKeys 完整暴露 12 项</li>
 *   <li>tps1m null / currentTick null → 跳过对应变量不报错</li>
 *   <li>shutdown 清状态</li>
 * </ul>
 */
class SystemVariableProviderTest {

    private FakeUserVariableDao fakeDao;
    private VariableStore store;
    private FakeDataSource dataSource;
    private SystemVariableProvider provider;

    @BeforeEach
    void setUp() {
        fakeDao = new FakeUserVariableDao();
        store = new VariableStore(fakeDao, w -> { });
        dataSource = new FakeDataSource();
        provider = new SystemVariableProvider(store, dataSource);
    }

    // ──────────────────────────────────────────────────────────
    //  declaredKeys
    // ──────────────────────────────────────────────────────────

    @Test
    void declaredKeys_returns8GlobalAnd4PerWall() {
        List<DeclaredKey> keys = provider.declaredKeys();
        assertEquals(12, keys.size(), "8 global + 4 per-wall");
        // 抽样检查若干 key
        assertTrue(keys.stream().anyMatch(k -> k.key().equals("server.time")));
        assertTrue(keys.stream().anyMatch(k -> k.key().equals("server.tick")));
        assertTrue(keys.stream().anyMatch(k -> k.key().equals("server.tps")));
        assertTrue(keys.stream().anyMatch(k -> k.key().equals("wall.id")));
        assertTrue(keys.stream().anyMatch(k -> k.key().equals("wall.alias")));
    }

    @Test
    void isDynamic_returnsFalse() {
        assertFalse(provider.isDynamic());
    }

    @Test
    void namespace_isSystem() {
        assertEquals("system", provider.namespace());
    }

    // ──────────────────────────────────────────────────────────
    //  initialize
    // ──────────────────────────────────────────────────────────

    @Test
    void initialize_createsAllGlobalAndPerWallVariables() {
        dataSource.wallIds.add("w-1");
        dataSource.wallIds.add("w-2");
        dataSource.wallMetas.put("w-1",
                new SystemVariableProvider.WallMeta("w-1", "AliasOne", "HaruP", "uuid-1"));
        dataSource.wallMetas.put("w-2",
                new SystemVariableProvider.WallMeta("w-2", null, "HaruP", "uuid-1"));

        provider.initialize();

        // 全局：8 个
        assertTrue(store.get("system/server.time").isPresent());
        assertTrue(store.get("system/server.real_time").isPresent());
        assertTrue(store.get("system/server.tick").isPresent());
        assertTrue(store.get("system/server.online").isPresent());
        assertTrue(store.get("system/server.online_list").isPresent());
        assertTrue(store.get("system/server.motd").isPresent());
        assertTrue(store.get("system/server.tps").isPresent());
        assertTrue(store.get("system/server.name").isPresent());

        // per-wall w-1: 4 个
        assertTrue(store.get("system:w-1/wall.id").isPresent());
        assertTrue(store.get("system:w-1/wall.alias").isPresent());
        assertTrue(store.get("system:w-1/wall.owner").isPresent());
        assertTrue(store.get("system:w-1/wall.owner_uuid").isPresent());

        // per-wall w-2: 4 个
        assertTrue(store.get("system:w-2/wall.id").isPresent());
        assertTrue(store.get("system:w-2/wall.alias").isPresent());

        // w-1 永久值已填入
        assertEquals("w-1", store.get("system:w-1/wall.id").get().currentValue());
        assertEquals("HaruP", store.get("system:w-1/wall.owner").get().currentValue());
        assertEquals("uuid-1", store.get("system:w-1/wall.owner_uuid").get().currentValue());
        assertEquals("AliasOne", store.get("system:w-1/wall.alias").get().currentValue());

        // w-2 alias 为 null，currentValue 应保持 null（create 不设 default + alias 没 push）
        assertEquals(null, store.get("system:w-2/wall.alias").get().currentValue());
    }

    @Test
    void initialize_alsoTriggersFirstRefresh() {
        dataSource.online = 12;
        dataSource.motd = "Hello MOTD";
        dataSource.tps = 19.85;
        provider.initialize();

        // initialize 内部调 refresh()，所以这些应该立刻有值
        assertEquals("12", store.get("system/server.online").get().currentValue());
        assertEquals("Hello MOTD", store.get("system/server.motd").get().currentValue());
        assertEquals("19.85", store.get("system/server.tps").get().currentValue());
    }

    @Test
    void initialize_isIdempotent_repeatedRegisterWallNoOp() {
        dataSource.wallIds.add("w-1");
        dataSource.wallMetas.put("w-1",
                new SystemVariableProvider.WallMeta("w-1", "A", "P", "u"));
        provider.initialize();
        int sizeAfterFirst = store.size();
        // 再次 register 同 wallId 不应抛 / 不增数
        provider.registerWall("w-1");
        assertEquals(sizeAfterFirst, store.size(),
                "重复 registerWall 应幂等");
    }

    // ──────────────────────────────────────────────────────────
    //  refresh
    // ──────────────────────────────────────────────────────────

    @Test
    void refresh_pushesUpdatedGlobalValues() {
        provider.initialize();
        // 改变源
        dataSource.online = 99;
        dataSource.motd = "New MOTD";
        // 把 nextRefreshAt 全部清回过去，强制全刷
        forceAllStale(provider);
        provider.refresh();
        assertEquals("99", store.get("system/server.online").get().currentValue());
        assertEquals("New MOTD", store.get("system/server.motd").get().currentValue());
    }

    /**
     * 变量 TTL 必须比刷新周期长。
     *
     * <p>两者取同一个数时，每一轮都会出现一段"值刚过期、下一轮还没写进来"的空窗
     * （刷新调度本身有抖动，算值还要切主线程），墙上就会周期性闪一下 {@code ???}。
     * 三个周期型 Provider 统一留一倍宽限。</p>
     */
    @Test
    void variableTtlOutlivesRefreshInterval() {
        provider.initialize();
        forceAllStale(provider);
        provider.refresh();

        // server.tick 的刷新周期是 1s（= BASE_REFRESH_INTERVAL_MS）
        long tickTtl = store.get("system/server.tick").orElseThrow().ttl();
        assertTrue(tickTtl > SystemVariableProvider.BASE_REFRESH_INTERVAL_MS,
                "TTL 必须大于刷新周期，否则每轮都有 stale 空窗: " + tickTtl);

        // 姊妹 Provider 同款纪律（常量级断言，防哪天又被改回相等）
        assertTrue(PapiVariableBridge.TTL_MS > PapiVariableBridge.REFRESH_INTERVAL_MS,
                "PAPI 的 TTL 必须大于刷新周期");
        assertTrue(ScoreboardVariableProvider.TTL_MS
                        > ScoreboardVariableProvider.REFRESH_INTERVAL_MS,
                "计分板的 TTL 必须大于刷新周期");
    }

    @Test
    void refresh_perWallAliasUpdated() {
        dataSource.wallIds.add("w-1");
        dataSource.wallMetas.put("w-1",
                new SystemVariableProvider.WallMeta("w-1", "OldAlias", "P", "u"));
        provider.initialize();
        assertEquals("OldAlias", store.get("system:w-1/wall.alias").get().currentValue());

        // 改 alias
        dataSource.wallMetas.put("w-1",
                new SystemVariableProvider.WallMeta("w-1", "NewAlias", "P", "u"));
        // P2-83：alias 刷新现在按 5s 节流（lastAliasPushAt）。initialize() 内的首次 refresh 已记一次
        // push 时刻，紧接着的第二次 refresh 落在 5s 窗口内会被 skip。把节流表设回过去强制刷新。
        forceAliasStale(provider);
        provider.refresh();
        assertEquals("NewAlias", store.get("system:w-1/wall.alias").get().currentValue());
    }

    @Test
    void refresh_skipsWhenTpsAndTickNull_doesNotCrash() {
        dataSource.tps = null;
        dataSource.tick = null;
        provider.initialize();
        forceAllStale(provider);
        // 不应抛
        provider.refresh();
        // tps / tick 的 currentValue 应保持原状（initialize 时也 null）
        assertEquals(null, store.get("system/server.tps").get().currentValue());
        assertEquals(null, store.get("system/server.tick").get().currentValue());
    }

    @Test
    void refresh_serverTimeAndRealTime_areSet() {
        provider.initialize();
        forceAllStale(provider);
        provider.refresh();
        String time = store.get("system/server.time").get().currentValue();
        assertNotNull(time);
        assertTrue(time.matches("\\d{2}:\\d{2}"), "server.time 形如 HH:mm；got: " + time);
        String real = store.get("system/server.real_time").get().currentValue();
        assertNotNull(real);
        assertTrue(real.contains("T"), "server.real_time 是 ISO；got: " + real);
    }

    @Test
    void refresh_onlineListJoinsCommaSeparated() {
        dataSource.onlineNames.add("Haru");
        dataSource.onlineNames.add("Suika");
        provider.initialize();
        forceAllStale(provider);
        provider.refresh();
        assertEquals("Haru,Suika", store.get("system/server.online_list").get().currentValue());
    }

    // ──────────────────────────────────────────────────────────
    //  registerWall / unregisterWall
    // ──────────────────────────────────────────────────────────

    @Test
    void registerWall_addsFourVariables() {
        provider.initialize();
        int sizeBefore = store.size();
        dataSource.wallMetas.put("w-new",
                new SystemVariableProvider.WallMeta("w-new", "AliasX", "Player", "uuid"));
        provider.registerWall("w-new");
        // 4 个 wall.* 变量
        assertEquals(sizeBefore + 4, store.size());
        assertEquals("w-new", store.get("system:w-new/wall.id").get().currentValue());
        assertEquals("AliasX", store.get("system:w-new/wall.alias").get().currentValue());
    }

    @Test
    void unregisterWall_removesAllFour() {
        dataSource.wallIds.add("w-x");
        dataSource.wallMetas.put("w-x",
                new SystemVariableProvider.WallMeta("w-x", "A", "P", "u"));
        provider.initialize();
        int sizeAfterInit = store.size();
        assertTrue(store.get("system:w-x/wall.id").isPresent());

        provider.unregisterWall("w-x");
        assertEquals(sizeAfterInit - 4, store.size());
        assertFalse(store.get("system:w-x/wall.id").isPresent());
        assertFalse(store.get("system:w-x/wall.alias").isPresent());
        assertFalse(store.get("system:w-x/wall.owner").isPresent());
        assertFalse(store.get("system:w-x/wall.owner_uuid").isPresent());
    }

    @Test
    void unregisterWall_unknownWallId_isNoop() {
        provider.initialize();
        int before = store.size();
        provider.unregisterWall("w-never-registered");
        assertEquals(before, store.size());
    }

    @Test
    void registerWall_nullOrEmpty_noop() {
        provider.initialize();
        int before = store.size();
        provider.registerWall(null);
        provider.registerWall("");
        assertEquals(before, store.size());
    }

    // ──────────────────────────────────────────────────────────
    //  shutdown
    // ──────────────────────────────────────────────────────────

    @Test
    void shutdown_clearsInternalState() {
        dataSource.wallIds.add("w-1");
        dataSource.wallMetas.put("w-1",
                new SystemVariableProvider.WallMeta("w-1", "A", "P", "u"));
        provider.initialize();

        provider.shutdown();
        // shutdown 后 unregisterWall("w-1") 视作 not registered（registeredWalls 已清）
        // 此处仅验证不抛
        provider.unregisterWall("w-1");
    }

    // ──────────────────────────────────────────────────────────
    //  scheduleMainThread 切换
    // ──────────────────────────────────────────────────────────

    @Test
    void refresh_callsScheduleMainThread_perGlobalVariableUpdate() {
        provider.initialize();
        int before = dataSource.scheduleCount.get();
        forceAllStale(provider);
        provider.refresh();
        // refresh 内部按 GLOBAL_KEYS 数量 schedule（默认 8）。perWallAlias 直接同步写不走 schedule。
        int after = dataSource.scheduleCount.get();
        assertTrue(after - before >= 8,
                "每个全局变量应触发 scheduleMainThread；got delta=" + (after - before));
    }

    // ──────────────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────────────

    /** 把所有全局 key 的 nextRefreshAt 设回过去，强制下一次 refresh 全部更新。 */
    private static void forceAllStale(SystemVariableProvider provider) {
        try {
            java.lang.reflect.Field f = SystemVariableProvider.class.getDeclaredField("nextRefreshAt");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Long> map = (Map<String, Long>) f.get(provider);
            long past = System.currentTimeMillis() - 1_000_000L;
            for (String k : map.keySet()) {
                map.put(k, past);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** P2-83：把所有 per-wall alias 节流时刻设回过去，强制下一次 refresh 重刷 alias。 */
    private static void forceAliasStale(SystemVariableProvider provider) {
        try {
            java.lang.reflect.Field f = SystemVariableProvider.class.getDeclaredField("lastAliasPushAt");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Long> map = (Map<String, Long>) f.get(provider);
            long past = System.currentTimeMillis() - 1_000_000L;
            for (String k : map.keySet()) {
                map.put(k, past);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ──────────────────────────────────────────────────────────
    //  伪 wall 守卫：占位符里的 namespace 段是玩家自由文本
    // ──────────────────────────────────────────────────────────

    /**
     * 任何人在文本元素里写 {@code ${var:system:随便什么/wall.alias}} 都会同步走到
     * dynamic-lookup hook。不校验形态的话，每个假 wallId 都会永久占 4 个内存变量 +
     * 每 5s 白跑一次 DB 查询，而且首次注册那趟 DB 往返就发生在渲染线程上。
     */
    @Test
    void dynamicWallRegistration_rejectsMalformedWallIds() {
        int before = store.size();
        provider.handleDynamicWall("不是墙 id");
        provider.handleDynamicWall("../../etc/passwd");
        provider.handleDynamicWall("w-XYZ");
        provider.handleDynamicWall("w-1");                 // 位数不够
        provider.handleDynamicWall("w-DEADBEEF");          // 大写十六进制不是本项目形态
        provider.handleDynamicWall("w-deadbeef-extra");

        assertTrue(provider.registeredWallsSnapshot().isEmpty(), "伪 wall 一个都不该注册");
        assertEquals(before, store.size(), "伪 wall 不该产生变量");
        assertEquals(0, dataSource.wallMetaCalls.get(), "伪 wall 不该发起 DB 查询");
    }

    /** 形态合法的 wallId 仍走懒注册（别把正常路径一起拦了）。 */
    @Test
    void dynamicWallRegistration_acceptsRealWallId() {
        dataSource.wallMetas.put("w-0a1b2c3d",
                new SystemVariableProvider.WallMeta("w-0a1b2c3d", "招牌", "HaruP", "uuid-1"));

        provider.handleDynamicWall("w-0a1b2c3d");

        assertTrue(provider.registeredWallsSnapshot().contains("w-0a1b2c3d"));
        assertTrue(store.get("system:w-0a1b2c3d/wall.alias").isPresent());
    }

    // ──────────────────────────────────────────────────────────
    //  Fakes
    // ──────────────────────────────────────────────────────────

    private static final class FakeDataSource implements SystemVariableProvider.DataSource {
        final List<String> wallIds = new ArrayList<>();
        final Map<String, SystemVariableProvider.WallMeta> wallMetas = new LinkedHashMap<>();
        int online = 0;
        final List<String> onlineNames = new ArrayList<>();
        Integer tick = 1234;
        String motd = "A Minecraft Server";
        Double tps = 20.0;
        String serverName = "TestServer";
        final AtomicInteger scheduleCount = new AtomicInteger();

        @Override
        public Iterable<String> allWallIds() {
            return new ArrayList<>(wallIds);
        }

        /** wallMeta 调用次数（生产实现是一次 DB 查询——伪 wall 守卫据此断言"零往返"）。 */
        final AtomicInteger wallMetaCalls = new AtomicInteger();

        @Override
        public SystemVariableProvider.WallMeta wallMeta(String wallId) {
            wallMetaCalls.incrementAndGet();
            return wallMetas.get(wallId);
        }

        @Override
        public void scheduleMainThread(Runnable r) {
            scheduleCount.incrementAndGet();
            // 同步 run，简化测试
            r.run();
        }

        @Override
        public int onlinePlayerCount() {
            return online;
        }

        @Override
        public List<String> onlinePlayerNames() {
            return new ArrayList<>(onlineNames);
        }

        @Override
        public Integer currentTick() {
            return tick;
        }

        @Override
        public String motd() {
            return motd;
        }

        @Override
        public Double tps1m() {
            return tps;
        }

        @Override
        public String serverName() {
            return serverName;
        }
    }

    private static final class FakeUserVariableDao extends UserVariableDao {
        FakeUserVariableDao() {
            super(Logger.getLogger("test"), null);
        }
        @Override
        public void upsert(String wallId, String name, ac.haru.hikaricanvas.variable.VarType type,
                           String defaultValue, String currentValue, String boundTo,
                           long createdAt, long updatedAt) {}
        @Override public void delete(String wallId, String name) {}
        @Override public List<Row> loadAll() { return new ArrayList<>(); }
    }
}
