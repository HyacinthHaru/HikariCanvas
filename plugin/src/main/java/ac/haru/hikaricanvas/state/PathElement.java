package ac.haru.hikaricanvas.state;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 通用 path 元素。承载线 / 箭头 / 软线 / 点 五种用途，由 d 串 + marker 决定形态。
 *
 * <p><b>d 字段：</b> SVG path 命令子集 —— {@code M / L / Q / C / Z}（大写绝对，小写相对）。
 * 由 {@link PathDValidator#validate} 词法校验；真正构造 Path2D 在
 * {@code render/CanvasCompositor} 与前端 {@code PreviewRenderer} 内。</p>
 *
 * <p><b>坐标系：</b> d 内坐标<b>相对 element.(x, y)</b>。{@code transform} 改 x/y 时 d 不动；
 * {@code update d} 时 element.w/h 也不变（用户可手动 fit）—— 简化 bbox 同步。</p>
 *
 * <p><b>Marker：</b> {@code markerStart / markerEnd ∈ {"arrow", "dot", null}}。大小 + 颜色
 * 从 stroke 派生（arrow size ≈ stroke.width × 3；dot radius ≈ stroke.width）。</p>
 *
 * <p>opacity / blendMode / renderMode 与其他元素同语义。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PathElement(
        String id,
        int x, int y, int w, int h,
        int rotation,
        boolean locked,
        boolean visible,
        String d,
        Fill fill,              // 可空（纯色或渐变）
        Stroke stroke,          // 可空（仅填充）
        String markerStart,     // null / "arrow" / "dot"
        String markerEnd,
        Float opacity,
        BlendMode blendMode,
        RenderMode renderMode,
        String fillRule          // SVG fill-rule: null / "nonzero" / "evenodd"（null = 默认 nonzero）
) implements Element {
}
