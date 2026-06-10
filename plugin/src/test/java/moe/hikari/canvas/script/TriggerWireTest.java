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
}
