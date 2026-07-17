package ac.haru.hikaricanvas.state;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 正多边形 / 星。
 *
 * <ul>
 *   <li>{@code kind = "polygon"}：正 {@code sides} 边形（{@code sides ∈ [3, 32]}）；
 *       {@code innerRatio} 字段被忽略</li>
 *   <li>{@code kind = "star"}：{@code sides} 角星（{@code sides ∈ [3, 32]}）；
 *       {@code innerRatio ∈ [0.1, 0.95]}，{@code null} 用默认 {@code 0.5}（标准 5 角星比例）</li>
 * </ul>
 *
 * <p>外接 bbox 完全由 element.x/y/w/h 决定（同 CircleElement 思路）：
 * 多边形 / 星的外接圆半径 = {@code min(w, h) / 2}，中心在 bbox 中心。
 * rotation 0 时第一个顶点朝上（−y 方向）。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShapeElement(
        String id,
        int x, int y, int w, int h,
        int rotation,
        boolean locked,
        boolean visible,
        String kind,            // "polygon" | "star"
        int sides,              // 3..32
        Float innerRatio,       // 仅 star 用；null = 默认 0.5
        Fill fill,              // 可空（纯色或渐变）
        Stroke stroke,          // 可空
        Float opacity,
        BlendMode blendMode,
        RenderMode renderMode
) implements Element {
}
