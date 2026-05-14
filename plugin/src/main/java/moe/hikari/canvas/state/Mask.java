package moe.hikari.canvas.state;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * M13 {@link ImageElement} 的可选蒙版。{@code d} 为 SVG path 字符串（M/L/Q/C/Z 子集，
 * 相对 element bbox {@code (0, 0)..(w, h)}），复用 {@link PathDValidator} 校验
 * （含 4096 字符长度上限）；{@code inverted=false} 时显示 mask 内部像素（默认），
 * {@code true} 时显示 mask 外部（用 element bbox 减去 mask 形状）。
 *
 * <p>契约见 {@code docs/protocol.md §7} ImageElement 段、{@code docs/security.md §4.5 (i)}、
 * {@code docs/rendering.md §4.4}。</p>
 *
 * <p>v1 仅前端 RightPanel dropdown 暴露 {@code none / circle / roundedRect / ellipse}
 * 4 预设；lasso 自由路径 / 拖动编辑 mask 形状 v2 再做（数据模型已留接口）。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Mask(String d, boolean inverted) {
}
