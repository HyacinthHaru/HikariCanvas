package moe.hikari.canvas.web;

import moe.hikari.canvas.script.ScriptRule;
import moe.hikari.canvas.script.ScriptStore;
import moe.hikari.canvas.session.Session;
import moe.hikari.canvas.session.SessionTestFactory;
import moe.hikari.canvas.state.PatchOp;
import moe.hikari.canvas.state.ProjectState;
import moe.hikari.canvas.state.StatePatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private ScriptStore store;
    private ScriptOpDispatcher dispatcher;
    private Session session;
    private final List<StatePatch> pushedPatches = new ArrayList<>();

    @BeforeEach
    void setup() {
        pushedPatches.clear();
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
        };
        // sessionManager / rateLimiter / wallRepo / auditLog 在 handler 路径不被触碰，传 null；
        // plugin=null → MainThreadPerms 直接调用路径（本测试规则 facets 为空，不会走到）。
        dispatcher = new ScriptOpDispatcher(/*sessionManager=*/null, /*rateLimiter=*/null,
                store, /*wallRepo=*/null, push, /*auditLog=*/null, /*plugin=*/null, LOG);
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
    //  ④ test 的 seam 前守卫（#6）
    // ──────────────────────────────────────────────────────────

    @Test
    void testWithMissingRuleIdReturnsScriptNotFoundBeforeSeam() {
        boolean[] seamCalled = {false};
        dispatcher.setTestSeam((wallId, ruleId) -> {
            seamCalled[0] = true;
            return Map.of("ok", true);
        });

        Envelope env = dispatcher.handleTest(envelope("script.test"),
                WALL, Map.of("ruleId", "sr-missing1"));

        assertEquals("error", env.op());
        assertEquals("SCRIPT_NOT_FOUND", codeOf(env));
        assertFalse(seamCalled[0], "不存在的 ruleId 不应触达 seam");

        // 存在的 ruleId 正常透传 seam
        ScriptRule rule = seedRule(store, WALL, "可试运行");
        Envelope ok = dispatcher.handleTest(envelope("script.test"),
                WALL, Map.of("ruleId", rule.id()));
        assertEquals("ack", ok.op());
        assertTrue(seamCalled[0]);
        assertNotNull(ok.payload());
    }
}
