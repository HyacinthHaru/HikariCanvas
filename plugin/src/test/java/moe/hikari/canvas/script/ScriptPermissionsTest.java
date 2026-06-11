package moe.hikari.canvas.script;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ScriptPermissionsTest {

    private static ScriptRule rule(Trigger t, List<Action> a) {
        return new ScriptRule("r-1", "w-1", true, "测试规则", t, a, "{}");
    }

    @Test
    void base_only() {
        ScriptRule r = rule(new Trigger.VariableChange("user/score"),
                List.of(new Action.SetVariable("user/score", "0")));
        assertEquals(Set.of(), ScriptPermissions.requiredFacets(r));
    }

    @Test
    void global_join() {
        ScriptRule r = rule(new Trigger.PlayerJoin(), List.of(new Action.Log("hi")));
        assertEquals(Set.of(ScriptPermissions.NODE_TRIGGER_GLOBAL),
                ScriptPermissions.requiredFacets(r));
    }

    @Test
    void global_kill() {
        ScriptRule r = rule(new Trigger.PlayerKill(), List.of(new Action.Log("hi")));
        assertEquals(Set.of(ScriptPermissions.NODE_TRIGGER_GLOBAL),
                ScriptPermissions.requiredFacets(r));
    }

    @Test
    void near_is_not_global() {
        ScriptRule r = rule(new Trigger.PlayerNear(8), List.of(new Action.Log("hi")));
        assertEquals(Set.of(), ScriptPermissions.requiredFacets(r));
    }

    @Test
    void sound_nested() {
        Action iff = new Action.If("1 > 0",
                List.of(new Action.PlaySound("ui.button.click", 1.0, 1.0, "near")),
                List.of());
        ScriptRule r = rule(new Trigger.VariableChange("user/score"), List.of(iff));
        assertEquals(Set.of(ScriptPermissions.NODE_SOUND),
                ScriptPermissions.requiredFacets(r));
    }

    @Test
    void command_nested_else() {
        Action iff = new Action.If("1 > 0",
                List.of(),
                List.of(new Action.RunCommand("announce", Map.of("msg", "hi"))));
        ScriptRule r = rule(new Trigger.VariableChange("user/score"), List.of(iff));
        assertEquals(Set.of(ScriptPermissions.NODE_COMMAND),
                ScriptPermissions.requiredFacets(r));
    }

    @Test
    void new_071_actions_no_extra_facets() {
        // 0.7.1 的 6 个新 action 均无附加权限面（仅走基础 canvas.script.edit）
        ScriptRule r = rule(new Trigger.VariableChange("user/score"), List.of(
                new Action.SetElementProperties("e-1", Map.of("x", "1"), "moveTo"),
                new Action.NudgeElement("e-1", 1.0, 1.0),
                new Action.SendMessage("hi", "chat"),
                new Action.SetRandomVariable("user/roll", 1.0, 6.0),
                new Action.ScaleVariable("user/score", "multiply", 2.0),
                new Action.PlayTimelineAwait("tl-1")));
        assertEquals(Set.of(), ScriptPermissions.requiredFacets(r));
    }

    @Test
    void all_three() {
        // PlayerKill(全局触发面) + PlaySound + RunCommand → 共 3 个节点
        ScriptRule r = rule(new Trigger.PlayerKill(), List.of(
                new Action.PlaySound("ui.button.click", 1.0, 1.0, "all"),
                new Action.RunCommand("announce", Map.of())));
        assertEquals(Set.of(
                ScriptPermissions.NODE_TRIGGER_GLOBAL,
                ScriptPermissions.NODE_SOUND,
                ScriptPermissions.NODE_COMMAND), ScriptPermissions.requiredFacets(r));
    }
}
