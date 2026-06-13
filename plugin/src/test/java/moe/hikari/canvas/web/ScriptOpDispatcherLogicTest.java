package moe.hikari.canvas.web;

import moe.hikari.canvas.script.Action;
import moe.hikari.canvas.script.ScriptRule;
import moe.hikari.canvas.script.ScriptRuleValidator;
import moe.hikari.canvas.script.Trigger;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.7.0 P1-6：{@link ScriptOpDispatcher#parseIncomingRule} 纯逻辑单测
 * （payload → ScriptRule 解析 + 缺省补齐 + 服务端字段覆写；零 Bukkit / 零 WS）。
 */
class ScriptOpDispatcherLogicTest {

    private static final String WALL = "w-deadbeef";

    /** 合法最小 rule：variableChange 触发 + log 动作。 */
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
        // 解析产物直接过 validator 应合法
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(rule));
    }

    /** rule 缺 trigger：解析成功（trigger=null），由 validator 拒（缺少触发器）。 */
    @Test
    void missingTriggerParsesButFailsValidation() {
        Map<String, Object> payload = Map.of("rule", Map.of(
                "name", "无触发器",
                "actions", List.of(Map.of("type", "log", "message", "x"))));
        ScriptOpDispatcher.ParsedRule parsed =
                ScriptOpDispatcher.parseIncomingRule(payload, WALL);

        assertNull(parsed.error());
        assertNull(parsed.rule().trigger());
        Optional<String> err = ScriptRuleValidator.validate(parsed.rule());
        assertTrue(err.isPresent());
        assertTrue(err.get().contains("触发器"), "validator 应报缺触发器: " + err.get());
    }

    /** trigger 非法 type → 解析期报错（INVALID_PAYLOAD 路径）。 */
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

    /** enabled 缺省 = true；显式 false 保留。 */
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

    /**
     * 0.7.0-P2-1：三参版 enabledFallback——update 路径缺 enabled 时继承现值
     * （fallback=FALSE 的 false 规则保持 false），不再被缺省 true 悄悄重启。
     * 显式给 enabled 时 fallback 不生效。
     */
    @Test
    void enabledFallbackInheritsCurrentValueWhenMissing() {
        Map<String, Object> noEnabled = Map.of("rule", Map.of(
                "name", "r",
                "trigger", Map.of("type", "wallReady"),
                "actions", List.of(Map.of("type", "log", "message", "x"))));
        // update 语义：现有规则 enabled=false → 缺字段继承 false
        ScriptOpDispatcher.ParsedRule inherited =
                ScriptOpDispatcher.parseIncomingRule(noEnabled, WALL, Boolean.FALSE);
        assertNull(inherited.error());
        assertFalse(inherited.rule().enabled(), "缺 enabled 应继承 fallback=false");

        // 显式 enabled=true 优先于 fallback
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

    /** 0.7.0-P1-7b #7：enabled 存在但非 Boolean（字符串 "false"）→ 真拒，不静默默认 true。 */
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
        assertNotNull(parsed.error(), "enabled 类型错必须拒（不能被默认成 true）");
        assertTrue(parsed.error().contains("enabled"), "错误信息应点名 enabled: " + parsed.error());
    }

    /** blockLayout 缺省 = "{}"。 */
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

    /** payload 带假 id / wallId → 被服务端值覆写（id 占位 + wallId=session）。 */
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

    /** actions 非数组 → 解析期报错。 */
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

    /** rule 字段整体缺失 / 非 object → 解析期报错。 */
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

    // ---------- 0.7.1-P5：WaitUntil 条件保存期预检（K16，对抗审查补）----------

    @Test
    void checkConditionSyntax_waitUntilBadCondition_rejected() {
        var actions = List.<Action>of(new Action.WaitUntil("(((", 5000));
        assertTrue(ScriptOpDispatcher.checkConditionSyntax(actions).isPresent(),
                "WaitUntil 坏条件应被保存期预检拒（与 if 一致，不留运行期静默等满超时）");
    }

    @Test
    void checkConditionSyntax_waitUntilGoodCondition_passes() {
        var actions = List.<Action>of(new Action.WaitUntil("var(\"user/x\") > 0", 5000));
        assertTrue(ScriptOpDispatcher.checkConditionSyntax(actions).isEmpty(),
                "WaitUntil 合法条件通过预检");
    }

    @Test
    void checkConditionSyntax_recursesIntoRepeatBody() {
        // repeat body 里的坏条件也要被预检（P5 补的递归——0.7.0-P2 既存遗漏）
        var inner = List.<Action>of(new Action.If("(((", List.of(), List.of()));
        var actions = List.<Action>of(new Action.Repeat(2, inner));
        assertTrue(ScriptOpDispatcher.checkConditionSyntax(actions).isPresent(),
                "repeat body 内坏条件也应被预检拒");
    }

    // ---------- 0.7.2-P3：repeatUntil 条件保存期预检（K16，照 WaitUntil）----------

    @Test
    void checkConditionSyntax_repeatUntilBadCondition_rejected() {
        var actions = List.<Action>of(new Action.RepeatUntil("(((", 10,
                List.of(new Action.Log("x"))));
        assertTrue(ScriptOpDispatcher.checkConditionSyntax(actions).isPresent(),
                "RepeatUntil 坏条件应被保存期预检拒（与 if/waitUntil 一致）");
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
        // repeatUntil body 里的坏条件也要被预检（递归 body）
        var inner = List.<Action>of(new Action.If("(((", List.of(), List.of()));
        var actions = List.<Action>of(new Action.RepeatUntil("var(\"user/x\") > 0", 5, inner));
        assertTrue(ScriptOpDispatcher.checkConditionSyntax(actions).isPresent(),
                "repeatUntil body 内坏条件也应被预检拒");
    }
}
