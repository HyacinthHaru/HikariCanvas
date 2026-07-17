package ac.haru.hikaricanvas.api;

/**
 * {@link HikariCanvasAPI#setVariable} / {@link HikariCanvasAPI#declareKey} /
 * {@link HikariCanvasAPI#unsetVariable} 抛：namespace 不存在或 ACL 拒绝。
 */
public final class PluginNamespaceException extends RuntimeException {

    public enum Code {
        /** namespace 未注册过 → 调用方必须先 {@link HikariCanvasAPI#registerNamespace}。 */
        NAMESPACE_NOT_REGISTERED,
        /** namespace 已存在但属于其他 plugin（spoof 防御）。 */
        NAMESPACE_ACL_DENIED
    }

    private final Code code;
    private final String namespace;

    public PluginNamespaceException(Code code, String namespace, String message) {
        super(message);
        this.code = code;
        this.namespace = namespace;
    }

    public Code code() {
        return code;
    }

    public String namespace() {
        return namespace;
    }
}
