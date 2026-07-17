package ac.haru.hikaricanvas.variable.provider;

import ac.haru.hikaricanvas.storage.UserVariableDao;
import ac.haru.hikaricanvas.variable.VariableInterpolator;
import ac.haru.hikaricanvas.variable.VariableStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.4.0-P3-J：{@link ScoreboardVariableProvider} 单测。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>initialize 注册 dynamic lookup hook（store.registerDynamicLookupHook）</li>
 *   <li>handleDynamic 解析 {@code scoreboard.<obj>.<player>} → 创建变量 + 跟踪</li>
 *   <li>refresh 读 scoreboard + setValue</li>
 *   <li>obj 不存在 → 不 setValue（cached value 保持）</li>
 *   <li>interpolator miss 触发 handleDynamic（端到端）</li>
 *   <li>非 scoreboard namespace 的 lookup 不被处理</li>
 *   <li>isDynamic = true / declaredKeys = empty</li>
 * </ul>
 */
class ScoreboardVariableProviderTest {

    private FakeUserVariableDao fakeDao;
    private VariableStore store;
    private FakeScoreboardDataSource dataSource;
    private ScoreboardVariableProvider provider;

    @BeforeEach
    void setUp() {
        fakeDao = new FakeUserVariableDao();
        store = new VariableStore(fakeDao, w -> { });
        dataSource = new FakeScoreboardDataSource();
        provider = new ScoreboardVariableProvider(store, dataSource);
    }

    @Test
    void namespace_isScoreboard() {
        assertEquals("scoreboard", provider.namespace());
    }

    @Test
    void isDynamic_returnsTrue() {
        assertTrue(provider.isDynamic());
    }

    @Test
    void declaredKeys_isEmpty() {
        assertTrue(provider.declaredKeys().isEmpty(),
                "动态 provider 不预声明 key");
    }

    // ──────────────────────────────────────────────────────────
    //  initialize 注册 hook
    // ──────────────────────────────────────────────────────────

    @Test
    void initialize_registersDynamicLookupHook() {
        provider.initialize();
        // 触发 store 内 hook：用 notifyDynamicLookup 直接验证
        store.notifyDynamicLookup("scoreboard.points.Haru");
        assertTrue(provider.trackedKeysSnapshot().contains("points.Haru"),
                "hook 应注册并接到回调");
    }

    @Test
    void initialize_doesNotPreCreateAnyVariables() {
        provider.initialize();
        assertEquals(0, store.size(), "动态 provider initialize 不应预创建变量");
    }

    // ──────────────────────────────────────────────────────────
    //  handleDynamic 解析
    // ──────────────────────────────────────────────────────────

    @Test
    void handleDynamic_dotForm_createsVariable() {
        provider.initialize();
        provider.handleDynamic("scoreboard.points.Haru");
        assertTrue(store.get("scoreboard/points.Haru").isPresent());
        assertTrue(provider.trackedKeysSnapshot().contains("points.Haru"));
    }

    @Test
    void handleDynamic_slashForm_createsVariable() {
        provider.initialize();
        // store 内部 fullName 形式（namespace + / + key）
        provider.handleDynamic("scoreboard/kills.Suika");
        assertTrue(store.get("scoreboard/kills.Suika").isPresent());
        assertTrue(provider.trackedKeysSnapshot().contains("kills.Suika"));
    }

    @Test
    void handleDynamic_invalidPattern_ignored() {
        provider.initialize();
        // 没有第二个 dot（只有 namespace.X，无 player）
        provider.handleDynamic("scoreboard.onlyone");
        assertFalse(store.get("scoreboard/onlyone").isPresent());
        assertTrue(provider.trackedKeysSnapshot().isEmpty());
    }

    @Test
    void handleDynamic_wrongNamespace_ignored() {
        provider.initialize();
        provider.handleDynamic("notscoreboard.foo.bar");
        assertTrue(provider.trackedKeysSnapshot().isEmpty());
    }

    @Test
    void handleDynamic_duplicateKey_idempotent() {
        provider.initialize();
        provider.handleDynamic("scoreboard.points.Haru");
        int sizeAfterFirst = store.size();
        provider.handleDynamic("scoreboard.points.Haru");
        assertEquals(sizeAfterFirst, store.size(),
                "重复 handleDynamic 应幂等");
        assertEquals(1, provider.trackedKeysSnapshot().size());
    }

    // ──────────────────────────────────────────────────────────
    //  refresh 读 scoreboard
    // ──────────────────────────────────────────────────────────

    @Test
    void refresh_readsAllTrackedKeysAndSetsValue() {
        provider.initialize();
        provider.handleDynamic("scoreboard.points.Haru");
        provider.handleDynamic("scoreboard.kills.Suika");
        dataSource.scores.put("points.Haru", 42);
        dataSource.scores.put("kills.Suika", 7);

        boolean ok = provider.refresh();
        assertTrue(ok, "refresh 应返 true 表示已 push");
        assertEquals("42", store.get("scoreboard/points.Haru").get().currentValue());
        assertEquals("7", store.get("scoreboard/kills.Suika").get().currentValue());
    }

    @Test
    void refresh_noTrackedKeys_returnsFalseAndDoesNotSchedule() {
        provider.initialize();
        boolean ok = provider.refresh();
        assertFalse(ok);
        assertEquals(0, dataSource.scheduleCount.get(),
                "无 tracked key 应 short-circuit 不进 scheduleMainThread");
    }

    @Test
    void refresh_objectiveNotFound_doesNotSetValue() {
        provider.initialize();
        provider.handleDynamic("scoreboard.missing.Player");
        // dataSource 未配置该 score → readScore 返 null
        provider.refresh();
        assertEquals(null,
                store.get("scoreboard/missing.Player").get().currentValue(),
                "obj 不存在 → 不 setValue（保持 currentValue = null，让 fallback 兜底）");
    }

    @Test
    void refresh_existingValueKeptWhenScoreNullNextTick() {
        provider.initialize();
        provider.handleDynamic("scoreboard.points.Haru");
        dataSource.scores.put("points.Haru", 100);
        provider.refresh();
        assertEquals("100", store.get("scoreboard/points.Haru").get().currentValue());

        // 下一 tick scoreboard 没了（plugin 重启等）→ readScore 返 null → 保留旧值
        dataSource.scores.remove("points.Haru");
        provider.refresh();
        assertEquals("100", store.get("scoreboard/points.Haru").get().currentValue(),
                "score null 时不覆盖，让旧值留在 cache");
    }

    // ──────────────────────────────────────────────────────────
    //  端到端：interpolator miss 触发 register
    // ──────────────────────────────────────────────────────────

    @Test
    void interpolator_missTriggersDynamicRegister() {
        provider.initialize();
        VariableInterpolator interp = new VariableInterpolator(store);
        // 首次 interpolate → 变量不存在 → notifyDynamicLookup → handleDynamic 创建
        var r = interp.interpolate("${var:scoreboard.points.Haru}", "w-1");
        assertEquals(VariableInterpolator.UNRESOLVED, r.text(),
                "首次渲染走 fallback");
        assertTrue(provider.trackedKeysSnapshot().contains("points.Haru"));

        // 第二次 interpolate（store 已有变量 + cached null currentValue）→ ???
        var r2 = interp.interpolate("${var:scoreboard.points.Haru|fallback=N/A}", "w-1");
        assertEquals("N/A", r2.text(),
                "currentValue 仍为 null → inline fallback");

        // 模拟 refresh 把值填上
        dataSource.scores.put("points.Haru", 42);
        provider.refresh();
        var r3 = interp.interpolate("${var:scoreboard.points.Haru}", "w-1");
        assertEquals("42", r3.text());
    }

    // ──────────────────────────────────────────────────────────
    //  shutdown
    // ──────────────────────────────────────────────────────────

    @Test
    void shutdown_clearsTrackedKeys() {
        provider.initialize();
        provider.handleDynamic("scoreboard.x.y");
        assertFalse(provider.trackedKeysSnapshot().isEmpty());
        provider.shutdown();
        assertTrue(provider.trackedKeysSnapshot().isEmpty());
    }

    // ──────────────────────────────────────────────────────────
    //  Fakes
    // ──────────────────────────────────────────────────────────

    private static final class FakeScoreboardDataSource
            implements ScoreboardVariableProvider.DataSource {
        final Map<String, Integer> scores = new HashMap<>();
        final AtomicInteger scheduleCount = new AtomicInteger();

        @Override
        public void scheduleMainThread(Runnable r) {
            scheduleCount.incrementAndGet();
            r.run(); // 同步
        }

        @Override
        public Integer readScore(String objective, String player) {
            String key = objective + "." + player;
            return scores.get(key);
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
