package ac.haru.hikaricanvas.script;

import ac.haru.hikaricanvas.state.Easing;
import ac.haru.hikaricanvas.state.EasingType;
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

    // ---------- 0.7.1：3 个新触发器 ----------

    @Test
    void leave_range_ok() {
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(
                rule(new Trigger.PlayerLeaveRange(8), okActions())));
    }

    @Test
    void leave_range_low_rejected() {
        assertTrue(ScriptRuleValidator.validate(
                rule(new Trigger.PlayerLeaveRange(0), okActions())).isPresent());
    }

    @Test
    void leave_range_high_rejected() {
        assertTrue(ScriptRuleValidator.validate(
                rule(new Trigger.PlayerLeaveRange(33), okActions())).isPresent());
    }

    @Test
    void right_click_wall_and_player_quit_no_fields_ok() {
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(
                rule(new Trigger.RightClickWall(), okActions())));
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(
                rule(new Trigger.PlayerQuit(), okActions())));
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
        Optional<ValidationError> err = ScriptRuleValidator.validate(rule(okTrigger(), List.of(inner)));
        assertTrue(err.isPresent());
        assertEquals("ifDepthExceeded", err.get().key());
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
        Optional<ValidationError> err = ScriptRuleValidator.validate(rule(okTrigger(), List.of(iff)));
        assertTrue(err.isPresent());
        assertEquals("blocksTotalExceeded", err.get().key());
        assertEquals("50", err.get().params().get("max"));
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
                List.of(new Action.SendMessage("hi", "chat", "trigger")))));
        // 空串合法
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.SendMessage("", "actionbar", "trigger")))));
    }

    @Test
    void send_message_null_text_rejected() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.SendMessage(null, "chat", "trigger")))).isPresent());
    }

    @Test
    void send_message_too_long_rejected() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.SendMessage("x".repeat(257), "chat", "trigger")))).isPresent());
    }

    @Test
    void send_message_bad_channel_rejected() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.SendMessage("hi", "hologram", "trigger")))).isPresent());
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

    // ---------- 0.7.1：Repeat 有界循环 ----------

    @Test
    void repeat_ok() {
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.Repeat(3, List.of(new Action.Log("loop")))))));
    }

    @Test
    void repeat_count_low_rejected() {
        Optional<ValidationError> err = ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.Repeat(0, List.of(new Action.Log("x"))))));
        assertTrue(err.isPresent());
        assertEquals("repeatCountRange", err.get().key(), err.get().toString());
        assertEquals("1", err.get().params().get("min"));
        assertEquals("100", err.get().params().get("max"));
    }

    @Test
    void repeat_count_high_rejected() {
        Optional<ValidationError> err = ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.Repeat(101, List.of(new Action.Log("x"))))));
        assertTrue(err.isPresent());
        assertEquals("repeatCountRange", err.get().key(), err.get().toString());
        assertEquals("1", err.get().params().get("min"));
        assertEquals("100", err.get().params().get("max"));
    }

    @Test
    void repeat_empty_body_rejected() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.Repeat(3, List.of())))).isPresent());
    }

    @Test
    void repeat_body_recursively_validated() {
        // body 内含非法动作（wait 太短）→ repeat 校验递归到 body 报错
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.Repeat(3, List.of(new Action.Wait(10)))))).isPresent());
    }

    @Test
    void repeat_body_counts_toward_total_blocks_notMultiplied() {
        // countBlocks 计 body 节点（不乘 count）：repeat(1) + 48 body 动作 = 49 ≤ 50 合法
        List<Action> body = java.util.stream.IntStream.range(0, 48)
                .mapToObj(i -> (Action) new Action.Log("l" + i)).toList();
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.Repeat(100, body)))),
                "repeat count=100 但 countBlocks 不乘 count：1 + 48 = 49 ≤ 50");
        // body 50 节点 → repeat(1) + 50 = 51 > 50 拒
        List<Action> body51 = java.util.stream.IntStream.range(0, 50)
                .mapToObj(i -> (Action) new Action.Log("l" + i)).toList();
        Optional<ValidationError> err = ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.Repeat(2, body51))));
        assertTrue(err.isPresent());
        assertEquals("blocksTotalExceeded", err.get().key(), err.get().toString());
        assertEquals("50", err.get().params().get("max"));
    }

    // ---------- 0.7.1-P5：停止 / 粒子 / 等待直到 ----------

    @Test
    void stop_script_always_valid() {
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.StopScript()))));
    }

    @Test
    void play_particle_accepts_valid() {
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.PlayParticle("minecraft:flame", 10, 0.5, 0.5, 0.5)))));
    }

    @Test
    void play_particle_rejects_unknown_particle() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.PlayParticle("minecraft:nonexist", 10, 0, 0, 0)))).isPresent());
    }

    @Test
    void play_particle_rejects_count_out_of_range() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.PlayParticle("minecraft:flame", 0, 0, 0, 0)))).isPresent());
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.PlayParticle("minecraft:flame", 2000, 0, 0, 0)))).isPresent());
    }

    @Test
    void play_particle_rejects_nonfinite_offset() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.PlayParticle("minecraft:flame", 10, Double.NaN, 0, 0)))).isPresent());
    }

    @Test
    void wait_until_accepts_valid() {
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.WaitUntil("var(\"user/x\")>0", 5000)))));
    }

    @Test
    void wait_until_rejects_blank_condition() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.WaitUntil("", 5000)))).isPresent());
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.WaitUntil("  ", 5000)))).isPresent());
    }

    @Test
    void wait_until_rejects_bad_timeout() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.WaitUntil("var(\"user/x\")>0", 10)))).isPresent()); // < MIN
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.WaitUntil("var(\"user/x\")>0", 999999)))).isPresent()); // > MAX
    }

    // ---------- 0.7.2-P2：copy / append / clone / delete ----------

    @Test
    void copyVariable_rejects_blank() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.CopyVariable("", "user/s")))).isPresent());
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.CopyVariable("user/d", "")))).isPresent());
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.CopyVariable("user/d", "user/s")))));
    }

    @Test
    void appendVariable_rejects_blank_name() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.AppendVariable("", "x")))).isPresent());
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.AppendVariable("user/log", "x")))));
    }

    @Test
    void appendVariable_rejects_overlong_text() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.AppendVariable("user/log", "x".repeat(4097))))).isPresent());
    }

    @Test
    void cloneElement_rejects_blank_id() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.CloneElement("", 0, 0)))).isPresent());
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.CloneElement("e-1", 5, 5)))));
    }

    @Test
    void cloneElement_rejects_offset_out_of_range() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.CloneElement("e-1", 5000, 0)))).isPresent());
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.CloneElement("e-1", 0, -5000)))).isPresent());
    }

    @Test
    void deleteElement_rejects_blank_id() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.DeleteElement("")))).isPresent());
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.DeleteElement("e-1")))));
    }

    // ---------- 0.7.2-P3：repeatUntil + sendMessage target 白名单 ----------

    @Test
    void repeatUntil_ok() {
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.RepeatUntil("var(\"user/x\")>0", 10,
                        List.of(new Action.Log("x")))))));
    }

    @Test
    void repeatUntil_blank_condition_rejected() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.RepeatUntil("", 10, List.of(new Action.Log("x")))))).isPresent());
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.RepeatUntil("   ", 10, List.of(new Action.Log("x")))))).isPresent());
    }

    @Test
    void repeatUntil_maxIterations_low_rejected() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.RepeatUntil("var(\"user/x\")>0", 0,
                        List.of(new Action.Log("x")))))).isPresent());
    }

    @Test
    void repeatUntil_maxIterations_high_rejected() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.RepeatUntil("var(\"user/x\")>0", 200,
                        List.of(new Action.Log("x")))))).isPresent());
    }

    @Test
    void repeatUntil_empty_body_rejected() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.RepeatUntil("var(\"user/x\")>0", 10, List.of())))).isPresent());
    }

    @Test
    void repeatUntil_body_recursively_validated() {
        // body 内含非法动作（wait 太短）→ 校验递归到 body 报错
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.RepeatUntil("var(\"user/x\")>0", 10,
                        List.of(new Action.Wait(10)))))).isPresent());
    }

    @Test
    void sendMessage_target_whitelist() {
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.SendMessage("x", "chat", "nope")))).isPresent());
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.SendMessage("x", "chat", "all")))));
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.SendMessage("x", "chat", "trigger")))));
    }

    // ---------- tween：补间动画包裹 validator ----------

    /** 辅助：造合法 TweenBlock（LINEAR 缓动 + moveTo body）。 */
    private static Action.TweenBlock okTween() {
        return new Action.TweenBlock(1500L, Easing.LINEAR,
                List.of(new Action.SetElementProperties("e-1", Map.of("x", "100", "y", "200"), "moveTo")));
    }

    @Test
    void tween_ok_linear() {
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(rule(okTrigger(), List.of(okTween()))));
    }

    @Test
    void tween_ok_easeOut() {
        Action a = new Action.TweenBlock(500L, new Easing(EasingType.EASE_OUT, null),
                List.of(new Action.SetElementProperties("e-1", Map.of("opacity", "0"), "setOpacity")));
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(rule(okTrigger(), List.of(a))));
    }

    @Test
    void tween_ok_cubicBezier() {
        Easing bz = new Easing(EasingType.CUBIC_BEZIER, List.of(0.25, 0.1, 0.25, 1.0));
        Action a = new Action.TweenBlock(1000L, bz,
                List.of(new Action.SetElementProperties("e-1", Map.of("rotation", "90"), "rotateTo")));
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(rule(okTrigger(), List.of(a))));
    }

    @Test
    void tween_durationMs_zero_rejected() {
        Action a = new Action.TweenBlock(0L, Easing.LINEAR,
                List.of(new Action.SetElementProperties("e-1", Map.of("x", "0"), "moveTo")));
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(), List.of(a))).isPresent());
    }

    @Test
    void tween_durationMs_over_max_rejected() {
        Action a = new Action.TweenBlock(ScriptRuleValidator.TWEEN_DURATION_MAX + 1, Easing.LINEAR,
                List.of(new Action.SetElementProperties("e-1", Map.of("x", "0"), "moveTo")));
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(), List.of(a))).isPresent());
    }

    @Test
    void tween_body_empty_rejected() {
        Action a = new Action.TweenBlock(500L, Easing.LINEAR, List.of());
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(), List.of(a))).isPresent());
    }

    @Test
    void tween_body_non_property_action_rejected() {
        // body 里放 sendMessage（非属性动作）→ 拒
        Action a = new Action.TweenBlock(500L, Easing.LINEAR,
                List.of(new Action.SendMessage("hi", "chat", "trigger")));
        Optional<ValidationError> err = ScriptRuleValidator.validate(rule(okTrigger(), List.of(a)));
        assertTrue(err.isPresent());
        assertEquals("tweenBodyNotPropertyAction", err.get().key());
    }

    @Test
    void tween_body_untweennable_kind_rejected() {
        // kind = show（不在 TWEENABLE_KINDS）→ 拒
        Action a = new Action.TweenBlock(500L, Easing.LINEAR,
                List.of(new Action.SetElementProperties("e-1", Map.of("opacity", "1"), "show")));
        Optional<ValidationError> err = ScriptRuleValidator.validate(rule(okTrigger(), List.of(a)));
        assertTrue(err.isPresent());
    }

    @Test
    void tween_easing_null_rejected() {
        // easing = null → 拒（record 允许 null 存储，校验层拒）
        Action a = new Action.TweenBlock(500L, null,
                List.of(new Action.SetElementProperties("e-1", Map.of("x", "0"), "moveTo")));
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(), List.of(a))).isPresent());
    }

    // ---------- 0.7.3-ultrareview：RandomBranch ifDepth 双端对齐 ----------

    @Test
    void randomBranch_ok_valid_probability() {
        // RandomBranch 合法：概率 50%，then/else 有简单动作
        Action a = new Action.RandomBranch(50,
                List.of(new Action.Log("yes")), List.of(new Action.Log("no")));
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(rule(okTrigger(), List.of(a))));
    }

    @Test
    void randomBranch_depth_3_ok() {
        // RandomBranch 3 层嵌套（含最外层 If 4 层总计 = 4，恰在上限内）
        // 外层 If(depth=1) → RandomBranch(depth=2) → If(depth=3) → RandomBranch(depth=4) ← 上限
        Action inner = new Action.Log("deep");
        inner = new Action.RandomBranch(50, List.of(inner), List.of());  // depth=4
        inner = new Action.If("1 > 0", List.of(inner), List.of());       // depth=3
        inner = new Action.RandomBranch(50, List.of(inner), List.of());  // depth=2
        inner = new Action.If("1 > 0", List.of(inner), List.of());       // depth=1
        assertEquals(Optional.empty(),
                ScriptRuleValidator.validate(rule(okTrigger(), List.of(inner))),
                "RandomBranch 与 If 混合 4 层应放行");
    }

    @Test
    void randomBranch_depth_5_rejected() {
        // RandomBranch 当 if 计深度：5 层应被拒（与前端 validator.ts randomBranch case 对齐）
        Action inner = new Action.Log("deep");
        for (int i = 0; i < 5; i++) {
            inner = new Action.RandomBranch(50, List.of(inner), List.of());
        }
        Optional<ValidationError> err = ScriptRuleValidator.validate(rule(okTrigger(), List.of(inner)));
        assertTrue(err.isPresent(), "RandomBranch 5 层嵌套应被拒（超 MAX_IF_DEPTH=4）");
        assertEquals("ifRandomBranchDepthExceeded", err.get().key(), err.get().toString());
    }

    @Test
    void randomBranch_mixed_with_if_depth_5_rejected() {
        // 4 层 If 外再套 RandomBranch → depth=5 被拒
        Action inner = new Action.Log("deep");
        for (int i = 0; i < 4; i++) {
            inner = new Action.If("1 > 0", List.of(inner), List.of());
        }
        inner = new Action.RandomBranch(50, List.of(inner), List.of());
        Optional<ValidationError> err = ScriptRuleValidator.validate(rule(okTrigger(), List.of(inner)));
        assertTrue(err.isPresent(), "RandomBranch 外包 4 层 If 应被拒（depth=5）");
    }

    @Test
    void randomBranch_else_branch_also_depth_checked() {
        // 超深嵌套在 else 分支里也应被检测
        Action inner = new Action.Log("deep");
        for (int i = 0; i < 5; i++) {
            inner = new Action.RandomBranch(50, List.of(), List.of(inner));  // 在 else 分支嵌套
        }
        Optional<ValidationError> err = ScriptRuleValidator.validate(rule(okTrigger(), List.of(inner)));
        assertTrue(err.isPresent(), "else 分支超深嵌套也应被拒");
    }

    @Test
    void tween_cubicBezier_missing_bezier_rejected() {
        // cubicBezier 类型但 bezier=null → 拒
        Easing bad = new Easing(EasingType.CUBIC_BEZIER, null);
        Action a = new Action.TweenBlock(500L, bad,
                List.of(new Action.SetElementProperties("e-1", Map.of("x", "0"), "moveTo")));
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(), List.of(a))).isPresent());
    }

    @Test
    void tween_cubicBezier_x_out_of_range_rejected() {
        // x1 = 1.5（超 [0,1]）→ 拒
        Easing bad = new Easing(EasingType.CUBIC_BEZIER, List.of(1.5, 0.1, 0.25, 1.0));
        Action a = new Action.TweenBlock(500L, bad,
                List.of(new Action.SetElementProperties("e-1", Map.of("x", "0"), "moveTo")));
        assertTrue(ScriptRuleValidator.validate(rule(okTrigger(), List.of(a))).isPresent());
    }

    // ---------- 0.7.3 D3：setColor 友好积木 patch 键修复（color 不是 fill）----------

    /** 0.7.3 D3：SetElementProperties patch 含 "color" 键合法（setColor 友好积木目标格式）。 */
    @Test
    void set_element_properties_color_key_is_whitelisted() {
        // 回归：旧版 ELEMENT_PROPERTIES 不含 "color"，setColor 友好积木 defaultPatch={color:'#FFF'}
        // 在 tweenBlock body 里走 validateAction→ELEMENT_PROPERTIES check → "元素属性不在允许范围：color"
        // 修复：ELEMENT_PROPERTIES 加入 "color"，校验放行，TweenScheduler color 分支端到端可达。
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(rule(okTrigger(),
                List.of(new Action.SetElementProperties("e-1",
                        Map.of("color", "#FF0000"), "setColor")))));
    }

    /** 0.7.3 D3：setColor 在 tweenBlock body 里合法（kind=setColor ∈ TWEENABLE_KINDS + color 已白名单）。 */
    @Test
    void tween_body_setColor_with_color_key_ok() {
        Action a = new Action.TweenBlock(500L, Easing.LINEAR,
                List.of(new Action.SetElementProperties("e-1",
                        Map.of("color", "#000000"), "setColor")));
        assertEquals(Optional.empty(),
                ScriptRuleValidator.validate(rule(okTrigger(), List.of(a))));
    }
}
