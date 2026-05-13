package moe.hikari.canvas.state;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 图层 / 元素混合模式（M8 v2 引入）。
 *
 * <p>v1 集合限定为 4 种最常用模式，足以满足像素招牌的多数表现需求；
 * 公式见 {@code docs/rendering.md §6.6}。后续如需 PS 风格更多模式（darken / lighten / color-burn 等），
 * 在不破坏现有 4 种枚举值的前提下追加即可。</p>
 *
 * <p>序列化采用小写字符串（{@code "normal"} 等）以贴合协议契约。</p>
 */
public enum BlendMode {
    @JsonProperty("normal") NORMAL,
    @JsonProperty("multiply") MULTIPLY,
    @JsonProperty("screen") SCREEN,
    @JsonProperty("overlay") OVERLAY
}
