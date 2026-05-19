package moe.hikari.canvas.variable.plugin;

import moe.hikari.canvas.api.NamespaceConflictException;
import moe.hikari.canvas.api.NamespaceInfo;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.4.0-P4-O：{@link PluginNamespaceRegistry} 单测。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>正常注册 + owns 判定</li>
 *   <li>同 plugin 幂等注册（覆盖 info / 保留 registeredAt）</li>
 *   <li>跨 plugin 注册同 namespace 抛 {@link NamespaceConflictException}</li>
 *   <li>保留 namespace（user / system / papi / scoreboard / schedule）抛
 *       {@link IllegalArgumentException}</li>
 *   <li>非法格式抛 {@link IllegalArgumentException}</li>
 *   <li>unregisterAllByPlugin 返回正确列表 + 清空注册</li>
 *   <li>get 返 Optional 行为</li>
 * </ul>
 */
class PluginNamespaceRegistryTest {

    private PluginNamespaceRegistry registry;
    private Plugin pluginA;
    private Plugin pluginB;

    @BeforeEach
    void setUp() {
        registry = new PluginNamespaceRegistry();
        pluginA = fakePlugin("PluginA");
        pluginB = fakePlugin("PluginB");
    }

    @Test
    void register_basicSuccess_ownsReturnsTrue() {
        PluginNamespaceRegistry.Registration r = registry.register(
                pluginA, "bedwars",
                new NamespaceInfo("BedWars", "PluginA", "1.0"));
        assertEquals("bedwars", registry.get("bedwars").orElseThrow().info().displayName().equals("BedWars")
                ? "bedwars" : null);
        assertTrue(registry.owns(pluginA, "bedwars"));
        assertFalse(registry.owns(pluginB, "bedwars"));
        assertSame(pluginA, r.plugin());
        assertEquals(1, registry.size());
    }

    @Test
    void register_sameNamespaceSamePlugin_isIdempotentAndPreservesRegisteredAt() throws Exception {
        PluginNamespaceRegistry.Registration first = registry.register(
                pluginA, "ns1", new NamespaceInfo("v1", "PluginA", "1.0"));
        long ts1 = first.registeredAt();
        Thread.sleep(2);  // 让 currentTimeMillis 推进至少 1ms
        PluginNamespaceRegistry.Registration second = registry.register(
                pluginA, "ns1", new NamespaceInfo("v2", "PluginA", "2.0"));
        // 保留 registeredAt
        assertEquals(ts1, second.registeredAt(), "同 plugin 重复 register 应保留 registeredAt");
        // 覆盖 info
        assertEquals("v2", registry.get("ns1").orElseThrow().info().displayName());
        assertEquals(1, registry.size());
    }

    @Test
    void register_sameNamespaceDifferentPlugin_throwsConflict() {
        registry.register(pluginA, "ns1", new NamespaceInfo("a", "PluginA", "1.0"));
        NamespaceConflictException ex = assertThrows(NamespaceConflictException.class,
                () -> registry.register(pluginB, "ns1",
                        new NamespaceInfo("b", "PluginB", "1.0")));
        assertEquals("ns1", ex.namespace());
        assertEquals("PluginA", ex.existingPluginName());
        // 原注册不被覆盖
        assertTrue(registry.owns(pluginA, "ns1"));
        assertFalse(registry.owns(pluginB, "ns1"));
    }

    @Test
    void register_reservedNamespaces_throwsIllegalArgument() {
        for (String reserved : PluginNamespaceRegistry.RESERVED_NAMESPACES) {
            assertThrows(IllegalArgumentException.class,
                    () -> registry.register(pluginA, reserved,
                            new NamespaceInfo("x", "PluginA", "1.0")),
                    "reserved namespace should be rejected: " + reserved);
        }
        assertEquals(0, registry.size());
    }

    @Test
    void register_invalidFormat_throwsIllegalArgument() {
        String[] invalid = {
                "",                  // empty
                "1bad",              // 数字开头
                "with space",        // 含空格
                "has:colon",         // 冒号保留给内部 user:<wallId>
                "has-hyphen",        // hyphen 不允许（内部 reserved char）
                "has.dot",           // dot 不允许
                "a".repeat(33),      // 超长
                "中文"               // 非 ASCII
        };
        for (String ns : invalid) {
            assertThrows(IllegalArgumentException.class,
                    () -> registry.register(pluginA, ns,
                            new NamespaceInfo("x", "PluginA", "1.0")),
                    "invalid namespace should be rejected: " + ns);
        }
    }

    @Test
    void register_nullArgs_throwsNpe() {
        assertThrows(NullPointerException.class,
                () -> registry.register(null, "ok", new NamespaceInfo("a", "b", "c")));
        assertThrows(NullPointerException.class,
                () -> registry.register(pluginA, null, new NamespaceInfo("a", "b", "c")));
        assertThrows(NullPointerException.class,
                () -> registry.register(pluginA, "ok", null));
    }

    @Test
    void owns_unregistered_returnsFalse() {
        assertFalse(registry.owns(pluginA, "nonexistent"));
    }

    @Test
    void get_returnsOptionalCorrectly() {
        assertTrue(registry.get("never_registered").isEmpty());
        registry.register(pluginA, "exists", new NamespaceInfo("e", "PluginA", "1.0"));
        assertTrue(registry.get("exists").isPresent());
        assertEquals("PluginA", registry.get("exists").orElseThrow().pluginName());
    }

    @Test
    void unregisterAllByPlugin_removesAllOwnedNamespaces() {
        registry.register(pluginA, "a1", new NamespaceInfo("a1", "PluginA", "1.0"));
        registry.register(pluginA, "a2", new NamespaceInfo("a2", "PluginA", "1.0"));
        registry.register(pluginB, "b1", new NamespaceInfo("b1", "PluginB", "1.0"));
        assertEquals(3, registry.size());

        List<String> removed = registry.unregisterAllByPlugin(pluginA);
        assertEquals(2, removed.size());
        assertTrue(removed.contains("a1"));
        assertTrue(removed.contains("a2"));
        assertFalse(registry.owns(pluginA, "a1"));
        assertFalse(registry.owns(pluginA, "a2"));
        assertTrue(registry.owns(pluginB, "b1"));
        assertEquals(1, registry.size());
    }

    @Test
    void unregisterAllByPlugin_pluginWithoutNamespaces_returnsEmpty() {
        registry.register(pluginA, "ns1", new NamespaceInfo("a", "PluginA", "1.0"));
        List<String> removed = registry.unregisterAllByPlugin(pluginB);
        assertTrue(removed.isEmpty());
        assertEquals(1, registry.size());
    }

    @Test
    void clear_removesAll() {
        registry.register(pluginA, "x", new NamespaceInfo("x", "PluginA", "1.0"));
        registry.register(pluginB, "y", new NamespaceInfo("y", "PluginB", "1.0"));
        registry.clear();
        assertEquals(0, registry.size());
        assertFalse(registry.owns(pluginA, "x"));
    }

    @Test
    void all_returnsImmutableSnapshot() {
        registry.register(pluginA, "ns1", new NamespaceInfo("a", "PluginA", "1.0"));
        var snapshot = registry.all();
        // 再注册不应影响 snapshot
        registry.register(pluginA, "ns2", new NamespaceInfo("b", "PluginA", "1.0"));
        assertEquals(1, snapshot.size());
        assertEquals(2, registry.all().size());
    }

    // ──────────────────────────────────────────────────────────
    //  Fake Plugin via java.lang.reflect.Proxy（避免引入 Mockito）
    // ──────────────────────────────────────────────────────────

    /**
     * 创建只实现 {@code getName()} 的最小 Plugin mock；其他方法返默认值（不会被本测试用到）。
     */
    static Plugin fakePlugin(String name) {
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) return name;
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("toString".equals(method.getName())) return "FakePlugin[" + name + "]";
                    // primitives 默认值
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
