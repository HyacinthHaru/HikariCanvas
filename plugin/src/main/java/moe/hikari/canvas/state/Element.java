package moe.hikari.canvas.state;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 工程状态中的单个图形元素。契约见 {@code docs/protocol.md §7}。
 *
 * <p>M3 最小元素集 = {@link TextElement} + {@link RectElement}。{@link IconElement}
 * 在 M7 polish 引入。M8 v2 新增 {@link #opacity()} / {@link #blendMode()} / {@link #renderMode()}
 * 三个统一字段，详见 {@code docs/architecture.md §10.5}。</p>
 *
 * <p><b>v2 字段默认值约定：</b> 三字段均为 nullable，序列化按 {@code NON_NULL} 省略；
 * Java 侧调用方可通过 {@link #effectiveOpacity()} / {@link #effectiveBlendMode()} /
 * {@link #effectiveRenderMode()} 取兜底后的值（{@code 1.0f / NORMAL / CLEAN}）。
 * M8-A 阶段所有构造调用传 null 保持与 v1 等价行为；M8-C/E 起 EditSession 才接受 patch 修改。</p>
 *
 * <p>Jackson 序列化：基于 {@code type} 字段的多态判定。写出的 JSON 会自动带
 * {@code "type": "text" | "rect" | "icon"}。</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TextElement.class, name = "text"),
        @JsonSubTypes.Type(value = RectElement.class, name = "rect"),
        @JsonSubTypes.Type(value = IconElement.class, name = "icon"),
})
public sealed interface Element permits TextElement, RectElement, IconElement {

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
