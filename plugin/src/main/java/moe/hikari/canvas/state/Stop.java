package moe.hikari.canvas.state;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 渐变停止点。契约见 {@code docs/rendering.md §6.6}。
 *
 * @param position 在渐变线 / 渐变圈上的归一化位置，范围 {@code [0.0, 1.0]}
 * @param color    停止点颜色，{@code "#RRGGBB"} 或 {@code "#RRGGBBAA"}
 */
public record Stop(
        @JsonProperty("position") double position,
        @JsonProperty("color") String color
) {
}
