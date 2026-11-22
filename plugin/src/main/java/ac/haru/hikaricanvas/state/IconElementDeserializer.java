package ac.haru.hikaricanvas.state;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * {@link IconElement} 的 Jackson 反序列化。
 *
 * <p><b>核心兼容职责：</b> 老数据只有 {@code tint: "#RRGGBB"} 字段；新数据
 * 写 {@code fill: {type, color}}。本 deserializer 负责"老→新"自动升级：</p>
 *
 * <ul>
 *   <li>读 {@code fill} —— 走 {@link FillDeserializer}；如非空则忽略 {@code tint}</li>
 *   <li>否则读 {@code tint} —— 转 {@link SolidFill}；保留 {@code tint} 字段值在
 *       record 里（不抹掉，前端 / 模板 raw_state 可能读它）</li>
 *   <li>两者都没 —— {@code fill = null}（即 pack 默认色）</li>
 * </ul>
 *
 * <p><b>未知字段策略：</b> {@link IconElement} 挂载自定义 {@code @JsonDeserialize} 后不再走默认
 * record 绑定，故显式维护 {@link #KNOWN_FIELDS} 并将未知字段转交 {@code ctxt.handleUnknownProperty}：
 * 协议入口的严格 mapper（{@code WebServer} / {@link ElementValidator}）下抛异常拒绝，
 * 持久化存储的宽容 mapper 下静默忽略；字段值范围校验统一由 {@link ElementValidator} 负责。</p>
 */
public final class IconElementDeserializer extends JsonDeserializer<IconElement> {

    /**
     * 本 deserializer 认识的字段。{@code type} 是 {@code @JsonTypeInfo} 的多态判别符——
     * 视 Jackson 是否把它留在转发过来的 tree 里而定，一律放行。
     */
    private static final Set<String> KNOWN_FIELDS = Set.of(
            "type", "id", "x", "y", "w", "h", "rotation", "locked", "visible",
            "source", "tint", "opacity", "blendMode", "renderMode", "fill");

    @Override
    public IconElement deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.readValueAsTree();
        if (node == null || node.isNull()) return null;
        if (!node.isObject()) {
            return (IconElement) ctxt.handleUnexpectedToken(
                    IconElement.class, p);
        }
        ObjectCodec codec = p.getCodec();

        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String field = names.next();
            if (KNOWN_FIELDS.contains(field)) continue;
            // 严格 mapper 下这一句会抛；宽容 mapper 下返 false，等同忽略该字段
            ctxt.handleUnknownProperty(p, this, IconElement.class, field);
        }

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
                blendMode = switch (s.toLowerCase(Locale.ROOT)) {
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
                renderMode = switch (s.toLowerCase(Locale.ROOT)) {
                    case "clean" -> RenderMode.CLEAN;
                    case "dither" -> RenderMode.DITHER;
                    default -> null;
                };
            }
        }

        // 关键兼容路径：fill 优先；fill 缺则把 tint 升级成 SolidFill
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
