package ac.haru.hikaricanvas.api;

import org.jetbrains.annotations.NotNull;

import java.io.Serial;
import java.util.Objects;

/**
 * 调用 {@link HikariCanvasAPI} 操作变量或元数据时抛出的命名空间归属权异常：命名空间尚未注册或不属于当前插件。
 */
public final class PluginNamespaceException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public enum Code {
        /** 命名空间尚未注册，调用方须先执行 {@link HikariCanvasAPI#registerNamespace}。 */
        NAMESPACE_NOT_REGISTERED,
        /** 命名空间属于其他插件实例（防跨插件冒充覆写）。 */
        NAMESPACE_ACL_DENIED
    }

    private final Code code;
    private final String namespace;

    public PluginNamespaceException(@NotNull Code code, @NotNull String namespace, @NotNull String message) {
        super(Objects.requireNonNull(message, "message"));
        this.code = Objects.requireNonNull(code, "code");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
    }

    public @NotNull Code code() {
        return code;
    }

    public @NotNull String namespace() {
        return namespace;
    }
}
