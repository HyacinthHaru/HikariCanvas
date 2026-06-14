package moe.hikari.canvas.script;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.7.3 四个新积木的 serde roundtrip + validator 覆盖。
 */
class Action073WireTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private void roundtrip(Action original) throws Exception {
        String json = mapper.writeValueAsString(original);
        Action back = mapper.readValue(json, Action.class);
        assertEquals(original, back, "roundtrip failed for: " + json);
    }

    // ────────── G1 RandomBranch ──────────

    @Test
    void randomBranch_roundTrip_withBranches() throws Exception {
        Action a = new Action.RandomBranch(
                60,
                List.of(new Action.Log("then-branch")),
                List.of(new Action.Log("else-branch")));
        roundtrip(a);
        String json = mapper.writeValueAsString(a);
        assertTrue(json.contains("\"type\":\"randomBranch\""), json);
        assertTrue(json.contains("\"probability\":60"), json);
        assertTrue(json.contains("\"then\""), json);
        assertTrue(json.contains("\"else\""), json);
    }

    @Test
    void randomBranch_roundTrip_emptyBranches() throws Exception {
        roundtrip(new Action.RandomBranch(0, List.of(), List.of()));
    }

    @Test
    void randomBranch_roundTrip_nestedIf() throws Exception {
        Action inner = new Action.If("x > 0", List.of(new Action.Log("y")), List.of());
        roundtrip(new Action.RandomBranch(50, List.of(inner), List.of()));
    }

    @Test
    void randomBranch_wireType() {
        assertEquals("randomBranch", new Action.RandomBranch(50, List.of(), List.of()).wireType());
    }

    // ────────── G2 SetElementLayer ──────────

    @Test
    void setElementLayer_front_roundTrip() throws Exception {
        roundtrip(new Action.SetElementLayer("el-1", "front"));
    }

    @Test
    void setElementLayer_back_roundTrip() throws Exception {
        roundtrip(new Action.SetElementLayer("el-2", "back"));
    }

    @Test
    void setElementLayer_wireType() {
        assertEquals("setElementLayer", new Action.SetElementLayer("e", "front").wireType());
    }

    // ────────── G3 RoundVariable ──────────

    @Test
    void roundVariable_round_roundTrip() throws Exception {
        roundtrip(new Action.RoundVariable("user/score", "round"));
    }

    @Test
    void roundVariable_floor_roundTrip() throws Exception {
        roundtrip(new Action.RoundVariable("user/val", "floor"));
    }

    @Test
    void roundVariable_ceil_roundTrip() throws Exception {
        roundtrip(new Action.RoundVariable("user/val", "ceil"));
    }

    @Test
    void roundVariable_wireType() {
        assertEquals("roundVariable", new Action.RoundVariable("x/y", "round").wireType());
    }

    // ────────── G4 ShowTitle ──────────

    @Test
    void showTitle_trigger_roundTrip() throws Exception {
        roundtrip(new Action.ShowTitle("Hello", "World", 500, 2000, 500, "trigger"));
    }

    @Test
    void showTitle_all_roundTrip() throws Exception {
        roundtrip(new Action.ShowTitle("Big news", "", 200, 3000, 200, "all"));
    }

    @Test
    void showTitle_defaultTarget_backCompat() throws Exception {
        // target フィールド欠如 → deserializer 默 "trigger"
        String json = "{\"type\":\"showTitle\",\"title\":\"Hi\",\"subtitle\":\"\","
                + "\"fadeInMs\":500,\"stayMs\":2000,\"fadeOutMs\":500}";
        Action a = mapper.readValue(json, Action.class);
        assertInstanceOf(Action.ShowTitle.class, a);
        assertEquals("trigger", ((Action.ShowTitle) a).target());
    }

    @Test
    void showTitle_wireType() {
        assertEquals("showTitle",
                new Action.ShowTitle("t", "s", 0, 0, 0, "all").wireType());
    }

    // ────────── Validator ──────────

    private static ScriptRule rule(Action action) {
        return new ScriptRule("r-1", "w-1", true, "测试",
                new Trigger.VariableChange("user/x"),
                List.of(action), "{}");
    }

    @Test
    void validator_randomBranch_ok() {
        var a = new Action.RandomBranch(50, List.of(), List.of());
        assertEquals(Optional.empty(), ScriptRuleValidator.validate(rule(a)));
    }

    @Test
    void validator_randomBranch_probability_low() {
        // 0 is valid
        assertEquals(Optional.empty(),
                ScriptRuleValidator.validate(rule(new Action.RandomBranch(0, List.of(), List.of()))));
    }

    @Test
    void validator_randomBranch_probability_high() {
        // 100 is valid
        assertEquals(Optional.empty(),
                ScriptRuleValidator.validate(rule(new Action.RandomBranch(100, List.of(), List.of()))));
    }

    @Test
    void validator_randomBranch_probability_over() {
        assertTrue(ScriptRuleValidator.validate(
                rule(new Action.RandomBranch(101, List.of(), List.of()))).isPresent());
    }

    @Test
    void validator_randomBranch_probability_under() {
        // 负数
        Action a = new Action.RandomBranch(-1, List.of(), List.of());
        assertTrue(ScriptRuleValidator.validate(rule(a)).isPresent());
    }

    @Test
    void validator_randomBranch_nested_invalid() {
        // then 分支含非法动作
        Action inner = new Action.Wait(1);  // 低于 WAIT_MIN=50
        Action a = new Action.RandomBranch(50, List.of(inner), List.of());
        assertTrue(ScriptRuleValidator.validate(rule(a)).isPresent());
    }

    @Test
    void validator_setElementLayer_ok() {
        assertEquals(Optional.empty(),
                ScriptRuleValidator.validate(rule(new Action.SetElementLayer("el-1", "front"))));
        assertEquals(Optional.empty(),
                ScriptRuleValidator.validate(rule(new Action.SetElementLayer("el-1", "back"))));
    }

    @Test
    void validator_setElementLayer_emptyId() {
        assertTrue(ScriptRuleValidator.validate(
                rule(new Action.SetElementLayer("", "front"))).isPresent());
    }

    @Test
    void validator_setElementLayer_invalidMode() {
        assertTrue(ScriptRuleValidator.validate(
                rule(new Action.SetElementLayer("el-1", "top"))).isPresent());
    }

    @Test
    void validator_roundVariable_ok() {
        for (String mode : new String[]{"round", "floor", "ceil"}) {
            assertEquals(Optional.empty(),
                    ScriptRuleValidator.validate(rule(new Action.RoundVariable("user/x", mode))),
                    "mode=" + mode);
        }
    }

    @Test
    void validator_roundVariable_emptyName() {
        assertTrue(ScriptRuleValidator.validate(
                rule(new Action.RoundVariable("", "round"))).isPresent());
    }

    @Test
    void validator_roundVariable_invalidMode() {
        assertTrue(ScriptRuleValidator.validate(
                rule(new Action.RoundVariable("user/x", "truncate"))).isPresent());
    }

    @Test
    void validator_showTitle_ok() {
        assertEquals(Optional.empty(),
                ScriptRuleValidator.validate(
                        rule(new Action.ShowTitle("Hello", "World", 500, 2000, 500, "trigger"))));
    }

    @Test
    void validator_showTitle_bothEmpty() {
        assertTrue(ScriptRuleValidator.validate(
                rule(new Action.ShowTitle("", "", 500, 2000, 500, "all"))).isPresent());
    }

    @Test
    void validator_showTitle_titleOnlyOk() {
        assertEquals(Optional.empty(),
                ScriptRuleValidator.validate(
                        rule(new Action.ShowTitle("Only title", "", 0, 1000, 0, "trigger"))));
    }

    @Test
    void validator_showTitle_subtitleOnlyOk() {
        assertEquals(Optional.empty(),
                ScriptRuleValidator.validate(
                        rule(new Action.ShowTitle("", "Only sub", 0, 1000, 0, "all"))));
    }

    @Test
    void validator_showTitle_fadeInOver() {
        assertTrue(ScriptRuleValidator.validate(
                rule(new Action.ShowTitle("Hi", "s", 10_001, 2000, 500, "trigger"))).isPresent());
    }

    @Test
    void validator_showTitle_stayOver() {
        assertTrue(ScriptRuleValidator.validate(
                rule(new Action.ShowTitle("Hi", "s", 500, 60_001, 500, "trigger"))).isPresent());
    }

    @Test
    void validator_showTitle_negativeFadeOut() {
        assertTrue(ScriptRuleValidator.validate(
                rule(new Action.ShowTitle("Hi", "s", 500, 2000, -1, "trigger"))).isPresent());
    }

    @Test
    void validator_showTitle_invalidTarget() {
        assertTrue(ScriptRuleValidator.validate(
                rule(new Action.ShowTitle("Hi", "s", 500, 2000, 500, "server"))).isPresent());
    }
}
