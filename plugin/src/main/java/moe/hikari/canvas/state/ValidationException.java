package moe.hikari.canvas.state;

/**
 * 编辑会话校验失败异常。EditSession / FillValidator 等模块抛出，
 * 由 op handler 外层 catch 后转为 {@code OpResult.Error(code, message)} 下行。
 *
 * <p>原本是 {@code EditSession} 的 private 内部类（M3 起）；M11 起为让 {@link FillValidator}
 * 等同包 helper 复用，提取为 top-level（同包可见）。</p>
 */
final class ValidationException extends RuntimeException {
    final String code;

    ValidationException(String code, String message) {
        super(message);
        this.code = code;
    }
}
