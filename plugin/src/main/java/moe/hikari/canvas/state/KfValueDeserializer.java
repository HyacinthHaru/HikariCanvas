package moe.hikari.canvas.state;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

/**
 * {@link KfValue} 反序列化：number → {@link KfValue.Num}、string → {@link KfValue.Str}、
 * object → {@link KfValue.FillV}（经 {@link FillDeserializer} 分流 solid/linear/radial）。
 * 范式同 {@link FillDeserializer}（string/object 双形态宽容输入）。
 */
public final class KfValueDeserializer extends JsonDeserializer<KfValue> {

    @Override
    public KfValue deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.readValueAsTree();
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return new KfValue.Num(node.asDouble());
        }
        if (node.isTextual()) {
            return new KfValue.Str(node.asText());
        }
        if (node.isObject()) {
            Fill fill = p.getCodec().treeToValue(node, Fill.class);
            return new KfValue.FillV(fill);
        }
        throw JsonMappingException.from(p,
                "keyframe value must be number, string or fill object, got: " + node.getNodeType());
    }
}
