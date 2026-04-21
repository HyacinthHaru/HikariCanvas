package moe.hikari.canvas.state;

/**
 * 文本元素。M3 使用现有 {@link moe.hikari.canvas.render.BitmapFont} 渲染，
 * {@code fontId} 字段预留以在 M4 接入 TTF 字体系统后切换；
 * 当前 M3 只识别 {@code "bitmap"} 一种 fontId，其余值回退。
 *
 * <p>字段 {@code align}：{@code "left" | "center" | "right"}。</p>
 *
 * <p>M3 未实装 {@code lineHeight / letterSpacing / vertical / effects}——
 * 协议 §7 列出的完整字段集在 M4 一并补齐。</p>
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
        String align
) implements Element {
}
