package ac.haru.hikaricanvas.script;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

/**
 * {@link Trigger} 序列化：{@code type} 判别字段 + 各子类扁平字段。
 * 与 {@link TriggerDeserializer} 严格互逆（wire 契约 {@code docs/scripting.md §2.2}）。
 */
public final class TriggerSerializer extends JsonSerializer<Trigger> {

    @Override
    public void serialize(Trigger value, JsonGenerator gen, SerializerProvider provider)
            throws IOException {
        gen.writeStartObject();
        gen.writeStringField("type", value.wireType());
        switch (value) {
            case Trigger.VariableChange v -> gen.writeStringField("fullName", v.fullName());
            case Trigger.Timer t -> gen.writeNumberField("intervalSeconds", t.intervalSeconds());
            case Trigger.PlayerNear n -> gen.writeNumberField("rangeBlocks", n.rangeBlocks());
            case Trigger.PlayerJoin ignored -> { }
            case Trigger.PlayerKill ignored -> { }
            case Trigger.WallReady ignored -> { }
            case Trigger.RightClickWall ignored -> { }
            case Trigger.PlayerLeaveRange n -> gen.writeNumberField("rangeBlocks", n.rangeBlocks());
            case Trigger.PlayerQuit ignored -> { }
        }
        gen.writeEndObject();
    }
}
