package ac.haru.hikaricanvas.variable;

import ac.haru.hikaricanvas.storage.UserGlobalVariableDao;
import ac.haru.hikaricanvas.storage.UserVariableDao;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VariableStore} 与 {@link VariableDto} 单测。
 *
 * <p>用 {@link FakeUserVariableDao} 在内存里跟踪 upsert / delete 调用。</p>
 */
class VariableStoreTest {

    private FakeUserVariableDao fakeDao;
    private List<String> dirtyWalls;
    private VariableStore store;

    @BeforeEach
    void setUp() {
        fakeDao = new FakeUserVariableDao();
        dirtyWalls = new ArrayList<>();
        store = new VariableStore(fakeDao, dirtyWalls::add);
    }

    // ──────────────────────────────────────────────────────────
    //  基本 CRUD
    // ──────────────────────────────────────────────────────────

    @Test
    void create_and_get_returnsVariable() {
        Variable v = store.create("bedwars", "red_score", VarType.NUMBER, "0", "BedWarsPlugin");
        assertEquals("bedwars", v.namespace());
        assertEquals("red_score", v.key());
        assertEquals(VarType.NUMBER, v.type());
        assertEquals("0", v.defaultValue());
        assertNull(v.currentValue());
        assertEquals("BedWarsPlugin", v.source());
        assertEquals(0L, v.ttl());

        var fetched = store.get("bedwars/red_score").orElseThrow();
        assertEquals(v.fullName(), fetched.fullName());
    }

    @Test
    void create_userNamespacePersists() {
        // user:<wallId> 形式触发持久化
        store.create("user:w-deadbeef", "red_score", VarType.NUMBER, "0", null);
        assertEquals(1, fakeDao.upserts.size());
        var row = fakeDao.upserts.get(0);
        assertEquals("w-deadbeef", row.wallId);
        assertEquals("red_score", row.name);
        assertEquals(VarType.NUMBER, row.type);
        assertEquals("0", row.defaultValue);
    }

    @Test
    void create_pluginNamespaceDoesNotPersist() {
        store.create("bedwars", "red_score", VarType.NUMBER, "0", "BedWarsPlugin");
        assertTrue(fakeDao.upserts.isEmpty(),
                "non-user namespace should not be persisted");
    }

    @Test
    void create_duplicateThrowsVariableExists() {
        store.create("bedwars", "score", VarType.NUMBER, null, null);
        var ex = assertThrows(VariableException.class,
                () -> store.create("bedwars", "score", VarType.STRING, null, null));
        assertEquals(VariableException.Code.VARIABLE_EXISTS, ex.code());
    }

    @Test
    void update_changesTypeAndDefault() {
        store.create("user:w-1", "score", VarType.NUMBER, "0", null);
        Variable updated = store.update("user:w-1/score",
                new VariablePatch(VarType.STRING, "N/A"));
        assertEquals(VarType.STRING, updated.type());
        assertEquals("N/A", updated.defaultValue());
        // persist follow-up
        assertEquals(2, fakeDao.upserts.size());  // create + update
        var lastRow = fakeDao.upserts.get(fakeDao.upserts.size() - 1);
        assertEquals("N/A", lastRow.defaultValue);
    }

    @Test
    void update_missingThrowsNotFound() {
        var ex = assertThrows(VariableException.class,
                () -> store.update("ghost/x", new VariablePatch(VarType.STRING, "v")));
        assertEquals(VariableException.Code.VARIABLE_NOT_FOUND, ex.code());
    }

    @Test
    void setValue_updatesCurrent() {
        store.create("bedwars", "score", VarType.NUMBER, "0", null);
        store.setValue("bedwars/score", "5", Duration.ofMinutes(5));
        var v = store.get("bedwars/score").orElseThrow();
        assertEquals("5", v.currentValue());
        assertEquals(Duration.ofMinutes(5).toMillis(), v.ttl());
    }

    @Test
    void setValue_nullTtlKeepsExisting() {
        store.create("bedwars", "score", VarType.NUMBER, "0", null);
        store.setValue("bedwars/score", "5", Duration.ofMinutes(5));
        store.setValue("bedwars/score", "7", null);
        var v = store.get("bedwars/score").orElseThrow();
        assertEquals("7", v.currentValue());
        assertEquals(Duration.ofMinutes(5).toMillis(), v.ttl(),
                "null ttl should preserve previous ttl");
    }

    @Test
    void delete_removesAndCleansIndex() {
        store.create("user:w-1", "v", VarType.STRING, null, null);
        store.markWallReferences("w-1", Set.of("user:w-1/v"));
        store.delete("user:w-1/v");
        assertTrue(store.get("user:w-1/v").isEmpty());
        // user 变量级联删 DB
        assertEquals(1, fakeDao.deletes.size());
        assertEquals("w-1", fakeDao.deletes.get(0).wallId);
        // wall 倒排索引也清掉
        assertFalse(store.referencedFullNamesByWall("w-1").contains("user:w-1/v"));
    }

    @Test
    void delete_missingThrowsNotFound() {
        assertThrows(VariableException.class, () -> store.delete("ghost/x"));
    }

    @Test
    void bind_updatesSourceAndPersistsForUserNamespace() {
        store.create("user:w-1", "score", VarType.NUMBER, "0", null);
        store.bind("user:w-1/score", "BedWarsPlugin");
        var v = store.get("user:w-1/score").orElseThrow();
        assertEquals("BedWarsPlugin", v.source());
        // upsert 走到 DB 一次（create）+ 一次（bind）
        assertEquals(2, fakeDao.upserts.size());
        assertEquals("BedWarsPlugin", fakeDao.upserts.get(1).boundTo);
    }

    // ──────────────────────────────────────────────────────────
    //  list 系列
    // ──────────────────────────────────────────────────────────

    @Test
    void listByNamespace_returnsOnlyMatching() {
        store.create("bedwars", "a", VarType.STRING, null, null);
        store.create("bedwars", "b", VarType.STRING, null, null);
        store.create("trains", "x", VarType.STRING, null, null);
        assertEquals(2, store.listByNamespace("bedwars").size());
        assertEquals(1, store.listByNamespace("trains").size());
        assertEquals(0, store.listByNamespace("absent").size());
    }

    /**
     * 删最后一个变量会把空桶从 byNamespace 摘掉（防 per-wall namespace 的桶永久驻留），
     * 但"判空 + 摘桶"必须对并发 create 原子。
     */
    @Test
    void namespaceBucket_removalIsAtomicAgainstConcurrentCreate() throws Exception {
        final String ns = "user:w-race001";
        final int rounds = 2000;
        Thread deleter = new Thread(() -> {
            for (int i = 0; i < rounds; i++) {
                try {
                    store.create(ns, "tmp" + i, VarType.STRING, null, null);
                    store.delete(ns + "/tmp" + i);
                } catch (VariableException ignored) {
                    // 与另一线程撞名 / 已删 —— 本测试只关心索引一致性
                }
            }
        });
        Thread creator = new Thread(() -> {
            for (int i = 0; i < rounds; i++) {
                try {
                    store.create(ns, "keep" + i, VarType.STRING, null, null);
                } catch (VariableException ignored) {
                }
            }
        });
        deleter.start();
        creator.start();
        deleter.join();
        creator.join();

        long alive = store.listAll().stream()
                .filter(v -> v.namespace().equals(ns))
                .count();
        assertEquals(alive, store.listByNamespace(ns).size(),
                "倒排索引与 store 必须一致——丢桶会让这些变量在 listByNamespace 里凭空消失");
    }

    @Test
    void listByWall_followsMarkedReferences() {
        store.create("bedwars", "score", VarType.NUMBER, null, null);
        store.create("system", "online", VarType.NUMBER, null, null);
        store.markWallReferences("w-1", Set.of("bedwars/score", "system/online"));
        store.markWallReferences("w-2", Set.of("system/online"));
        assertEquals(2, store.listByWall("w-1").size());
        assertEquals(1, store.listByWall("w-2").size());
        assertEquals(0, store.listByWall("w-empty").size());
    }

    // ──────────────────────────────────────────────────────────
    //  listVisibleToWall（ready payload 可见集）
    // ──────────────────────────────────────────────────────────

    @Test
    void listVisibleToWall_includesGlobalNamespaces() {
        // 全局 namespace（不含 ':'）→ 任何 wall 可见
        store.create("system", "online", VarType.NUMBER, null, null);
        store.create("papi", "balance", VarType.STRING, null, null);
        store.create("scoreboard", "kills", VarType.NUMBER, null, null);
        store.create("bedwars", "score", VarType.NUMBER, null, null);
        var visible = store.listVisibleToWall("w-1");
        assertEquals(4, visible.size());
    }

    @Test
    void listVisibleToWall_includesOwnPerWallNamespaces() {
        // per-wall namespace（X:wallId 形式）只匹配本 wall
        store.create("user:w-1", "score", VarType.NUMBER, null, null);
        store.create("schedule:w-1", "next", VarType.STRING, null, null);
        store.create("user:w-2", "other", VarType.STRING, null, null);
        var visible = store.listVisibleToWall("w-1");
        assertEquals(2, visible.size());
        assertTrue(visible.stream().anyMatch(v -> v.fullName().equals("user:w-1/score")));
        assertTrue(visible.stream().anyMatch(v -> v.fullName().equals("schedule:w-1/next")));
    }

    @Test
    void listVisibleToWall_excludesOtherWallsPerWallNamespaces() {
        store.create("user:w-1", "secret", VarType.STRING, null, null);
        var visible = store.listVisibleToWall("w-2");
        assertEquals(0, visible.size());
    }

    @Test
    void listVisibleToWall_emptyStoreReturnsEmpty() {
        assertEquals(0, store.listVisibleToWall("w-any").size());
    }

    @Test
    void listVisibleToWall_doesNotDependOnByWallIndex() {
        // 即使 Compositor 尚未 markWallReferences，listVisibleToWall 仍能返回 wall 可见变量
        store.create("system", "online", VarType.NUMBER, null, null);
        store.create("schedule:w-1", "next", VarType.STRING, null, null);
        assertEquals(0, store.listByWall("w-1").size(), "byWall index empty");
        assertEquals(2, store.listVisibleToWall("w-1").size(),
                "listVisibleToWall works without markWallReferences");
    }

    // ──────────────────────────────────────────────────────────
    //  create 反查 byWall 索引
    // ──────────────────────────────────────────────────────────

    @Test
    void create_inheritsReferencedByWallsFromByWallIndex() {
        // Compositor 先 markWallReferences("w-1", {"schedule:w-1/eta"})——此时变量未 create，
        // markWallReferences 把 "schedule:w-1/eta" 加进 byWall["w-1"] 但 addWallToReferencedSet
        // 因变量不存在被 noop。
        store.markWallReferences("w-1", Set.of("schedule:w-1/eta"));
        // 然后 ManualScheduleProvider.ensureWallRegistered 触发 create
        store.create("schedule:w-1", "eta", VarType.NUMBER, null, "schedule");
        var v = store.get("schedule:w-1/eta").orElseThrow();
        assertTrue(v.referencedByWalls().contains("w-1"),
                "create 必须反查 byWall，把已引用我的 wall 注入 referencedByWalls");
    }

    @Test
    void create_inheritsReferencesFromMultipleWalls() {
        // 多个 wall 都引用同一插件变量；create 时全部继承
        store.markWallReferences("w-1", Set.of("bedwars/score"));
        store.markWallReferences("w-2", Set.of("bedwars/score"));
        store.markWallReferences("w-3", Set.of("other/var"));
        store.create("bedwars", "score", VarType.NUMBER, null, null);
        var v = store.get("bedwars/score").orElseThrow();
        assertTrue(v.referencedByWalls().contains("w-1"));
        assertTrue(v.referencedByWalls().contains("w-2"));
        assertFalse(v.referencedByWalls().contains("w-3"));
    }

    @Test
    void setValue_afterCreateInheritedRefs_triggersDirtyCallback() {
        // 验证完整链路：mark → create → setValue → wallDirtyCallback 被调
        store.markWallReferences("w-A", Set.of("schedule:w-A/eta"));
        store.create("schedule:w-A", "eta", VarType.NUMBER, null, "schedule");
        dirtyWalls.clear();
        store.setValue("schedule:w-A/eta", "30", null);
        assertTrue(dirtyWalls.contains("w-A"),
                "create 反查 byWall 后，setValue 必须能触发 wallDirtyCallback");
    }

    // ──────────────────────────────────────────────────────────
    //  Wall dirty 触发
    // ──────────────────────────────────────────────────────────

    @Test
    void setValue_triggersWallDirtyCallback() {
        store.create("bedwars", "score", VarType.NUMBER, null, null);
        store.markWallReferences("w-A", Set.of("bedwars/score"));
        store.markWallReferences("w-B", Set.of("bedwars/score"));

        dirtyWalls.clear();
        store.setValue("bedwars/score", "5", null);
        assertEquals(2, dirtyWalls.size(),
                "both referencing walls should be marked dirty");
        assertTrue(dirtyWalls.contains("w-A"));
        assertTrue(dirtyWalls.contains("w-B"));
    }

    @Test
    void create_doesNotTriggerDirtyCallback() {
        // 刚创建无 referencer，按设计不触发 dirty
        store.create("bedwars", "score", VarType.NUMBER, null, null);
        assertTrue(dirtyWalls.isEmpty(),
                "create should not trigger dirty (no referencers yet)");
    }

    @Test
    void clearWallReferences_removesFromInvertedIndex() {
        store.create("bedwars", "score", VarType.NUMBER, null, null);
        store.markWallReferences("w-1", Set.of("bedwars/score"));
        store.clearWallReferences("w-1");
        // 之后 setValue 不再触发 w-1
        store.setValue("bedwars/score", "x", null);
        assertFalse(dirtyWalls.contains("w-1"));
    }

    // ──────────────────────────────────────────────────────────
    //  高频 wall 判断
    // ──────────────────────────────────────────────────────────

    @Test
    void isWallHighFreq_nullOrEmptyReturnsFalse() {
        assertFalse(store.isWallHighFreq(null), "null wallId → false");
        assertFalse(store.isWallHighFreq("w-empty"),
                "未引用任何变量 → false");
    }

    @Test
    void isWallHighFreq_onlyUserVariablesReturnsFalse() {
        // user 变量 / 普通插件变量都不算高频
        store.markWallReferences("w-1",
                Set.of("user:w-1/score", "bedwars/red_score"));
        assertFalse(store.isWallHighFreq("w-1"),
                "user 变量 + 普通插件变量都不算高频");
    }

    @Test
    void isWallHighFreq_scheduleEtaSecondsTriggers() {
        store.markWallReferences("w-A",
                Set.of("schedule:w-A/eta_seconds", "user:w-A/title"));
        assertTrue(store.isWallHighFreq("w-A"),
                "含 schedule:wallId/eta_seconds 应判为高频");
    }

    @Test
    void isWallHighFreq_arrivalStatusTriggers() {
        store.markWallReferences("w-B",
                Set.of("schedule:w-B/arrival_status"));
        assertTrue(store.isWallHighFreq("w-B"),
                "arrival_status 后缀应判为高频");
        store.markWallReferences("w-C",
                Set.of("schedule:w-C/next2_eta_mmss"));
        assertTrue(store.isWallHighFreq("w-C"),
                "next2_eta_mmss 后缀应判为高频");
    }

    @Test
    void isWallHighFreq_systemServerTickTriggers() {
        store.markWallReferences("w-tick",
                Set.of("system/server.tick"));
        assertTrue(store.isWallHighFreq("w-tick"),
                "system/server.tick 全局变量应判为高频");
    }

    @Test
    void isWallHighFreq_slowScheduleKeysReturnFalse() {
        // schedule 变量但不是秒级 key（譬如 eta_minutes / next_origin 等假设非高频）
        store.markWallReferences("w-slow",
                Set.of("schedule:w-slow/eta_minutes", "schedule:w-slow/next_origin"));
        assertFalse(store.isWallHighFreq("w-slow"),
                "schedule 但非秒级 key 不算高频");
    }

    @Test
    void markWallReferences_firesWallRefsUpdatedListener() {
        // listener 必须能监听到 markWallReferences 后引用集合变化
        java.util.List<VariableStore.VariableChangeEvent> events = new java.util.ArrayList<>();
        store.registerChangeListener(events::add);
        store.markWallReferences("w-1", Set.of("schedule:w-1/eta_seconds"));
        assertTrue(events.stream().anyMatch(e -> e.type()
                        == VariableStore.ChangeType.WALL_REFS_UPDATED
                && e.referencingWalls().contains("w-1")),
                "markWallReferences 后必须 fire WALL_REFS_UPDATED");
    }

    @Test
    void markWallReferences_noopWhenSetUnchangedDoesNotFire() {
        // 重复 mark 同样的集合 → 不 fire（避免风暴）
        store.markWallReferences("w-1", Set.of("schedule:w-1/eta_seconds"));
        java.util.List<VariableStore.VariableChangeEvent> events = new java.util.ArrayList<>();
        store.registerChangeListener(events::add);
        store.markWallReferences("w-1", Set.of("schedule:w-1/eta_seconds"));
        assertTrue(events.isEmpty(),
                "引用集合无变化时不应 fire WALL_REFS_UPDATED");
    }

    // ──────────────────────────────────────────────────────────
    //  loadFromDb
    // ──────────────────────────────────────────────────────────

    @Test
    void loadFromDb_rehydratesUserNamespaceVariables() {
        // 重启场景：DAO 里有 1 个旧变量，store 从空开始。
        // 注：key 受 [a-zA-Z0-9_.-]+ 校验，用 ASCII；defaultValue / currentValue 是任意 UTF-8 字符串。
        fakeDao.preload.add(new UserVariableDao.Row(
                "w-saved", "announcement", VarType.STRING, "默认公告", "Hello", null,
                10_000L, 20_000L));
        store.loadFromDb();
        var v = store.get("user:w-saved/announcement").orElseThrow();
        assertEquals("默认公告", v.defaultValue());
        assertEquals("Hello", v.currentValue());
        assertEquals(VarType.STRING, v.type());
    }

    @Test
    void loadFromDb_skipsBadRows() {
        fakeDao.preload.add(new UserVariableDao.Row(
                "w-1", "valid", VarType.STRING, null, null, null, 1L, 1L));
        // 非法 key（含空格）
        fakeDao.preload.add(new UserVariableDao.Row(
                "w-1", "bad key with space", VarType.STRING, null, null, null, 1L, 1L));
        store.loadFromDb();
        assertEquals(1, store.size(), "bad row should be skipped");
        assertNotNull(store.get("user:w-1/valid").orElse(null));
    }

    // ──────────────────────────────────────────────────────────
    //  边界 / 错误码
    // ──────────────────────────────────────────────────────────

    @Test
    void create_invalidNamespaceThrowsNameInvalid() {
        var ex = assertThrows(VariableException.class,
                () -> store.create("123-bad", "x", VarType.STRING, null, null));
        assertEquals(VariableException.Code.VARIABLE_NAME_INVALID, ex.code());
    }

    @Test
    void create_invalidKeyThrowsNameInvalid() {
        var ex = assertThrows(VariableException.class,
                () -> store.create("bedwars", "bad key", VarType.STRING, null, null));
        assertEquals(VariableException.Code.VARIABLE_NAME_INVALID, ex.code());
    }

    @Test
    void create_oversizeDefaultThrowsValueTooLong() {
        String huge = "x".repeat(VariableStore.MAX_VALUE_LENGTH + 1);
        var ex = assertThrows(VariableException.class,
                () -> store.create("bedwars", "k", VarType.STRING, huge, null));
        assertEquals(VariableException.Code.VARIABLE_VALUE_TOO_LONG, ex.code());
    }

    @Test
    void setValue_oversizeThrowsValueTooLong() {
        store.create("bedwars", "k", VarType.STRING, null, null);
        String huge = "x".repeat(VariableStore.MAX_VALUE_LENGTH + 1);
        var ex = assertThrows(VariableException.class,
                () -> store.setValue("bedwars/k", huge, null));
        assertEquals(VariableException.Code.VARIABLE_VALUE_TOO_LONG, ex.code());
    }

    @Test
    void setValue_subMinTtlThrowsTtlInvalid() {
        store.create("bedwars", "k", VarType.STRING, null, null);
        var ex = assertThrows(VariableException.class,
                () -> store.setValue("bedwars/k", "v", Duration.ofMillis(50)));
        assertEquals(VariableException.Code.TTL_INVALID, ex.code());
    }

    @Test
    void setValue_zeroTtlIsAllowedAsPermanent() {
        store.create("bedwars", "k", VarType.STRING, null, null);
        store.setValue("bedwars/k", "v", Duration.ZERO);
        assertEquals(0L, store.get("bedwars/k").orElseThrow().ttl());
    }

    @Test
    void setValue_negativeTtlThrowsTtlInvalid() {
        store.create("bedwars", "k", VarType.STRING, null, null);
        var ex = assertThrows(VariableException.class,
                () -> store.setValue("bedwars/k", "v", Duration.ofMillis(-10)));
        assertEquals(VariableException.Code.TTL_INVALID, ex.code());
    }

    @Test
    void create_perNamespaceQuotaTriggers() {
        // 不实际跑 1000 个；改测 size() 行为：填满 1000 后再 create 抛配额
        // 直接测边界：分配比 quota 多 1，最后一次抛
        int quota = VariableStore.MAX_PER_NAMESPACE;
        for (int i = 0; i < quota; i++) {
            store.create("bedwars", "k" + i, VarType.STRING, null, null);
        }
        var ex = assertThrows(VariableException.class,
                () -> store.create("bedwars", "overflow", VarType.STRING, null, null));
        assertEquals(VariableException.Code.QUOTA_EXCEEDED, ex.code());
    }

    @Test
    void markWallReferences_updatesReferencedByWallsField() {
        store.create("bedwars", "score", VarType.NUMBER, null, null);
        store.markWallReferences("w-1", Set.of("bedwars/score"));
        var v = store.get("bedwars/score").orElseThrow();
        assertTrue(v.referencedByWalls().contains("w-1"));

        // 再次 mark 不含 w-1 → 应清出
        store.markWallReferences("w-1", Set.of());
        v = store.get("bedwars/score").orElseThrow();
        assertFalse(v.referencedByWalls().contains("w-1"));
    }

    @Test
    void variable_isStale_zeroTtlIsPermanent() {
        Variable v = new Variable("ns", "k", VarType.STRING, null, null,
                0L, 0L, null, Set.of());
        assertFalse(v.isStale(Long.MAX_VALUE));
    }

    // ──────────────────────────────────────────────────────────
    //  ChangeListener 广播
    // ──────────────────────────────────────────────────────────

    @Test
    void changeListener_create_firesCreatedEvent() {
        List<VariableStore.VariableChangeEvent> events = new ArrayList<>();
        store.registerChangeListener(events::add);

        store.create("bedwars", "score", VarType.NUMBER, "0", "BedWars");

        assertEquals(1, events.size());
        var e = events.get(0);
        assertEquals(VariableStore.ChangeType.CREATED, e.type());
        assertEquals("bedwars/score", e.fullName());
        assertNotNull(e.variable());
        assertEquals("0", e.variable().defaultValue());
        // 新建变量初始 referencedByWalls 空 → event.referencingWalls() 空
        assertTrue(e.referencingWalls().isEmpty());
    }

    @Test
    void changeListener_setValue_firesValueSetEvent() {
        store.create("bedwars", "score", VarType.NUMBER, "0", null);
        List<VariableStore.VariableChangeEvent> events = new ArrayList<>();
        store.registerChangeListener(events::add);

        store.markWallReferences("w-1", Set.of("bedwars/score"));
        store.setValue("bedwars/score", "42", null);

        // VALUE_SET 事件应被触发，referencingWalls 含 w-1
        var valueSetEvents = events.stream()
                .filter(ev -> ev.type() == VariableStore.ChangeType.VALUE_SET)
                .toList();
        assertEquals(1, valueSetEvents.size());
        var e = valueSetEvents.get(0);
        assertEquals("bedwars/score", e.fullName());
        assertEquals("42", e.variable().currentValue());
        assertTrue(e.referencingWalls().contains("w-1"),
                "VALUE_SET 应携带 referencingWalls 用于路由广播");
    }

    @Test
    void changeListener_delete_firesDeletedEventWithNullVariableAndPreservedWalls() {
        store.create("bedwars", "score", VarType.NUMBER, null, null);
        store.markWallReferences("w-1", Set.of("bedwars/score"));
        store.markWallReferences("w-2", Set.of("bedwars/score"));
        List<VariableStore.VariableChangeEvent> events = new ArrayList<>();
        store.registerChangeListener(events::add);

        store.delete("bedwars/score");

        var deletedEvents = events.stream()
                .filter(ev -> ev.type() == VariableStore.ChangeType.DELETED)
                .toList();
        assertEquals(1, deletedEvents.size());
        var e = deletedEvents.get(0);
        assertEquals("bedwars/score", e.fullName());
        assertNull(e.variable(), "DELETED 事件 variable 应为 null");
        // 删除前的 referencingWalls 应保留在 event 里（否则 listener 无法路由广播给该 wall）
        assertTrue(e.referencingWalls().contains("w-1"));
        assertTrue(e.referencingWalls().contains("w-2"));
    }

    @Test
    void changeListener_update_firesUpdatedEvent() {
        store.create("bedwars", "score", VarType.NUMBER, "0", null);
        List<VariableStore.VariableChangeEvent> events = new ArrayList<>();
        store.registerChangeListener(events::add);

        store.update("bedwars/score", new VariablePatch(VarType.STRING, "default-new"));

        var updatedEvents = events.stream()
                .filter(ev -> ev.type() == VariableStore.ChangeType.UPDATED)
                .toList();
        assertEquals(1, updatedEvents.size());
        var e = updatedEvents.get(0);
        assertEquals(VarType.STRING, e.variable().type());
        assertEquals("default-new", e.variable().defaultValue());
    }

    @Test
    void changeListener_bind_firesBoundEvent() {
        store.create("user:w-1", "score", VarType.NUMBER, null, null);
        List<VariableStore.VariableChangeEvent> events = new ArrayList<>();
        store.registerChangeListener(events::add);

        store.bind("user:w-1/score", "BedWarsPlugin");

        var boundEvents = events.stream()
                .filter(ev -> ev.type() == VariableStore.ChangeType.BOUND)
                .toList();
        assertEquals(1, boundEvents.size());
        assertEquals("BedWarsPlugin", boundEvents.get(0).variable().source());
    }

    @Test
    void changeListener_throwingListener_doesNotAbortOthers() {
        java.util.concurrent.atomic.AtomicInteger goodCalls = new java.util.concurrent.atomic.AtomicInteger();
        store.registerChangeListener(e -> { throw new RuntimeException("simulated crash"); });
        store.registerChangeListener(e -> goodCalls.incrementAndGet());

        // 主路径不被打断；第二个 listener 仍被调
        store.create("bedwars", "k", VarType.STRING, null, null);
        assertEquals(1, goodCalls.get(),
                "listener 抛异常应被吞掉，不影响其他 listener");
        // store 本身不受影响
        assertNotNull(store.get("bedwars/k").orElse(null));
    }

    @Test
    void changeListener_multipleListeners_calledInOrder() {
        List<String> order = new ArrayList<>();
        store.registerChangeListener(e -> order.add("A"));
        store.registerChangeListener(e -> order.add("B"));
        store.create("bedwars", "k", VarType.STRING, null, null);
        assertEquals(List.of("A", "B"), order);
    }

    @Test
    void variable_isStale_ttlExceeded() {
        long now = System.currentTimeMillis();
        Variable v = new Variable("ns", "k", VarType.STRING, null, null,
                now - 60_000L, 30_000L, null, Set.of());
        assertTrue(v.isStale(now));
    }

    // ──────────────────────────────────────────────────────────
    //  全局用户变量（userglobal/*）
    // ──────────────────────────────────────────────────────────

    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void createGlobal_throwsIfDaoNotConfigured() {
        // store 默认 globalDao = null → createGlobal 立即拒
        var ex = assertThrows(VariableException.class,
                () -> store.createGlobal(ALICE, "Alice", "red_score", VarType.NUMBER, "0"));
        assertEquals(VariableException.Code.QUOTA_EXCEEDED, ex.code());
    }

    @Test
    void createGlobal_persistsAndRegistersOwner() {
        FakeUserGlobalVariableDao globalDao = new FakeUserGlobalVariableDao();
        store.configureUserGlobal(globalDao, 500, 10_000);

        Variable v = store.createGlobal(ALICE, "Alice", "red_score", VarType.NUMBER, "0");

        assertEquals("userglobal", v.namespace());
        assertEquals("red_score", v.key());
        assertEquals(VarType.NUMBER, v.type());
        assertEquals("userglobal/red_score", v.fullName());

        // DB upsert 写入
        assertEquals(1, globalDao.upserts.size());
        var row = globalDao.upserts.get(0);
        assertEquals("red_score", row.name);
        assertEquals(ALICE, row.ownerUuid);
        assertEquals("Alice", row.ownerName);
        assertEquals(VarType.NUMBER, row.type);
        assertEquals("0", row.defaultValue);

        // 内存 owner 表注册
        var info = store.getGlobalOwner("red_score").orElseThrow();
        assertEquals(ALICE, info.ownerUuid());
        assertEquals("Alice", info.ownerName());
    }

    @Test
    void createGlobal_perOwnerQuotaEnforced() {
        FakeUserGlobalVariableDao globalDao = new FakeUserGlobalVariableDao();
        store.configureUserGlobal(globalDao, 2, 10_000);  // per-owner = 2
        store.createGlobal(ALICE, "Alice", "a", VarType.STRING, null);
        store.createGlobal(ALICE, "Alice", "b", VarType.STRING, null);
        // 第 3 次拒（per-owner 配额）
        var ex = assertThrows(VariableException.class,
                () -> store.createGlobal(ALICE, "Alice", "c", VarType.STRING, null));
        assertEquals(VariableException.Code.QUOTA_EXCEEDED, ex.code());
        assertTrue(ex.getMessage().contains("owner"));
    }

    @Test
    void createGlobal_globalQuotaEnforced() {
        FakeUserGlobalVariableDao globalDao = new FakeUserGlobalVariableDao();
        store.configureUserGlobal(globalDao, 100, 2);  // global = 2
        store.createGlobal(ALICE, "Alice", "a", VarType.STRING, null);
        store.createGlobal(BOB, "Bob", "b", VarType.STRING, null);
        // 第 3 次拒（global 配额）
        var ex = assertThrows(VariableException.class,
                () -> store.createGlobal(ALICE, "Alice", "c", VarType.STRING, null));
        assertEquals(VariableException.Code.QUOTA_EXCEEDED, ex.code());
        assertTrue(ex.getMessage().contains("globally"));
    }

    @Test
    void createGlobal_duplicateNameRejected() {
        FakeUserGlobalVariableDao globalDao = new FakeUserGlobalVariableDao();
        store.configureUserGlobal(globalDao, 500, 10_000);
        store.createGlobal(ALICE, "Alice", "shared", VarType.STRING, null);
        // 即使是别的玩家也不能用同 key 抢占
        var ex = assertThrows(VariableException.class,
                () -> store.createGlobal(BOB, "Bob", "shared", VarType.STRING, null));
        assertEquals(VariableException.Code.VARIABLE_EXISTS, ex.code());
    }

    @Test
    void deleteGlobal_removesOwnerEntryAndPersistsDelete() {
        FakeUserGlobalVariableDao globalDao = new FakeUserGlobalVariableDao();
        store.configureUserGlobal(globalDao, 500, 10_000);
        store.createGlobal(ALICE, "Alice", "shared", VarType.STRING, "x");
        assertTrue(store.getGlobalOwner("shared").isPresent());

        store.delete("userglobal/shared");

        assertFalse(store.get("userglobal/shared").isPresent());
        assertFalse(store.getGlobalOwner("shared").isPresent());
        assertEquals(1, globalDao.deletes.size());
        assertEquals("shared", globalDao.deletes.get(0));
    }

    @Test
    void listGlobals_returnsOnlyUserGlobalNamespace() {
        FakeUserGlobalVariableDao globalDao = new FakeUserGlobalVariableDao();
        store.configureUserGlobal(globalDao, 500, 10_000);
        store.createGlobal(ALICE, "Alice", "red", VarType.STRING, null);
        store.create("bedwars", "score", VarType.NUMBER, null, null);  // 干扰项
        store.create("user:w-1", "local_red", VarType.STRING, null, null);  // 干扰项

        List<Variable> globals = store.listGlobals();
        assertEquals(1, globals.size());
        assertEquals("userglobal", globals.get(0).namespace());
        assertEquals("red", globals.get(0).key());
    }

    @Test
    void loadGlobalsFromDb_restoresOwnersAndVariables() {
        FakeUserGlobalVariableDao globalDao = new FakeUserGlobalVariableDao();
        globalDao.preload.add(new UserGlobalVariableDao.Row(
                "shared", ALICE, "Alice", VarType.STRING, "default", "current", null, 1L, 2L));
        store.configureUserGlobal(globalDao, 500, 10_000);
        store.loadGlobalsFromDb();

        var v = store.get("userglobal/shared").orElseThrow();
        assertEquals(VarType.STRING, v.type());
        assertEquals("default", v.defaultValue());
        assertEquals("current", v.currentValue());
        // owner 表也恢复
        var info = store.getGlobalOwner("shared").orElseThrow();
        assertEquals(ALICE, info.ownerUuid());
        assertEquals("Alice", info.ownerName());
    }

    @Test
    void setValueGlobal_persistsValue() {
        FakeUserGlobalVariableDao globalDao = new FakeUserGlobalVariableDao();
        store.configureUserGlobal(globalDao, 500, 10_000);
        store.createGlobal(ALICE, "Alice", "score", VarType.NUMBER, "0");
        globalDao.upserts.clear();

        store.setValue("userglobal/score", "42", null);

        assertEquals(1, globalDao.upserts.size());
        assertEquals("42", globalDao.upserts.get(0).currentValue);
    }

    @Test
    void listVisibleToWall_includesUserGlobalForAnyWall() {
        FakeUserGlobalVariableDao globalDao = new FakeUserGlobalVariableDao();
        store.configureUserGlobal(globalDao, 500, 10_000);
        store.createGlobal(ALICE, "Alice", "shared", VarType.STRING, null);

        var visibleA = store.listVisibleToWall("w-A");
        var visibleB = store.listVisibleToWall("w-B");
        assertTrue(visibleA.stream().anyMatch(v -> "userglobal".equals(v.namespace())));
        assertTrue(visibleB.stream().anyMatch(v -> "userglobal".equals(v.namespace())));
    }

    @Test
    void createGlobal_inheritsReferencedByWalls_fromMarkWallReferences() {
        FakeUserGlobalVariableDao globalDao = new FakeUserGlobalVariableDao();
        store.configureUserGlobal(globalDao, 500, 10_000);

        store.markWallReferences("w-A", Set.of("userglobal/red_score"));

        Variable v = store.createGlobal(ALICE, "Alice", "red_score", VarType.NUMBER, "0");
        assertTrue(v.referencedByWalls().contains("w-A"),
                "createGlobal should inject initialRefs from byWall inverted index");
    }

    @Test
    void changeListener_firedForGlobalCreate() {
        FakeUserGlobalVariableDao globalDao = new FakeUserGlobalVariableDao();
        store.configureUserGlobal(globalDao, 500, 10_000);
        List<VariableStore.VariableChangeEvent> events = new ArrayList<>();
        store.registerChangeListener(events::add);
        store.createGlobal(ALICE, "Alice", "shared", VarType.STRING, "x");

        assertEquals(1, events.size());
        assertEquals(VariableStore.ChangeType.CREATED, events.get(0).type());
        assertEquals("userglobal/shared", events.get(0).fullName());
    }

    // ──────────────────────────────────────────────────────────
    //  statsByNamespace & VariableDto wire 视图
    // ──────────────────────────────────────────────────────────

    @Test
    void statsByNamespace_emptyStore_returnsEmptyMap() {
        assertTrue(store.statsByNamespace().isEmpty());
        assertEquals(0, store.size());
    }

    @Test
    void statsByNamespace_countsByNamespacePrefix_andSumEqualsSize() {
        store.create("user:w-1", "score_a", VarType.NUMBER, "0", null);
        store.create("user:w-1", "score_b", VarType.NUMBER, "0", null);
        store.create("user:w-2", "score_c", VarType.NUMBER, "0", null);
        store.create("schedule:w-1", "eta", VarType.NUMBER, "0", "system");
        store.create("system", "server.online", VarType.NUMBER, "0", "system");
        store.create("system", "server.tps", VarType.NUMBER, "0", "system");
        store.create("scoreboard", "kills", VarType.NUMBER, "0", "scoreboard");
        store.create("papi", "pct_foo_pct", VarType.STRING, null, "papi");
        store.create("userglobal", "global_msg", VarType.STRING, "hi", null);
        store.create("bedwars", "red_score", VarType.NUMBER, "0", "BedWars");

        Map<String, Integer> stats = store.statsByNamespace();

        assertEquals(3, stats.get("user").intValue());
        assertEquals(1, stats.get("schedule").intValue());
        assertEquals(2, stats.get("system").intValue());
        assertEquals(1, stats.get("scoreboard").intValue());
        assertEquals(1, stats.get("papi").intValue());
        assertEquals(1, stats.get("userglobal").intValue());
        assertEquals(1, stats.get("bedwars").intValue());

        int sum = stats.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(store.size(), sum, "各 namespace 计数之和应等于 store.size()");
        assertEquals(10, store.size());
        assertEquals(7, stats.size(), "应有 7 个 namespace 前缀");
    }

    @Test
    void statsByNamespace_sortedByCountDescending() {
        store.create("user:w-1", "a", VarType.NUMBER, "0", null);
        store.create("user:w-1", "b", VarType.NUMBER, "0", null);
        store.create("user:w-1", "c", VarType.NUMBER, "0", null);
        store.create("system", "x", VarType.NUMBER, "0", "system");

        List<String> order = new ArrayList<>(store.statsByNamespace().keySet());
        assertEquals("user", order.get(0), "计数最高的 namespace 应排在前");
    }

    @Test
    void variableDto_jsonOmitsReferencedByWallsAndPreservesCoreFields() throws Exception {
        store.create("bedwars", "score", VarType.NUMBER, "0", "BedWarsPlugin");
        store.setValue("bedwars/score", "42", Duration.ofMinutes(5));
        store.markWallReferences("w-secret-peer", Set.of("bedwars/score"));

        Variable v = store.get("bedwars/score").orElseThrow();
        assertTrue(v.referencedByWalls().contains("w-secret-peer"));

        ObjectMapper mapper = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        String json = mapper.writeValueAsString(VariableDto.from(v, store));
        JsonNode node = mapper.readTree(json);

        assertFalse(node.has("referencedByWalls"),
                "VariableDto wire 序列化不得泄露 referencedByWalls: " + json);
        assertFalse(json.contains("w-secret-peer"),
                "不得泄露其他引用该变量的 wallId: " + json);
        assertEquals("bedwars", node.get("namespace").asText());
        assertEquals("score", node.get("key").asText());
        assertEquals("NUMBER", node.get("type").asText());
        assertEquals("0", node.get("defaultValue").asText());
        assertEquals("42", node.get("currentValue").asText());
        assertEquals("BedWarsPlugin", node.get("source").asText());
        assertTrue(node.has("updatedAt"));
        assertTrue(node.has("ttl"));
        assertFalse(node.has("ownerUuid"), "非 userglobal 变量 ownerUuid 为 null 时应省略");
    }

    // ──────────────────────────────────────────────────────────
    //  Helper：内存 fake DAO
    // ──────────────────────────────────────────────────────────

    private static final class FakeUserGlobalVariableDao extends UserGlobalVariableDao {
        record UpsertCall(String name, UUID ownerUuid, String ownerName, VarType type,
                          String defaultValue, String currentValue, String boundTo,
                          long createdAt, long updatedAt) {}

        final List<UpsertCall> upserts = new ArrayList<>();
        final List<String> deletes = new ArrayList<>();
        final List<Row> preload = new ArrayList<>();

        FakeUserGlobalVariableDao() {
            super(Logger.getLogger("test"), null);
        }

        @Override
        public void upsert(String name, UUID ownerUuid, String ownerName, VarType type,
                           String defaultValue, String currentValue, String boundTo,
                           long createdAt, long updatedAt) {
            upserts.add(new UpsertCall(name, ownerUuid, ownerName, type,
                    defaultValue, currentValue, boundTo, createdAt, updatedAt));
        }

        @Override
        public void delete(String name) {
            deletes.add(name);
        }

        @Override
        public int countByOwner(UUID ownerUuid) {
            // 当前 DB 状态 = upserts 中独立 name 但未被 delete 的、且 owner 匹配
            java.util.Set<String> deleted = new java.util.HashSet<>(deletes);
            java.util.Map<String, UpsertCall> lastByName = new java.util.LinkedHashMap<>();
            for (UpsertCall u : upserts) lastByName.put(u.name(), u);  // 同 name 后写覆盖
            int n = 0;
            for (var entry : lastByName.entrySet()) {
                if (deleted.contains(entry.getKey())) continue;
                if (entry.getValue().ownerUuid().equals(ownerUuid)) n++;
            }
            return n;
        }

        @Override
        public int countTotal() {
            java.util.Set<String> deleted = new java.util.HashSet<>(deletes);
            java.util.Set<String> alive = new java.util.HashSet<>();
            for (UpsertCall u : upserts) alive.add(u.name());
            alive.removeAll(deleted);
            return alive.size();
        }

        @Override
        public List<Row> loadAll() {
            return new ArrayList<>(preload);
        }
    }

    // ──────────────────────────────────────────────────────────
    //  Helper：内存 fake DAO（原 user_variables 用）
    // ──────────────────────────────────────────────────────────

    /**
     * 极简 fake：不连真 SQLite，把 upsert / delete 调用录到 list 给断言用，preload
     * 列表喂给 loadAll() 模拟"重启后 DB 里已有数据"场景。
     */
    private static final class FakeUserVariableDao extends UserVariableDao {
        record UpsertCall(String wallId, String name, VarType type,
                          String defaultValue, String currentValue, String boundTo,
                          long createdAt, long updatedAt) {}
        record DeleteCall(String wallId, String name) {}

        final List<UpsertCall> upserts = new ArrayList<>();
        final List<DeleteCall> deletes = new ArrayList<>();
        final List<Row> preload = new ArrayList<>();

        FakeUserVariableDao() {
            super(Logger.getLogger("test"), null);
        }

        @Override
        public void upsert(String wallId, String name, VarType type,
                           String defaultValue, String currentValue, String boundTo,
                           long createdAt, long updatedAt) {
            upserts.add(new UpsertCall(wallId, name, type, defaultValue,
                    currentValue, boundTo, createdAt, updatedAt));
        }

        @Override
        public void delete(String wallId, String name) {
            deletes.add(new DeleteCall(wallId, name));
        }

        @Override
        public List<Row> loadAll() {
            return new ArrayList<>(preload);
        }
    }
}
