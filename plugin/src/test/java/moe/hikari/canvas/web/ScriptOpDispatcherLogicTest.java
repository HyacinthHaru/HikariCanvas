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
}
