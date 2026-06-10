package moe.hikari.canvas.script;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * {@link Action} 序列化：{@code type} 判别字段 + 各子类扁平字段。
 * 与 {@link ActionDeserializer} 严格互逆（wire 契约 {@code docs/scripting.md §2.2}）。
 *
 * <p>特殊点：{@link Action.PlayTimeline#seekMs()} 为 null 不写字段；
 * {@link Action.If} 的 {@code elseActions} 写出为 {@code "else"} 字段、两分支递归序列化。</p>
 */
public final class ActionSerializer extends JsonSerializer<Action> {

    @Override
    public void serialize(Action value, JsonGenerator gen, SerializerProvider provider)
            throws IOException {
        gen.writeStartObject();
        gen.writeStringField("type", value.wireType());
        switch (value) {
            case Action.SetVariable a -> {
                gen.writeStringField("fullName", a.fullName());
                gen.writeStringField("value", a.value());
            }
            case Action.IncrementVariable a -> {
                gen.writeStringField("fullName", a.fullName());
                gen.writeNumberField("delta", a.delta());
            }
            case Action.SetElementProperty a -> {
                gen.writeStringField("elementId", a.elementId());
                gen.writeStringField("property", a.property());
                gen.writeStringField("value", a.value());
            }
            case Action.PlayTimeline a -> {
                gen.writeStringField("timelineId", a.timelineId());
                gen.writeStringField("op", a.op());
                if (a.seekMs() != null) {
                    gen.writeNumberField("seekMs", a.seekMs());
                }
            }
            case Action.PlaySound a -> {
                gen.writeStringField("soundId", a.soundId());
                gen.writeNumberField("volume", a.volume());
                gen.writeNumberField("pitch", a.pitch());
                gen.writeStringField("scope", a.scope());
            }
            case Action.Wait a -> gen.writeNumberField("ms", a.ms());
            case Action.RunCommand a -> {
                gen.writeStringField("templateId", a.templateId());
                gen.writeObjectFieldStart("params");
                for (Map.Entry<String, String> e : a.params().entrySet()) {
                    gen.writeStringField(e.getKey(), e.getValue());
                }
                gen.writeEndObject();
            }
            case Action.Log a -> gen.writeStringField("message", a.message());
            case Action.If a -> {
                gen.writeStringField("condition", a.condition());
                writeActions(gen, provider, "then", a.then());
                writeActions(gen, provider, "else", a.elseActions());
            }
        }
        gen.writeEndObject();
    }

    /** 递归写出分支数组（每个元素回到本 serializer）。 */
    private void writeActions(JsonGenerator gen, SerializerProvider provider,
                              String field, List<Action> actions) throws IOException {
        gen.writeArrayFieldStart(field);
        for (Action a : actions) {
            serialize(a, gen, provider);
        }
        gen.writeEndArray();
    }
}
