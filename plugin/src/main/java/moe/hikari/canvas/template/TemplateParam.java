package moe.hikari.canvas.template;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 单个参数声明。契约见 {@code docs/template-spec.md §5}。
 *
 * <p>共享字段池：不是每种 {@code type} 都用到全部字段。多余字段在 v1 静默忽略。
 * 详细类型→字段对应见 §5.1 表。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TemplateParam(
        // type: string|text|int|float|bool|color|enum|font
        String type,
        String label,
        String description,
        boolean required,
        @JsonProperty("default") Object defaultValue,
        @JsonProperty("visible_when") String visibleWhen,
        String group,

        // string / text
        @JsonProperty("max_length") Integer maxLength,
        @JsonProperty("min_length") Integer minLength,
        String placeholder,
        String pattern,

        // int / float
        Double min,
        Double max,
        Double step,

        // enum
        List<Option> options,

        // color
        List<Option> presets
) {

    /** {@code enum / color} 用的选项条目。{@code value} 可为字符串或数字。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Option(String label, Object value) {
    }
}
