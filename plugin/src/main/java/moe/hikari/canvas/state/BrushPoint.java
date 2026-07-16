package moe.hikari.canvas.state;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 笔触采样点。坐标 {@code x / y} 相对 {@link BrushStrokeElement#x()} / {@code y()}
 * 即 element bbox 左上原点；{@code pressure} 是 PointerEvent.pressure 范围 {@code [0, 1]}，
 * 鼠标默认 {@code 0.5}（spec 行为）、数位板真实压感、Apple Pencil 真实压感。
 *
 * @param x        相对 bbox 左上的 x 坐标（px）
 * @param y        相对 bbox 左上的 y 坐标（px）
 * @param pressure 压感 {@code [0, 1]}
 */
public record BrushPoint(
        @JsonProperty("x") double x,
        @JsonProperty("y") double y,
        @JsonProperty("pressure") double pressure
) {
}
