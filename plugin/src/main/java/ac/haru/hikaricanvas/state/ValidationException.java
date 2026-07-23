package ac.haru.hikaricanvas.state;

/**
 * 编辑会话校验失败异常。EditSession / FillValidator 等模块抛出，
 * 由 op handler 外层 catch 后转为 {@code OpResult.Error(code, message)} 下行。
 *
 * <p>public：供 {@code canvasfile.ProjectMaterializer} 等在 .canvas / pack 导入时 catch
 * {@code EditSession.validateElementForTemplateApply} 抛的异常。</p>
 */
public final class ValidationException extends RuntimeException {
    public final String code;

    public ValidationException(String code, String message) {
        super(message);
        this.code = code;
    }
}
