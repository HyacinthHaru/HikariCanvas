package moe.hikari.canvas.template;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 模板顶层 {@code layout:} 块。契约见 {@code docs/template-spec.md §4}。
 *
 * <p><b>v1 实装范围：</b> {@code type: stack | free}。
 * {@code grid} 推迟到 M7 polish，由 {@link TemplateLoader} 在解析阶段拒绝。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TemplateLayout(
        String type,
        String direction,
        Integer gap,
        Integer columns,
        Integer rows,
        List<TemplateElement> elements
) {
}
