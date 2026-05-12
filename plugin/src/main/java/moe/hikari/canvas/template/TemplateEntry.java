package moe.hikari.canvas.template;

/**
 * 注册表中的单条模板条目 = 纯数据 {@link TemplateSpec} + 来源元信息。
 *
 * <p>{@code sourceLabel} 用于诊断日志（"templates/subway.yml" 或 jar 内路径）；
 * 模板 id 冲突时方便服主追踪哪个文件被覆盖了。</p>
 */
public record TemplateEntry(
        TemplateSpec spec,
        TemplateSource source,
        String sourceLabel
) {
}
