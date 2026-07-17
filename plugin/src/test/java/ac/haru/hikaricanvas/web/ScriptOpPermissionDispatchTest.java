package ac.haru.hikaricanvas.web;

import ac.haru.hikaricanvas.script.ScriptPermissions;
import ac.haru.hikaricanvas.script.ScriptStore;
import ac.haru.hikaricanvas.session.Session;
import ac.haru.hikaricanvas.session.SessionTestFactory;
import ac.haru.hikaricanvas.session.WallKey;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.state.StatePatch;
import ac.haru.hikaricanvas.storage.AuditLog;
import ac.haru.hikaricanvas.storage.Database;
import ac.haru.hikaricanvas.storage.MigrationRunner;
import ac.haru.hikaricanvas.storage.WallRepo;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.7.0-P2-1：权限拒绝路径 dispatch 级测试（P1 终审记账 §11 第 4 项）。
 *
 * <p><b>路线选择</b>：MockBukkit 依赖虽在（M15 引入），但全仓零 ServerMock 实跑先例，
 * 且与 Paper 1.21.11 / MainThreadPerms.callSyncMethod 主线程语义的兼容性未验证——
 * 按任务"二选一"取 {@link MainThreadPerms#testResolver} 静态 seam 路线：注入 fake
 * 权限解析结果（在线 / 收回节点），驱动 package-private 的
 * {@code checkBasePermission} / {@code handleCreate}（照 {@code
 * ScriptOpDispatchBehaviorTest} 的 handler 直驱范式，绕开 final 的 SessionManager /
 * WsMessageContext）。</p>
 *
 * <p><b>装配</b>：真 Database(tmpdir SQLite) + MigrationRunner + 真 {@link WallRepo}
 * （createWithMapIds 建墙，checkBasePermission 的 wall 存在性检查走真实路径）+
 * 真 {@link AuditLog}（事件直读 audit_log 表断言，非 mock）。</p>
 *
 * <p>覆盖：① 在线玩家被收回 {@code canvas.script.edit} → checkBasePermission 拒
 * PERMISSION_DENIED + audit ② create 带 playSound 但无 {@code canvas.script.sound}
 * → PERMISSION_DENIED + audit + 规则不入库不推 patch ③ 对照组：权限齐全放行。</p>
 */
class ScriptOpPermissionDispatchTest {

    private static final String SESSION_ID = "sess-perm-1";
    private static final UUID CALLER = UUID.randomUUID();
    private static final Logger LOG =
            Logger.getLogger(ScriptOpPermissionDispatchTest.class.getName());

    private Path tmpDir;
    private Database database;
    private WallRepo wallRepo;
    private ScriptStore store;
    private AuditLog auditLog;
    private ScriptOpDispatcher dispatcher;
    private Session session;
    private String wallId;
    private final List<StatePatch> pushedPatches = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("hikari-scriptperm-test-");
        database = new Database(LOG, tmpDir.resolve("data.db"));
        new MigrationRunner(database.jdbi(), LOG).run();
        wallRepo = new WallRepo(LOG, database.jdbi());
        wallId = wallRepo.createWithMapIds(
                new WallKey("world", 0, 64, 0, BlockFace.NORTH),
                new ProjectState(1, 1), List.of(0), 1, 1, CALLER, "tester");
        store = new ScriptStore(LOG, /*dao=*/null, /*maxRulesPerWall=*/16);
        auditLog = new AuditLog(database.jdbi(), LOG);
        pushedPatches.clear();
        OpPushCallback push = new OpPushCallback() {
            @Override
            public boolean pushSnapshot(String sessionId, ProjectState state) {
                return true;
            }

            @Override
            public boolean pushPatch(String sessionId, StatePatch patch) {
                pushedPatches.add(patch);
                return true;
            }
        };
        dispatcher = new ScriptOpDispatcher(/*sessionManager=*/null, /*rateLimiter=*/null,
                store, wallRepo, push, auditLog, /*plugin=*/null, LOG, /*messages=*/null);
        session = SessionTestFactory.withWall(SESSION_ID, CALLER, "tester", wallId);
    }

    @AfterEach
    void tearDown() throws Exception {
        // seam 必须复位,防泄漏污染其他测试(生产路径恒 null)
        MainThreadPerms.testResolver = null;
        if (database != null) database.close();
        if (tmpDir != null && Files.exists(tmpDir)) {
            try (var s = Files.walk(tmpDir)) {
                s.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    //  helpers
    // ──────────────────────────────────────────────────────────

    /** seam：在线玩家,所有节点统一 granted / denied。 */
    private static void seamOnlineAllGranted(boolean granted) {
        MainThreadPerms.testResolver = (uuid, nodes) -> {
            boolean[] g = new boolean[nodes.length];
            java.util.Arrays.fill(g, granted);
            return new MainThreadPerms.Resolved(true, g);
        };
    }

    /** seam：在线玩家,仅 {@code deniedNode} 拒,其余节点放行。 */
    private static void seamOnlineDenyOnly(String deniedNode) {
        MainThreadPerms.testResolver = (uuid, nodes) -> {
            boolean[] g = new boolean[nodes.length];
            for (int i = 0; i < nodes.length; i++) {
                g[i] = !nodes[i].equals(deniedNode);
            }
            return new MainThreadPerms.Resolved(true, g);
        };
    }

    /** 直读 audit_log 表：返回 (event, details) 行列表（按插入序）。 */
    private List<Map<String, Object>> auditRows() {
        return database.jdbi().withHandle(h ->
                h.createQuery("SELECT event, details FROM audit_log ORDER BY ts, rowid")
                        .mapToMap()
                        .list());
    }

    private static String codeOf(Envelope env) {
        Object p = env.payload();
        return (p instanceof Map<?, ?> m) ? (String) m.get("code") : null;
    }

    private static Envelope envelope(String op) {
        return Envelope.of(op, "c-1", Map.of());
    }

    /** 带 playSound 动作的合法 rule payload（requiredFacets = {canvas.script.sound}）。 */
    private static Map<String, Object> soundRulePayload() {
        return Map.of("rule", Map.of(
                "name", "响铃规则",
                "trigger", Map.of("type", "wallReady"),
                "actions", List.of(Map.of(
                        "type", "playSound",
                        "soundId", "entity.ender_dragon.growl",
                        "volume", 1.0,
                        "pitch", 1.0,
                        "scope", "near"))));
    }

    // ──────────────────────────────────────────────────────────
    //  ① checkBasePermission：在线被收回 canvas.script.edit → 真拒
    // ──────────────────────────────────────────────────────────

    @Test
    void onlinePlayerWithoutEditNodeIsDeniedWithAudit() {
        seamOnlineAllGranted(false);

        Envelope denied = dispatcher.checkBasePermission(
                envelope("script.create"), SESSION_ID, session);

        assertNotNull(denied, "在线且被收回 canvas.script.edit 必须真拒");
        assertEquals("error", denied.op());
        assertEquals("PERMISSION_DENIED", codeOf(denied));

        List<Map<String, Object>> rows = auditRows();
        assertEquals(1, rows.size(), "拒绝应记 1 条 audit: " + rows);
        assertEquals("PERMISSION_DENIED", rows.get(0).get("event"));
        String details = String.valueOf(rows.get(0).get("details"));
        assertTrue(details.contains(ScriptPermissions.NODE_EDIT),
                "details 应含被拒节点: " + details);
        assertTrue(details.contains(wallId), "details 应含 wall_id: " + details);
    }

    /** 对照组：在线且持节点 → 放行（返回 null）,零 audit。 */
    @Test
    void onlinePlayerWithEditNodePasses() {
        seamOnlineAllGranted(true);

        Envelope result = dispatcher.checkBasePermission(
                envelope("script.create"), SESSION_ID, session);

        assertNull(result, "持节点应放行");
        assertTrue(auditRows().isEmpty(), "放行不应记 audit");
    }

    /** offline（seam online=false）→ default-true 兜底放行（既有语义回归）。 */
    @Test
    void offlinePlayerFallsBackToDefaultTrue() {
        MainThreadPerms.testResolver = (uuid, nodes) ->
                new MainThreadPerms.Resolved(false, new boolean[nodes.length]);

        Envelope result = dispatcher.checkBasePermission(
                envelope("script.create"), SESSION_ID, session);

        assertNull(result, "offline 玩家 default-true 节点应兜底放行");
    }

    // ──────────────────────────────────────────────────────────
    //  ② checkFacets：create 带 playSound 但无 canvas.script.sound → 真拒
    // ──────────────────────────────────────────────────────────

    @Test
    void createWithPlaySoundButNoSoundFacetIsDeniedWithAudit() {
        seamOnlineDenyOnly(ScriptPermissions.NODE_SOUND);

        Envelope env = dispatcher.handleCreate(envelope("script.create"),
                SESSION_ID, session, wallId, soundRulePayload());

        assertEquals("error", env.op());
        assertEquals("PERMISSION_DENIED", codeOf(env));
        // message 点名缺的节点
        Object msg = ((Map<?, ?>) env.payload()).get("message");
        assertTrue(String.valueOf(msg).contains(ScriptPermissions.NODE_SOUND),
                "错误信息应点名缺的面节点: " + msg);

        // 拒绝即截断：规则不入库、不推 patch
        assertTrue(store.listByWall(wallId).isEmpty(), "被拒的 create 不应入库");
        assertTrue(pushedPatches.isEmpty(), "被拒的 create 不应推 patch");

        // audit：PERMISSION_DENIED 记录被拒面节点
        List<Map<String, Object>> rows = auditRows();
        assertEquals(1, rows.size(), "拒绝应记 1 条 audit: " + rows);
        assertEquals("PERMISSION_DENIED", rows.get(0).get("event"));
        String details = String.valueOf(rows.get(0).get("details"));
        assertTrue(details.contains(ScriptPermissions.NODE_SOUND),
                "details 应含被拒面节点: " + details);
    }

    /** 对照组：sound 面齐全 → create 成功 + SCRIPT_CREATE audit（含 facets）。 */
    @Test
    void createWithPlaySoundAndSoundFacetSucceeds() {
        seamOnlineAllGranted(true);

        Envelope env = dispatcher.handleCreate(envelope("script.create"),
                SESSION_ID, session, wallId, soundRulePayload());

        assertEquals("ack", env.op());
        assertEquals(1, store.listByWall(wallId).size());
        assertEquals(1, pushedPatches.size(), "成功的 create 应推 1 条 patch");

        List<Map<String, Object>> rows = auditRows();
        assertEquals(1, rows.size());
        assertEquals("SCRIPT_CREATE", rows.get(0).get("event"));
        String details = String.valueOf(rows.get(0).get("details"));
        assertTrue(details.contains(ScriptPermissions.NODE_SOUND),
                "SCRIPT_CREATE details 应含 facets: " + details);
    }
}
