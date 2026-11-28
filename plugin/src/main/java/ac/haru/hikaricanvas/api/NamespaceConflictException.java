package ac.haru.hikaricanvas.api;

import org.jetbrains.annotations.NotNull;

import java.io.Serial;
import java.util.Objects;

/**
 * {@link HikariCanvasAPI#registerNamespace} 抛出的命名空间冲突异常：目标命名空间已被其他插件占用。
 *
 * <p>同一插件实例重复注册自身已持有的命名空间不会抛此异常（视为幂等刷新）。</p>
 */
public final class NamespaceConflictException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String namespace;
    private final String existingPluginName;

    public NamespaceConflictException(@NotNull String namespace, @NotNull String existingPluginName) {
        super("namespace '" + Objects.requireNonNull(namespace, "namespace")
                + "' already registered by '" + Objects.requireNonNull(existingPluginName, "existingPluginName") + "'");
        this.namespace = namespace;
        this.existingPluginName = existingPluginName;
    }

    public @NotNull String namespace() {
        return namespace;
    }

    public @NotNull String existingPluginName() {
        return existingPluginName;
    }
}
