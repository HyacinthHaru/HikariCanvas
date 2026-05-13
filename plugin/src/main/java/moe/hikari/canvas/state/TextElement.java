package moe.hikari.canvas.state;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 文本元素。契约对应 {@code docs/protocol.md §7 TextElement} + {@code docs/rendering.md §3}。
 *
 * <p><b>M4-T5 实装字段：</b> {@code text / fontId / fontSize / color / align /
 * letterSpacing / lineHeight / vertical}。</p>
 *
 * <p><b>M4-T5 注意：</b></p>
 * <ul>
 *   <li>{@code align}：{@code "left" | "center" | "right"}，对每一行分别应用</li>
 *   <li>{@code lineHeight}：行高倍数（{@code fontSize * lineHeight}），默认 1.2</li>
 *   <li>{@code letterSpacing}：字符间距（px），可为负数；首尾不加</li>
 *   <li>{@code vertical}：竖排标志位</li>
 *   <li>{@code effects}（描边 / 阴影 / 发光）：M4-T8 / T9 / T10 追加字段</li>
 * </ul>
 *
 * <p><b>M8 v2 新增：</b> {@code opacity / blendMode / renderMode}（追加到末尾；nullable）。
 * 序列化按 {@link JsonInclude.Include#NON_NULL} 省略默认值。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TextElement(
        String id,
        int x, int y, int w, int h,
        int rotation,
        boolean locked,
        boolean visible,
        String text,
        String fontId,
        int fontSize,
        String color,
        String align,
        float letterSpacing,
        float lineHeight,
        boolean vertical,
        Effects effects,
        Float opacity,
        BlendMode blendMode,
        RenderMode renderMode
) implements Element {
}
