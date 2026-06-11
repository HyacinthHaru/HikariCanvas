package moe.hikari.canvas.script;

import com.fasterxml.jackson.databind.ObjectMapper;
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
        Action a = new Action.SendMessage("hi ${var:user/name}", "actionbar");
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
}
