package moe.hikari.canvas.state;

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
 *   <li>{@code vertical}：竖排标志位；M4-T5 暂不实装，渲染按 {@code false} + log WARN
 *       （见 {@code docs/rendering.md §3.3}，真正竖排排版推迟到 M4.5 / M7）</li>
 *   <li>{@code effects}（描边 / 阴影 / 发光）：M4-T8 / T9 / T10 追加字段；本 T5 未纳入</li>
 * </ul>
 */
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
        boolean vertical
) implements Element {
}
