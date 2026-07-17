package ac.haru.hikaricanvas.variable;

/**
 * VariableStore 抛的业务异常。错误码与 WS 协议错误码对齐——
 * 详见 {@code docs/dynamic-data.md §3.1} 与 {@code docs/protocol.md §error codes}。
 *
 * <p>RuntimeException 而非 checked：与项目其他业务异常风格一致
 * （{@code TemplateException} / {@code UploadException}）。调用方在 WS op handler
 * 边界 catch 后翻译为 protocol error。</p>
 */
public final class VariableException extends RuntimeException {

    public enum Code {
        VARIABLE_NOT_FOUND,
        VARIABLE_EXISTS,
        VARIABLE_TYPE_MISMATCH,
        VARIABLE_NAMESPACE_DENIED,
        VARIABLE_NAME_INVALID,
        VARIABLE_VALUE_TOO_LONG,
        QUOTA_EXCEEDED,
        TTL_INVALID
    }

    private final Code code;

    public VariableException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public VariableException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
