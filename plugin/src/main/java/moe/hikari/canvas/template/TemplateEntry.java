package moe.hikari.canvas.template;

import java.util.Optional;
import java.util.UUID;

/**
 * 注册表中的单条模板条目 = 纯数据 {@link TemplateSpec} + 来源元信息。
 *
 * <p>{@code sourceLabel} 用于诊断日志（"templates/subway.yml" 或 jar 内路径）；
 * 模板 id 冲突时方便服主追踪哪个文件被覆盖了。</p>
 *
 * <p><b>M16 P1.6 跨用户隔离：</b> {@code ownerUuid} 非空表示该条目来自
 * {@code user-templates/&lt;uuid&gt;/} 目录，apply 时调用方必须是 owner 或持
 * {@code canvas.template.use-others} bypass 权限。builtin / server 模板
 * {@code ownerUuid} = {@link Optional#empty()}，所有玩家可用。</p>
 */
public record TemplateEntry(
        TemplateSpec spec,
        TemplateSource source,
        String sourceLabel,
        Optional<UUID> ownerUuid
) {
    /** 兼容老调用方（M16 P1.6 之前无 ownerUuid 字段，默认 empty = 全局可用）。 */
    public TemplateEntry(TemplateSpec spec, TemplateSource source, String sourceLabel) {
        this(spec, source, sourceLabel, Optional.empty());
    }
}
