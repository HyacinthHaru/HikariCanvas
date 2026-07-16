package moe.hikari.canvas.state;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 图层 / 元素混合模式。
 *
 * <p>公式见 {@code docs/rendering.md §6.6}；追加新模式时勿改现有 4 个枚举值。</p>
 *
 * <p>序列化采用小写字符串（{@code "normal"} 等）以贴合协议契约。</p>
 */
public enum BlendMode {
    @JsonProperty("normal") NORMAL,
    @JsonProperty("multiply") MULTIPLY,
    @JsonProperty("screen") SCREEN,
    @JsonProperty("overlay") OVERLAY
}
