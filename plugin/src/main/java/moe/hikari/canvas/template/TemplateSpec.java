package moe.hikari.canvas.template;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * 单个模板的顶层 schema。契约见 {@code docs/template-spec.md §2}。
 *
 * <p>这是纯数据 record——加载来源 / 校验状态由 {@link TemplateEntry} 包装持有。</p>
 *
 * <p><b>解析使用：</b> {@link TemplateLoader} 用 {@code jackson-dataformat-yaml} 的
 * {@code ObjectMapper.readValue(stream, TemplateSpec.class)} 直接构造。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TemplateSpec(
        int spec,
        String id,
        String name,
        String description,
        Integer version,
        String author,
        List<String> tags,
        String preview,
        TemplateCanvas canvas,
        Map<String, TemplateParam> params,
        TemplateLayout layout
) {
}
