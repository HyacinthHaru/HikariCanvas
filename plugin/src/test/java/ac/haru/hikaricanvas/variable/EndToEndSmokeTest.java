package ac.haru.hikaricanvas.variable;

import ac.haru.hikaricanvas.api.NamespaceInfo;
import ac.haru.hikaricanvas.api.PluginNamespaceException;
import ac.haru.hikaricanvas.storage.AuditLog;
import ac.haru.hikaricanvas.storage.UserVariableDao;
import ac.haru.hikaricanvas.variable.plugin.HikariCanvasAPIImpl;
import ac.haru.hikaricanvas.variable.plugin.PluginCleanupListener;
import ac.haru.hikaricanvas.variable.plugin.PluginNamespaceRegistry;
import ac.haru.hikaricanvas.variable.plugin.PushRateLimiter;
import ac.haru.hikaricanvas.variable.provider.VariableProviderDaemon;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 变量子系统端到端装配链冒烟单测（VariableStore + Interpolator + APIImpl +
 * PluginCleanupListener + PushRateLimiter）。
 */
class EndToEndSmokeTest {

    private FakeUserVariableDao fakeDao;
    private VariableStore store;
    private VariableInterpolator interp;
    private VariableProviderDaemon daemon;
    private PluginNamespaceRegistry registry;
    private HikariCanvasAPIImpl api;
    private AuditLog auditLog;

    @BeforeEach
    void setUp() {
        fakeDao = new FakeUserVariableDao();
        store = new VariableStore(fakeDao, w -> {});
        interp = new VariableInterpolator(store);
        daemon = new VariableProviderDaemon();
        registry = new PluginNamespaceRegistry();
        auditLog = new AuditLog(null, Logger.getLogger("test"));
        api = new HikariCanvasAPIImpl(registry, store, daemon,
                new PushRateLimiter(PushRateLimiter.Config.unlimited()), auditLog);
    }

    @AfterEach
    void tearDown() {
        daemon.shutdown();
    }

    // ──────────────────────────────────────────────────────────
    //  端到端
    // ──────────────────────────────────────────────────────────

    @Test
    void endToEnd_userVariable_crud() {
        // create
        Variable v = store.create("user:w-test", "red_score", VarType.NUMBER, "0", null);
        assertEquals("user:w-test/red_score", v.fullName());
        assertEquals(1, fakeDao.upserts.size());

        // setValue
        store.setValue("user:w-test/red_score", "42", null);
        assertEquals("42", store.get("user:w-test/red_score").orElseThrow().currentValue());

        // interpolate（含 wallId 自动注入：${var:user/red_score} → user:w-test/red_score）
        String out = interp.interpolate("score=${var:user/red_score}", "w-test").text();
        assertEquals("score=42", out);

        // delete
        store.delete("user:w-test/red_score");
        assertTrue(store.get("user:w-test/red_score").isEmpty());
        assertEquals(1, fakeDao.deletes.size());

        // 删除后再 interpolate → "???"（系统兜底）
        String afterDelete = interp.interpolate("v=${var:user/red_score}", "w-test").text();
        assertEquals("v=???", afterDelete);
    }

    @Test
    void endToEnd_pluginNamespacePushAndInterpolate() {
        Plugin pluginA = fakePlugin("PluginA");
        api.registerNamespace(pluginA, "bedwars", new NamespaceInfo("BedWars", "PluginA", "1.0"));
        api.setVariable(pluginA, "bedwars", "red_score", "7", null);
        api.setVariable(pluginA, "bedwars", "blue_score", "3", null);

        String out = interp.interpolate(
                "RED=${var:bedwars/red_score} BLUE=${var:bedwars/blue_score}", "w-anywhere").text();
        assertEquals("RED=7 BLUE=3", out);
    }

    @Test
    void endToEnd_pluginDisableTriggersCleanup() {
        Plugin pluginA = fakePlugin("PluginA");
        Plugin host = fakePlugin("HikariCanvas");
        RecordingScheduler scheduler = new RecordingScheduler();
        PluginCleanupListener listener = new PluginCleanupListener(registry, api, host, scheduler);

        // 注册 + push 几个变量
        api.registerNamespace(pluginA, "bedwars", new NamespaceInfo("BW", "PluginA", "1.0"));
        api.setVariable(pluginA, "bedwars", "score", "10", null);
        api.registerNamespace(pluginA, "tnt", new NamespaceInfo("TNT", "PluginA", "1.0"));
        api.setVariable(pluginA, "tnt", "stock", "5", null);
        assertEquals(2, registry.size());
        assertEquals(2, store.size());

        // disable
        listener.handleDisable(pluginA);

        // 立即效果：registry 摘除；store 保留 30s
        assertEquals(0, registry.size());
        assertEquals(2, store.size());

        // 跑 scheduler 的延迟任务（模拟 30s grace 已过）
        assertEquals(1, scheduler.tasks.size());
        scheduler.tasks.get(0).task.run();

        // 现在 store 也清了
        assertEquals(0, store.size());
    }

    @Test
    void endToEnd_rateLimitDropTail() {
        // 用固定虚拟时钟锁定同一 1s 窗口
        long fixedNow = 1_700_000_000_000L;
        PushRateLimiter limiter = new PushRateLimiter(
                new PushRateLimiter.Config(5, 1_000_000, 0L),
                () -> fixedNow);
        // 重建 API 用窄限流器
        HikariCanvasAPIImpl rlApi = new HikariCanvasAPIImpl(registry, store, daemon, limiter, auditLog);
        Plugin pluginA = fakePlugin("PluginA");
        rlApi.registerNamespace(pluginA, "test", new NamespaceInfo("Test", "PluginA", "1.0"));

        // 5 次 push 都该成功
        for (int i = 1; i <= 5; i++) {
            rlApi.setVariable(pluginA, "test", "k", String.valueOf(i), null);
        }
        assertEquals("5", store.get("test/k").orElseThrow().currentValue());

        // 第 6 次同窗口内被 drop（fake clock 锁定窗口 → 严格同秒）
        rlApi.setVariable(pluginA, "test", "k", "999", null);
        assertEquals("5", store.get("test/k").orElseThrow().currentValue(),
                "6th push in same second should be dropped by per-plugin limiter");
    }

    @Test
    void endToEnd_aclSpoofDenied() {
        Plugin pluginA = fakePlugin("PluginA");
        Plugin pluginB = fakePlugin("PluginB");
        api.registerNamespace(pluginA, "alpha", new NamespaceInfo("A", "PluginA", "1.0"));

        PluginNamespaceException ex = assertThrows(PluginNamespaceException.class,
                () -> api.setVariable(pluginB, "alpha", "k", "v", null));
        assertEquals(PluginNamespaceException.Code.NAMESPACE_ACL_DENIED, ex.code());

        // 同样 pluginB 试 declareKey 也拒
        assertThrows(PluginNamespaceException.class,
                () -> api.declareKey(pluginB, "alpha", "k",
                        ac.haru.hikaricanvas.api.VarType.STRING, "spoofed"));
    }

    @Test
    void endToEnd_fallbackChain() {
        // 档 1：cached value 优先
        store.create("system", "x", VarType.STRING, "DEFAULT", null);
        store.setValue("system/x", "live", null);
        assertEquals("live", interp.interpolate("${var:system/x}", null).text());

        // 档 2：清掉 currentValue → defaultValue
        store.setValue("system/x", null, null);
        assertEquals("DEFAULT", interp.interpolate("${var:system/x}", null).text());

        // 档 3：变量不存在 + 提供 fallback= → 用 fallback
        store.delete("system/x");
        assertEquals("N/A", interp.interpolate("${var:system/x|fallback=N/A}", null).text());

        // 档 4：变量不存在 + 无 fallback → 系统兜底 "???"
        assertEquals("???", interp.interpolate("${var:system/x}", null).text());
    }

    // ──────────────────────────────────────────────────────────
    //  Fakes
    // ──────────────────────────────────────────────────────────

    /** 在 FakeUserVariableDao 内部追踪 upserts / deletes 便于断言。 */
    static final class FakeUserVariableDao extends UserVariableDao {
        final List<Object> upserts = new ArrayList<>();
        final List<Object> deletes = new ArrayList<>();
        FakeUserVariableDao() { super(Logger.getLogger("test"), null); }
        @Override public void upsert(String wallId, String name, VarType type,
                                     String defaultValue, String currentValue, String boundTo,
                                     long createdAt, long updatedAt) {
            upserts.add(new Object[]{wallId, name, type, currentValue});
        }
        @Override public void delete(String wallId, String name) {
            deletes.add(new Object[]{wallId, name});
        }
        @Override public List<Row> loadAll() { return new ArrayList<>(); }
    }

    /** 同 PluginCleanupListenerTest 用法：抓 (delayTicks, Runnable) 让单测同步触发。 */
    static final class RecordingScheduler implements PluginCleanupListener.DelayedScheduler {
        record Scheduled(long delayTicks, Runnable task) {}
        final List<Scheduled> tasks = new ArrayList<>();
        @Override public void runLater(long delayTicks, Runnable task) {
            tasks.add(new Scheduled(delayTicks, task));
        }
    }

    static Plugin fakePlugin(String name) {
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) return name;
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("toString".equals(method.getName())) return "FakePlugin[" + name + "]";
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    if (rt == double.class) return 0.0;
                    if (rt == float.class) return 0.0f;
                    if (rt.isPrimitive()) return 0;
                    return null;
                });
    }
}
