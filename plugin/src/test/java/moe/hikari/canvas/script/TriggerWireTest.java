package moe.hikari.canvas.script;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TriggerWireTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void variableChangeRoundtrip() throws Exception {
        String json = "{\"type\":\"variableChange\",\"fullName\":\"user/score\"}";
        Trigger t = mapper.readValue(json, Trigger.class);
        assertEquals(new Trigger.VariableChange("user/score"), t);
        assertEquals(mapper.readTree(json), mapper.readTree(mapper.writeValueAsString(t)));
    }

    @Test
    void timerRoundtrip() throws Exception {
        String json = "{\"type\":\"timer\",\"intervalSeconds\":30}";
        Trigger t = mapper.readValue(json, Trigger.class);
        assertEquals(new Trigger.Timer(30), t);
        assertEquals(mapper.readTree(json), mapper.readTree(mapper.writeValueAsString(t)));
    }

    @Test
    void noFieldTriggersRoundtrip() throws Exception {
        for (String type : new String[]{"playerJoin", "playerKill", "wallReady"}) {
            String json = "{\"type\":\"" + type + "\"}";
            Trigger t = mapper.readValue(json, Trigger.class);
            assertEquals(mapper.readTree(json), mapper.readTree(mapper.writeValueAsString(t)));
        }
    }

    @Test
    void playerNearRoundtrip() throws Exception {
        String json = "{\"type\":\"playerNear\",\"rangeBlocks\":8}";
        assertEquals(new Trigger.PlayerNear(8), mapper.readValue(json, Trigger.class));
    }

    // ---------- 0.7.1：3 个新触发器 round-trip ----------

    @Test
    void rightClickWallRoundtrip() throws Exception {
        Trigger t = new Trigger.RightClickWall();
        assertEquals(t, mapper.readValue(mapper.writeValueAsString(t), Trigger.class));
        assertTrue(mapper.writeValueAsString(t).contains("\"type\":\"rightClickWall\""));
    }

    @Test
    void playerLeaveRangeRoundtrip() throws Exception {
        Trigger t = new Trigger.PlayerLeaveRange(8);
        assertEquals(t, mapper.readValue(mapper.writeValueAsString(t), Trigger.class));
        assertTrue(mapper.writeValueAsString(t).contains("\"type\":\"playerLeaveRange\""));
    }

    @Test
    void playerQuitRoundtrip() throws Exception {
        Trigger t = new Trigger.PlayerQuit();
        assertEquals(t, mapper.readValue(mapper.writeValueAsString(t), Trigger.class));
        assertTrue(mapper.writeValueAsString(t).contains("\"type\":\"playerQuit\""));
    }

    /** 0.7.1：playerLeaveRange 的 rangeBlocks 非整数值 → 拒（同 playerNear K8 纪律）。 */
    @Test
    void playerLeaveRangeFractionalRejected() {
        assertThrows(Exception.class, () -> mapper.readValue(
                "{\"type\":\"playerLeaveRange\",\"rangeBlocks\":8.5}", Trigger.class));
        assertThrows(Exception.class, () -> mapper.readValue(
                "{\"type\":\"playerLeaveRange\",\"rangeBlocks\":8.0}", Trigger.class));
    }

    @Test
    void unknownTypeRejected() {
        assertThrows(Exception.class,
                () -> mapper.readValue("{\"type\":\"onCommand\"}", Trigger.class));
    }

    @Test
    void missingTypeRejected() {
        assertThrows(Exception.class,
                () -> mapper.readValue("{\"fullName\":\"x\"}", Trigger.class));
    }

    /** 0.7.0-P2-1(K8)：intervalSeconds 非整数值（1.9）→ 拒，不静默截断成 1。 */
    @Test
    void timerFractionalIntervalRejected() {
        assertThrows(Exception.class, () -> mapper.readValue(
                "{\"type\":\"timer\",\"intervalSeconds\":1.9}", Trigger.class));
        // 整值浮点 30.0 同拒（wire 形态必须是 JSON 整数）
        assertThrows(Exception.class, () -> mapper.readValue(
                "{\"type\":\"timer\",\"intervalSeconds\":30.0}", Trigger.class));
        // rangeBlocks 同理
        assertThrows(Exception.class, () -> mapper.readValue(
                "{\"type\":\"playerNear\",\"rangeBlocks\":8.5}", Trigger.class));
    }
}
