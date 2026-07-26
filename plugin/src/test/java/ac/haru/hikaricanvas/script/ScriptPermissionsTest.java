package ac.haru.hikaricanvas.script;

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

    /**
     * 全服广播（{@code target=all}）要 broadcast 面：这两个积木不带任何面的话，
     * 默认权限玩家就能稳定刷全服 chat / title（草稿墙人人可开 + budget 10 次/秒），
     * 而侵扰性更低的播声音 / 粒子反而有可撤销的开关。服主至少得有个旋钮能收回。
     */
    @Test
    void broadcast_targetAll_requiresBroadcastFacet() {
        ScriptRule msg = rule(new Trigger.VariableChange("user/score"),
                List.of(new Action.SendMessage("大家好", "chat", "all")));
        assertEquals(Set.of(ScriptPermissions.NODE_BROADCAST),
                ScriptPermissions.requiredFacets(msg));

        ScriptRule title = rule(new Trigger.VariableChange("user/score"),
                List.of(new Action.ShowTitle("标题", "副标题", 200, 2000, 200, "all")));
        assertEquals(Set.of(ScriptPermissions.NODE_BROADCAST),
                ScriptPermissions.requiredFacets(title));
    }

    /** {@code target=trigger} 只发给触发玩家一个人，不算广播，不要面。 */
    @Test
    void broadcast_targetTrigger_needsNoFacet() {
        ScriptRule msg = rule(new Trigger.VariableChange("user/score"),
                List.of(new Action.SendMessage("你好", "chat", "trigger"),
                        new Action.ShowTitle("标题", "副标题", 200, 2000, 200, "trigger")));
        assertEquals(Set.of(), ScriptPermissions.requiredFacets(msg));
    }

    /** 嵌在 if / repeat 里的广播同样要面（递归扫描）。 */
    @Test
    void broadcast_insideNestedBlocks_stillCounted() {
        ScriptRule r = rule(new Trigger.VariableChange("user/score"), List.of(
                new Action.If("1 > 0",
                        List.of(new Action.Repeat(3,
                                List.of(new Action.SendMessage("刷屏", "chat", "all")))),
                        List.of())));
        assertEquals(Set.of(ScriptPermissions.NODE_BROADCAST),
                ScriptPermissions.requiredFacets(r));
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

    // ---------- 0.7.1：3 个新触发器权限面 ----------

    @Test
    void global_rightClickWall() {
        ScriptRule r = rule(new Trigger.RightClickWall(), List.of(new Action.Log("hi")));
        assertEquals(Set.of(ScriptPermissions.NODE_TRIGGER_GLOBAL),
                ScriptPermissions.requiredFacets(r));
    }

    @Test
    void global_playerQuit() {
        ScriptRule r = rule(new Trigger.PlayerQuit(), List.of(new Action.Log("hi")));
        assertEquals(Set.of(ScriptPermissions.NODE_TRIGGER_GLOBAL),
                ScriptPermissions.requiredFacets(r));
    }

    @Test
    void leaveRange_is_not_global() {
        // playerLeaveRange 是墙级（同 playerNear），仅基础 NODE_EDIT
        ScriptRule r = rule(new Trigger.PlayerLeaveRange(8), List.of(new Action.Log("hi")));
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
                new Action.SendMessage("hi", "chat", "trigger"),
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

    // ---------- 0.7.1-P5：停止 / 粒子 / 等待直到 ----------

    @Test
    void playParticle_requires_sound_facet() {
        ScriptRule r = rule(new Trigger.VariableChange("user/score"),
                List.of(new Action.PlayParticle("minecraft:flame", 10, 0, 0, 0)));
        assertEquals(Set.of(ScriptPermissions.NODE_SOUND), ScriptPermissions.requiredFacets(r));
    }

    @Test
    void stopScript_and_waitUntil_only_base_edit() {
        ScriptRule r1 = rule(new Trigger.VariableChange("user/score"),
                List.of(new Action.StopScript()));
        ScriptRule r2 = rule(new Trigger.VariableChange("user/score"),
                List.of(new Action.WaitUntil("x>0", 5000)));
        assertEquals(Set.of(), ScriptPermissions.requiredFacets(r1));
        assertEquals(Set.of(), ScriptPermissions.requiredFacets(r2));
    }

    @Test
    void playParticle_nested_in_if() {
        // if 分支内的 playParticle 也需 sound 面（递归扫描）
        Action iff = new Action.If("1 > 0",
                List.of(new Action.PlayParticle("minecraft:heart", 5, 0, 0, 0)),
                List.of());
        ScriptRule r = rule(new Trigger.VariableChange("user/score"), List.of(iff));
        assertEquals(Set.of(ScriptPermissions.NODE_SOUND), ScriptPermissions.requiredFacets(r));
    }

    // ---------- 0.7.2-P2：copy / append / clone / delete 均只走基础 edit ----------

    @Test
    void p2_actions_only_base_edit() {
        for (Action a : List.of(
                new Action.CopyVariable("user/d", "user/s"),
                new Action.AppendVariable("user/l", "x"),
                new Action.CloneElement("e-1", 0, 0),
                new Action.DeleteElement("e-1"))) {
            ScriptRule r = rule(new Trigger.VariableChange("user/score"), List.of(a));
            Set<String> f = ScriptPermissions.requiredFacets(r);
            assertEquals(Set.of(), f, "0.7.2-P2 动作无附加权限面: " + a.wireType());
        }
    }

    // ---------- 0.7.2-P3：repeatUntil body 递归扫描 ----------

    @Test
    void repeatUntil_recursesIntoBody() {
        // body 里的 playSound → 递归 scan 出 NODE_SOUND（repeatUntil 本身仅基础 edit）
        ScriptRule r = rule(new Trigger.VariableChange("user/score"),
                List.of(new Action.RepeatUntil("var(\"user/x\")>0", 5,
                        List.of(new Action.PlaySound("s", 1, 1, "near")))));
        assertEquals(Set.of(ScriptPermissions.NODE_SOUND), ScriptPermissions.requiredFacets(r));
    }

    @Test
    void repeatUntil_emptyBody_onlyBaseEdit() {
        ScriptRule r = rule(new Trigger.VariableChange("user/score"),
                List.of(new Action.RepeatUntil("var(\"user/x\")>0", 5,
                        List.of(new Action.Log("x")))));
        assertEquals(Set.of(), ScriptPermissions.requiredFacets(r));
    }
}
