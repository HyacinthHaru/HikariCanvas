package ac.haru.hikaricanvas.state;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 元素级渲染策略。
 *
 * <ul>
 *   <li>{@link #CLEAN}：颜色直接量化到 MC 调色板（默认）</li>
 *   <li>{@link #DITHER}：Bayer 4×4 ordered dither 后再量化</li>
 * </ul>
 *
 * <p>详见 {@code docs/rendering.md §6.7}。</p>
 */
public enum RenderMode {
    @JsonProperty("clean") CLEAN,
    @JsonProperty("dither") DITHER
}
