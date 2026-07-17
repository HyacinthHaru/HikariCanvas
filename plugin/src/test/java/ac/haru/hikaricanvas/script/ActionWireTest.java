package ac.haru.hikaricanvas.script;

import com.fasterxml.jackson.databind.ObjectMapper;
import ac.haru.hikaricanvas.state.Easing;
import ac.haru.hikaricanvas.state.EasingType;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ActionWireTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private void roundtrip(String json) throws Exception {
        Action a = mapper.readValue(json, Action.class);
        assertEquals(mapper.readTree(json), mapper.readTree(mapper.writeValueAsString(a)));
    }

    @Test void setVariable() throws Exception {
        roundtrip("{\"type\":\"setVariable\",\"fullName\":\"user/score\",\"value\":\"0\"}");
    }
    @Test void incrementVariable() throws Exception {
        roundtrip("{\"type\":\"incrementVariable\",\"fullName\":\"user/score\",\"delta\":1.0}");
    }
    @Test void setElementProperty() throws Exception {
        roundtrip("{\"type\":\"setElementProperty\",\"elementId\":\"el-1\","
                + "\"property\":\"opacity\",\"value\":\"0.5\"}");
    }
    @Test void playTimelineSeek() throws Exception {
        String json = "{\"type\":\"playTimeline\",\"timelineId\":\"tl-1\",\"op\":\"seek\",\"seekMs\":1000}";
        Action a = mapper.readValue(json, Action.class);
        assertEquals(new Action.PlayTimeline("tl-1", "seek", 1000L), a);
    }
    @Test void playTimelinePlayOmitsSeekMs() throws Exception {
        Action a = new Action.PlayTimeline("tl-1", "play", null);
        String out = mapper.writeValueAsString(a);
        assertFalse(out.contains("seekMs"));
        assertEquals(a, mapper.readValue(out, Action.class));
    }
    @Test void playSound() throws Exception {
        roundtrip("{\"type\":\"playSound\",\"soundId\":\"entity.ender_dragon.growl\","
                + "\"volume\":1.0,\"pitch\":1.0,\"scope\":\"near\"}");
    }
    @Test void waitAction() throws Exception { roundtrip("{\"type\":\"wait\",\"ms\":500}"); }
    @Test void runCommand() throws Exception {
        Action a = mapper.readValue("{\"type\":\"runCommand\",\"templateId\":\"announce\","
                + "\"params\":{\"msg\":\"hi\"}}", Action.class);
        assertEquals(new Action.RunCommand("announce", Map.of("msg", "hi")), a);
    }
    @Test void logAction() throws Exception { roundtrip("{\"type\":\"log\",\"message\":\"scored\"}"); }

    @Test void ifNestedRoundtrip() throws Exception {
        String json = "{\"type\":\"if\",\"condition\":\"1 > 0\","
                + "\"then\":[{\"type\":\"log\",\"message\":\"yes\"},"
                + "{\"type\":\"if\",\"condition\":\"2 > 1\",\"then\":[],\"else\":[]}],"
                + "\"else\":[{\"type\":\"wait\",\"ms\":100}]}";
        Action a = mapper.readValue(json, Action.class);
        assertInstanceOf(Action.If.class, a);
        Action.If iff = (Action.If) a;
        assertEquals(2, iff.then().size());
        assertInstanceOf(Action.If.class, iff.then().get(1));
        assertEquals(List.of(new Action.Wait(100)), iff.elseActions());
        assertEquals(mapper.readTree(json), mapper.readTree(mapper.writeValueAsString(a)));
    }

    @Test void unknownTypeRejected() {
        assertThrows(Exception.class,
                () -> mapper.readValue("{\"type\":\"loop\"}", Action.class));
    }

    /** I-1 回归：if 分支元素为 null 必须走 reportInputMismatch（Jackson 异常），不能裸 NPE。 */
    @Test void ifBranchNullElementRejected() {
        String json = "{\"type\":\"if\",\"condition\":\"x\",\"then\":[null],\"else\":[]}";
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> mapper.readValue(json, Action.class));
    }

    /** M-4 回归：setVariable 缺 value 字段被拒。 */
    @Test void setVariableMissingFieldRejected() {
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> mapper.readValue("{\"type\":\"setVariable\",\"fullName\":\"x\"}", Action.class));
    }

    // ---------- 0.7.1：6 个新 Action 子类 round-trip ----------

    @Test void setElementProperties_roundTrip() throws Exception {
        Action a = new Action.SetElementProperties(
                "e-1", Map.of("x", "128", "y", "64"), "moveTo");
        String json = mapper.writeValueAsString(a);
        Action back = mapper.readValue(json, Action.class);
        assertEquals(a, back);
        assertTrue(json.contains("\"type\":\"setElementProperties\""), json);
    }

    /** kind=null 序列化成 ""，反序列化读回 ""（null→"" 单向折叠；kind 仅前端皮肤标记，无语义损失）。 */
    @Test void setElementProperties_nullKind_serializesEmptyReadsEmpty() throws Exception {
        Action a = new Action.SetElementProperties("e-1", Map.of("opacity", "0.5"), null);
        String json = mapper.writeValueAsString(a);
        assertTrue(json.contains("\"kind\":\"\""), json);
        Action back = mapper.readValue(json, Action.class);
        assertEquals(new Action.SetElementProperties("e-1", Map.of("opacity", "0.5"), ""), back);
    }

    @Test void nudgeElement_roundTrip() throws Exception {
        Action a = new Action.NudgeElement("e-1", 5.0, -3.0);
        assertEquals(a, mapper.readValue(mapper.writeValueAsString(a), Action.class));
    }

    @Test void sendMessage_roundTrip() throws Exception {
        Action a = new Action.SendMessage("hi ${var:user/name}", "actionbar", "trigger");
        assertEquals(a, mapper.readValue(mapper.writeValueAsString(a), Action.class));
    }

    @Test void setRandomVariable_roundTrip() throws Exception {
        Action a = new Action.SetRandomVariable("user/roll", 1.0, 6.0);
        assertEquals(a, mapper.readValue(mapper.writeValueAsString(a), Action.class));
    }

    @Test void scaleVariable_roundTrip() throws Exception {
        Action a = new Action.ScaleVariable("user/score", "multiply", 2.0);
        assertEquals(a, mapper.readValue(mapper.writeValueAsString(a), Action.class));
    }

    @Test void playTimelineAwait_roundTrip() throws Exception {
        Action a = new Action.PlayTimelineAwait("t-1");
        assertEquals(a, mapper.readValue(mapper.writeValueAsString(a), Action.class));
    }

    // ---------- 0.7.1：Repeat 有界循环 round-trip ----------

    @Test void repeat_roundTrip() throws Exception {
        Action a = new Action.Repeat(3, List.of(new Action.Log("loop")));
        String json = mapper.writeValueAsString(a);
        assertEquals(a, mapper.readValue(json, Action.class));
        assertTrue(json.contains("\"type\":\"repeat\""), json);
        assertTrue(json.contains("\"count\":3"), json);
    }

    @Test void repeat_nestedBody_roundTrip() throws Exception {
        // body 含 if 嵌套（递归 round-trip）
        String json = "{\"type\":\"repeat\",\"count\":5,\"body\":["
                + "{\"type\":\"log\",\"message\":\"a\"},"
                + "{\"type\":\"if\",\"condition\":\"1 > 0\","
                + "\"then\":[{\"type\":\"wait\",\"ms\":100}],\"else\":[]}]}";
        Action a = mapper.readValue(json, Action.class);
        assertInstanceOf(Action.Repeat.class, a);
        Action.Repeat rep = (Action.Repeat) a;
        assertEquals(5, rep.count());
        assertEquals(2, rep.body().size());
        assertEquals(mapper.readTree(json), mapper.readTree(mapper.writeValueAsString(a)));
    }

    /** repeat.count 非整数值 → 拒（K8 纪律）。 */
    @Test void repeat_fractionalCountRejected() {
        assertThrows(Exception.class, () -> mapper.readValue(
                "{\"type\":\"repeat\",\"count\":3.5,\"body\":[]}", Action.class));
    }

    /** repeat 缺 count → 拒。 */
    @Test void repeat_missingCountRejected() {
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> mapper.readValue("{\"type\":\"repeat\",\"body\":[]}", Action.class));
    }

    /** repeat body 缺失 → 空 list（合法 wire 形态；结构校验在 validator）。 */
    @Test void repeat_missingBody_emptyList() throws Exception {
        Action a = mapper.readValue("{\"type\":\"repeat\",\"count\":2}", Action.class);
        assertEquals(new Action.Repeat(2, List.of()), a);
    }

    /** setElementProperties 缺 elementId → 拒。 */
    @Test void setElementProperties_missingElementId_rejected() {
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> mapper.readValue(
                        "{\"type\":\"setElementProperties\",\"patch\":{\"x\":\"1\"}}", Action.class));
    }

    /** 0.7.0-P2-1(K8)：wait.ms 非整数值（100.5）→ 拒，不静默截断。 */
    @Test void waitFractionalMsRejected() {
        assertThrows(Exception.class, () -> mapper.readValue(
                "{\"type\":\"wait\",\"ms\":100.5}", Action.class));
        // 整值浮点 500.0 同拒
        assertThrows(Exception.class, () -> mapper.readValue(
                "{\"type\":\"wait\",\"ms\":500.0}", Action.class));
        // playTimeline.seekMs（可选字段）存在但非整数同拒
        assertThrows(Exception.class, () -> mapper.readValue(
                "{\"type\":\"playTimeline\",\"timelineId\":\"tl-1\",\"op\":\"seek\",\"seekMs\":1000.5}",
                Action.class));
    }

    // ---------- 0.7.1-P5：停止 / 粒子 / 等待直到 3 个新 Action 子类 round-trip ----------

    @Test void stopScript_roundTrip() throws Exception {
        Action a = new Action.StopScript();
        String json = mapper.writeValueAsString(a);
        assertTrue(json.contains("\"type\":\"stopScript\""), json);
        assertEquals(a, mapper.readValue(json, Action.class));
    }

    @Test void playParticle_roundTrip() throws Exception {
        // wire 字段名是 particle（双端同名约束）
        Action a = new Action.PlayParticle("minecraft:flame", 10, 0.5, 0.5, 0.5);
        String json = mapper.writeValueAsString(a);
        assertTrue(json.contains("\"type\":\"playParticle\""), json);
        assertTrue(json.contains("\"particle\":\"minecraft:flame\""), json);
        assertEquals(a, mapper.readValue(json, Action.class));
    }

    @Test void waitUntil_roundTrip() throws Exception {
        Action a = new Action.WaitUntil("var(\"user/x\") > 0", 5000L);
        String json = mapper.writeValueAsString(a);
        assertTrue(json.contains("\"type\":\"waitUntil\""), json);
        assertEquals(a, mapper.readValue(json, Action.class));
    }

    /** playParticle 缺 particle 字段 → 拒。 */
    @Test void playParticle_missingParticleRejected() {
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> mapper.readValue("{\"type\":\"playParticle\",\"count\":10,"
                        + "\"offsetX\":0,\"offsetY\":0,\"offsetZ\":0}", Action.class));
    }

    /** waitUntil.timeoutMs 非整数值（K8 纪律）→ 拒。 */
    @Test void waitUntil_fractionalTimeoutRejected() {
        assertThrows(Exception.class, () -> mapper.readValue(
                "{\"type\":\"waitUntil\",\"condition\":\"x>0\",\"timeoutMs\":5000.5}", Action.class));
    }

    // ---------- 0.7.2-P2：copy / append / clone / delete 4 个新 Action 子类 round-trip ----------

    @Test void copyVariable_roundtrip() throws Exception {
        Action a = new Action.CopyVariable("user/dst", "user/src");
        String json = mapper.writeValueAsString(a);
        assertTrue(json.contains("\"type\":\"copyVariable\""), json);
        assertEquals(a, mapper.readValue(json, Action.class));
    }

    @Test void appendVariable_roundtrip() throws Exception {
        Action a = new Action.AppendVariable("user/log", "事件 ${var:user/x}");
        String json = mapper.writeValueAsString(a);
        assertTrue(json.contains("\"type\":\"appendVariable\""), json);
        assertEquals(a, mapper.readValue(json, Action.class));
    }

    @Test void cloneElement_roundtrip() throws Exception {
        Action a = new Action.CloneElement("e-abc", 10, 20);
        String json = mapper.writeValueAsString(a);
        assertTrue(json.contains("\"type\":\"cloneElement\""), json);
        assertTrue(json.contains("\"offsetX\":10"), json);
        assertEquals(a, mapper.readValue(json, Action.class));
    }

    @Test void deleteElement_roundtrip() throws Exception {
        Action a = new Action.DeleteElement("e-xyz");
        String json = mapper.writeValueAsString(a);
        assertTrue(json.contains("\"type\":\"deleteElement\""), json);
        assertEquals(a, mapper.readValue(json, Action.class));
    }

    /** copyVariable 缺 source → 拒。 */
    @Test void copyVariable_missingSourceRejected() {
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> mapper.readValue("{\"type\":\"copyVariable\",\"target\":\"user/d\"}", Action.class));
    }

    /** cloneElement.offsetX 非整数值（K8 纪律）→ 拒。 */
    @Test void cloneElement_fractionalOffsetRejected() {
        assertThrows(Exception.class, () -> mapper.readValue(
                "{\"type\":\"cloneElement\",\"elementId\":\"e-1\",\"offsetX\":1.5,\"offsetY\":2}",
                Action.class));
    }

    // ---------- 0.7.2-P3：repeatUntil round-trip + sendMessage 加 target ----------

    @Test void repeatUntil_roundtrip() throws Exception {
        Action a = new Action.RepeatUntil("var(\"user/x\") > 5", 10,
                List.of(new Action.Log("loop")));
        String json = mapper.writeValueAsString(a);
        assertTrue(json.contains("\"type\":\"repeatUntil\""), json);
        assertTrue(json.contains("\"maxIterations\":10"), json);
        assertEquals(a, mapper.readValue(json, Action.class));
    }

    /** repeatUntil body 含 if 嵌套（递归 round-trip）。 */
    @Test void repeatUntil_nestedBody_roundtrip() throws Exception {
        String json = "{\"type\":\"repeatUntil\",\"condition\":\"1 > 0\",\"maxIterations\":5,"
                + "\"body\":[{\"type\":\"log\",\"message\":\"a\"},"
                + "{\"type\":\"if\",\"condition\":\"2 > 1\",\"then\":[{\"type\":\"wait\",\"ms\":100}],"
                + "\"else\":[]}]}";
        Action a = mapper.readValue(json, Action.class);
        assertInstanceOf(Action.RepeatUntil.class, a);
        Action.RepeatUntil ru = (Action.RepeatUntil) a;
        assertEquals(5, ru.maxIterations());
        assertEquals(2, ru.body().size());
        assertEquals(mapper.readTree(json), mapper.readTree(mapper.writeValueAsString(a)));
    }

    /** repeatUntil.maxIterations 非整数值 → 拒（K8 纪律）。 */
    @Test void repeatUntil_fractionalMaxIterationsRejected() {
        assertThrows(Exception.class, () -> mapper.readValue(
                "{\"type\":\"repeatUntil\",\"condition\":\"x>0\",\"maxIterations\":3.5,\"body\":[]}",
                Action.class));
    }

    /** repeatUntil 缺 condition → 拒。 */
    @Test void repeatUntil_missingConditionRejected() {
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> mapper.readValue(
                        "{\"type\":\"repeatUntil\",\"maxIterations\":3,\"body\":[]}", Action.class));
    }

    @Test void sendMessage_withTarget_roundtrip() throws Exception {
        Action a = new Action.SendMessage("hi", "chat", "all");
        String json = mapper.writeValueAsString(a);
        assertTrue(json.contains("\"target\":\"all\""), json);
        assertEquals(a, mapper.readValue(json, Action.class));
    }

    /** 旧 payload 无 target → 反序列化默 "trigger"（向后兼容）。 */
    @Test void sendMessage_legacyNoTarget_defaultsToTrigger() throws Exception {
        String legacy = "{\"type\":\"sendMessage\",\"text\":\"x\",\"channel\":\"chat\"}";
        Action a = mapper.readValue(legacy, Action.class);
        assertEquals("trigger", ((Action.SendMessage) a).target());
    }

    // ---------- tween：补间动画包裹 round-trip ----------

    /** 基础 roundtrip：LINEAR 缓动 + moveTo body。 */
    @Test void tweenBlock_linear_roundtrip() throws Exception {
        Action body = new Action.SetElementProperties("e-1", Map.of("x", "100", "y", "200"), "moveTo");
        Action a = new Action.TweenBlock(1500L, Easing.LINEAR, List.of(body));
        String json = mapper.writeValueAsString(a);
        assertTrue(json.contains("\"type\":\"tweenBlock\""), json);
        assertTrue(json.contains("\"durationMs\":1500"), json);
        assertTrue(json.contains("\"type\":\"linear\""), json);
        Action back = mapper.readValue(json, Action.class);
        assertInstanceOf(Action.TweenBlock.class, back);
        Action.TweenBlock tb = (Action.TweenBlock) back;
        assertEquals(1500L, tb.durationMs());
        assertEquals(EasingType.LINEAR, tb.easing().type());
        assertNull(tb.easing().bezier());
        assertEquals(1, tb.body().size());
        assertEquals(body, tb.body().get(0));
    }

    /** easeOut 缓动 roundtrip。 */
    @Test void tweenBlock_easeOut_roundtrip() throws Exception {
        Action a = new Action.TweenBlock(2000L, new Easing(EasingType.EASE_OUT, null),
                List.of(new Action.SetElementProperties("e-1", Map.of("opacity", "0"), "setOpacity")));
        String json = mapper.writeValueAsString(a);
        assertTrue(json.contains("\"type\":\"easeOut\""), json);
        Action back = mapper.readValue(json, Action.class);
        assertEquals(EasingType.EASE_OUT, ((Action.TweenBlock) back).easing().type());
        assertEquals(2000L, ((Action.TweenBlock) back).durationMs());
    }

    /** cubicBezier 缓动 roundtrip：4 控制点逐字段等。 */
    @Test void tweenBlock_cubicBezier_roundtrip() throws Exception {
        Easing bz = new Easing(EasingType.CUBIC_BEZIER, List.of(0.25, 0.1, 0.25, 1.0));
        Action a = new Action.TweenBlock(800L, bz,
                List.of(new Action.SetElementProperties("e-2", Map.of("rotation", "90"), "rotateTo")));
        String json = mapper.writeValueAsString(a);
        assertTrue(json.contains("\"bezier\":[0.25,0.1,0.25,1.0]"), json);
        Action back = mapper.readValue(json, Action.class);
        Action.TweenBlock tb = (Action.TweenBlock) back;
        assertEquals(EasingType.CUBIC_BEZIER, tb.easing().type());
        assertEquals(List.of(0.25, 0.1, 0.25, 1.0), tb.easing().bezier());
    }

    /** easing 字段缺失 → fallback LINEAR（Easing.LINEAR 等价）。 */
    @Test void tweenBlock_missingEasing_defaultsLinear() throws Exception {
        String json = "{\"type\":\"tweenBlock\",\"durationMs\":500,"
                + "\"body\":[{\"type\":\"setElementProperties\","
                + "\"elementId\":\"e-1\",\"patch\":{\"opacity\":\"1\"},\"kind\":\"setOpacity\"}]}";
        Action a = mapper.readValue(json, Action.class);
        assertInstanceOf(Action.TweenBlock.class, a);
        Action.TweenBlock tb = (Action.TweenBlock) a;
        assertEquals(EasingType.LINEAR, tb.easing().type());
    }

    /** durationMs 非整数值 → 拒（K8 纪律）。 */
    @Test void tweenBlock_fractionalDurationRejected() {
        assertThrows(Exception.class, () -> mapper.readValue(
                "{\"type\":\"tweenBlock\",\"durationMs\":1500.5,\"easing\":{\"type\":\"linear\"},"
                + "\"body\":[]}", Action.class));
    }

    /** tweenBlock 缺 durationMs → 拒。 */
    @Test void tweenBlock_missingDurationRejected() {
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> mapper.readValue("{\"type\":\"tweenBlock\","
                        + "\"easing\":{\"type\":\"linear\"},\"body\":[]}", Action.class));
    }
}
