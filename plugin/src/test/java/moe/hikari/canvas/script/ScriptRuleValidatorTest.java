package moe.hikari.canvas.script;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ScriptRuleValidatorTest {

    /** 造默认合法 rule（trigger / actions 可换）。 */
    private static ScriptRule rule(Trigger t, List<Action> a) {
        return new ScriptRule("r-1", "w-1", true, "测试规则", t, a, "{}");
    }

    private static Trigger okTrigger() {
        return new Trigger.VariableChange("user/score");
    }

    private static List<Action> okActions() {
        return List.of(new Action.Log("hello"));
    }

    @Test
    void ok_minimal() {
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(rule(okTrigger(), okActions())));
    }

    @Test
    void name_blank() {
        ScriptRule r = new ScriptRule("r-1", "w-1", true, "  ", okTrigger(), okActions(), "{}");
        assertTrue(ScriptRuleValidator.validate(r).isPresent());
    }

    @Test
    void name_too_long() {
        ScriptRule r = new ScriptRule("r-1", "w-1", true, "x".repeat(65), okTrigger(), okActions(), "{}");
        assertTrue(ScriptRuleValidator.validate(r).isPresent());
    }

    @Test
    void trigger_null() {
        assertTrue(ScriptRuleValidator.validate(rule(null, okActions())).isPresent());
    }

    @Test
    void actions_empty() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(), List.of())).isPresent());
    }

    @Test
    void timer_interval_low() {
        assertTrue(ScriptRuleValidator.validate(rule(new Trigger.Timer(0), okActions())).isPresent());
    }

    @Test
    void timer_interval_high() {
        assertTrue(ScriptRuleValidator.validate(rule(new Trigger.Timer(86401), okActions())).isPresent());
    }

    @Test
    void near_range_low() {
        assertTrue(ScriptRuleValidator.validate(rule(new Trigger.PlayerNear(0), okActions())).isPresent());
    }

    @Test
    void near_range_high() {
        assertTrue(ScriptRuleValidator.validate(rule(new Trigger.PlayerNear(33), okActions())).isPresent());
    }

    @Test
    void varchange_fullname_blank() {
        assertTrue(ScriptRuleValidator.validate(
                rule(new Trigger.VariableChange(" "), okActions())).isPresent());
    }

    @Test
    void wait_too_short() {
        assertTrue(ScriptRuleValidator.validate(
                rule(okTrigger(), List.of(new Action.Wait(49)))).isPresent());
    }

    @Test
    void wait_too_long() {
        assertTrue(ScriptRuleValidator.validate(
                rule(okTrigger(), List.of(new Action.Wait(5001)))).isPresent());
    }

    @Test
    void if_depth_4_ok() {
        // 四层嵌套 if，最内层放 Log —— 恰在上限内
        Action inner = new Action.Log("deep");
        for (int i = 0; i < 4; i++) {
            inner = new Action.If("1 > 0", List.of(inner), List.of());
        }
        assertEquals(Optional.empty(),
                ScriptRuleValidator.validate(rule(okTrigger(), List.of(inner))));
    }

    @Test
    void if_depth_5_rejected() {
        Action inner = new Action.Log("deep");
        for (int i = 0; i < 5; i++) {
            inner = new Action.If("1 > 0", List.of(inner), List.of());
        }
        Optional<String> err = ScriptRuleValidator.validate(rule(okTrigger(), List.of(inner)));
        assertTrue(err.isPresent());
        assertTrue(err.get().contains("depth") || err.get().contains("嵌套"));
    }

    @Test
    void total_actions_50_ok() {
        // if 自身计 1 + then 内 49 个 Log = 50,恰在上限内
        List<Action> logs = java.util.stream.IntStream.range(0, 49)
                .mapToObj(i -> (Action) new Action.Log("l" + i)).toList();
        Action iff = new Action.If("1 > 0", logs, List.of());
        assertEquals(Optional.empty(),
                ScriptRuleValidator.validate(rule(okTrigger(), List.of(iff))));
    }

    @Test
    void total_actions_51_rejected() {
        List<Action> logs = java.util.stream.IntStream.range(0, 50)
                .mapToObj(i -> (Action) new Action.Log("l" + i)).toList();
        Action iff = new Action.If("1 > 0", logs, List.of());
        Optional<String> err = ScriptRuleValidator.validate(rule(okTrigger(), List.of(iff)));
        assertTrue(err.isPresent());
        assertTrue(err.get().contains("50"));
    }

    @Test
    void blocklayout_too_big() {
        ScriptRule r = new ScriptRule("r-1", "w-1", true, "测试规则",
                okTrigger(), okActions(), "x".repeat(65537));
        assertTrue(ScriptRuleValidator.validate(r).isPresent());
    }

    @Test
    void element_property_not_whitelisted() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.SetElementProperty("el-1", "zIndex", "5")))).isPresent());
    }

    @Test
    void play_timeline_bad_op() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.PlayTimeline("tl-1", "stop", null)))).isPresent());
    }

    @Test
    void play_timeline_seek_without_ms() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.PlayTimeline("tl-1", "seek", null)))).isPresent());
    }

    @Test
    void sound_scope_bad() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.PlaySound("ui.button.click", 1.0, 1.0, "world")))).isPresent());
    }

    @Test
    void sound_volume_out_of_range() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.PlaySound("ui.button.click", -0.1, 1.0, "near")))).isPresent());
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.PlaySound("ui.button.click", 2.1, 1.0, "near")))).isPresent());
    }

    @Test
    void sound_pitch_out_of_range() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.PlaySound("ui.button.click", 1.0, 0.4, "near")))).isPresent());
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.PlaySound("ui.button.click", 1.0, 2.1, "near")))).isPresent());
    }

    // —— 补充边界（可补不可删之外的防御覆盖） ——

    @Test
    void blocklayout_null_is_ok() {
        ScriptRule r = new ScriptRule("r-1", "w-1", true, "测试规则",
                okTrigger(), okActions(), null);
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(r));
    }

    @Test
    void blocklayout_null_coerced_to_empty_json() {
        // 紧凑构造器把 null 收敛为 "{}"（前端 blockLayout 类型是 string 非 nullable）
        ScriptRule r = new ScriptRule("r-1", "w-1", true, "测试规则",
                okTrigger(), okActions(), null);
        assertEquals("{}", r.blockLayout());
    }

    @Test
    void set_variable_empty_value_ok_null_rejected() {
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.SetVariable("user/score", "")))));
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.SetVariable("user/score", null)))).isPresent());
    }

    @Test
    void play_timeline_negative_seek_rejected() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.PlayTimeline("tl-1", "seek", -1L)))).isPresent());
    }

    @Test
    void wait_bounds_ok() {
        // 边界值恰在 [50, 5000] 上 —— 合法
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.Wait(50)))));
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.Wait(5000)))));
    }

    @Test
    void increment_delta_infinite_rejected() {
        // I-2 回归：累加步长非有限数值（Infinity / NaN）被拒
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.IncrementVariable("user/x", Double.POSITIVE_INFINITY)))).isPresent());
    }

    @Test
    void run_command_param_too_long_rejected() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.RunCommand("announce", Map.of("msg", "x".repeat(257)))))).isPresent());
    }

    // ---------- 0.7.1：6 个新 Action 子类校验 ----------

    @Test
    void set_element_properties_ok() {
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.SetElementProperties("e-1",
                        Map.of("x", "128", "y", "64"), "moveTo")))));
    }

    @Test
    void set_element_properties_empty_patch_rejected() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.SetElementProperties("e-1", Map.of(), "show")))).isPresent());
    }

    @Test
    void set_element_properties_blank_element_id_rejected() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.SetElementProperties(" ", Map.of("x", "1"), "moveTo")))).isPresent());
    }

    @Test
    void set_element_properties_non_whitelisted_key_rejected() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.SetElementProperties("e-1",
                        Map.of("fontSize", "12"), "moveTo")))).isPresent());
    }

    @Test
    void set_element_properties_too_many_keys_rejected() {
        Map<String, String> tooMany = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 9; i++) tooMany.put("k" + i, "1"); // 9 > PATCH_MAX_KEYS(8)
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.SetElementProperties("e-1", tooMany, "moveTo")))).isPresent());
    }

    @Test
    void set_element_properties_blank_value_rejected() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.SetElementProperties("e-1",
                        Map.of("x", " "), "moveTo")))).isPresent());
    }

    @Test
    void set_element_properties_blank_text_value_ok() {
        // text 空串是合法内容（setText 友好积木 defaultPatch={text:''}）；其余键空仍拒（见上一用例）
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.SetElementProperties("e-1",
                        Map.of("text", ""), "setText")))));
    }

    @Test
    void set_element_properties_kind_too_long_rejected() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.SetElementProperties("e-1",
                        Map.of("x", "1"), "k".repeat(33))))).isPresent());
    }

    @Test
    void nudge_element_ok() {
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.NudgeElement("e-1", 5.0, -3.0)))));
    }

    @Test
    void nudge_element_blank_id_rejected() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.NudgeElement("", 1.0, 1.0)))).isPresent());
    }

    @Test
    void nudge_element_nan_delta_rejected() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.NudgeElement("e-1", Double.NaN, 1.0)))).isPresent());
    }

    @Test
    void send_message_ok() {
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.SendMessage("hi", "chat")))));
        // 空串合法
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.SendMessage("", "actionbar")))));
    }

    @Test
    void send_message_null_text_rejected() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.SendMessage(null, "chat")))).isPresent());
    }

    @Test
    void send_message_too_long_rejected() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.SendMessage("x".repeat(257), "chat")))).isPresent());
    }

    @Test
    void send_message_bad_channel_rejected() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.SendMessage("hi", "hologram")))).isPresent());
    }

    @Test
    void set_random_variable_ok() {
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.SetRandomVariable("user/roll", 1.0, 6.0)))));
    }

    @Test
    void set_random_variable_blank_name_rejected() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.SetRandomVariable(" ", 1.0, 6.0)))).isPresent());
    }

    @Test
    void set_random_variable_min_gt_max_rejected() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.SetRandomVariable("user/roll", 6.0, 1.0)))).isPresent());
    }

    @Test
    void set_random_variable_nan_rejected() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.SetRandomVariable("user/roll", Double.NaN, 6.0)))).isPresent());
    }

    @Test
    void scale_variable_ok() {
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.ScaleVariable("user/score", "multiply", 2.0)))));
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.ScaleVariable("user/score", "divide", 2.0)))));
    }

    @Test
    void scale_variable_blank_name_rejected() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.ScaleVariable("", "multiply", 2.0)))).isPresent());
    }

    @Test
    void scale_variable_bad_op_rejected() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.ScaleVariable("user/score", "modulo", 2.0)))).isPresent());
    }

    @Test
    void scale_variable_nan_factor_rejected() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.ScaleVariable("user/score", "multiply", Double.NaN)))).isPresent());
    }

    @Test
    void scale_variable_divide_by_zero_rejected() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.ScaleVariable("user/score", "divide", 0.0)))).isPresent());
    }

    @Test
    void play_timeline_await_ok() {
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.PlayTimelineAwait("tl-1")))));
    }

    @Test
    void play_timeline_await_blank_id_rejected() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.PlayTimelineAwait(" ")))).isPresent());
    }
}
