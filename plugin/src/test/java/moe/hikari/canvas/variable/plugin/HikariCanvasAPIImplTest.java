package moe.hikari.canvas.variable.plugin;

import moe.hikari.canvas.api.HikariCanvasAPI;
import moe.hikari.canvas.api.NamespaceConflictException;
import moe.hikari.canvas.api.NamespaceInfo;
import moe.hikari.canvas.api.PluginNamespaceException;
import moe.hikari.canvas.api.VariableUpdate;
import moe.hikari.canvas.storage.AuditLog;
import moe.hikari.canvas.storage.UserVariableDao;
import moe.hikari.canvas.variable.VarType;
import moe.hikari.canvas.variable.Variable;
import moe.hikari.canvas.variable.VariableStore;
import moe.hikari.canvas.variable.provider.VariableProviderDaemon;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import static moe.hikari.canvas.variable.plugin.PluginNamespaceRegistryTest.fakePlugin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.4.0-P4-O：{@link HikariCanvasAPIImpl} 单测。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>registerNamespace → registry + daemon provider 同步</li>
 *   <li>declareKey → provider.declaredKeys 反映；未注册 namespace 抛 ACL/NOT_REGISTERED</li>
 *   <li>setVariable 首次 → store.create + setValue（auto-create）</li>
 *   <li>setVariable 重复 key → 只 setValue 不 create</li>
 *   <li>setVariables 批量分发 + null 单条容错</li>
 *   <li>ACL：plugin B 推 plugin A 的 namespace → PluginNamespaceException ACL_DENIED</li>
 *   <li>未注册 namespace 直接 setVariable → NAMESPACE_NOT_REGISTERED</li>
 *   <li>unsetVariable → store.delete；不删 declared key</li>
 *   <li>VarType 4 case 转换正确</li>
 *   <li>unregisterPluginProviders → daemon.unregister + 摘除 provider</li>
 *   <li>purgeNamespaceData → store.delete 该 namespace 所有变量</li>
 *   <li>declared key 影响新建变量 type；未声明 fallback STRING</li>
 *   <li>setVariable 业务异常（QUOTA / VALUE_TOO_LONG）静默不抛</li>
 * </ul>
 */
class HikariCanvasAPIImplTest {

    private FakeUserVariableDao fakeDao;
    private VariableStore store;
    private VariableProviderDaemon daemon;
    private PluginNamespaceRegistry registry;
    private HikariCanvasAPIImpl api;
    /** 0.4.10 P3-4：null jdbi 的 AuditLog —— record() 的 jdbi.useHandle 抛 NPE 被吞，走 log fallback。 */
    private AuditLog auditLog;
    private Plugin pluginA;
    private Plugin pluginB;

    @BeforeEach
    void setUp() {
        fakeDao = new FakeUserVariableDao();
        store = new VariableStore(fakeDao, w -> { });
        daemon = new VariableProviderDaemon();
        registry = new PluginNamespaceRegistry();
        auditLog = new AuditLog(null, Logger.getLogger("test"));
        api = new HikariCanvasAPIImpl(registry, store, daemon,
                new PushRateLimiter(PushRateLimiter.Config.unlimited()), auditLog);
        pluginA = fakePlugin("PluginA");
        pluginB = fakePlugin("PluginB");
    }

    @AfterEach
    void tearDown() {
        daemon.shutdown();
    }

    // ──────────────────────────────────────────────────────────
    //  registerNamespace
    // ──────────────────────────────────────────────────────────

    @Test
    void registerNamespace_succeeds_andCreatesProvider() {
        api.registerNamespace(pluginA, "bedwars",
                new NamespaceInfo("BedWars", "PluginA", "1.0"));
        assertTrue(registry.owns(pluginA, "bedwars"));
        Optional<PluginNamespaceProvider> provider = api.providerOf("bedwars");
        assertTrue(provider.isPresent());
        assertEquals("bedwars", provider.orElseThrow().namespace());
        assertEquals("BedWars", provider.orElseThrow().info().displayName());
        // daemon 内也已 register
        assertTrue(daemon.registeredProviders().stream()
                .anyMatch(p -> "bedwars".equals(p.namespace())));
        // 注册不会向 store 创建变量（仅元信息）
        assertEquals(0, store.size());
    }

    @Test
    void registerNamespace_reserved_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> api.registerNamespace(pluginA, "user",
                        new NamespaceInfo("X", "PluginA", "1.0")));
    }

    @Test
    void registerNamespace_conflict_throws() {
        api.registerNamespace(pluginA, "ns1",
                new NamespaceInfo("A", "PluginA", "1.0"));
        assertThrows(NamespaceConflictException.class,
                () -> api.registerNamespace(pluginB, "ns1",
                        new NamespaceInfo("B", "PluginB", "1.0")));
    }

    @Test
    void registerNamespace_idempotent_sameProviderInstance() {
        api.registerNamespace(pluginA, "ns1",
                new NamespaceInfo("A", "PluginA", "1.0"));
        PluginNamespaceProvider first = api.providerOf("ns1").orElseThrow();
        // 同 plugin 重复 register
        api.registerNamespace(pluginA, "ns1",
                new NamespaceInfo("A2", "PluginA", "1.1"));
        PluginNamespaceProvider second = api.providerOf("ns1").orElseThrow();
        // computeIfAbsent 保留同 provider 实例（避免 daemon 二次注册时机错乱）
        assertSame(first, second);
        // daemon.register 第二次抛 IllegalStateException 被吞掉，daemon 仍只一个 entry
        assertEquals(1, daemon.registeredProviders().size());
    }

    // ──────────────────────────────────────────────────────────
    //  declareKey
    // ──────────────────────────────────────────────────────────

    @Test
    void declareKey_addsToProvider() {
        api.registerNamespace(pluginA, "bedwars",
                new NamespaceInfo("BedWars", "PluginA", "1.0"));
        api.declareKey(pluginA, "bedwars", "red_score",
                moe.hikari.canvas.api.VarType.NUMBER, "红队分数");
        PluginNamespaceProvider provider = api.providerOf("bedwars").orElseThrow();
        assertEquals(1, provider.declaredCount());
        var dk = provider.declaredKeys().get(0);
        assertEquals("red_score", dk.key());
        assertEquals(VarType.NUMBER, dk.type());
        assertEquals("红队分数", dk.description());
    }

    @Test
    void declareKey_unregisteredNamespace_throws() {
        PluginNamespaceException ex = assertThrows(PluginNamespaceException.class,
                () -> api.declareKey(pluginA, "missing", "k",
                        moe.hikari.canvas.api.VarType.STRING, null));
        assertEquals(PluginNamespaceException.Code.NAMESPACE_NOT_REGISTERED, ex.code());
    }

    @Test
    void declareKey_aclDenied_throws() {
        api.registerNamespace(pluginA, "ns1",
                new NamespaceInfo("A", "PluginA", "1.0"));
        PluginNamespaceException ex = assertThrows(PluginNamespaceException.class,
                () -> api.declareKey(pluginB, "ns1", "k",
                        moe.hikari.canvas.api.VarType.STRING, null));
        assertEquals(PluginNamespaceException.Code.NAMESPACE_ACL_DENIED, ex.code());
    }

    // ──────────────────────────────────────────────────────────
    //  setVariable
    // ──────────────────────────────────────────────────────────

    @Test
    void setVariable_firstTime_autoCreatesInStore() {
        api.registerNamespace(pluginA, "bedwars",
                new NamespaceInfo("BedWars", "PluginA", "1.0"));
        api.setVariable(pluginA, "bedwars", "red_score", "5", Duration.ofSeconds(30));
        var v = store.get("bedwars/red_score").orElseThrow();
        assertEquals("5", v.currentValue());
        assertEquals(VarType.STRING, v.type());  // 未 declare → fallback STRING
        assertEquals("PluginA", v.source());
    }

    @Test
    void setVariable_repeated_onlyUpdatesValue() {
        api.registerNamespace(pluginA, "bedwars",
                new NamespaceInfo("BedWars", "PluginA", "1.0"));
        api.setVariable(pluginA, "bedwars", "k", "1", null);
        long created = store.get("bedwars/k").orElseThrow().updatedAt();
        api.setVariable(pluginA, "bedwars", "k", "2", null);
        var v = store.get("bedwars/k").orElseThrow();
        assertEquals("2", v.currentValue());
        // 仍只一个变量（没 create 第二次）
        assertEquals(1, store.size());
        // updatedAt 应推进或不退
        assertTrue(v.updatedAt() >= created);
    }

    @Test
    void setVariable_declaredKeyInfluencesType() {
        api.registerNamespace(pluginA, "bedwars",
                new NamespaceInfo("BedWars", "PluginA", "1.0"));
        api.declareKey(pluginA, "bedwars", "red_score",
                moe.hikari.canvas.api.VarType.NUMBER, "分数");
        api.setVariable(pluginA, "bedwars", "red_score", "10", null);
        assertEquals(VarType.NUMBER,
                store.get("bedwars/red_score").orElseThrow().type());
    }

    @Test
    void setVariable_unregisteredNamespace_throwsNotRegistered() {
        PluginNamespaceException ex = assertThrows(PluginNamespaceException.class,
                () -> api.setVariable(pluginA, "nope", "k", "v", null));
        assertEquals(PluginNamespaceException.Code.NAMESPACE_NOT_REGISTERED, ex.code());
    }

    @Test
    void setVariable_aclDenied_throws() {
        api.registerNamespace(pluginA, "ns1",
                new NamespaceInfo("A", "PluginA", "1.0"));
        PluginNamespaceException ex = assertThrows(PluginNamespaceException.class,
                () -> api.setVariable(pluginB, "ns1", "k", "v", null));
        assertEquals(PluginNamespaceException.Code.NAMESPACE_ACL_DENIED, ex.code());
    }

    @Test
    void setVariable_valueTooLong_silentlyDropped() {
        api.registerNamespace(pluginA, "ns1",
                new NamespaceInfo("A", "PluginA", "1.0"));
        String tooLong = "x".repeat(5000);  // > MAX_VALUE_LENGTH=4096
        // 不抛 — VariableException 被 catch + log
        api.setVariable(pluginA, "ns1", "k", tooLong, null);
        // store 仍空
        assertTrue(store.get("ns1/k").isEmpty());
    }

    // ──────────────────────────────────────────────────────────
    //  setVariables
    // ──────────────────────────────────────────────────────────

    @Test
    void setVariables_batchSucceeds() {
        api.registerNamespace(pluginA, "ns1",
                new NamespaceInfo("A", "PluginA", "1.0"));
        Map<String, VariableUpdate> batch = Map.of(
                "a", new VariableUpdate("1", null),
                "b", new VariableUpdate("2", Duration.ofSeconds(60))
        );
        api.setVariables(pluginA, "ns1", batch);
        assertEquals("1", store.get("ns1/a").orElseThrow().currentValue());
        assertEquals("2", store.get("ns1/b").orElseThrow().currentValue());
    }

    @Test
    void setVariables_emptyMap_isNoop() {
        api.registerNamespace(pluginA, "ns1",
                new NamespaceInfo("A", "PluginA", "1.0"));
        api.setVariables(pluginA, "ns1", Map.of());
        assertEquals(0, store.size());
    }

    @Test
    void setVariables_aclDenied_throws() {
        api.registerNamespace(pluginA, "ns1",
                new NamespaceInfo("A", "PluginA", "1.0"));
        assertThrows(PluginNamespaceException.class,
                () -> api.setVariables(pluginB, "ns1",
                        Map.of("k", new VariableUpdate("v", null))));
    }

    // ──────────────────────────────────────────────────────────
    //  unsetVariable
    // ──────────────────────────────────────────────────────────

    @Test
    void unsetVariable_removesFromStore_keepsDeclaredKey() {
        api.registerNamespace(pluginA, "ns1",
                new NamespaceInfo("A", "PluginA", "1.0"));
        api.declareKey(pluginA, "ns1", "k",
                moe.hikari.canvas.api.VarType.STRING, "hint");
        api.setVariable(pluginA, "ns1", "k", "v", null);
        assertTrue(store.get("ns1/k").isPresent());

        api.unsetVariable(pluginA, "ns1", "k");
        assertTrue(store.get("ns1/k").isEmpty());
        // declared key 仍保留（生命周期与变量值解耦）
        PluginNamespaceProvider provider = api.providerOf("ns1").orElseThrow();
        assertEquals(1, provider.declaredCount());
    }

    @Test
    void unsetVariable_nonexistent_silent() {
        api.registerNamespace(pluginA, "ns1",
                new NamespaceInfo("A", "PluginA", "1.0"));
        // 不抛
        api.unsetVariable(pluginA, "ns1", "never_existed");
    }

    @Test
    void unsetVariable_aclDenied_throws() {
        api.registerNamespace(pluginA, "ns1",
                new NamespaceInfo("A", "PluginA", "1.0"));
        api.setVariable(pluginA, "ns1", "k", "v", null);
        assertThrows(PluginNamespaceException.class,
                () -> api.unsetVariable(pluginB, "ns1", "k"));
        // 原变量未被删
        assertTrue(store.get("ns1/k").isPresent());
    }

    // ──────────────────────────────────────────────────────────
    //  VarType conversion
    // ──────────────────────────────────────────────────────────

    @Test
    void convertVarType_allFourCases() {
        assertEquals(VarType.STRING,
                HikariCanvasAPIImpl.convertVarType(moe.hikari.canvas.api.VarType.STRING));
        assertEquals(VarType.NUMBER,
                HikariCanvasAPIImpl.convertVarType(moe.hikari.canvas.api.VarType.NUMBER));
        assertEquals(VarType.BOOLEAN,
                HikariCanvasAPIImpl.convertVarType(moe.hikari.canvas.api.VarType.BOOLEAN));
        assertEquals(VarType.COLOR,
                HikariCanvasAPIImpl.convertVarType(moe.hikari.canvas.api.VarType.COLOR));
    }

    // ──────────────────────────────────────────────────────────
    //  Q 任务钩子
    // ──────────────────────────────────────────────────────────

    @Test
    void unregisterPluginProviders_removesFromDaemonAndProviders() {
        api.registerNamespace(pluginA, "ns1",
                new NamespaceInfo("A", "PluginA", "1.0"));
        api.registerNamespace(pluginA, "ns2",
                new NamespaceInfo("A", "PluginA", "1.0"));
        assertEquals(2, api.registeredProviderCount());

        api.unregisterPluginProviders(List.of("ns1", "ns2"));
        assertEquals(0, api.registeredProviderCount());
        assertTrue(daemon.registeredProviders().isEmpty());
    }

    @Test
    void unregisterPluginProviders_nullList_noop() {
        api.registerNamespace(pluginA, "ns1",
                new NamespaceInfo("A", "PluginA", "1.0"));
        api.unregisterPluginProviders(null);
        assertEquals(1, api.registeredProviderCount());
    }

    @Test
    void purgeNamespaceData_deletesAllVariablesInNamespace() {
        api.registerNamespace(pluginA, "ns1",
                new NamespaceInfo("A", "PluginA", "1.0"));
        api.setVariable(pluginA, "ns1", "a", "1", null);
        api.setVariable(pluginA, "ns1", "b", "2", null);
        api.setVariable(pluginA, "ns1", "c", "3", null);
        // 同时另一个 namespace 不被波及
        api.registerNamespace(pluginA, "ns2",
                new NamespaceInfo("B", "PluginA", "1.0"));
        api.setVariable(pluginA, "ns2", "untouched", "x", null);
        assertEquals(4, store.size());

        api.purgeNamespaceData("ns1");
        assertEquals(1, store.size());
        assertTrue(store.get("ns2/untouched").isPresent());
    }

    @Test
    void purgeNamespaceData_emptyNamespace_silent() {
        // 不抛
        api.purgeNamespaceData("never_existed");
    }

    // ──────────────────────────────────────────────────────────
    //  constructor null checks
    // ──────────────────────────────────────────────────────────

    @Test
    void constructor_nullArgs_throws() {
        PushRateLimiter fakeLimiter = new PushRateLimiter(PushRateLimiter.Config.unlimited());
        assertThrows(NullPointerException.class,
                () -> new HikariCanvasAPIImpl(null, store, daemon, fakeLimiter, auditLog));
        assertThrows(NullPointerException.class,
                () -> new HikariCanvasAPIImpl(registry, null, daemon, fakeLimiter, auditLog));
        assertThrows(NullPointerException.class,
                () -> new HikariCanvasAPIImpl(registry, store, null, fakeLimiter, auditLog));
        assertThrows(NullPointerException.class,
                () -> new HikariCanvasAPIImpl(registry, store, daemon, null, auditLog));
        assertThrows(NullPointerException.class,
                () -> new HikariCanvasAPIImpl(registry, store, daemon, fakeLimiter, null));
    }

    // ──────────────────────────────────────────────────────────
    //  HikariCanvasAPI surface contract
    // ──────────────────────────────────────────────────────────

    @Test
    void impl_isHikariCanvasAPI() {
        HikariCanvasAPI iface = api;
        assertNotNull(iface);
    }

    // ──────────────────────────────────────────────────────────
    //  Fakes
    // ──────────────────────────────────────────────────────────

    private static final class FakeUserVariableDao extends UserVariableDao {
        FakeUserVariableDao() {
            super(Logger.getLogger("test"), null);
        }
        @Override
        public void upsert(String wallId, String name, VarType type,
                           String defaultValue, String currentValue, String boundTo,
                           long createdAt, long updatedAt) {}
        @Override public void delete(String wallId, String name) {}
        @Override public List<Row> loadAll() { return new ArrayList<>(); }
    }
}
