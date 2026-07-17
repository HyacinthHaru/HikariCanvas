package ac.haru.hikaricanvas.state;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
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
        // 宽容路径：boolean / array 等非预期形态不抛硬错（异常会冒泡致整个 ProjectState
        // 加载失败，违反「坏数据不在反序列化期抛硬错」承诺）。返回 null 交 KeyframeInterpolator
        // 的 instanceof 守卫兜底（null 使该轨道采样跳过），与 Easing.java 宽容策略一致。
        // WS 写路径仍在 op 层（TimelineOperations）做严格类型校验，拒绝非法值。
        return null;
    }
}
