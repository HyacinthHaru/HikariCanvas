package moe.hikari.canvas.state;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 工程状态中的单个图形元素。契约见 {@code docs/protocol.md §7}。
 *
 * <p>M3 最小元素集 = {@link TextElement} + {@link RectElement}。{@link IconElement}
 * 在 M7 polish 引入。M8 v2 新增 {@link #opacity()} / {@link #blendMode()} / {@link #renderMode()}
 * 三个统一字段，详见 {@code docs/architecture.md §10.5}。
 * M9 新增 {@link PathElement} / {@link CircleElement} / {@link ShapeElement}：</p>
 *
 * <ul>
 *   <li>{@code path}：通用 SVG-like 路径（M/L/Q/C/Z 子集 + marker），承载线/箭头/软线/点</li>
 *   <li>{@code circle}：圆 / 椭圆（bbox 推 cx/cy/rx/ry）</li>
 *   <li>{@code shape}：正多边形 / 星（kind + sides + innerRatio）</li>
 *   <li>{@code brush}（M12）：笔触，含 RDP 简化后的采样点 + 压感（{@link BrushStrokeElement}）</li>
 *   <li>{@code image}（M13）：位图图片，sha256[:16] 内容寻址 + 可选 SVG path 蒙版（{@link ImageElement}）</li>
 * </ul>
 *
 * <p><b>v2 字段默认值约定：</b> 三字段均为 nullable，序列化按 {@code NON_NULL} 省略；
 * Java 侧调用方可通过 {@link #effectiveOpacity()} / {@link #effectiveBlendMode()} /
 * {@link #effectiveRenderMode()} 取兜底后的值（{@code 1.0f / NORMAL / CLEAN}）。</p>
 *
 * <p>Jackson 序列化：基于 {@code type} 字段的多态判定。写出的 JSON 会自动带
 * {@code "type": "text" | "rect" | "icon" | "path" | "circle" | "shape" | "brush" | "image"}。</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TextElement.class, name = "text"),
        @JsonSubTypes.Type(value = RectElement.class, name = "rect"),
        @JsonSubTypes.Type(value = IconElement.class, name = "icon"),
        @JsonSubTypes.Type(value = PathElement.class, name = "path"),
        @JsonSubTypes.Type(value = CircleElement.class, name = "circle"),
        @JsonSubTypes.Type(value = ShapeElement.class, name = "shape"),
        @JsonSubTypes.Type(value = BrushStrokeElement.class, name = "brush"),
        @JsonSubTypes.Type(value = ImageElement.class, name = "image"),
})
public sealed interface Element permits TextElement, RectElement, IconElement,
        PathElement, CircleElement, ShapeElement, BrushStrokeElement, ImageElement {

    /** {@code "e-<uuid>"}，由服务端生成，全局唯一。 */
    String id();

    /** 画布内像素坐标原点（左上角）。范围 {@code 0 .. widthMaps * 128}。 */
    int x();
    int y();

    /** 元素尺寸（像素）。正值；为 0 表示隐藏行为由渲染器决定。 */
    int w();
    int h();

    /** 旋转度数。M5-D6 起接受 [0, 360)。 */
    int rotation();

    /** 编辑器 UI 锁定；服务端不做权威校验，仅对前端生效。 */
    boolean locked();

    /** 是否参与渲染；false 时跳过渲染，不生成像素。 */
    boolean visible();

    // ---------- M8 v2 新增（nullable，序列化省略默认值）----------

    /** 元素级不透明度 0.0–1.0；{@code null} = 默认 1.0。 */
    Float opacity();

    /** 元素级混合模式；{@code null} = 默认 {@link BlendMode#NORMAL}。 */
    BlendMode blendMode();

    /** 量化策略；{@code null} = 默认 {@link RenderMode#CLEAN}。 */
    RenderMode renderMode();

    // ---------- 兜底访问器 ----------

    default float effectiveOpacity() {
        Float v = opacity();
        return v == null ? 1.0f : v;
    }

    default BlendMode effectiveBlendMode() {
        BlendMode m = blendMode();
        return m == null ? BlendMode.NORMAL : m;
    }

    default RenderMode effectiveRenderMode() {
        RenderMode m = renderMode();
        return m == null ? RenderMode.CLEAN : m;
    }
}
