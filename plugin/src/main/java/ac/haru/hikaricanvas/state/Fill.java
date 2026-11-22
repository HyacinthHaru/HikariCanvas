package ac.haru.hikaricanvas.state;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * 填充描述联合类型（纯色 / 线性渐变 / 径向渐变）。
 *
 * <p><b>三种形态：</b></p>
 * <ul>
 *   <li>{@link SolidFill} — 纯色</li>
 *   <li>{@link LinearGradient} — 线性渐变（角度 + N 停止点）</li>
 *   <li>{@link RadialGradient} — 径向渐变（中心 + 半径 + N 停止点）</li>
 * </ul>
 *
 * <p><b>反序列化 string / object union：</b> {@link FillDeserializer} 处理。
 * 字符串形态 {@code "#RRGGBB"} 等价于 {@code {"type":"solid","color":"#RRGGBB"}}，
 * 保留旧工程文件向后兼容。序列化统一输出 object 形态。</p>
 *
 * <p>{@link RectElement} / {@link CircleElement} / {@link ShapeElement} / {@link PathElement} /
 * {@link BrushStrokeElement} / {@link IconElement} 的 {@code fill} 字段以及
 * {@link ProjectState.Canvas#background()} 均使用 {@code Fill}；
 * {@code TextElement.color} / {@code Stroke.color} / {@code Effects} 内颜色仍为 {@code String}。</p>
 */
@JsonDeserialize(using = FillDeserializer.class)
public sealed interface Fill permits SolidFill, LinearGradient, RadialGradient {

    /** {@code "solid" | "linear" | "radial"}；序列化时写出。 */
    @JsonProperty("type")
    String type();

    /**
     * 便利构造：{@code "#RRGGBB"} / {@code "#RRGGBBAA"} → {@link SolidFill}。
     * 统一旧字符串入口。
     */
    static Fill solid(String color) {
        return new SolidFill(color == null ? "#FFFFFF" : color);
    }
}
