package moe.hikari.canvas.state;

/**
 * 矩形边框（{@link RectElement#stroke}）或未来效果族（M4 text outline）共用的描边参数。
 *
 * @param width 边框宽度（像素）。{@code 0} 表示无描边
 * @param color {@code "#RRGGBB"} 或 {@code "#RRGGBBAA"}
 */
public record Stroke(int width, String color) {
}
