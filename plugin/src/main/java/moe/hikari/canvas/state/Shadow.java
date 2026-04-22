package moe.hikari.canvas.state;

/**
 * 文本阴影效果参数，契约对应 {@code docs/protocol.md §7 TextElement.effects.shadow}。
 *
 * @param dx    水平偏移（px），正值向右
 * @param dy    垂直偏移（px），正值向下
 * @param color {@code "#RRGGBB"} 或 {@code "#RRGGBBAA"}
 */
public record Shadow(int dx, int dy, String color) {
}
