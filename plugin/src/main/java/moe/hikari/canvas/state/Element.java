package moe.hikari.canvas.state;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 工程状态中的单个图形元素。契约见 {@code docs/protocol.md §7}。
 *
 * <p>M3 最小元素集 = {@link TextElement} + {@link RectElement}。
 * {@code LineElement / IconElement / 效果族（stroke/shadow/glow）} 留 M4。</p>
 *
 * <p>Jackson 序列化：基于 {@code type} 字段的多态判定。写出的 JSON 会自动带
 * {@code "type": "text" | "rect"}。</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TextElement.class, name = "text"),
        @JsonSubTypes.Type(value = RectElement.class, name = "rect"),
})
public sealed interface Element permits TextElement, RectElement {

    /** {@code "e-<uuid>"}，由服务端生成，全局唯一。 */
    String id();

    /** 画布内像素坐标原点（左上角）。范围 {@code 0 .. widthMaps * 128}。 */
    int x();
    int y();

    /** 元素尺寸（像素）。正值；为 0 表示隐藏行为由渲染器决定。 */
    int w();
    int h();

    /** 旋转度数。M3 接受 0 / 90 / 180 / 270（规避双端渲染差异）。 */
    int rotation();

    /** 编辑器 UI 锁定；服务端不做权威校验，仅对前端生效。 */
    boolean locked();

    /** 是否参与渲染；false 时跳过渲染，不生成像素。 */
    boolean visible();
}
