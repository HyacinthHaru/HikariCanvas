package ac.haru.hikaricanvas.state;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

/**
 * {@link Fill} 的 Jackson 反序列化：支持 {@code string ↔ object} 联合形态。
 *
 * <ul>
 *   <li>{@code "#FF0000"} → {@link SolidFill}（旧工程文件兼容）</li>
 *   <li>{@code {"type":"solid", ...}} / {@code {"type":"linear", ...}} / {@code {"type":"radial", ...}}
 *       → 对应 record（新工程文件写出形态）</li>
 *   <li>缺 {@code type} 字段的 object 当 {@code solid} 处理（防御已知 buggy 写出方）</li>
 * </ul>
 *
 * <p>写出方向 Jackson 默认行为即可：{@link Fill#type()} 上的 {@link com.fasterxml.jackson.annotation.JsonProperty}
 * 让 record accessor 之外的 {@code type} 字段也出现在 JSON 中。</p>
 */
public final class FillDeserializer extends JsonDeserializer<Fill> {

    @Override
    public Fill deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.readValueAsTree();
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return new SolidFill(node.asText());
        }
        if (node.isObject()) {
            String type = node.path("type").asText("solid");
            ObjectCodec codec = p.getCodec();
            return switch (type) {
                case "solid" -> new SolidFill(node.path("color").asText(null));
                case "linear" -> codec.treeToValue(node, LinearGradient.class);
                case "radial" -> codec.treeToValue(node, RadialGradient.class);
                default -> throw JsonMappingException.from(p,
                        "unknown fill type: " + type);
            };
        }
        throw JsonMappingException.from(p,
                "fill must be string or object, got: " + node.getNodeType());
    }
}
