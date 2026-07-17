package ac.haru.hikaricanvas.template;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 模板侧的 text 元素效果块。结构与 {@link ac.haru.hikaricanvas.state.Effects} 平行，
 * 但允许字段值为 {@code ${param}} 表达式字符串。仅持有原文本，实例化时插值。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TemplateEffects(Stroke stroke, Shadow shadow, Glow glow) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Stroke(Object width, String color) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Shadow(Object dx, Object dy, String color) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Glow(Object radius, String color) {}
}
