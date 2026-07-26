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
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守卫：{@link ScriptOpDispatcher} 每个会改动"规则内容 / 启用态"的 op 都必须过权限面检查。
 *
 * <p>这条守卫是从一次真实漏检里长出来的：{@code script.enable} 抄了兄弟 handler 的骨架
 * （查基础权限 → 改 store → 推 patch → 记 audit），唯独没抄 create/update/test 都有的
 * {@code checkFacets}。后果是服主收回 {@code canvas.script.command} 之后，任何能打开这面墙的
 * 玩家（草稿墙人人可开）把旧规则重新打开就能继续以 <b>console 身份</b>跑命令。
 * 破口不在核心路径，而在"同族 op 里被漏掉的那一个"——所以守卫也按族来写，不是只补一个 case。</p>
 *
 * <h2>两层守卫</h2>
 *
 * <ol>
 *   <li><b>结构层</b>：反射清点 {@code handleXxx} 方法集合，必须与本测试的分类表完全一致。
 *       新加一个 op 而不在表里登记 → 直接红，逼作者表态"它要不要查面"。</li>
 *   <li><b>行为层</b>：对表里标了"要查面"的每个 op，用一条带 {@code runCommand} 的规则
 *       （面 = {@code canvas.script.command}）驱动一遍：收回该节点必须回
 *       {@code PERMISSION_DENIED} + 记 audit；对照组给齐节点必须成功。
 *       两边都断言才排除"其实是别的原因失败了"。</li>
 * </ol>
 *
 * <p>装配同 {@code ScriptOpPermissionDispatchTest}：真 SQLite + 真 WallRepo + 真 AuditLog +
 * {@link MainThreadPerms#testResolver} 注入权限解析结果；handler 直驱（绕开 final 的
 * SessionManager / WsMessageContext）。</p>
 */
class ScriptOpFacetGuardTest {

    private static final String SESSION_ID = "sess-facet-guard";
    private static final UUID CALLER = UUID.randomUUID();
    private static final Logger LOG =
            Logger.getLogger(ScriptOpFacetGuardTest.class.getName());

    /**
     * op 分类表 —— 新增 handler 必须在这里登记。
     *
     * <p>value = 该 op 是否必须过 {@code checkFacets}。当前只有 {@code handleDelete} 是 false：
     * 删规则是收敛动作，不该被"你没有命令权限"挡住。{@code handleEnable} 的关闭方向同理，
     * 但开启方向必须查——两个方向的差异由本类的 enableFalse 用例单独钉住。</p>
     */
    private static final Map<String, Boolean> HANDLERS_REQUIRING_FACET_CHECK = Map.of(
            "handleCreate", true,
            "handleUpdate", true,
            "handleEnable", true,
            "handleTest", true,
            "handleDelete", false);

    private Path tmpDir;
    private Database database;
    private WallRepo wallRepo;
    private ScriptStore store;
    private AuditLog auditLog;
    private ScriptOpDispatcher dispatcher;
    private Session session;
    private String wallId;
    private String ruleId;

    @BeforeEach
    void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("hikari-facet-guard-");
        database = new Database(LOG, tmpDir.resolve("data.db"));
        new MigrationRunner(database.jdbi(), LOG).run();
        wallRepo = new WallRepo(LOG, database.jdbi());
        wallId = wallRepo.createWithMapIds(
                new WallKey("world", 0, 64, 0, BlockFace.NORTH),
                new ProjectState(1, 1), List.of(0), 1, 1, CALLER, "tester");
        store = new ScriptStore(LOG, /*dao=*/null, /*maxRulesPerWall=*/16);
        auditLog = new AuditLog(database.jdbi(), LOG);
        OpPushCallback push = new OpPushCallback() {
            @Override
            public boolean pushSnapshot(String sessionId, ProjectState state) {
                return true;
            }

            @Override
            public boolean pushPatch(String sessionId, StatePatch patch) {
                return true;
            }
        };
        dispatcher = new ScriptOpDispatcher(/*sessionManager=*/null, /*rateLimiter=*/null,
                store, wallRepo, push, auditLog, /*plugin=*/null, LOG, /*messages=*/null);
        // script.test 的 launcher 缺失会在面检查之前就返 SCRIPT_ENGINE_UNAVAILABLE，
        // 装一个什么都不做的替身，让 test 路径真的走到面检查
        dispatcher.setTestLauncher((w, r, cb) -> { });
        session = SessionTestFactory.withWall(SESSION_ID, CALLER, "tester", wallId);
        // 库里预置一条含 runCommand 的规则（update / enable / test / delete 都用它）
        ruleId = store.create(wallId, commandRule("危险规则")).id();
    }

    @AfterEach
    void tearDown() throws Exception {
        MainThreadPerms.testResolver = null;
        if (database != null) database.close();
        if (tmpDir != null && Files.exists(tmpDir)) {
            try (var s = Files.walk(tmpDir)) {
                s.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    //  ① 结构层：新增 handler 必须登记
    // ──────────────────────────────────────────────────────────

    @Test
    void everyHandlerIsClassifiedInThisGuard() {
        Set<String> declared = new TreeSet<>();
        for (Method m : ScriptOpDispatcher.class.getDeclaredMethods()) {
            if (m.getName().startsWith("handle")) declared.add(m.getName());
        }
        assertEquals(new TreeSet<>(HANDLERS_REQUIRING_FACET_CHECK.keySet()), declared,
                "新增 script.* handler 必须在 HANDLERS_REQUIRING_FACET_CHECK 里登记"
                        + "（要不要过 checkFacets 得有人明确表态，不能默默漏掉）");
    }

    // ──────────────────────────────────────────────────────────
    //  ② 行为层：标了要查面的 op，缺面必拒；给齐必成
    // ──────────────────────────────────────────────────────────

    @Test
    void everyMutatingOpDeniesWhenCommandFacetIsRevoked() throws Exception {
        for (Map.Entry<String, Boolean> e : HANDLERS_REQUIRING_FACET_CHECK.entrySet()) {
            if (!e.getValue()) continue;
            String handler = e.getKey();
            // 每个 op 重置一次库与 audit，避免相互影响
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

            assertEquals("ack", out.op(),
                    handler + " 面齐全应放行（否则上一条测试的『拒』说明不了是面检查起的作用）");
        }
    }

    // ──────────────────────────────────────────────────────────
    //  ③ 两个方向：enable=true 查面，enable=false 永远放行
    // ──────────────────────────────────────────────────────────

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
        // 关规则是收敛动作：哪怕连基础面都被收回，也必须能关掉
        seamOnlineDenyOnly(ScriptPermissions.NODE_COMMAND);

        Envelope out = dispatcher.handleEnable(envelope("script.enable"), SESSION_ID,
                session, wallId, Map.of("ruleId", ruleId, "enabled", false));

        assertEquals("ack", out.op(), "关闭方向不该被权限面挡住: " + out.payload());
        assertEquals(false, store.find(wallId, ruleId).orElseThrow().enabled());
    }

    /** delete 不查面：清理动作不该被"你没有命令权限"卡住。 */
    @Test
    void deleteDoesNotRequireFacets() {
        seamOnlineDenyOnly(ScriptPermissions.NODE_COMMAND);

        Envelope out = dispatcher.handleDelete(envelope("script.delete"), SESSION_ID,
                session, wallId, Map.of("ruleId", ruleId));

        assertEquals("ack", out.op(), "delete 不该查面: " + out.payload());
        assertTrue(store.find(wallId, ruleId).isEmpty());
    }

    // ──────────────────────────────────────────────────────────
    //  helpers
    // ──────────────────────────────────────────────────────────

    /** 按 handler 名反射调用（5 个 handler 签名一致，便于表驱动遍历）。 */
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
        return "script." + handler.substring("handle".length()).toLowerCase(java.util.Locale.ROOT);
    }

    private Map<String, Object> payloadFor(String handler) {
        return switch (handler) {
            case "handleCreate" -> Map.of("rule", commandRuleWire());
            case "handleUpdate" -> Map.of("ruleId", ruleId, "rule", commandRuleWire());
            case "handleEnable" -> Map.of("ruleId", ruleId, "enabled", true);
            case "handleTest", "handleDelete" -> Map.of("ruleId", ruleId);
            default -> throw new IllegalArgumentException("未登记的 handler: " + handler);
        };
    }

    /** 库里预置的规则被 delete / update 改过后复位，保证各 op 起点一致。 */
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

    /** wire 形态（dispatcher 的 parseIncomingRule 吃的是 Map）。 */
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

    private static void seamOnlineAllGranted(boolean granted) {
        MainThreadPerms.testResolver = (uuid, nodes) -> {
            boolean[] g = new boolean[nodes.length];
            java.util.Arrays.fill(g, granted);
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
}
