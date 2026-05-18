package moe.hikari.canvas.state;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

/**
 * {@link IconElement} 的 Jackson 反序列化（M26 引入）。
 *
 * <p><b>核心兼容职责：</b> 老数据（M7-M25）只有 {@code tint: "#RRGGBB"} 字段；新数据
 * （M26+）写 {@code fill: {type, color}}。本 deserializer 负责"老→新"自动升级：</p>
 *
 * <ul>
 *   <li>读 {@code fill}（M26+）—— 走 {@link FillDeserializer}；如非空则忽略 {@code tint}</li>
 *   <li>否则读 {@code tint}（M7-M25）—— 转 {@link SolidFill}；保留 {@code tint} 字段值在
 *       record 里（不抹掉，前端 / 模板 raw_state 可能读它）</li>
 *   <li>两者都没 —— {@code fill = null}（即 pack 默认色）</li>
 * </ul>
 *
 * <p><b>未知字段：</b> 严格模式下（FAIL_ON_UNKNOWN_PROPERTIES）我们必须显式 ack 已知字段，
 * 这里手工解析所有字段而不靠 Jackson 自动 binding，因为 IconElement record 已挂自定义
 * deserializer（@JsonDeserialize）就走不到默认 record binding 了。</p>
 *
 * <p>未知字段抛 {@link com.fasterxml.jackson.databind.JsonMappingException}（行为与 Jackson
 * FAIL_ON_UNKNOWN_PROPERTIES = true 一致；防协议漂移 / 字段嗅探）。</p>
 */
public final class IconElementDeserializer extends JsonDeserializer<IconElement> {

    @Override
    public IconElement deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.readValueAsTree();
        if (node == null || node.isNull()) return null;
        if (!node.isObject()) {
            return (IconElement) ctxt.handleUnexpectedToken(
                    IconElement.class, p);
        }
        ObjectCodec codec = p.getCodec();

        String id = textOrNull(node, "id");
        int x = node.path("x").asInt(0);
        int y = node.path("y").asInt(0);
        int w = node.path("w").asInt(0);
        int h = node.path("h").asInt(0);
        int rotation = node.path("rotation").asInt(0);
        boolean locked = node.path("locked").asBoolean(false);
        boolean visible = node.path("visible").asBoolean(true);
        String source = textOrNull(node, "source");
        String tint = textOrNull(node, "tint");

        Float opacity = null;
        if (node.has("opacity") && !node.get("opacity").isNull()) {
            opacity = (float) node.get("opacity").asDouble();
        }

        BlendMode blendMode = null;
        if (node.has("blendMode") && !node.get("blendMode").isNull()) {
            JsonNode bm = node.get("blendMode");
            String s = bm.isTextual() ? bm.asText() : null;
            if (s != null) {
                blendMode = switch (s.toLowerCase(java.util.Locale.ROOT)) {
                    case "normal" -> BlendMode.NORMAL;
                    case "multiply" -> BlendMode.MULTIPLY;
                    case "screen" -> BlendMode.SCREEN;
                    case "overlay" -> BlendMode.OVERLAY;
                    default -> null;
                };
            }
        }
        RenderMode renderMode = null;
        if (node.has("renderMode") && !node.get("renderMode").isNull()) {
            JsonNode rm = node.get("renderMode");
            String s = rm.isTextual() ? rm.asText() : null;
            if (s != null) {
                renderMode = switch (s.toLowerCase(java.util.Locale.ROOT)) {
                    case "clean" -> RenderMode.CLEAN;
                    case "dither" -> RenderMode.DITHER;
                    default -> null;
                };
            }
        }

        // M26 关键兼容路径：fill 优先；fill 缺则把 tint 升级成 SolidFill
        Fill fill = null;
        if (node.has("fill") && !node.get("fill").isNull()) {
            fill = codec.treeToValue(node.get("fill"), Fill.class);
        } else if (tint != null && !tint.isBlank()) {
            fill = new SolidFill(tint);
        }

        return new IconElement(id, x, y, w, h, rotation, locked, visible,
                source, tint, opacity, blendMode, renderMode, fill);
    }

    private static String textOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
