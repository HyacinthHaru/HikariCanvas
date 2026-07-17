package ac.haru.hikaricanvas.state;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 圆 / 椭圆。完全由 bbox 决定：
 * <pre>
 *   cx = x + w / 2
 *   cy = y + h / 2
 *   rx = w / 2
 *   ry = h / 2
 * </pre>
 * {@code rx == ry} 即正圆；其它即任意椭圆。这样 element.transform op 直接对 x/y/w/h
 * 操作，无需新增字段。
 *
 * <p>支持 fill / stroke 任一或两者皆有（同 RectElement）。空心圆 = fill=null + stroke.width>0。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CircleElement(
        String id,
        int x, int y, int w, int h,
        int rotation,
        boolean locked,
        boolean visible,
        Fill fill,            // 可空（纯色或渐变）
        Stroke stroke,        // 可空（与 fill 至少一个非空）
        Float opacity,
        BlendMode blendMode,
        RenderMode renderMode
) implements Element {
}
