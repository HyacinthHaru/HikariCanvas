package ac.haru.hikaricanvas.web;

import ac.haru.hikaricanvas.script.Action;
import ac.haru.hikaricanvas.script.ScriptRule;
import ac.haru.hikaricanvas.script.ScriptRuleValidator;
import ac.haru.hikaricanvas.script.ScriptStore;
import ac.haru.hikaricanvas.script.Trigger;
import ac.haru.hikaricanvas.script.ValidationError;
import ac.haru.hikaricanvas.script.engine.TraceStep;
import ac.haru.hikaricanvas.session.Session;
import ac.haru.hikaricanvas.session.SessionTestFactory;
import ac.haru.hikaricanvas.state.PatchOp;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.state.StatePatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScriptOpDispatcher} 规则解析、保存期条件语法预检与 handler 行为单测。
 */
class ScriptOpDispatcherTest {

    private static final String WALL = "w-deadbeef";
    private static final String OTHER_WALL = "w-other999";
    private static final String SESSION_ID = "sess-1";
    private static final UUID CALLER = UUID.randomUUID();
    private static final Logger LOG = Logger.getLogger(ScriptOpDispatcherTest.class.getName());

    @Nested
    class RuleParsingAndConditionPrecheck {

        @Test
        void minimalValidRuleParses() {
            Map<String, Object> payload = Map.of("rule", Map.of(
                    "name", "测试规则",
                    "trigger", Map.of("type", "variableChange", "fullName", "user/score"),
                    "actions", List.of(Map.of("type", "log", "message", "hi"))));
            ScriptOpDispatcher.ParsedRule parsed =
                    ScriptOpDispatcher.parseIncomingRule(payload, WALL);

            assertNull(parsed.error(), "合法 rule 不应报错: " + parsed.error());
            ScriptRule rule = parsed.rule();
            assertNotNull(rule);
            assertEquals("测试规则", rule.name());
            assertInstanceOf(Trigger.VariableChange.class, rule.trigger());
            assertEquals(1, rule.actions().size());
            assertInstanceOf(Action.Log.class, rule.actions().get(0));
            assertEquals(Optional.empty(), ScriptRuleValidator.validate(rule));
        }

        @Test
        void missingTriggerParsesButFailsValidation() {
            Map<String, Object> payload = Map.of("rule", Map.of(
                    "name", "无触发器",
                    "actions", List.of(Map.of("type", "log", "message", "x"))));
            ScriptOpDispatcher.ParsedRule parsed =
                    ScriptOpDispatcher.parseIncomingRule(payload, WALL);

            assertNull(parsed.error());
            assertNull(parsed.rule().trigger());
            Optional<ValidationError> err = ScriptRuleValidator.validate(parsed.rule());
            assertTrue(err.isPresent());
            assertEquals("triggerMissing", err.get().key(), "validator 应报缺触发器: " + err.get());
        }

        @Test
        void unknownTriggerTypeIsParseError() {
            Map<String, Object> payload = Map.of("rule", Map.of(
                    "name", "坏触发器",
                    "trigger", Map.of("type", "explode"),
                    "actions", List.of(Map.of("type", "log", "message", "x"))));
            ScriptOpDispatcher.ParsedRule parsed =
                    ScriptOpDispatcher.parseIncomingRule(payload, WALL);

            assertNull(parsed.rule());
            assertNotNull(parsed.error());
        }

        @Test
        void enabledDefaultsToTrue() {
            Map<String, Object> payload = Map.of("rule", Map.of(
                    "name", "r",
                    "trigger", Map.of("type", "wallReady"),
                    "actions", List.of(Map.of("type", "log", "message", "x"))));
            ScriptOpDispatcher.ParsedRule parsed =
                    ScriptOpDispatcher.parseIncomingRule(payload, WALL);
            assertNull(parsed.error());
            assertTrue(parsed.rule().enabled(), "enabled 缺省应为 true");

            Map<String, Object> payloadOff = Map.of("rule", Map.of(
                    "name", "r",
                    "enabled", false,
                    "trigger", Map.of("type", "wallReady"),
                    "actions", List.of(Map.of("type", "log", "message", "x"))));
            ScriptOpDispatcher.ParsedRule parsedOff =
                    ScriptOpDispatcher.parseIncomingRule(payloadOff, WALL);
            assertNull(parsedOff.error());
            assertFalse(parsedOff.rule().enabled(), "显式 enabled=false 应保留");
        }

        @Test
        void enabledFallbackInheritsCurrentValueWhenMissing() {
            Map<String, Object> noEnabled = Map.of("rule", Map.of(
                    "name", "r",
                    "trigger", Map.of("type", "wallReady"),
                    "actions", List.of(Map.of("type", "log", "message", "x"))));
            ScriptOpDispatcher.ParsedRule inherited =
                    ScriptOpDispatcher.parseIncomingRule(noEnabled, WALL, Boolean.FALSE);
            assertNull(inherited.error());
            assertFalse(inherited.rule().enabled(), "缺 enabled 应继承 fallback=false");

            Map<String, Object> explicitOn = Map.of("rule", Map.of(
                    "name", "r",
                    "enabled", true,
                    "trigger", Map.of("type", "wallReady"),
                    "actions", List.of(Map.of("type", "log", "message", "x"))));
            ScriptOpDispatcher.ParsedRule explicit =
                    ScriptOpDispatcher.parseIncomingRule(explicitOn, WALL, Boolean.FALSE);
            assertNull(explicit.error());
            assertTrue(explicit.rule().enabled(), "显式 enabled=true 应覆盖 fallback");
        }

        @Test
        void enabledStringIsRejectedNotCoerced() {
            Map<String, Object> payload = Map.of("rule", Map.of(
                    "name", "r",
                    "enabled", "false",
                    "trigger", Map.of("type", "wallReady"),
                    "actions", List.of(Map.of("type", "log", "message", "x"))));
            ScriptOpDispatcher.ParsedRule parsed =
                    ScriptOpDispatcher.parseIncomingRule(payload, WALL);

            assertNull(parsed.rule());
            assertNotNull(parsed.error(), "enabled 类型错必须拒");
            assertTrue(parsed.error().contains("enabled"), "错误信息应点名 enabled: " + parsed.error());
        }

        @Test
        void blockLayoutDefaultsToEmptyObject() {
            Map<String, Object> payload = Map.of("rule", Map.of(
                    "name", "r",
                    "trigger", Map.of("type", "wallReady"),
                    "actions", List.of(Map.of("type", "log", "message", "x"))));
            ScriptOpDispatcher.ParsedRule parsed =
                    ScriptOpDispatcher.parseIncomingRule(payload, WALL);
            assertNull(parsed.error());
            assertEquals("{}", parsed.rule().blockLayout());
        }

        @Test
        void clientSuppliedIdAndWallIdAreOverwritten() {
            Map<String, Object> payload = Map.of("rule", Map.of(
                    "id", "sr-evil666",
                    "wallId", "w-other999",
                    "name", "r",
                    "trigger", Map.of("type", "wallReady"),
                    "actions", List.of(Map.of("type", "log", "message", "x"))));
            ScriptOpDispatcher.ParsedRule parsed =
                    ScriptOpDispatcher.parseIncomingRule(payload, WALL);

            assertNull(parsed.error());
            assertNotEquals("sr-evil666", parsed.rule().id(), "客户端假 id 必须被覆写");
            assertEquals(WALL, parsed.rule().wallId(), "wallId 必须是 session 的 wall");
        }

        @Test
        void actionsNotArrayIsParseError() {
            Map<String, Object> payload = Map.of("rule", Map.of(
                    "name", "r",
                    "trigger", Map.of("type", "wallReady"),
                    "actions", "boom"));
            ScriptOpDispatcher.ParsedRule parsed =
                    ScriptOpDispatcher.parseIncomingRule(payload, WALL);

            assertNull(parsed.rule());
            assertNotNull(parsed.error());
        }

        @Test
        void ruleMissingOrNotObjectIsParseError() {
            ScriptOpDispatcher.ParsedRule missing =
                    ScriptOpDispatcher.parseIncomingRule(Map.of(), WALL);
            assertNull(missing.rule());
            assertNotNull(missing.error());

            ScriptOpDispatcher.ParsedRule notObject =
                    ScriptOpDispatcher.parseIncomingRule(Map.of("rule", "boom"), WALL);
            assertNull(notObject.rule());
            assertNotNull(notObject.error());
        }

        @Test
        void checkConditionSyntax_waitUntilBadCondition_rejected() {
            var actions = List.<Action>of(new Action.WaitUntil("(((", 5000));
            assertTrue(ScriptOpDispatcher.checkConditionSyntax(actions).isPresent(),
                    "WaitUntil 坏条件应被保存期预检拒");
        }

        @Test
        void checkConditionSyntax_waitUntilGoodCondition_passes() {
            var actions = List.<Action>of(new Action.WaitUntil("var(\"user/x\") > 0", 5000));
            assertTrue(ScriptOpDispatcher.checkConditionSyntax(actions).isEmpty(),
                    "WaitUntil 合法条件通过预检");
        }

        @Test
        void checkConditionSyntax_recursesIntoRepeatBody() {
            var inner = List.<Action>of(new Action.If("(((", List.of(), List.of()));
            var actions = List.<Action>of(new Action.Repeat(2, inner));
            assertTrue(ScriptOpDispatcher.checkConditionSyntax(actions).isPresent(),
                    "repeat body 内坏条件也应被预检拒");
        }

        @Test
        void checkConditionSyntax_repeatUntilBadCondition_rejected() {
            var actions = List.<Action>of(new Action.RepeatUntil("(((", 10,
                    List.of(new Action.Log("x"))));
            assertTrue(ScriptOpDispatcher.checkConditionSyntax(actions).isPresent(),
                    "RepeatUntil 坏条件应被保存期预检拒");
        }

        @Test
        void checkConditionSyntax_repeatUntilGoodCondition_passes() {
            var actions = List.<Action>of(new Action.RepeatUntil("var(\"user/x\") > 0", 10,
                    List.of(new Action.Log("x"))));
            assertTrue(ScriptOpDispatcher.checkConditionSyntax(actions).isEmpty(),
                    "RepeatUntil 合法条件通过预检");
        }

        @Test
        void checkConditionSyntax_recursesIntoRepeatUntilBody() {
            var inner = List.<Action>of(new Action.If("(((", List.of(), List.of()));
            var actions = List.<Action>of(new Action.RepeatUntil("var(\"user/x\") > 0", 5, inner));
            assertTrue(ScriptOpDispatcher.checkConditionSyntax(actions).isPresent(),
                    "repeatUntil body 内坏条件也应被预检拒");
        }

        @Test
        void checkConditionSyntax_randomBranch_thenBranchBadIf_rejected() {
            var badIf = new Action.If("(((", List.of(), List.of());
            var actions = List.<Action>of(new Action.RandomBranch(50, List.of(badIf), List.of()));
            assertTrue(ScriptOpDispatcher.checkConditionSyntax(actions).isPresent(),
                    "RandomBranch then 分支内坏 If 条件应被保存期预检拒");
        }

        @Test
        void checkConditionSyntax_randomBranch_elseBranchBadWaitUntil_rejected() {
            var badWait = new Action.WaitUntil("(((", 5000);
            var actions = List.<Action>of(new Action.RandomBranch(50, List.of(), List.of(badWait)));
            assertTrue(ScriptOpDispatcher.checkConditionSyntax(actions).isPresent(),
                    "RandomBranch else 分支内坏 WaitUntil 条件应被保存期预检拒");
        }

        @Test
        void checkConditionSyntax_randomBranch_elseBranchBadRepeatUntil_rejected() {
            var badRU = new Action.RepeatUntil("(((", 5, List.of(new Action.Log("x")));
            var actions = List.<Action>of(new Action.RandomBranch(50, List.of(), List.of(badRU)));
            assertTrue(ScriptOpDispatcher.checkConditionSyntax(actions).isPresent(),
                    "RandomBranch else 分支内坏 RepeatUntil 条件应被保存期预检拒");
        }

        @Test
        void checkConditionSyntax_randomBranch_goodConditionsInBranches_passes() {
            var goodIf = new Action.If("var(\"user/x\") > 0", List.of(), List.of());
            var actions = List.<Action>of(new Action.RandomBranch(50, List.of(goodIf), List.of(goodIf)));
            assertTrue(ScriptOpDispatcher.checkConditionSyntax(actions).isEmpty(),
                    "RandomBranch 分支内合法条件应通过预检");
        }
    }

    @Nested
    class DispatchBehavior {

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
            store = new ScriptStore(LOG, null, 1);
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
            dispatcher = new ScriptOpDispatcher(null, null, store, null, push, null, null, LOG, null);
            session = SessionTestFactory.withWall(SESSION_ID, CALLER, "tester", WALL);
        }

        private static Map<String, Object> rulePayload(String name) {
            return Map.of("rule", Map.of(
                    "name", name,
                    "trigger", Map.of("type", "wallReady"),
                    "actions", List.of(Map.of("type", "log", "message", "x"))));
        }

        private static ScriptRule seedRule(ScriptStore store, String wallId, String name) {
            ScriptOpDispatcher.ParsedRule parsed =
                    ScriptOpDispatcher.parseIncomingRule(rulePayload(name), wallId);
            assertNull(parsed.error(), "seed rule 应合法: " + parsed.error());
            return store.create(wallId, parsed.rule());
        }

        private static Envelope envelope(String op) {
            return Envelope.of(op, "c-1", Map.of());
        }

        private static String codeOf(Envelope env) {
            Object p = env.payload();
            return (p instanceof Map<?, ?> m) ? (String) m.get("code") : null;
        }

        @Test
        void updateWithOtherWallsRuleIdReturnsScriptNotFound() {
            ScriptRule foreign = seedRule(store, OTHER_WALL, "别人墙的规则");

            Map<String, Object> payload = new LinkedHashMap<>(rulePayload("改名"));
            payload.put("ruleId", foreign.id());
            Envelope env = dispatcher.handleUpdate(envelope("script.update"),
                    SESSION_ID, session, WALL, payload);

            assertEquals("error", env.op());
            assertEquals("SCRIPT_NOT_FOUND", codeOf(env));
            assertEquals("别人墙的规则",
                    store.find(OTHER_WALL, foreign.id()).orElseThrow().name());
            assertTrue(pushedPatches.isEmpty(), "被拒的 update 不应推 patch");
        }

        @Test
        void createBeyondQuotaReturnsQuotaExceeded() {
            seedRule(store, WALL, "第一条");

            Envelope env = dispatcher.handleCreate(envelope("script.create"),
                    SESSION_ID, session, WALL, rulePayload("第二条"));

            assertEquals("error", env.op());
            assertEquals("SCRIPT_QUOTA_EXCEEDED", codeOf(env));
            assertEquals(1, store.listByWall(WALL).size(), "超配额不应入库");
            assertTrue(pushedPatches.isEmpty(), "被拒的 create 不应推 patch");
        }

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

        @Test
        void updateWithoutEnabledKeepsDisabledRuleDisabled() {
            ScriptRule rule = seedRule(store, WALL, "已禁用的");
            store.setEnabled(WALL, rule.id(), false);

            Map<String, Object> payload = new LinkedHashMap<>(rulePayload("改名了"));
            payload.put("ruleId", rule.id());
            Envelope env = dispatcher.handleUpdate(envelope("script.update"),
                    SESSION_ID, session, WALL, payload);

            assertEquals("ack", env.op());
            ScriptRule after = store.find(WALL, rule.id()).orElseThrow();
            assertEquals("改名了", after.name(), "其余字段应正常更新");
            assertFalse(after.enabled(), "缺 enabled 的 update 不得悄悄重启已禁用规则");

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
            dispatcher.setTestLauncher((wallId, ruleId, cb) -> {});
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
            dispatcher.setTestLauncher((wallId, ruleId, cb) -> cb.accept(List.of(
                    new TraceStep("trigger", "trigger", "ok", "TEST test"),
                    new TraceStep("actions/0", "action", "ok", null),
                    new TraceStep("actions/1", "action", "error", "boom"))));

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
                    steps.get(1), "detail=null 不进 wire");
            assertEquals("boom", steps.get(2).get("detail"));
        }

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
            store.listByWall(WALL).forEach(r -> store.delete(WALL, r.id()));
            Envelope bad = dispatcher.handleCreate(envelope("script.create"),
                    SESSION_ID, session, WALL, nested);
            assertEquals("error", bad.op());
            assertEquals("SCRIPT_INVALID", codeOf(bad));

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
}
