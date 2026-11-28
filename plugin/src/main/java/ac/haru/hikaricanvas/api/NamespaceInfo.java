package ac.haru.hikaricanvas.api;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * 外部插件向 {@link HikariCanvasAPI#registerNamespace} 注册命名空间时附带的展示元信息。
 *
 * <p>用于网页编辑器变量选择器分组标题与审计日志展示。</p>
 *
 * @param displayName 可读分组名称（如 {@code "起床战争比赛数据"}），显示在编辑器变量选择器中
 * @param pluginName  调用方插件名称（建议与 {@code plugin.getPluginMeta().getName()} 一致）
 * @param version     调用方插件版本字符串
 */
public record NamespaceInfo(
        @NotNull String displayName,
        @NotNull String pluginName,
        @NotNull String version
) {
    public NamespaceInfo {
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(pluginName, "pluginName");
        Objects.requireNonNull(version, "version");
    }
}
