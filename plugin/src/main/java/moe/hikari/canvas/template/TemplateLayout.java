package moe.hikari.canvas.template;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 模板顶层 {@code layout:} 块。契约见 {@code docs/template-spec.md §4}。
 *
 * <p><b>支持的布局：</b> {@code type: stack | free | grid}
 * （{@code grid} 需 {@code columns} / {@code rows} >= 1，见 {@link TemplateLoader}）。</p>
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
