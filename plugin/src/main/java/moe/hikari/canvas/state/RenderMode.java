package moe.hikari.canvas.state;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 元素级渲染策略（M8 v2 引入）。
 *
 * <ul>
 *   <li>{@link #CLEAN}：颜色直接量化到 MC 调色板（默认；M8 即生效）</li>
 *   <li>{@link #DITHER}：Bayer 4×4 ordered dither 后再量化（M11 真正实装；M8 仅占字段）</li>
 * </ul>
 *
 * <p>字段在 M8 写入协议是为了一次性升级避免再做 v3，详见 {@code docs/rendering.md §6.7}。</p>
 */
public enum RenderMode {
    @JsonProperty("clean") CLEAN,
    @JsonProperty("dither") DITHER
}
