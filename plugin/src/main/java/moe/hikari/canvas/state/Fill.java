package moe.hikari.canvas.state;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * 填充描述（M11 引入）。承载几何元素的 fill 字段，统一替代原本的纯色 hex 字符串。
 *
 * <p><b>三种形态：</b></p>
 * <ul>
 *   <li>{@link SolidFill} — 纯色（v1 兼容形态）</li>
 *   <li>{@link LinearGradient} — 线性渐变（角度 + N 停止点）</li>
 *   <li>{@link RadialGradient} — 径向渐变（中心 + 半径 + N 停止点）</li>
 * </ul>
 *
 * <p><b>反序列化 string / object union：</b> {@link FillDeserializer} 处理。
 * 字符串形态 {@code "#RRGGBB"} 等价于 {@code {"type":"solid","color":"#RRGGBB"}}，
 * 保留 M0–M10 工程文件向后兼容。序列化统一输出 object 形态。</p>
 *
 * <p>仅 Rect / Circle / Shape / Path 元素的 {@code fill} 字段使用 {@code Fill}。
 * TextElement.color / IconElement.tint / Stroke.color / Effects.*.color 仍是
 * {@code String}（v1 不支持文本与描边的渐变；契约见 PROPOSAL §6 M11 条目）。</p>
 */
@JsonDeserialize(using = FillDeserializer.class)
public sealed interface Fill permits SolidFill, LinearGradient, RadialGradient {

    /** {@code "solid" | "linear" | "radial"}；序列化时写出。 */
    @JsonProperty("type")
    String type();
}
