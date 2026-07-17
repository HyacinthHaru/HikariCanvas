package ac.haru.hikaricanvas.web;

import ac.haru.hikaricanvas.script.Action;
import ac.haru.hikaricanvas.script.ScriptRule;
import ac.haru.hikaricanvas.script.ScriptStore;
import ac.haru.hikaricanvas.script.ValidationError;
import ac.haru.hikaricanvas.session.Session;
import ac.haru.hikaricanvas.session.SessionTestFactory;
import ac.haru.hikaricanvas.state.PatchOp;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.state.StatePatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.7.0-P1-7b #3：{@link ScriptOpDispatcher} 行为级最小测试（dispatch handler 级）。
 *
 * <p>照 {@link TimelinePlaybackDispatchTest} 的范式：{@code WsMessageContext} 是
 * Javalin final 类不可 mock，直接驱动 package-private 的 {@code handleCreate /
 * handleUpdate / handleDelete / handleTest}（返回待发送的 {@link Envelope}），绕开
 * WS 装配链。装配：纯内存 {@link ScriptStore}（dao=null）+ plugin=null（MainThreadPerms
 * 直接调用路径，但本测试的规则均为 log 动作 + wallReady/variableChange 触发 →
 * requiredFacets 为空，不触碰 Bukkit）+ fake {@link OpPushCallback} 捕获 patch。</p>
 *
 * <p>覆盖：① update 非本墙 ruleId → SCRIPT_NOT_FOUND（跨墙 guard 回归）
 * ② create 超配额 → SCRIPT_QUOTA_EXCEEDED ③ delete 不存在 ruleId → ack
 * removed=false 且仍推 remove patch（幂等收敛）④ test 不存在 ruleId →
 * SCRIPT_NOT_FOUND（#6 seam 前守卫）。</p>
 */
class ScriptOpDispatchBehaviorTest {

    private static final String WALL = "w-deadbeef";
    private static final String OTHER_WALL = "w-other999";
    private static final String SESSION_ID = "sess-1";
    private static final UUID CALLER = UUID.randomUUID();
    private static final Logger LOG =
            Logger.getLogger(ScriptOpDispatchBehaviorTest.class.getName());

    /** A2：pushOp 捕获（script.trace 推送 wire 形态断言用）。 */
    record PushedOp(String sessionId, String op, Object payload) {}

    private ScriptStore store;
    private ScriptOpDispatcher dispatcher;
    private Session session;
    private final List<StatePatch> pushedPatches = new ArrayList<>();
    private final List<PushedOp> pushedOps = new ArrayList<>();

    @BeforeEach
    void setup() {
        pushedPatches.clear();
        pushedOps.clear();
        store = new ScriptStore(LOG, /*dao=*/null, /*maxRulesPerWall=*/1);
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

            @Override
            public boolean pushOp(String sessionId, String op, Object payload) {
                pushedOps.add(new PushedOp(sessionId, op, payload));
                return true;
            }
        };
        // sessionManager / rateLimiter / wallRepo / auditLog 在 handler 路径不被触碰，传 null；
        // plugin=null → MainThreadPerms 直接调用路径（本测试规则 facets 为空，不会走到）。
        dispatcher = new ScriptOpDispatcher(/*sessionManager=*/null, /*rateLimiter=*/null,
                store, /*wallRepo=*/null, push, /*auditLog=*/null, /*plugin=*/null, LOG, /*messages=*/null);
        session = SessionTestFactory.withWall(SESSION_ID, CALLER, "tester", WALL);
    }

    // ──────────────────────────────────────────────────────────
    //  helpers
    // ──────────────────────────────────────────────────────────

    /** 合法最小 rule payload（log 动作 + wallReady 触发 → 零权限面，零 Bukkit）。 */
    private static Map<String, Object> rulePayload(String name) {
        return Map.of("rule", Map.of(
                "name", name,
                "trigger", Map.of("type", "wallReady"),
                "actions", List.of(Map.of("type", "log", "message", "x"))));
    }

    /** 直接往 store 塞一条规则（绕 dispatcher），返回生成的规则。 */
    private static ScriptRule seedRule(ScriptStore store, String wallId, String name) {
        ScriptOpDispatcher.ParsedRule parsed =
                ScriptOpDispatcher.parseIncomingRule(rulePayload(name), wallId);
        assertNull(parsed.error(), "seed rule 应合法: " + parsed.error());
        return store.create(wallId, parsed.rule());
    }

    private static Envelope envelope(String op) {
        return Envelope.of(op, "c-1", Map.of());
    }

    /** 从 ack/error Envelope 取 payload code（error 才有；ack 返 null）。 */
    private static String codeOf(Envelope env) {
        Object p = env.payload();
        return (p instanceof Map<?, ?> m) ? (String) m.get("code") : null;
    }

    // ──────────────────────────────────────────────────────────
    //  ① 跨墙 guard 回归
    // ──────────────────────────────────────────────────────────

    @Test
    void updateWithOtherWallsRuleIdReturnsScriptNotFound() {
        // 规则建在别人的墙上；session 绑 WALL → update 必须被"find 先行"挡掉，
        // 不能透传给 store.update（它按反查索引定位，会改到别人墙）
        ScriptRule foreign = seedRule(store, OTHER_WALL, "别人墙的规则");

        Map<String, Object> payload = new LinkedHashMap<>(rulePayload("改名"));
        payload.put("ruleId", foreign.id());
        Envelope env = dispatcher.handleUpdate(envelope("script.update"),
                SESSION_ID, session, WALL, payload);

        assertEquals("error", env.op());
        assertEquals("SCRIPT_NOT_FOUND", codeOf(env));
        // 别人墙的规则原封不动 + 没有 patch 外推
        assertEquals("别人墙的规则",
                store.find(OTHER_WALL, foreign.id()).orElseThrow().name());
        assertTrue(pushedPatches.isEmpty(), "被拒的 update 不应推 patch");
    }

    // ──────────────────────────────────────────────────────────
    //  ② 配额
    // ──────────────────────────────────────────────────────────

    @Test
    void createBeyondQuotaReturnsQuotaExceeded() {
        // maxRulesPerWall=1：先占满
        seedRule(store, WALL, "第一条");

        Envelope env = dispatcher.handleCreate(envelope("script.create"),
                SESSION_ID, session, WALL, rulePayload("第二条"));

        assertEquals("error", env.op());
        assertEquals("SCRIPT_QUOTA_EXCEEDED", codeOf(env));
        assertEquals(1, store.listByWall(WALL).size(), "超配额不应入库");
        assertTrue(pushedPatches.isEmpty(), "被拒的 create 不应推 patch");
    }

    // ──────────────────────────────────────────────────────────
    //  ③ delete 幂等
    // ──────────────────────────────────────────────────────────

    @Test
    void deleteMissingRuleAcksRemovedFalseAndStillPushesRemovePatch() {
        Envelope env = dispatcher.handleDelete(envelope("script.delete"),
                SESSION_ID, session, WALL, Map.of("ruleId", "sr-missing1"));

        assertEquals("ack", env.op());
        Object p = env.payload();
        assertTrue(p instanceof Map<?, ?>, "ack payload 应为 map");
        Map<?, ?> ack = (Map<?, ?>) p;
        assertEquals("sr-missing1", ack.get("ruleId"));
        assertEquals(Boolean.FALSE, ack.get("removed"), "不存在的规则 removed 应为 false");

        // 幂等：即便不存在也推 remove patch，让前端 mirror 收敛
        assertEquals(1, pushedPatches.size(), "delete 应恒推一条 patch");
        List<PatchOp> ops = pushedPatches.get(0).ops();
        assertEquals(1, ops.size());
        assertEquals("remove", ops.get(0).op());
        assertEquals("/scripts/sr-missing1", ops.get(0).path());
    }

    @Test
    void deleteExistingRuleAcksRemovedTrue() {
        ScriptRule rule = seedRule(store, WALL, "要删的");

        Envelope env = dispatcher.handleDelete(envelope("script.delete"),
                SESSION_ID, session, WALL, Map.of("ruleId", rule.id()));

        assertEquals("ack", env.op());
        assertEquals(Boolean.TRUE, ((Map<?, ?>) env.payload()).get("removed"));
        assertTrue(store.find(WALL, rule.id()).isEmpty(), "规则应被真删");
        assertEquals(1, pushedPatches.size());
        assertEquals("remove", pushedPatches.get(0).ops().get(0).op());
    }

    // ──────────────────────────────────────────────────────────
    //  ③.5 0.7.0-P2-1：update 缺 enabled 继承现值（dispatch 全链）
    // ──────────────────────────────────────────────────────────

    @Test
    void updateWithoutEnabledKeepsDisabledRuleDisabled() {
        ScriptRule rule = seedRule(store, WALL, "已禁用的");
        store.setEnabled(WALL, rule.id(), false);

        // 第三方 WS 客户端式 update：payload 不带 enabled 字段
        Map<String, Object> payload = new LinkedHashMap<>(rulePayload("改名了"));
        payload.put("ruleId", rule.id());
        Envelope env = dispatcher.handleUpdate(envelope("script.update"),
                SESSION_ID, session, WALL, payload);

        assertEquals("ack", env.op());
        ScriptRule after = store.find(WALL, rule.id()).orElseThrow();
        assertEquals("改名了", after.name(), "其余字段应正常更新");
        assertFalse(after.enabled(), "缺 enabled 的 update 不得悄悄重启已禁用规则");

        // 对照：显式 enabled=true 才能重新启用
        Map<String, Object> ruleOn = new LinkedHashMap<>();
        ruleOn.put("name", "再启用");
        ruleOn.put("enabled", true);
        ruleOn.put("trigger", Map.of("type", "wallReady"));
        ruleOn.put("actions", List.of(Map.of("type", "log", "message", "x")));
        Map<String, Object> payloadOn = new LinkedHashMap<>();
        payloadOn.put("ruleId", rule.id());
        payloadOn.put("rule", ruleOn);
        Envelope envOn = dispatcher.handleUpdate(envelope("script.update"),
                SESSION_ID, session, WALL, payloadOn);
        assertEquals("ack", envOn.op());
        assertTrue(store.find(WALL, rule.id()).orElseThrow().enabled());
    }

    // ──────────────────────────────────────────────────────────
    //  ④ test 的 launcher 前守卫（#6）+ K11 异步 ack / script.trace 推送
    // ──────────────────────────────────────────────────────────

    @Test
    void testWithMissingRuleIdReturnsScriptNotFoundBeforeLauncher() {
        boolean[] launcherCalled = {false};
        dispatcher.setTestLauncher((wallId, ruleId, cb) -> launcherCalled[0] = true);

        Envelope env = dispatcher.handleTest(envelope("script.test"),
                SESSION_ID, session, WALL, Map.of("ruleId", "sr-missing1"));

        assertEquals("error", env.op());
        assertEquals("SCRIPT_NOT_FOUND", codeOf(env));
        assertFalse(launcherCalled[0], "不存在的 ruleId 不应触达 launcher");
    }

    @Test
    void testWithoutLauncherReturnsEngineUnavailable() {
        ScriptRule rule = seedRule(store, WALL, "未接引擎");
        Envelope env = dispatcher.handleTest(envelope("script.test"),
                SESSION_ID, session, WALL, Map.of("ruleId", rule.id()));
        assertEquals("error", env.op());
        assertEquals("SCRIPT_ENGINE_UNAVAILABLE", codeOf(env));
    }

    @Test
    void testAckIsImmediateAcceptedForm_notTrace() {
        // K11：ack 不等执行——launcher 不调 callback，ack 也必须立即返 accepted 形态
        dispatcher.setTestLauncher((wallId, ruleId, cb) -> { /* 异步执行中，不回调 */ });
        ScriptRule rule = seedRule(store, WALL, "异步试跑");

        Envelope env = dispatcher.handleTest(envelope("script.test"),
                SESSION_ID, session, WALL, Map.of("ruleId", rule.id()));

        assertEquals("ack", env.op());
        Map<?, ?> payload = (Map<?, ?>) env.payload();
        assertEquals(Boolean.TRUE, payload.get("accepted"));
        assertEquals(rule.id(), payload.get("ruleId"));
        assertNull(payload.get("steps"), "ack 不携带轨迹（轨迹走 script.trace 推送）");
    }

    @Test
    void traceCallbackPushesScriptTraceOpWithWireSteps() {
        ScriptRule rule = seedRule(store, WALL, "轨迹推送");
        // launcher 同步回调（模拟 runner 完成）；detail=null 的 step 验证 wire 省略该键
        dispatcher.setTestLauncher((wallId, ruleId, cb) -> cb.accept(List.of(
                new ac.haru.hikaricanvas.script.engine.TraceStep("trigger", "trigger", "ok", "TEST test"),
                new ac.haru.hikaricanvas.script.engine.TraceStep("actions/0", "action", "ok", null),
                new ac.haru.hikaricanvas.script.engine.TraceStep("actions/1", "action", "error", "boom"))));

        dispatcher.handleTest(envelope("script.test"),
                SESSION_ID, session, WALL, Map.of("ruleId", rule.id()));

        assertEquals(1, pushedOps.size(), "恰一次 script.trace 推送");
        PushedOp pushed = pushedOps.get(0);
        assertEquals(SESSION_ID, pushed.sessionId());
        assertEquals("script.trace", pushed.op());
        Map<?, ?> payload = (Map<?, ?>) pushed.payload();
        assertEquals(rule.id(), payload.get("ruleId"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) payload.get("steps");
        assertEquals(3, steps.size());
        assertEquals(Map.of("blockId", "trigger", "kind", "trigger",
                "result", "ok", "detail", "TEST test"), steps.get(0));
        assertEquals(Map.of("blockId", "actions/0", "kind", "action", "result", "ok"),
                steps.get(1), "detail=null 不进 wire（Map.of 无 null 值，前端读 undefined）");
        assertEquals("boom", steps.get(2).get("detail"));
    }

    // ──────────────────────────────────────────────────────────
    //  ⑤ K16：if.condition 保存期预 parse
    // ──────────────────────────────────────────────────────────

    /** 含 if 动作的 rule payload（condition 由参数给）。 */
    private static Map<String, Object> rulePayloadWithIf(String name, String condition) {
        return Map.of("rule", Map.of(
                "name", name,
                "trigger", Map.of("type", "wallReady"),
                "actions", List.of(Map.of(
                        "type", "if",
                        "condition", condition,
                        "then", List.of(Map.of("type", "log", "message", "t")),
                        "else", List.of()))));
    }

    @Test
    void createWithBadConditionRejectedScriptInvalid() {
        Envelope env = dispatcher.handleCreate(envelope("script.create"),
                SESSION_ID, session, WALL, rulePayloadWithIf("坏条件", "(((("));
        assertEquals("error", env.op());
        assertEquals("SCRIPT_INVALID", codeOf(env));
        assertTrue(pushedPatches.isEmpty(), "被拒的 create 不应推 patch");
        assertTrue(store.listByWall(WALL).isEmpty(), "坏条件规则不得入库");
    }

    @Test
    void createWithGoodConditionPasses_andNestedBadConditionRejected() {
        Envelope ok = dispatcher.handleCreate(envelope("script.create"),
                SESSION_ID, session, WALL,
                rulePayloadWithIf("好条件", "var(\"user/score\") >= 10"));
        assertEquals("ack", ok.op());

        // 嵌套 then 里的坏条件同样拦（递归走树）
        Map<String, Object> nested = Map.of("rule", Map.of(
                "name", "嵌套坏条件",
                "trigger", Map.of("type", "wallReady"),
                "actions", List.of(Map.of(
                        "type", "if",
                        "condition", "1 == 1",
                        "then", List.of(Map.of(
                                "type", "if",
                                "condition", "&&&&",
                                "then", List.of(Map.of("type", "log", "message", "x")),
                                "else", List.of())),
                        "else", List.of()))));
        // store 配额=1，先清掉已建的（delete 走 store 直删，绕 dispatcher）
        store.listByWall(WALL).forEach(r -> store.delete(WALL, r.id()));
        Envelope bad = dispatcher.handleCreate(envelope("script.create"),
                SESSION_ID, session, WALL, nested);
        assertEquals("error", bad.op());
        assertEquals("SCRIPT_INVALID", codeOf(bad));

        // 0.9.7：错误的 blockId 定位不再拼进消息串（已结构化进 ValidationError.params），
        // 直接对同构动作树验 key + blockId 参（本 dispatcher 装配 messages=null，
        // renderValidation 只回退 key 名，无法从消息串取 blockId）。
        Action badIf = new Action.If("&&&&",
                List.of(new Action.Log("x")), List.of());
        Action outerIf = new Action.If("1 == 1", List.of(badIf), List.of());
        Optional<ValidationError> ve =
                ScriptOpDispatcher.checkConditionSyntax(List.of(outerIf));
        assertTrue(ve.isPresent(), "嵌套 then 里的坏条件应被预检拒");
        assertEquals("conditionSyntaxIf", ve.get().key());
        assertEquals("actions/0/then/0", ve.get().params().get("blockId"),
                "ValidationError 带 blockId 定位");
    }
}
