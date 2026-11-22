package ac.haru.hikaricanvas.web;

import ac.haru.hikaricanvas.script.Action;
import ac.haru.hikaricanvas.script.ScriptPermissions;
import ac.haru.hikaricanvas.script.ScriptRule;
import ac.haru.hikaricanvas.script.ScriptStore;
import ac.haru.hikaricanvas.script.Trigger;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScriptOpDispatcher} 权限拒绝路径、审计留痕与全 handler 权限面覆盖守卫单测。
 */
class ScriptOpSecurityTest {

    private static final String SESSION_ID = "sess-script-sec";
    private static final UUID CALLER = UUID.randomUUID();
    private static final Logger LOG = Logger.getLogger(ScriptOpSecurityTest.class.getName());

    /**
     * op 分类表 —— 新增 handler 必须在此登记该 op 是否必须过 {@code checkFacets}。
     */
    private static final Map<String, Boolean> HANDLERS_REQUIRING_FACET_CHECK = Map.of(
            "handleCreate", true,
            "handleUpdate", true,
            "handleEnable", true,
            "handleTest", true,
            "handleDelete", false);

    @TempDir
    Path tmpDir;

    private Database database;
    private WallRepo wallRepo;
    private ScriptStore store;
    private AuditLog auditLog;
    private ScriptOpDispatcher dispatcher;
    private Session session;
    private String wallId;
    private final List<StatePatch> pushedPatches = new ArrayList<>();

    @BeforeEach
    void setUp() {
        database = new Database(LOG, tmpDir.resolve("data.db"));
        new MigrationRunner(database.jdbi(), LOG).run();
        wallRepo = new WallRepo(LOG, database.jdbi());
        wallId = wallRepo.createWithMapIds(
                new WallKey("world", 0, 64, 0, BlockFace.NORTH),
                new ProjectState(1, 1), List.of(0), 1, 1, CALLER, "tester");
        store = new ScriptStore(LOG, null, 16);
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
        dispatcher = new ScriptOpDispatcher(null, null, store, wallRepo, push, auditLog, null, LOG, null);
        session = SessionTestFactory.withWall(SESSION_ID, CALLER, "tester", wallId);
    }

    @AfterEach
    void tearDown() {
        MainThreadPerms.testResolver = null;
        if (database != null) database.close();
    }

    private static void seamOnlineAllGranted(boolean granted) {
        MainThreadPerms.testResolver = (uuid, nodes) -> {
            boolean[] g = new boolean[nodes.length];
            Arrays.fill(g, granted);
            return new MainThreadPerms.Resolved(true, g);
        };
    }

    private static void seamOnlineDenyOnly(String deniedNode) {
        MainThreadPerms.testResolver = (uuid, nodes) -> {
            boolean[] g = new boolean[nodes.length];
            for (int i = 0; i < nodes.length; i++) {
                g[i] = !nodes[i].equals(deniedNode);
            }
            return new MainThreadPerms.Resolved(true, g);
        };
    }

    private List<Map<String, Object>> auditRows() {
        return database.jdbi().withHandle(h ->
                h.createQuery("SELECT event, details FROM audit_log ORDER BY ts, rowid")
                        .mapToMap()
                        .list());
    }

    private void clearAudit() {
        database.jdbi().useHandle(h -> h.execute("DELETE FROM audit_log"));
    }

    private static String codeOf(Envelope env) {
        Object p = env.payload();
        return (p instanceof Map<?, ?> m) ? (String) m.get("code") : null;
    }

    private static Object messageOf(Envelope env) {
        Object p = env.payload();
        return (p instanceof Map<?, ?> m) ? m.get("message") : null;
    }

    private static Envelope envelope(String op) {
        return Envelope.of(op, "c-1", Map.of());
    }

    @Nested
    class BaseAndCreatePermissions {

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

        @Test
        void onlinePlayerWithEditNodePasses() {
            seamOnlineAllGranted(true);

            Envelope result = dispatcher.checkBasePermission(
                    envelope("script.create"), SESSION_ID, session);

            assertNull(result, "持节点应放行");
            assertTrue(auditRows().isEmpty(), "放行不应记 audit");
        }

        @Test
        void offlinePlayerFallsBackToDefaultTrue() {
            MainThreadPerms.testResolver = (uuid, nodes) ->
                    new MainThreadPerms.Resolved(false, new boolean[nodes.length]);

            Envelope result = dispatcher.checkBasePermission(
                    envelope("script.create"), SESSION_ID, session);

            assertNull(result, "offline 玩家 default-true 节点应兜底放行");
        }

        @Test
        void createWithPlaySoundButNoSoundFacetIsDeniedWithAudit() {
            seamOnlineDenyOnly(ScriptPermissions.NODE_SOUND);

            Envelope env = dispatcher.handleCreate(envelope("script.create"),
                    SESSION_ID, session, wallId, soundRulePayload());

            assertEquals("error", env.op());
            assertEquals("PERMISSION_DENIED", codeOf(env));
            assertTrue(String.valueOf(messageOf(env)).contains(ScriptPermissions.NODE_SOUND),
                    "错误信息应点名缺的面节点: " + messageOf(env));

            assertTrue(store.listByWall(wallId).isEmpty(), "被拒的 create 不应入库");
            assertTrue(pushedPatches.isEmpty(), "被拒的 create 不应推 patch");

            List<Map<String, Object>> rows = auditRows();
            assertEquals(1, rows.size(), "拒绝应记 1 条 audit: " + rows);
            assertEquals("PERMISSION_DENIED", rows.get(0).get("event"));
            String details = String.valueOf(rows.get(0).get("details"));
            assertTrue(details.contains(ScriptPermissions.NODE_SOUND),
                    "details 应含被拒面节点: " + details);
        }

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

    @Nested
    class HandlerFacetCoverageGuard {

        private String ruleId;

        @BeforeEach
        void setUpGuard() {
            dispatcher.setTestLauncher((w, r, cb) -> {});
            ruleId = store.create(wallId, commandRule("危险规则")).id();
        }

        @Test
        void everyHandlerIsClassifiedInThisGuard() {
            Set<String> declared = new TreeSet<>();
            for (Method m : ScriptOpDispatcher.class.getDeclaredMethods()) {
                if (m.getName().startsWith("handle")) declared.add(m.getName());
            }
            assertEquals(new TreeSet<>(HANDLERS_REQUIRING_FACET_CHECK.keySet()), declared,
                    "新增 script.* handler 必须在 HANDLERS_REQUIRING_FACET_CHECK 里登记");
        }

        @Test
        void everyMutatingOpDeniesWhenCommandFacetIsRevoked() throws Exception {
            for (Map.Entry<String, Boolean> e : HANDLERS_REQUIRING_FACET_CHECK.entrySet()) {
                if (!e.getValue()) continue;
                String handler = e.getKey();
                resetRule();
                clearAudit();
                seamOnlineDenyOnly(ScriptPermissions.NODE_COMMAND);

                Envelope out = invoke(handler);

                assertEquals("error", out.op(), handler + " 缺命令面必须拒");
                assertEquals("PERMISSION_DENIED", codeOf(out), handler + " 缺命令面必须拒");
                assertTrue(String.valueOf(messageOf(out)).contains(ScriptPermissions.NODE_COMMAND),
                        handler + " 报错应点名缺的节点: " + messageOf(out));
                List<Map<String, Object>> rows = auditRows();
                assertEquals(1, rows.size(), handler + " 拒绝应记 1 条 audit: " + rows);
                assertEquals("PERMISSION_DENIED", rows.get(0).get("event"));
                assertTrue(String.valueOf(rows.get(0).get("details"))
                                .contains(ScriptPermissions.NODE_COMMAND),
                        handler + " audit 应含被拒节点: " + rows.get(0));
            }
        }

        @Test
        void everyMutatingOpSucceedsWhenFacetsGranted() throws Exception {
            for (Map.Entry<String, Boolean> e : HANDLERS_REQUIRING_FACET_CHECK.entrySet()) {
                if (!e.getValue()) continue;
                String handler = e.getKey();
                resetRule();
                seamOnlineAllGranted(true);

                Envelope out = invoke(handler);

                assertEquals("ack", out.op(), handler + " 面齐全应放行");
            }
        }

        @Test
        void enableTrueChecksFacetsOfStoredRule() {
            seamOnlineDenyOnly(ScriptPermissions.NODE_COMMAND);

            Envelope out = dispatcher.handleEnable(envelope("script.enable"), SESSION_ID,
                    session, wallId, Map.of("ruleId", ruleId, "enabled", true));

            assertEquals("PERMISSION_DENIED", codeOf(out),
                    "被禁用的危险规则不能靠 enable 重新打开");
            assertTrue(store.find(wallId, ruleId).isPresent());
        }

        @Test
        void enableFalseIsAlwaysAllowed() {
            seamOnlineDenyOnly(ScriptPermissions.NODE_COMMAND);

            Envelope out = dispatcher.handleEnable(envelope("script.enable"), SESSION_ID,
                    session, wallId, Map.of("ruleId", ruleId, "enabled", false));

            assertEquals("ack", out.op(), "关闭方向不该被权限面挡住: " + out.payload());
            assertFalse(store.find(wallId, ruleId).orElseThrow().enabled());
        }

        @Test
        void deleteDoesNotRequireFacets() {
            seamOnlineDenyOnly(ScriptPermissions.NODE_COMMAND);

            Envelope out = dispatcher.handleDelete(envelope("script.delete"), SESSION_ID,
                    session, wallId, Map.of("ruleId", ruleId));

            assertEquals("ack", out.op(), "delete 不该查面: " + out.payload());
            assertTrue(store.find(wallId, ruleId).isEmpty());
        }

        private Envelope invoke(String handler) throws Exception {
            Method m = ScriptOpDispatcher.class.getDeclaredMethod(handler,
                    Envelope.class, String.class, Session.class, String.class, Map.class);
            m.setAccessible(true);
            Object out = m.invoke(dispatcher, envelope(opNameOf(handler)), SESSION_ID,
                    session, wallId, payloadFor(handler));
            assertNotNull(out, handler + " 应返回 Envelope");
            return (Envelope) out;
        }

        private static String opNameOf(String handler) {
            return "script." + handler.substring("handle".length()).toLowerCase(Locale.ROOT);
        }

        private Map<String, Object> payloadFor(String handler) {
            return switch (handler) {
                case "handleCreate" -> Map.of("rule", commandRuleWire());
                case "handleUpdate" -> Map.of("ruleId", ruleId, "rule", commandRuleWire());
                case "handleEnable" -> Map.of("ruleId", ruleId, "enabled", true);
                case "handleTest", "handleDelete" -> Map.of("ruleId", ruleId);
                default -> throw new IllegalArgumentException("Unregistered handler: " + handler);
            };
        }

        private void resetRule() {
            store.clearWall(wallId);
            ruleId = store.create(wallId, commandRule("危险规则")).id();
        }

        private static ScriptRule commandRule(String name) {
            return new ScriptRule("sr-pending", "w-ignored", true, name,
                    new Trigger.WallReady(),
                    List.of(new Action.RunCommand("announce", Map.of("msg", "hi"))),
                    "{}");
        }

        private static Map<String, Object> commandRuleWire() {
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("name", "危险规则");
            rule.put("trigger", Map.of("type", "wallReady"));
            rule.put("actions", List.of(Map.of(
                    "type", "runCommand",
                    "templateId", "announce",
                    "params", Map.of("msg", "hi"))));
            return rule;
        }
    }
}
