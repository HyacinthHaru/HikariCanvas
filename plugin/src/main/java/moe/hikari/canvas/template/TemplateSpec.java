package moe.hikari.canvas.template;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * 单个模板的顶层 schema。契约见 {@code docs/template-spec.md §2}。
 *
 * <p>这是纯数据 record——加载来源 / 校验状态由 {@link TemplateEntry} 包装持有。</p>
 *
 * <p><b>解析使用：</b> {@link TemplateLoader} 用 {@code jackson-dataformat-yaml} 的
 * {@code ObjectMapper.readValue(stream, TemplateSpec.class)} 直接构造。</p>
 *
 * <p><b>raw state 模式：</b> {@link #rawState} 非空时，模板进入「raw state」模式 ——
 * 内嵌完整 {@code ProjectState} 的序列化形式（{@code Map} 结构）。{@link TemplateInstantiator}
 * 检测到 rawState 时绕开传统 {@code layout / canvas} 实例化路径，先对 raw state 内所有
 * String 字段做 {@code ${param}} 占位符替换，再反序列化为 {@code ProjectState} → 走 EditSession
 * replaceContent。{@code layout / canvas} 在 raw state 模式下可空。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
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
        TemplateLayout layout,
        /** 内嵌 ProjectState JSON（Map 形式）。null = 老 elements 模式。 */
        @JsonProperty("raw_state") Map<String, Object> rawState
) {
    /** 兼容老调用方（无 rawState 字段）。 */
    public TemplateSpec(int spec, String id, String name, String description,
                        Integer version, String author, List<String> tags, String preview,
                        TemplateCanvas canvas, Map<String, TemplateParam> params,
                        TemplateLayout layout) {
        this(spec, id, name, description, version, author, tags, preview,
                canvas, params, layout, null);
    }

    public boolean isRawStateMode() {
        return rawState != null;
    }
}
