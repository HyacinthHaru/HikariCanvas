package ac.haru.hikaricanvas.variable.provider;

import ac.haru.hikaricanvas.storage.UserVariableDao;
import ac.haru.hikaricanvas.variable.VarType;
import ac.haru.hikaricanvas.variable.VariableStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.4.0-P3-K：{@link PapiVariableBridge} 单测。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>PAPI 未装时 initialize 不抛 + refreshInterval=ZERO + declaredKeys=empty +
 *       handleDynamic noop + refresh noop</li>
 *   <li>PAPI 装了时 hook 注册 + handleDynamic 编码 + create</li>
 *   <li>encode / decode 编码层（{@code %xxx%} ↔ {@code pct_xxx_pct}）</li>
 *   <li>refresh 调 accessor.resolve + setValue store</li>
 *   <li>refresh accessor 返 null（PAPI 解析失败）→ 不覆盖旧值</li>
 *   <li>interpolator end-to-end miss（disabled，因为 interpolator 不带 papi 编码层；
 *       仅验证 handleDynamic 路径）</li>
 *   <li>shutdown 清 tracked + accessor.shutdown 被调</li>
 * </ul>
 */
class PapiVariableBridgeTest {

    private FakeUserVariableDao fakeDao;
    private VariableStore store;

    @BeforeEach
    void setUp() {
        fakeDao = new FakeUserVariableDao();
        store = new VariableStore(fakeDao, w -> { });
    }

    // ──────────────────────────────────────────────────────────
    //  基础属性
    // ──────────────────────────────────────────────────────────

    @Test
    void namespace_isPapi() {
        var bridge = new PapiVariableBridge(store, new FakePapiAccessor(false));
        assertEquals("papi", bridge.namespace());
    }

    @Test
    void isDynamic_returnsTrue() {
        var bridge = new PapiVariableBridge(store, new FakePapiAccessor(false));
        assertTrue(bridge.isDynamic());
    }

    @Test
    void displayName_returnsPlaceholderAPI() {
        var bridge = new PapiVariableBridge(store, new FakePapiAccessor(false));
        assertEquals("PlaceholderAPI", bridge.displayName());
    }

    // ──────────────────────────────────────────────────────────
    //  PAPI 未装：全 noop
    // ──────────────────────────────────────────────────────────

    @Test
    void papiAbsent_initializeDoesNotThrow() {
        var accessor = new FakePapiAccessor(false);
        var bridge = new PapiVariableBridge(store, accessor);
        bridge.initialize(); // 不抛
        assertFalse(accessor.isAvailable());
    }

    @Test
    void papiAbsent_refreshIntervalIsZero() {
        var bridge = new PapiVariableBridge(store, new FakePapiAccessor(false));
        bridge.initialize();
        assertEquals(Duration.ZERO, bridge.refreshInterval(),
                "未装 PAPI 时 refreshInterval 必须返 ZERO（daemon 不调度）");
    }

    @Test
    void papiAbsent_declaredKeysIsEmpty() {
        var bridge = new PapiVariableBridge(store, new FakePapiAccessor(false));
        bridge.initialize();
        assertTrue(bridge.declaredKeys().isEmpty());
    }

    @Test
    void papiAbsent_handleDynamicNoop() {
        var bridge = new PapiVariableBridge(store, new FakePapiAccessor(false));
        bridge.initialize();
        bridge.handleDynamic("papi/%player_name%");
        assertTrue(bridge.trackedKeysSnapshot().isEmpty(),
                "PAPI 未装时 handleDynamic 必须 noop");
        assertEquals(0, store.size(),
                "PAPI 未装时不应创建任何 store 变量");
    }

    @Test
    void papiAbsent_refreshReturnsFalseAndDoesNotCallAccessor() {
        var accessor = new FakePapiAccessor(false);
        var bridge = new PapiVariableBridge(store, accessor);
        bridge.initialize();
        assertFalse(bridge.refresh());
        assertEquals(0, accessor.resolveCallCount,
                "未装 PAPI 时 refresh 必须 short-circuit 不调 accessor.resolve");
    }

    @Test
    void papiAbsent_initializeDoesNotRegisterHook() {
        var bridge = new PapiVariableBridge(store, new FakePapiAccessor(false));
        bridge.initialize();
        // 走 store.notifyDynamicLookup 模拟 interpolator miss——无 hook 时应无副作用
        store.notifyDynamicLookup("papi/%player_name%");
        assertTrue(bridge.trackedKeysSnapshot().isEmpty());
    }

    // ──────────────────────────────────────────────────────────
    //  PAPI 装了：基础路径
    // ──────────────────────────────────────────────────────────

    @Test
    void papiPresent_initializeRegistersHook() {
        var bridge = new PapiVariableBridge(store, new FakePapiAccessor(true));
        bridge.initialize();
        store.notifyDynamicLookup("papi/%player_name%");
        assertTrue(bridge.trackedKeysSnapshot().contains("pct_player_name_pct"),
                "hook 应被 store.notifyDynamicLookup 触发，编码后加入 tracking");
    }

    @Test
    void papiPresent_refreshIntervalIsFiveSeconds() {
        var bridge = new PapiVariableBridge(store, new FakePapiAccessor(true));
        bridge.initialize();
        assertEquals(Duration.ofMillis(PapiVariableBridge.REFRESH_INTERVAL_MS),
                bridge.refreshInterval());
    }

    // ──────────────────────────────────────────────────────────
    //  handleDynamic 解析路径
    // ──────────────────────────────────────────────────────────

    @Test
    void handleDynamic_slashWithRawPlaceholder_encodesAndCreates() {
        var bridge = new PapiVariableBridge(store, new FakePapiAccessor(true));
        bridge.initialize();
        bridge.handleDynamic("papi/%player_name%");
        assertTrue(store.get("papi/pct_player_name_pct").isPresent(),
                "原文形态应编码后 create");
        assertEquals("%player_name%",
                bridge.placeholderMappingSnapshot().get("pct_player_name_pct"));
    }

    @Test
    void handleDynamic_slashWithEncodedKey_directCreate() {
        var bridge = new PapiVariableBridge(store, new FakePapiAccessor(true));
        bridge.initialize();
        bridge.handleDynamic("papi/pct_player_name_pct");
        assertTrue(store.get("papi/pct_player_name_pct").isPresent());
        assertEquals("%player_name%",
                bridge.placeholderMappingSnapshot().get("pct_player_name_pct"));
    }

    @Test
    void handleDynamic_dotForm_alsoParsed() {
        var bridge = new PapiVariableBridge(store, new FakePapiAccessor(true));
        bridge.initialize();
        bridge.handleDynamic("papi.%player_uuid%");
        assertTrue(store.get("papi/pct_player_uuid_pct").isPresent(),
                "dot alias 形态也应解析");
    }

    @Test
    void handleDynamic_wrongNamespace_ignored() {
        var bridge = new PapiVariableBridge(store, new FakePapiAccessor(true));
        bridge.initialize();
        bridge.handleDynamic("notpapi/%x%");
        assertTrue(bridge.trackedKeysSnapshot().isEmpty());
    }

    @Test
    void handleDynamic_emptyKey_ignored() {
        var bridge = new PapiVariableBridge(store, new FakePapiAccessor(true));
        bridge.initialize();
        bridge.handleDynamic("papi/");
        bridge.handleDynamic("papi/%%"); // length < 3
        assertTrue(bridge.trackedKeysSnapshot().isEmpty());
    }

    @Test
    void handleDynamic_invalidInnerChars_ignored() {
        var bridge = new PapiVariableBridge(store, new FakePapiAccessor(true));
        bridge.initialize();
        // 含空格 / 非法字符 → encodeKey 拒
        bridge.handleDynamic("papi/%player name%");
        assertTrue(bridge.trackedKeysSnapshot().isEmpty());
    }

    @Test
    void handleDynamic_duplicate_idempotent() {
        var bridge = new PapiVariableBridge(store, new FakePapiAccessor(true));
        bridge.initialize();
        bridge.handleDynamic("papi/%player_name%");
        int sizeAfterFirst = store.size();
        bridge.handleDynamic("papi/%player_name%");
        bridge.handleDynamic("papi.%player_name%");
        bridge.handleDynamic("papi/pct_player_name_pct");
        assertEquals(sizeAfterFirst, store.size(),
                "多种形态指向同一 placeholder 应幂等");
        assertEquals(1, bridge.trackedKeysSnapshot().size());
    }

    // ──────────────────────────────────────────────────────────
    //  refresh
    // ──────────────────────────────────────────────────────────

    @Test
    void refresh_callsAccessorAndSetsStoreValue() {
        var accessor = new FakePapiAccessor(true);
        var bridge = new PapiVariableBridge(store, accessor);
        bridge.initialize();
        bridge.handleDynamic("papi/%player_name%");
        accessor.placeholders.put("%player_name%", "Haru");

        assertTrue(bridge.refresh());
        assertEquals("Haru",
                store.get("papi/pct_player_name_pct").get().currentValue());
        assertEquals(1, accessor.resolveCallCount);
    }

    @Test
    void refresh_emptyTracked_returnsFalse() {
        var accessor = new FakePapiAccessor(true);
        var bridge = new PapiVariableBridge(store, accessor);
        bridge.initialize();
        assertFalse(bridge.refresh());
        assertEquals(0, accessor.resolveCallCount);
    }

    @Test
    void refresh_accessorReturnsNull_keepsOldValue() {
        var accessor = new FakePapiAccessor(true);
        var bridge = new PapiVariableBridge(store, accessor);
        bridge.initialize();
        bridge.handleDynamic("papi/%player_name%");
        accessor.placeholders.put("%player_name%", "Haru");
        bridge.refresh();
        assertEquals("Haru",
                store.get("papi/pct_player_name_pct").get().currentValue());

        // 下一轮 PAPI 返 null（plugin 重启 / 短暂失败）→ 保留旧值
        accessor.placeholders.remove("%player_name%");
        bridge.refresh();
        assertEquals("Haru",
                store.get("papi/pct_player_name_pct").get().currentValue(),
                "accessor 返 null 应保留旧 cached，不覆盖");
    }

    @Test
    void refresh_accessorThrows_keepsOldValueAndContinues() {
        var accessor = new FakePapiAccessor(true);
        var bridge = new PapiVariableBridge(store, accessor);
        bridge.initialize();
        bridge.handleDynamic("papi/%first%");
        bridge.handleDynamic("papi/%second%");
        accessor.placeholders.put("%first%", "A");
        accessor.placeholders.put("%second%", "B");
        bridge.refresh();
        assertEquals("A", store.get("papi/pct_first_pct").get().currentValue());
        assertEquals("B", store.get("papi/pct_second_pct").get().currentValue());

        // 让 first 抛异常；second 仍应正常 push
        accessor.throwingPlaceholders.add("%first%");
        accessor.placeholders.put("%second%", "B2");
        bridge.refresh();
        assertEquals("A", store.get("papi/pct_first_pct").get().currentValue(),
                "抛异常的 placeholder 应保留旧值");
        assertEquals("B2", store.get("papi/pct_second_pct").get().currentValue(),
                "未抛的 placeholder 应继续刷新");
    }

    // ──────────────────────────────────────────────────────────
    //  declaredKeys 反映 tracked
    // ──────────────────────────────────────────────────────────

    @Test
    void declaredKeys_reflectsTrackedPlaceholders() {
        var bridge = new PapiVariableBridge(store, new FakePapiAccessor(true));
        bridge.initialize();
        bridge.handleDynamic("papi/%player_name%");
        bridge.handleDynamic("papi/%server_motd%");
        List<DeclaredKey> keys = bridge.declaredKeys();
        assertEquals(2, keys.size());
        for (DeclaredKey k : keys) {
            assertEquals(VarType.STRING, k.type());
            assertEquals(PapiVariableBridge.TTL_MS, k.ttlMs());
            assertNotNull(k.description());
            assertTrue(k.description().startsWith("PAPI: %"),
                    "description 应含原文 placeholder");
        }
    }

    // ──────────────────────────────────────────────────────────
    //  shutdown
    // ──────────────────────────────────────────────────────────

    @Test
    void shutdown_clearsTrackedAndAccessor() {
        var accessor = new FakePapiAccessor(true);
        var bridge = new PapiVariableBridge(store, accessor);
        bridge.initialize();
        bridge.handleDynamic("papi/%x%");
        assertFalse(bridge.trackedKeysSnapshot().isEmpty());

        bridge.shutdown();
        assertTrue(bridge.trackedKeysSnapshot().isEmpty());
        assertTrue(accessor.shutdownCalled,
                "shutdown 应级联调 accessor.shutdown");
    }

    // ──────────────────────────────────────────────────────────
    //  encode / decode 编码层
    // ──────────────────────────────────────────────────────────

    @Test
    void encodeKey_wrapsWithPct() {
        assertEquals("pct_player_name_pct",
                PapiVariableBridge.encodeKey("%player_name%"));
        assertEquals("pct_a_pct",
                PapiVariableBridge.encodeKey("%a%"));
    }

    @Test
    void encodeKey_rejectsInvalidFormat() {
        assertNull(PapiVariableBridge.encodeKey(null));
        assertNull(PapiVariableBridge.encodeKey(""));
        assertNull(PapiVariableBridge.encodeKey("%"));
        assertNull(PapiVariableBridge.encodeKey("%%"));  // length 2 < 3
        assertNull(PapiVariableBridge.encodeKey("no_pct"));
        assertNull(PapiVariableBridge.encodeKey("%has space%"));
        assertNull(PapiVariableBridge.encodeKey("%has\nnewline%"));
    }

    @Test
    void decodeKey_unwrapsPct() {
        assertEquals("%player_name%",
                PapiVariableBridge.decodeKey("pct_player_name_pct"));
        assertEquals("%a%",
                PapiVariableBridge.decodeKey("pct_a_pct"));
    }

    @Test
    void decodeKey_rejectsInvalidFormat() {
        assertNull(PapiVariableBridge.decodeKey(null));
        assertNull(PapiVariableBridge.decodeKey(""));
        assertNull(PapiVariableBridge.decodeKey("pct__pct"));    // inner empty
        assertNull(PapiVariableBridge.decodeKey("no_prefix"));
        assertNull(PapiVariableBridge.decodeKey("pct_xyz"));     // no suffix
    }

    @Test
    void encodeDecode_roundTrip() {
        String[] cases = {"%player_name%", "%server_motd%", "%a_b.c-d%"};
        for (String original : cases) {
            String encoded = PapiVariableBridge.encodeKey(original);
            assertNotNull(encoded, "encode failed for " + original);
            String decoded = PapiVariableBridge.decodeKey(encoded);
            assertEquals(original, decoded,
                    "round-trip failed: " + original);
        }
    }

    // ──────────────────────────────────────────────────────────
    //  Fakes
    // ──────────────────────────────────────────────────────────

    /** 测试用 PapiAccessor：通过构造参数控制 isAvailable，map 模拟 placeholder 解析。 */
    private static final class FakePapiAccessor implements PapiVariableBridge.PapiAccessor {
        final Map<String, String> placeholders = new HashMap<>();
        final java.util.Set<String> throwingPlaceholders = new java.util.HashSet<>();
        int resolveCallCount = 0;
        boolean shutdownCalled = false;
        private final boolean available;

        FakePapiAccessor(boolean available) {
            this.available = available;
        }

        @Override public void initialize() {}
        @Override public boolean isAvailable() { return available; }
        @Override
        public @Nullable String resolve(String placeholder) {
            resolveCallCount++;
            if (throwingPlaceholders.contains(placeholder)) {
                throw new RuntimeException("simulated PAPI failure for " + placeholder);
            }
            return placeholders.get(placeholder);
        }
        @Override public void shutdown() { shutdownCalled = true; }
    }

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
