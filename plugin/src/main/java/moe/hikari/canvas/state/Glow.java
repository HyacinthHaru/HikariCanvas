package moe.hikari.canvas.state;

/**
 * 文本发光效果参数，契约对应 {@code docs/protocol.md §7 TextElement.effects.glow}。
 *
 * <p>M4-T10 实装：字形 mask → 盒式模糊 radius 半径 → 着色合成。
 * M4-T8/T9 阶段字段已就位但 {@link moe.hikari.canvas.render.CanvasCompositor} 尚未绘制。</p>
 *
 * @param radius 模糊半径（px），0 = 无发光；上限由 EditSession 校验
 * @param color  {@code "#RRGGBB"} 或 {@code "#RRGGBBAA"}
 */
public record Glow(int radius, String color) {
}
