package moe.hikari.canvas.state;

/**
 * 编辑会话校验失败异常。EditSession / FillValidator 等模块抛出，
 * 由 op handler 外层 catch 后转为 {@code OpResult.Error(code, message)} 下行。
 *
 * <p>原本是 {@code EditSession} 的 private 内部类（M3 起）；M11 起为让 {@link FillValidator}
 * 等同包 helper 复用，提取为 top-level（同包可见）。</p>
 */
// M15.4 P0-23：从 package-private 提升为 public，让 template 包内 TemplateInstantiator
// 可以 catch raw_state 反序列化后 EditSession.validateElementForTemplateApply 抛的异常。
public final class ValidationException extends RuntimeException {
    public final String code;

    public ValidationException(String code, String message) {
        super(message);
        this.code = code;
    }
}
