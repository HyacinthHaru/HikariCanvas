package ac.haru.hikaricanvas.api;

/**
 * {@link HikariCanvasAPI#registerNamespace} 抛：namespace 已被其他插件注册。
 *
 * <p>同插件重复注册同 namespace 不抛此异常（视为幂等覆盖）。</p>
 *
 * <p>调用方应在 onEnable 调用处 catch，决定是否禁用功能 / fail-fast plugin 启动。</p>
 */
public final class NamespaceConflictException extends RuntimeException {

    private final String namespace;
    private final String existingPluginName;

    public NamespaceConflictException(String namespace, String existingPluginName) {
        super("namespace '" + namespace + "' already registered by '" + existingPluginName + "'");
        this.namespace = namespace;
        this.existingPluginName = existingPluginName;
    }

    public String namespace() {
        return namespace;
    }

    public String existingPluginName() {
        return existingPluginName;
    }
}
