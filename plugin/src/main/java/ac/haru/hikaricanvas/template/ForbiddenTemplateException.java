package ac.haru.hikaricanvas.template;

/**
 * 调用方尝试 apply 一个不属于自己的 user-template，且无
 * {@code canvas.template.use-others} bypass 权限时由
 * {@link TemplateRegistry#byIdForApply(String, java.util.UUID, boolean)} 抛出。
 *
 * <p>上游 WS dispatcher 应捕获并转 {@code FORBIDDEN} 错误码，不向客户端 echo
 * 内部异常细节。</p>
 */
public final class ForbiddenTemplateException extends RuntimeException {
    private final String templateId;

    public ForbiddenTemplateException(String templateId) {
        super("template '" + templateId + "' is owned by another player");
        this.templateId = templateId;
    }

    public String templateId() {
        return templateId;
    }
}
