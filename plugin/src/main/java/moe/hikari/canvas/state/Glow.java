package moe.hikari.canvas.state;

/**
 * 文本发光效果参数，契约对应 {@code docs/protocol.md §7 TextElement.effects.glow}。
 *
 * <p>字形 mask → 盒式模糊（{@link #radius} 半径）→ 着色合成。</p>
 *
 * @param radius 模糊半径（px），0 = 无发光；上限由 EditSession 校验
 * @param color  {@code "#RRGGBB"} 或 {@code "#RRGGBBAA"}
 */
public record Glow(int radius, String color) {
}
