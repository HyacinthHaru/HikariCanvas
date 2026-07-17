package ac.haru.hikaricanvas.api;

import java.util.Objects;

/**
 * 外部插件向 {@link HikariCanvasAPI#registerNamespace} 声明 namespace 时附带的元信息。
 *
 * <p>用于编辑器 UI 显示与审计日志——HikariCanvas 自身行为不依赖这些字段。</p>
 *
 * @param displayName 人类可读的 namespace 名称（如 {@code "BedWars 比赛数据"}），编辑器 Picker 显示
 * @param pluginName  调用插件名（约定与 {@code plugin.getName()} 一致；编辑器 / 审计日志显示）
 * @param version     调用插件版本字符串（无格式约束）
 */
public record NamespaceInfo(
        String displayName,
        String pluginName,
        String version
) {
    public NamespaceInfo {
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(pluginName, "pluginName");
        Objects.requireNonNull(version, "version");
    }
}
