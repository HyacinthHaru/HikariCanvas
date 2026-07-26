package ac.haru.hikaricanvas.state;

import ac.haru.hikaricanvas.storage.UserGlobalVariableDao;
import ac.haru.hikaricanvas.storage.UserVariableDao;
import ac.haru.hikaricanvas.variable.VarType;
import ac.haru.hikaricanvas.variable.Variable;
import ac.haru.hikaricanvas.variable.VariablePatch;
import ac.haru.hikaricanvas.variable.VariableStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.4.0-P1-B：EditSession 的 5 个 variable.* op 方法单测。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>createVariable → store.get 验证 + patch add path</li>
 *   <li>updateUserVariable → patch 应用 + replace</li>
 *   <li>setUserVariableValue → currentValue 变化 + dirty callback 触发（前提：referencedByWalls 非空）</li>
 *   <li>deleteUserVariable → store.get returns empty + patch remove</li>
 *   <li>bindUserVariable → source 字段变化</li>
 *   <li>跨 wall 改其他 wall 变量 → VARIABLE_NAMESPACE_DENIED</li>
 *   <li>nil wallId → WALL_NOT_FOUND</li>
 *   <li>nil store → INTERNAL_ERROR</li>
 *   <li>variable.create 重复 → VARIABLE_EXISTS</li>
 *   <li>variable.set 不存在 → VARIABLE_NOT_FOUND</li>
 *   <li>变量 op patch path 用 JSON Pointer 转义</li>
 * </ul>
 *
 * <p>不连真 SQLite：用 {@link FakeUserVariableDao}（与 VariableStoreTest 同款）。</p>
 */
class EditSessionVariableTest {

    private static final String WALL = "w-deadbeef";

    private EditSession es;
    private VariableStore store;
    private FakeUserVariableDao dao;
    private List<String> dirtyWalls;

    @BeforeEach
    void setUp() {
        es = new EditSession(new ProjectState(2, 1));
        dao = new FakeUserVariableDao();
        dirtyWalls = new ArrayList<>();
        store = new VariableStore(dao, dirtyWalls::add);
    }

    // ──────────────────────────────────────────────────────────
    //  createVariable
    // ──────────────────────────────────────────────────────────

    @Test
    void createVariable_persistsAndProducesAddPatch() {
        EditSession.OpResult result = es.createVariable(store, WALL,
                "red_score", VarType.NUMBER, "0");
        EditSession.OpResult.Ok ok = assertInstanceOf(EditSession.OpResult.Ok.class, result);

        // store 端
        Variable v = store.get("user:" + WALL + "/red_score").orElseThrow();
        assertEquals("user:" + WALL, v.namespace());
        assertEquals("red_score", v.key());
        assertEquals(VarType.NUMBER, v.type());
        assertEquals("0", v.defaultValue());
        assertNull(v.currentValue());
        assertEquals("manual", v.source());

        // patch path 形态
        assertEquals(1, ok.patch().ops().size());
        PatchOp op = ok.patch().ops().get(0);
        assertEquals("add", op.op());
        assertEquals("/variables/user:" + WALL + "~1red_score", op.path());
        assertInstanceOf(Map.class, op.value());

        // 不动 dirty（变量 create 时 referencedByWalls 是空）
        assertTrue(dirtyWalls.isEmpty(), "create should not trigger dirty");

        // 持久化到 fake DAO
        assertEquals(1, dao.upserts.size());
    }

    @Test
    void createVariable_duplicateReturnsVariableExists() {
        es.createVariable(store, WALL, "score", VarType.STRING, null);
        EditSession.OpResult r = es.createVariable(store, WALL, "score", VarType.NUMBER, null);
        EditSession.OpResult.Error err = assertInstanceOf(EditSession.OpResult.Error.class, r);
        assertEquals("VARIABLE_EXISTS", err.code());
    }

    @Test
    void createVariable_invalidNameReturnsInvalidPayload() {
        // 名字含空格非法 → store 抛 VARIABLE_NAME_INVALID → 协议层翻 INVALID_PAYLOAD
        EditSession.OpResult r = es.createVariable(store, WALL, "with space", VarType.STRING, null);
        EditSession.OpResult.Error err = assertInstanceOf(EditSession.OpResult.Error.class, r);
        assertEquals("INVALID_PAYLOAD", err.code());
    }

    @Test
    void createVariable_nullWallIdReturnsWallNotFound() {
        EditSession.OpResult r = es.createVariable(store, null, "x", VarType.STRING, null);
        EditSession.OpResult.Error err = assertInstanceOf(EditSession.OpResult.Error.class, r);
        assertEquals("WALL_NOT_FOUND", err.code());
    }

    @Test
    void createVariable_nullStoreReturnsInternalError() {
        EditSession.OpResult r = es.createVariable(null, WALL, "x", VarType.STRING, null);
        EditSession.OpResult.Error err = assertInstanceOf(EditSession.OpResult.Error.class, r);
        assertEquals("INTERNAL_ERROR", err.code());
    }

    // ──────────────────────────────────────────────────────────
    //  updateUserVariable
    // ──────────────────────────────────────────────────────────

    @Test
    void updateUserVariable_changesTypeAndDefault() {
        es.createVariable(store, WALL, "x", VarType.NUMBER, "0");
        EditSession.OpResult r = es.updateUserVariable(store, WALL,
                "user:" + WALL + "/x", new VariablePatch(VarType.STRING, "N/A"));
        EditSession.OpResult.Ok ok = assertInstanceOf(EditSession.OpResult.Ok.class, r);

        Variable v = store.get("user:" + WALL + "/x").orElseThrow();
        assertEquals(VarType.STRING, v.type());
        assertEquals("N/A", v.defaultValue());

        // patch replace path
        assertEquals(1, ok.patch().ops().size());
        assertEquals("replace", ok.patch().ops().get(0).op());
    }

    @Test
    void updateUserVariable_missingFullNameReturnsNotFound() {
        EditSession.OpResult r = es.updateUserVariable(store, WALL,
                "user:" + WALL + "/missing", new VariablePatch(VarType.STRING, null));
        EditSession.OpResult.Error err = assertInstanceOf(EditSession.OpResult.Error.class, r);
        assertEquals("VARIABLE_NOT_FOUND", err.code());
    }

    // ──────────────────────────────────────────────────────────
    //  setUserVariableValue
    // ──────────────────────────────────────────────────────────

    @Test
    void setUserVariableValue_updatesCurrentValueAndPatchesReplace() {
        es.createVariable(store, WALL, "score", VarType.NUMBER, "0");
        EditSession.OpResult r = es.setUserVariableValue(store, WALL,
                "user:" + WALL + "/score", "5");
        EditSession.OpResult.Ok ok = assertInstanceOf(EditSession.OpResult.Ok.class, r);

        assertEquals("5", store.get("user:" + WALL + "/score").orElseThrow().currentValue());

        // 走 /currentValue 字段精确 patch（不发整 Variable）
        PatchOp op = ok.patch().ops().get(0);
        assertEquals("replace", op.op());
        assertTrue(op.path().endsWith("/currentValue"),
                "expected currentValue suffix, got " + op.path());
        assertEquals("5", op.value());
    }

    @Test
    void setUserVariableValue_triggersDirtyCallbackWhenWallReferences() {
        es.createVariable(store, WALL, "score", VarType.NUMBER, "0");
        // 模拟："另一个 wall（w-other）也引用了本变量" — markWallReferences 后变量 set
        // 时 wallDirtyCallback 应被对所有 referencer 触发，包括同 wall。
        store.markWallReferences(WALL,
                java.util.Set.of("user:" + WALL + "/score"));

        es.setUserVariableValue(store, WALL, "user:" + WALL + "/score", "9");
        assertEquals(List.of(WALL), dirtyWalls);
    }

    @Test
    void setUserVariableValue_crossWallReturnsNamespaceDenied() {
        // 在 WALL 上创建；尝试从 w-other 改 → 拒
        es.createVariable(store, WALL, "secret", VarType.STRING, null);
        EditSession.OpResult r = es.setUserVariableValue(store, "w-other",
                "user:" + WALL + "/secret", "leak");
        EditSession.OpResult.Error err = assertInstanceOf(EditSession.OpResult.Error.class, r);
        assertEquals("VARIABLE_NAMESPACE_DENIED", err.code());
        // 值未改
        assertNull(store.get("user:" + WALL + "/secret").orElseThrow().currentValue());
    }

    @Test
    void setUserVariableValue_nullValueClearsCurrent() {
        es.createVariable(store, WALL, "x", VarType.STRING, "default");
        es.setUserVariableValue(store, WALL, "user:" + WALL + "/x", "first");
        // 用 null 清值
        EditSession.OpResult r = es.setUserVariableValue(store, WALL,
                "user:" + WALL + "/x", null);
        EditSession.OpResult.Ok ok = assertInstanceOf(EditSession.OpResult.Ok.class, r);
        assertNull(store.get("user:" + WALL + "/x").orElseThrow().currentValue());
        // P3-9：null 清值改用 replace(path, null)（与 SessionManager VALUE_SET 同形，
        // 前端 applyVariablePatches 的 replace && sub==='currentValue' 分支可处理），
        // 不再发前端无对应分支的 remove .../currentValue。
        assertEquals("replace", ok.patch().ops().get(0).op());
        assertTrue(ok.patch().ops().get(0).path().endsWith("/currentValue"));
        assertNull(ok.patch().ops().get(0).value());
    }

    // ──────────────────────────────────────────────────────────
    //  deleteUserVariable
    // ──────────────────────────────────────────────────────────

    @Test
    void deleteUserVariable_removesFromStoreAndProducesRemovePatch() {
        es.createVariable(store, WALL, "tmp", VarType.STRING, null);
        EditSession.OpResult r = es.deleteUserVariable(store, WALL, "user:" + WALL + "/tmp");
        EditSession.OpResult.Ok ok = assertInstanceOf(EditSession.OpResult.Ok.class, r);

        assertTrue(store.get("user:" + WALL + "/tmp").isEmpty());
        PatchOp op = ok.patch().ops().get(0);
        assertEquals("remove", op.op());
        assertEquals("/variables/user:" + WALL + "~1tmp", op.path());
    }

    @Test
    void deleteUserVariable_crossWallDenied() {
        es.createVariable(store, WALL, "tmp", VarType.STRING, null);
        EditSession.OpResult r = es.deleteUserVariable(store, "w-other",
                "user:" + WALL + "/tmp");
        EditSession.OpResult.Error err = assertInstanceOf(EditSession.OpResult.Error.class, r);
        assertEquals("VARIABLE_NAMESPACE_DENIED", err.code());
        assertTrue(store.get("user:" + WALL + "/tmp").isPresent());
    }

    // ──────────────────────────────────────────────────────────
    //  bindUserVariable
    // ──────────────────────────────────────────────────────────

    @Test
    void bindUserVariable_setsSourceFieldAndEmitsReplacePatch() {
        es.createVariable(store, WALL, "score", VarType.NUMBER, "0");
        // create 时 source = "manual"
        assertEquals("manual", store.get("user:" + WALL + "/score").orElseThrow().source());

        EditSession.OpResult r = es.bindUserVariable(store, WALL,
                "user:" + WALL + "/score", "BedWarsPlugin");
        EditSession.OpResult.Ok ok = assertInstanceOf(EditSession.OpResult.Ok.class, r);
        assertEquals("BedWarsPlugin",
                store.get("user:" + WALL + "/score").orElseThrow().source());

        // patch on /source field
        PatchOp op = ok.patch().ops().get(0);
        assertEquals("replace", op.op());
        assertTrue(op.path().endsWith("/source"));
        assertEquals("BedWarsPlugin", op.value());
    }

    /**
     * 解绑必须发 {@code replace(path, null)}，<b>不能</b>发 remove。
     * 前端 applyVariablePatches 只认 replace，remove 会落 else 分支被整条丢弃 ——
     * 表现是「点了解绑，绑定关系还挂在面板上，得重连才消失」。
     */
    @Test
    void bindUserVariable_unbindEmitsReplaceNullNotRemove() {
        es.createVariable(store, WALL, "score", VarType.NUMBER, "0");
        es.bindUserVariable(store, WALL, "user:" + WALL + "/score", "BedWarsPlugin");
        // 现在解绑 → source 回 null
        EditSession.OpResult r = es.bindUserVariable(store, WALL,
                "user:" + WALL + "/score", null);
        EditSession.OpResult.Ok ok = assertInstanceOf(EditSession.OpResult.Ok.class, r);
        assertNull(store.get("user:" + WALL + "/score").orElseThrow().source());

        PatchOp op = ok.patch().ops().get(0);
        assertEquals("replace", op.op(), "解绑要发 replace(path, null)，remove 前端认不了");
        assertTrue(op.path().endsWith("/source"));
        assertNull(op.value());
    }

    @Test
    void bindUserVariable_crossWallDenied() {
        es.createVariable(store, WALL, "x", VarType.STRING, null);
        EditSession.OpResult r = es.bindUserVariable(store, "w-other",
                "user:" + WALL + "/x", "FakePlugin");
        EditSession.OpResult.Error err = assertInstanceOf(EditSession.OpResult.Error.class, r);
        assertEquals("VARIABLE_NAMESPACE_DENIED", err.code());
    }

    // ──────────────────────────────────────────────────────────
    //  JSON Pointer 转义
    // ──────────────────────────────────────────────────────────

    @Test
    void variablePath_jsonPointerEscapesSlashAndTilde() {
        // 名字含 ~ 字符（合法 KEY_RE [a-zA-Z0-9_.-]+ 不含 ~，所以 ~ 不会出现在 user 变量 key；
        // 但 namespace user:<wallId> 自带冒号，path 用 ~1 转义 /）
        es.createVariable(store, WALL, "a.b-c_d", VarType.STRING, null);
        EditSession.OpResult.Ok ok = (EditSession.OpResult.Ok)
                es.deleteUserVariable(store, WALL, "user:" + WALL + "/a.b-c_d");
        // namespace 内的 / 必须转义为 ~1；冒号原样
        assertEquals("/variables/user:" + WALL + "~1a.b-c_d",
                ok.patch().ops().get(0).path());
    }

    @Test
    void version_bumpsOnEveryVariableOp() {
        long v0 = es.state().version();
        es.createVariable(store, WALL, "a", VarType.STRING, null);
        long v1 = es.state().version();
        assertTrue(v1 > v0);
        es.setUserVariableValue(store, WALL, "user:" + WALL + "/a", "x");
        assertTrue(es.state().version() > v1);
    }

    // ──────────────────────────────────────────────────────────
    //  0.4.3 全局用户变量路径
    // ──────────────────────────────────────────────────────────

    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void createGlobalVariable_ok() {
        FakeGlobalDao globalDao = new FakeGlobalDao();
        store.configureUserGlobal(globalDao, 500, 10_000);

        EditSession.OpResult result = es.createGlobalVariable(store, ALICE, "Alice",
                "red_score", VarType.NUMBER, "0");
        EditSession.OpResult.Ok ok = assertInstanceOf(EditSession.OpResult.Ok.class, result);

        Variable v = store.get("userglobal/red_score").orElseThrow();
        assertEquals("userglobal", v.namespace());
        assertEquals("red_score", v.key());
        assertEquals(VarType.NUMBER, v.type());
        // patch 形态：add /variables/userglobal~1red_score
        assertEquals(1, ok.patch().ops().size());
        assertEquals("add", ok.patch().ops().get(0).op());
        assertEquals("/variables/userglobal~1red_score", ok.patch().ops().get(0).path());
        // patch value 含 owner 信息
        Object value = ok.patch().ops().get(0).value();
        assertNotNull(value);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) value;
        assertEquals(ALICE.toString(), m.get("ownerUuid"));
        assertEquals("Alice", m.get("ownerName"));
    }

    @Test
    void createGlobalVariable_nullOwnerUuid_returnsForbidden() {
        FakeGlobalDao globalDao = new FakeGlobalDao();
        store.configureUserGlobal(globalDao, 500, 10_000);
        EditSession.OpResult result = es.createGlobalVariable(store, null, "Alice",
                "red", VarType.STRING, null);
        EditSession.OpResult.Error err = assertInstanceOf(EditSession.OpResult.Error.class, result);
        assertEquals("FORBIDDEN", err.code());
    }

    @Test
    void updateGlobalVariable_nonGlobalFullName_returnsNamespaceDenied() {
        // 防越权：updateGlobalVariable 拒绝非 userglobal fullName
        FakeGlobalDao globalDao = new FakeGlobalDao();
        store.configureUserGlobal(globalDao, 500, 10_000);
        // 创建一个 user 变量（per-wall），再用 updateGlobalVariable 越权改它
        es.createVariable(store, WALL, "a", VarType.STRING, null);
        EditSession.OpResult result = es.updateGlobalVariable(store,
                "user:" + WALL + "/a",
                new VariablePatch(VarType.NUMBER, null));
        EditSession.OpResult.Error err = assertInstanceOf(EditSession.OpResult.Error.class, result);
        assertEquals("VARIABLE_NAMESPACE_DENIED", err.code());
    }

    @Test
    void setGlobalVariableValue_ok() {
        FakeGlobalDao globalDao = new FakeGlobalDao();
        store.configureUserGlobal(globalDao, 500, 10_000);
        es.createGlobalVariable(store, ALICE, "Alice", "score", VarType.NUMBER, "0");

        EditSession.OpResult result = es.setGlobalVariableValue(store,
                "userglobal/score", "42");
        EditSession.OpResult.Ok ok = assertInstanceOf(EditSession.OpResult.Ok.class, result);
        // patch 形态：replace /variables/userglobal~1score/currentValue
        assertEquals("replace", ok.patch().ops().get(0).op());
        assertEquals("/variables/userglobal~1score/currentValue", ok.patch().ops().get(0).path());
        assertEquals("42", ok.patch().ops().get(0).value());
    }

    @Test
    void deleteGlobalVariable_ok() {
        FakeGlobalDao globalDao = new FakeGlobalDao();
        store.configureUserGlobal(globalDao, 500, 10_000);
        es.createGlobalVariable(store, ALICE, "Alice", "score", VarType.STRING, null);

        EditSession.OpResult result = es.deleteGlobalVariable(store, "userglobal/score");
        EditSession.OpResult.Ok ok = assertInstanceOf(EditSession.OpResult.Ok.class, result);
        assertEquals("remove", ok.patch().ops().get(0).op());
        assertEquals("/variables/userglobal~1score", ok.patch().ops().get(0).path());
        assertFalse(store.get("userglobal/score").isPresent());
    }

    // ──────────────────────────────────────────────────────────
    //  Fake DAO（同 VariableStoreTest 配方，避免共享 helper 文件）
    // ──────────────────────────────────────────────────────────

    private static final class FakeUserVariableDao extends UserVariableDao {
        record UpsertCall(String wallId, String name, VarType type,
                          String defaultValue, String currentValue, String boundTo,
                          long createdAt, long updatedAt) {}
        record DeleteCall(String wallId, String name) {}

        final List<UpsertCall> upserts = new ArrayList<>();
        final List<DeleteCall> deletes = new ArrayList<>();

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
            return new ArrayList<>();
        }
    }

    /** 0.4.3：UserGlobalVariableDao 内存 fake；最小够测试用。 */
    private static final class FakeGlobalDao extends UserGlobalVariableDao {
        final List<String> upsertedNames = new ArrayList<>();
        final Set<String> deletedNames = new HashSet<>();
        final Map<String, UUID> owners = new LinkedHashMap<>();

        FakeGlobalDao() {
            super(Logger.getLogger("test"), null);
        }

        @Override
        public void upsert(String name, UUID ownerUuid, String ownerName, VarType type,
                           String defaultValue, String currentValue, String boundTo,
                           long createdAt, long updatedAt) {
            upsertedNames.add(name);
            owners.put(name, ownerUuid);
            deletedNames.remove(name);
        }

        @Override
        public void delete(String name) {
            deletedNames.add(name);
        }

        @Override
        public int countByOwner(UUID ownerUuid) {
            int n = 0;
            for (var e : owners.entrySet()) {
                if (deletedNames.contains(e.getKey())) continue;
                if (e.getValue().equals(ownerUuid)) n++;
            }
            return n;
        }

        @Override
        public int countTotal() {
            int n = 0;
            for (String name : owners.keySet()) {
                if (!deletedNames.contains(name)) n++;
            }
            return n;
        }

        @Override
        public List<Row> loadAll() {
            return new ArrayList<>();
        }
    }
}
